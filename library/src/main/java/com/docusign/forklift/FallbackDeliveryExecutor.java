package com.docusign.forklift;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
* Created by chris.sarbora on 2/1/14.
*/
class FallbackDeliveryExecutor extends ThreadPoolExecutor {
    // these are shamelessly stolen from android.os.AsyncTask's values for THREAD_POOL_EXECUTOR
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE = 1;

    private class RunnableWrapper implements Runnable {
        private final AsyncChainLoader<?> mLoader;
        private final Runnable mRunnable;

        public RunnableWrapper(AsyncChainLoader<?> loader, Runnable runnable) {
            if (loader == null)
                throw new NullPointerException("loader must not be null");
            if (runnable == null)
                throw new NullPointerException("runnable must not be null");

            mLoader = loader;
            mRunnable = runnable;
        }

        public void run() {
            ((FallbackQueue)FallbackDeliveryExecutor.this.getQueue()).startedRunning(this); // no-op unless a new Worker was started directly with this job
            mRunnable.run();
            ((FallbackQueue)FallbackDeliveryExecutor.this.getQueue()).finishedRunning(this);
        }

        public AsyncChainLoader<?> getLoader() {
            return mLoader;
        }
    }

    private static class FallbackQueue implements BlockingQueue<Runnable> {
        private final HashMap<AsyncChainLoader<?>, LinkedBlockingQueue<RunnableWrapper>> mQueues = new HashMap<AsyncChainLoader<?>, LinkedBlockingQueue<RunnableWrapper>>();
        private final HashSet<AsyncChainLoader<?>> mCurrentlyRunning = new HashSet<AsyncChainLoader<?>>();
        private final Object mNotifier = new Object();

        private LinkedBlockingQueue<RunnableWrapper> getQueue(AsyncChainLoader<?> loader) {
            synchronized (mQueues) {
                LinkedBlockingQueue<RunnableWrapper> queue = mQueues.get(loader);
                if (queue == null) {
                    queue = new LinkedBlockingQueue<RunnableWrapper>();
                    mQueues.put(loader, queue);
                }
                return queue;
            }
        }

        private RunnableWrapper checkWrapped(Runnable r) {
            if (r instanceof RunnableWrapper)
                return (RunnableWrapper)r;

            throw new UnsupportedOperationException("FallbackQueue is only for RunnableWrappers");
        }

        public void startedRunning(RunnableWrapper rw) {
            mCurrentlyRunning.add(rw.getLoader());
        }

        public void finishedRunning(RunnableWrapper rw) {
            mCurrentlyRunning.remove(rw.getLoader());
            synchronized (mNotifier) {
                mNotifier.notify(); // do NOT use notifyAll here - we only want to wake up one thread!
            }
        }

        // ADDERS

        @Override
        public boolean add(Runnable runnable) {
            RunnableWrapper runnableWrapper = checkWrapped(runnable);
            boolean changed;
            synchronized (mQueues) {
                changed = getQueue(runnableWrapper.getLoader()).add(runnableWrapper);
            }
            if (changed) {
                synchronized (mNotifier) {
                    mNotifier.notify(); // do NOT use notifyAll here - we only want to wake up one thread!
                }
            }
            return changed;
        }

        @Override
        public boolean addAll(Collection<? extends Runnable> collection) {
            boolean changed = false;
            for (Runnable r : collection)
                changed |= add(r);
            return changed;
        }

        @Override
        public boolean offer(Runnable runnable) {
            RunnableWrapper runnableWrapper = checkWrapped(runnable);
            boolean changed;
            synchronized (mQueues) {
                changed = getQueue(runnableWrapper.getLoader()).offer(runnableWrapper);
            }
            if (changed) {
                synchronized (mNotifier) {
                    mNotifier.notify(); // do NOT use notifyAll here - we only want to wake up one thread!
                }
            }
            return changed;
        }

        @Override
        public void put(Runnable runnable) throws InterruptedException {
            RunnableWrapper runnableWrapper = checkWrapped(runnable);
            synchronized (mQueues) {
                getQueue(runnableWrapper.getLoader()).put(runnableWrapper);
            }
            synchronized (mNotifier) { // if we got here, we added successfully (no throw)
                mNotifier.notify(); // do NOT use notifyAll here - we only want to wake up one thread!
            }
        }

        @Override
        public boolean offer(Runnable runnable, long timeout, TimeUnit unit) throws InterruptedException {
            RunnableWrapper runnableWrapper = checkWrapped(runnable);
            boolean changed;
            synchronized (mQueues) {
                changed = getQueue(runnableWrapper.getLoader()).offer(runnableWrapper, timeout, unit);
            }
            if (changed) {
                synchronized (mNotifier) {
                    mNotifier.notify(); // do NOT use notifyAll here - we only want to wake up one thread!
                }
            }
            return changed;
        }

        // REMOVERS

        @Override
        public boolean remove(Object o) {
            if (o instanceof RunnableWrapper) {
                RunnableWrapper rw = (RunnableWrapper)o;
                LinkedBlockingQueue<RunnableWrapper> queue;
                synchronized (mQueues) {
                    queue = mQueues.get(rw.getLoader());
                    if (queue != null) {
                        boolean changed = queue.remove(o);
                        if (queue.isEmpty()) { // anytime we take an object out, make sure we don't keep around empty queues
                            mQueues.remove(rw.getLoader());
                        }
                        return changed;
                    }
                    else {
                        return false;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean removeAll(Collection<?> collection) {
            boolean changed = false;
            for (Object o : collection)
                changed |= remove(o);
            return changed;
        }

        @Override
        public boolean retainAll(Collection<?> collection) {
            boolean changed = false;
            synchronized (mQueues) {
                Iterator<AsyncChainLoader<?>> iter = mQueues.keySet().iterator();
                while (iter.hasNext()) {
                    LinkedBlockingQueue<RunnableWrapper> queues = mQueues.get(iter.next());
                    changed |= queues.retainAll(collection);
                    if (queues.isEmpty())
                        iter.remove();
                }
            }
            return changed;
        }

        @Override
        public void clear() {
            synchronized (mQueues) {
                mQueues.clear();
            }
        }

        @Override
        public RunnableWrapper remove() {
            RunnableWrapper rw = poll();
            if (rw == null)
                throw new NoSuchElementException();
            return rw;
        }

        @Override
        public RunnableWrapper poll() {
            synchronized (mQueues) {
                RunnableWrapper rw = peek();
                if (rw != null) {
                    LinkedBlockingQueue<RunnableWrapper> queue = mQueues.get(rw.getLoader());
                    queue.remove();
                    if (queue.isEmpty())
                        mQueues.remove(rw.getLoader());
                }
                if (rw != null)
                    startedRunning(rw);
                return rw;
            }
        }

        @Override
        public RunnableWrapper poll(long timeout, TimeUnit unit) throws InterruptedException {
            RunnableWrapper rw = poll();
            if (rw == null) {
                synchronized (mNotifier) {
                    rw = poll();
                    if (rw == null) {
                        unit.timedWait(mNotifier, timeout);
                        rw = poll();
                    }
                }
            }
            return rw;
        }

        @Override
        public RunnableWrapper take() throws InterruptedException {
            return poll(Long.MAX_VALUE, TimeUnit.DAYS);
        }

        // LOOKERS

        @Override
        public RunnableWrapper element() {
            RunnableWrapper rw = peek();
            if (rw == null)
                throw new NoSuchElementException();
            return rw;
        }

        @Override
        public RunnableWrapper peek() {
            synchronized (mQueues) {
                for (AsyncChainLoader<?> key : mQueues.keySet()) {
                    if (mCurrentlyRunning.contains(key))
                        continue;

                    return mQueues.get(key).peek();
                }
            }

            return null;
        }

        // SIZE OR OTHER

        @Override
        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int size() {
            synchronized (mQueues) {
                int total = 0;
                for (LinkedBlockingQueue<RunnableWrapper> q : mQueues.values()) {
                    total += q.size();
                }
                return total;
            }
        }

        @Override
        public Object[] toArray() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T[] toArray(T[] array) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean contains(Object o) {
            if (o instanceof RunnableWrapper) {
                RunnableWrapper rw = (RunnableWrapper)o;
                LinkedBlockingQueue<RunnableWrapper> queue;
                synchronized (mQueues) {
                    queue = mQueues.get(rw.getLoader());
                }
                if (queue != null)
                    return queue.contains(o);
                else
                    return false;
            }
            return false;
        }

        @Override
        public boolean containsAll(Collection<?> collection) {
            boolean containsAll = false;
            for (Object o : collection)
                containsAll |= contains(o);
            return containsAll;
        }

        @Override
        public boolean isEmpty() {
            return mQueues.isEmpty();
        }

        @Override
        public Iterator<Runnable> iterator() {
            return new Iterator<Runnable>() {
                @Override
                public boolean hasNext() {
                    return peek() != null;
                }

                @Override
                public RunnableWrapper next() {
                    return FallbackQueue.this.remove(); // should this actually walk through instead?
                }

                @Override
                public void remove() {
                    // no op.. next() has already removed it
                    // TODO: does that break Iterator contract?
                }
            };
        }

        @Override
        public int drainTo(Collection<? super Runnable> c) {
            return drainTo(c, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            if (c == this)
                throw new IllegalArgumentException("Cannot drain to myself!");

            int count = 0;
            for (Runnable r : this) {
                c.add(r);
                count++;
            }
            return count;
        }
    };

    private static final FallbackDeliveryExecutor sInstance = new FallbackDeliveryExecutor();

    private FallbackDeliveryExecutor() {
        super(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS, new FallbackQueue(), new ThreadFactory() {
            private final AtomicInteger mCount = new AtomicInteger(1);

            public Thread newThread(Runnable r) {
                return new Thread(r, "Fallback Delivery Boy #" + mCount.getAndIncrement());
            }
        }, new ThreadPoolExecutor.DiscardPolicy());

        prestartAllCoreThreads();
    }

    public static Executor get(final AsyncChainLoader<?> loader) {
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                sInstance.execute(sInstance.new RunnableWrapper(loader, command));
            }
        };
    }
}
