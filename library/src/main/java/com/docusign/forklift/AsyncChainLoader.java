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

    public static class AsyncChainLoaderHelper<T> extends LoaderHelper<Result<T>> {

        private AsyncChainLoader<T> m_Loader;

        public AsyncChainLoaderHelper(AsyncChainLoader<T> loader) {
            super(loader);
            m_Loader = loader;
        }

        public Result<T> getSync() {
            T ret;
            try {
                try {
                    try {
                        ret = m_Loader.doLoad();
                        return Result.success(ret);
                    } catch (NoResultException e) {
                        if (m_Loader.getChainLoader() == null)
                            throw new UnsupportedOperationException("If there is no chained loader, doLoad() must return a result.");

                        m_Loader.getChainLoader().unregisterListener(m_Loader);
                        ret = LoaderHelper.getSync(m_Loader.getChainLoader()).get();
                        m_Loader.getChainLoader().registerListener(0, m_Loader);
                        m_Loader.onFallbackDelivered(ret, null); // TODO: this doesn't understand PARTIAL!
                        return Result.success(ret);
                    }
                } catch (ChainLoaderException e) {
                    return Result.failure(e);
                }
            } finally {
                m_Loader.reset(); // leave in a fresh state TODO: this violates the documented contract that onReset() is always called from main thread
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

    @Override
    protected void onAbandon() {
        cancelLoad();
    }

    @Override
    public void onCanceled(Result<T> data) {
        super.onCanceled(data);
        // TODO: should we be resetting anything here? state, specifically?
        releaseData(data);
    }

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

    protected final void releaseData(Result<T> data) {
        if (data != null)
            onReleaseData(data);
    }

    protected void onReleaseData(Result<T> data) {

    }

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

    protected T onFallbackDelivered(T data, Type type) throws ChainLoaderException {
        return data;
    }

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

    public abstract T doLoad() throws ChainLoaderException;

    protected Loader<Result<T>> getChainLoader() {
        return m_Chain;
    }
}