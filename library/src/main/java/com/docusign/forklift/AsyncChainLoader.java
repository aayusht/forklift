package com.docusign.forklift;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.os.SystemClock;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.util.Log;

import com.docusign.forklift.Result.Type;

import java.util.ArrayList;

public abstract class AsyncChainLoader<T> extends AsyncTaskLoader<Result<T>>
        implements Loader.OnLoadCompleteListener<Result<T>> {

    /* package */ static class ACLForklift<T> extends Forklift<Result<T>> {

        private AsyncChainLoader<T> m_Loader;

        public ACLForklift(AsyncChainLoader<T> loader) {
            super(loader);
            m_Loader = loader;
        }

        @Override
        protected Result<T> getSync() throws LoadCancelledException {
            T ret;
            resetSynchronouslyOnUiThread();
            try {
                try {
                    m_Loader.m_State = LOADING_SELF;
                    ret = m_Loader.doLoad();
                    return Result.success(ret);
                } catch (NoResultException e) {
                    if (m_Loader.getChainLoader() == null)
                        throw new UnsupportedOperationException("If there is no chained loader, doLoad() must return a result.");

                    if (m_Loader.m_State != LOADING_SELF)
                        throw new LoadCancelledException();

                    m_Loader.m_State = LOADING_CHAIN;
                    m_Loader.getChainLoader().unregisterListener(m_Loader);
                    ret = Forklift.getSync(m_Loader.getChainLoader()).get();
                    m_Loader.getChainLoader().registerListener(0, m_Loader);

                    if (m_Loader.m_State != LOADING_CHAIN)
                        throw new LoadCancelledException();
                    m_Loader.onFallbackDelivered(ret, null); // TODO: this doesn't understand PARTIAL!
                    return Result.success(ret);
                }
            } catch (LoadCancelledException e) {
                throw e;
            } catch (ChainLoaderException e) {
                return Result.failure(e);
            } finally {
                resetSynchronouslyOnUiThread();
            }
        }
    }

    private class FallbackDeliveredAsyncTask extends AsyncTask<Result<T>, Void, Result<T>> {
        @Override
        protected Result<T> doInBackground(Result<T>... params) {
            if (isCancelled())
                return null;

            Result<T> data = (Result<T>)params[0];

            try {
                data = new Result<T>(AsyncChainLoader.this.onFallbackDelivered(data.get(), data.getType()), null, data.getType());
            } catch (NoResultException nores) {
                data = null;
            } catch (ChainLoaderException e) {
                data = Result.failure(e);
            }

            return data;
        }

        @Override
        protected void onCancelled(Result<T> result) {
            removeTask(this);
        }

        @Override
        protected void onPostExecute(Result<T> result) {
            AsyncChainLoader.this.deliverResult(result);
            removeTask(this);
        }
    }

    private static class NoResultException extends ChainLoaderException {
        private static final long serialVersionUID = 2596920468208815208L;
    }

    /**
     * Special case of ChainLoaderException to be thrown during {@link com.docusign.forklift.AsyncChainLoader#loadInBackground()} if this instance cannot return a result.
     * Must not be thrown if there is no chained loader.
     */
    protected static final NoResultException NO_RESULT = new NoResultException();

    private static final int INITIALIZED 		= 0;
    private static final int LOADING_SELF 		= INITIALIZED + 1;
    private static final int LOADING_CHAIN		= LOADING_SELF + 1;
    @SuppressWarnings("unused")
    private static final int ALL_LOADS_COMPLETE = LOADING_CHAIN + 1; // this should always be "last + 1"

    private final Loader<Result<T>> m_Chain;
    private final ArrayList<AsyncTask<?, ?, ?>> mFallbackDeliveredTasks;
    private Result<T> m_Data;
    private int m_State;
    private final Throwable mCreatedLocation;

    /**
     * Constructor
     * @param context Context that this loader should be attached to.
     * @param chain Another loader to process after this one completes its own loading.
     */
    public AsyncChainLoader(Context context, Loader<Result<T>> chain) {
        super(context);

        m_Chain = chain;
        mFallbackDeliveredTasks = new ArrayList<AsyncTask<?, ?, ?>>();

        m_State = INITIALIZED;

        if (m_Chain != null)
            m_Chain.registerListener(0, this);

        mCreatedLocation = new Exception().fillInStackTrace();
    }

    private void removeTask(FallbackDeliveredAsyncTask task) {
        synchronized (mFallbackDeliveredTasks) {
            mFallbackDeliveredTasks.remove(task);
            mFallbackDeliveredTasks.notifyAll();
        }
    }

    // TODO: wrap this somehow and make it work
//	public AsyncChainLoader(Context context, Loader<T> chain) {
//		super(context);
//	}

    /** @inheritDoc */
    @Override
    protected void onReset() {
        cancelLoad();
        releaseData(m_Data);

        m_Data = null;
        m_State = INITIALIZED;

        if (m_Chain != null)
            m_Chain.reset();

        for (AsyncTask<?, ?, ?> task : mFallbackDeliveredTasks)
            task.cancel(false);
    }

    /** @inheritDoc */
    @Override
    protected final void onStartLoading() {
        if (m_Data != null)
            deliverResult(m_Data);

        performLoad();
    }

    private void performLoad() {
        if (m_State < LOADING_SELF)
            forceLoad();
        else if (m_State < LOADING_CHAIN && m_Chain != null) {
            m_Chain.startLoading();
        }

        m_State++;
    }

    /** @inheritDoc */
    @Override
    protected void onAbandon() {
        cancelLoad();
    }

    /** @inheritDoc */
    @Override
    public void onCanceled(Result<T> data) {
        super.onCanceled(data);
        // TODO: should we be resetting anything here? state, specifically?
        releaseData(data);
    }

    /** @inheritDoc */
    @Override
    public final void deliverResult(Result<T> data) {
        if (isReset()) {
            return;
        }

        Result<T> oldData = m_Data;
        m_Data = data;

        // we don't deliver null data here because it's actually a null Result<T> object
        // if the intent is to actually deliver "null" as a result, you can return Result.success(null)
        if (isStarted() && !isAbandoned() && m_Data != null) {
            super.deliverResult(m_Data);
            releaseData(oldData);
        }

        if (data == null || data.getType() == Type.COMPLETE)
            performLoad();
    }

    /**
     *
     * @param data
     */
    protected final void releaseData(Result<T> data) {
        if (data != null)
            onReleaseData(data);
    }

    /**
     *
     * @param data
     */
    protected void onReleaseData(Result<T> data) {

    }

    /** @inheritDoc */
    @SuppressLint("NewApi")
    @Override
    public final void onLoadComplete(Loader<Result<T>> loader, Result<T> data) {
        if (loader != m_Chain)
            throw new UnsupportedOperationException("ChainAsyncTaskLoader must only handle callbacks for its chained loader.");

        if (!isStarted()) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            mFallbackDeliveredTasks.add(new FallbackDeliveredAsyncTask().execute(data));
        else
            mFallbackDeliveredTasks.add(new FallbackDeliveredAsyncTask().executeOnExecutor(FallbackDeliveryExecutor.get(this), data));
    }

    /**
     * Called when the chained loader returns its result, allowing this loader to operate on the returned data before delivering the result itself.
     * Will not be called if the chained loader threw an exception during processing or otherwise failed to deliver a result.
     *
     * NOTA BENE: This method will never be called on the main thread. If the chained loader returns multiple results, each result is guaranteed
     * to be delivered to this method one at a time, in the same order as the loader returned them. This method will not block the chained loader.
     * @param data The result delivered by the chained loader
     * @param type What type of result this is - a partial result (expect further deliveries to complete the data) or a complete result.
     * @return The result to deliver to this loader's consumer.
     * @throws ChainLoaderException If an error occurs while processing the delivered data.
     */
    protected T onFallbackDelivered(T data, Type type) throws ChainLoaderException {
        return data;
    }

    /** @inheritDoc */
    @Override
    public Result<T> loadInBackground() {
        synchronized (mFallbackDeliveredTasks) {
            long start = SystemClock.currentThreadTimeMillis();
            while (!mFallbackDeliveredTasks.isEmpty()) {
                try {
                    mFallbackDeliveredTasks.wait(5000);
                } catch (InterruptedException ignored) { }
                if (SystemClock.currentThreadTimeMillis() - start > 5000)
                    Log.w("DocuSign", "Waiting for onFallbackDelivered to finish: " + this);
            }
        }

        try {
            return Result.success(doLoad());
        } catch (NoResultException nores) {
            if (m_Chain == null)
                throw new UnsupportedOperationException("If there is no chained loader, doLoad() must return a result.");

            return null;
        } catch (ChainLoaderException err) {
            return Result.failure(err);
        } catch (Error err) {
            Log.e("AsyncChainLoader", "The following exception occurred during processing of an AsyncChainLoader started at:", mCreatedLocation);
            throw err;
        } catch (RuntimeException re) {
            Log.e("AsyncChainLoader", "The following exception occurred during processing of an AsyncChainLoader started at:", mCreatedLocation);
            throw re;
        }
    }

    /**
     * Performs the work of loading data.
     * @return The complete data to deliver
     * @throws ChainLoaderException If an error occurs while loading the data. Can throw NO_RESULT to indicate that processing should fall to the chained loader.
     */
    public abstract T doLoad() throws ChainLoaderException;

    /**
     * Retrieves the {@link android.support.v4.content.Loader} that will be processed after this loader completes its own processing.
     * @return The chained {@link android.support.v4.content.Loader}
     */
    protected Loader<Result<T>> getChainLoader() {
        return m_Chain;
    }
}