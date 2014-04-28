package com.docusign.forklift;

import android.content.Context;
import android.test.AndroidTestCase;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by chris.sarbora on 3/11/14.
 */
public class FallbackDeliveryExecutorTest extends AndroidTestCase {
    private class StubACL extends AsyncChainLoader<Object> {
        private StubACL(Context context) {
            super(context, null);
        }

        @Override
        public Object doLoad() throws ChainLoaderException {
            return null; // this would normally cause an exception, because we have a null chainloader - but we don't ever actually run this
        }
    }

    // test ordering maintained within ACL - a job enqueued after another job in the same ACL will not execute until the prior one has finished
    public void testOrdering() throws Exception {
        final int JOBLINE_COUNT = 10; // stress it
        final int MIN_JOBS_PER_LINE = 4;
        final int MAX_JOBS_PER_LINE = 8;
        final int MIN_JOB_MILLIS = 10;
        final int MAX_JOB_MILLIS = 500;
        final long seed = new Random().nextLong();
        Random r = new Random(seed);

        ArrayList<FutureTask<Void>> futures = new ArrayList<FutureTask<Void>>(JOBLINE_COUNT * MAX_JOBS_PER_LINE);

        for (int i = 0; i < JOBLINE_COUNT; i++) {
            final AtomicInteger atomInt = new AtomicInteger(0);
            final int aclId = i;
            int numJobs = MIN_JOBS_PER_LINE + r.nextInt(MAX_JOBS_PER_LINE - MIN_JOBS_PER_LINE);
            StubACL acl = new StubACL(getContext());

            for (int j = 0; j < numJobs; j++) {
                final int jobId = j;
                final long delay = MIN_JOB_MILLIS + r.nextInt(MAX_JOB_MILLIS - MIN_JOB_MILLIS);

                FutureTask task = new FutureTask<Void>(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        int atomState = atomInt.get();
                        if (jobId != atomState) {
                            // don't use an assert here so that we don't build this string until we actually need it.
                            // probably a dumb optimization but whatever.
                            fail("ACL " + aclId + ", Job " + jobId + " begun when atomState == " + atomState + "! Seed value " + seed + " to repro.");
                        }
                        try {
                            Thread.sleep(delay);
                            return null;
                        } finally {
                            atomInt.getAndIncrement();
                        }
                    }
                });

                futures.add(task);
                FallbackDeliveryExecutor.get(acl).execute(task);
            }
        }

        for (FutureTask<Void> task : futures) {
            task.get();
        }
    }

    // test that we're not simply running them serially
    public void testRunningParallel() throws Exception {
        final int THREAD_COUNT = 2; // at a VERY minimum, there will always be two threads in the pool
        final long DELAY = 1000;
        final double MIN_CONCURRENCY = .5; // lower values allow less concurrency. valid range: [0 - 1]

        final CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            FallbackDeliveryExecutor.get(new StubACL(getContext())).execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(DELAY);
                        latch.countDown();
                    } catch (InterruptedException e) {
                        // ignore, but don't count down - causing test failure
                    }
                }
            });
        }

        latch.await((long)(DELAY * (THREAD_COUNT * (1 - MIN_CONCURRENCY))), TimeUnit.MILLISECONDS);
    }

    // test that they're not running on the main thread
    public void testNotMainThread() throws Exception {
        final Thread mainThread = Thread.currentThread();
        FutureTask<Boolean> testOnMainThread = new FutureTask<Boolean>(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return Thread.currentThread() == mainThread;
            }
        });
        FallbackDeliveryExecutor.get(new StubACL(getContext())).execute(testOnMainThread);
        assertFalse("Task given to FallbackDeliveryExecutor was executed on main thread.", testOnMainThread.get(1, TimeUnit.SECONDS));
    }
}
