package com.docusign.forklift;

import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class Forklift<T> implements Loader.OnLoadCompleteListener<T> {

    /**
     * Processes a {@link android.support.v4.content.Loader} synchronously and blocks until there is a result to return.
     * The Loader will be reset and then run through its normal life cycle until it would have normally delivered a result via
     * {@link android.support.v4.content.Loader.OnLoadCompleteListener#onLoadComplete(android.support.v4.content.Loader, Object)}.
     * NOTA BENE: If the given Loader normally returns multiple results, this method will only return the first.
     *
     * @param loader The {@link android.support.v4.content.Loader} to run synchronously
     * @param <T> The data type that the given {@link android.support.v4.content.Loader} will return
     * @return The first returned result
     * @throws LoadCancelledException If the loader is cancelled or reset before a result is returned.
     */
    @SuppressWarnings("unchecked")
	public static <T> T getSync(Loader<T> loader) throws LoadCancelledException {
		if (loader instanceof AsyncChainLoader<?>)
			return (T)new AsyncChainLoader.ACLForklift<T>((AsyncChainLoader<T>)loader).getSync();
		else
			return new Forklift<T>(loader).getSync();
	}

    private static final long LOADER_WATCH_MILLIS = 20;
	
	private Loader<T> m_Loader;
	private T m_Data;
	private final Object m_Lock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Constructor.
     * @param loader The {@link android.support.v4.content.Loader} to process synchronously
     */
	protected Forklift(Loader<T> loader) {
		m_Loader = loader;
	}

    /** @inheritDoc */
	@Override
	public void onLoadComplete(Loader<T> loader, T data) {
		if (loader != m_Loader)
			throw new UnsupportedOperationException("Cannot manage any but my own loader.");
		
		synchronized (m_Lock) {
			m_Data = data;
			m_Lock.notifyAll();
		}
	}

    /**
     * Performs the actual work of resetting the loader, processing it and returning the result.
     * @return The first result delivered by the given loader.
     * @throws LoadCancelledException If the given loader is cancelled or reset before a result is returned.
     */
	protected T getSync() throws LoadCancelledException {
        resetSynchronouslyOnUiThread();
        try {
            if (m_Loader instanceof AsyncTaskLoader<?>) {
                T t = ((AsyncTaskLoader<T>)m_Loader).loadInBackground();
                return t;
            } else {
                synchronized (m_Lock) {
                    m_Loader.registerListener(0, this);
                    m_Loader.startLoading();
                    while (m_Data == null) {
                        try {
                            if (!m_Loader.isStarted())
                                throw new LoadCancelledException();
                            m_Lock.wait(LOADER_WATCH_MILLIS);
                        } catch (InterruptedException ignored) { }
                    }
                    m_Loader.unregisterListener(this);
                    return m_Data;
                }
            }
        } finally {
            resetSynchronouslyOnUiThread();
        }
	}

    /**
     * Resets our loader, according to the documented contract that onReset is always called from the main thread.
     */
    protected void resetSynchronouslyOnUiThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            m_Loader.reset();
        } else {
            FutureTask<Void> task = new FutureTask<Void>(new Runnable() {
                @Override
                public void run() {
                    m_Loader.reset();
                }
            }, null);
            mMainHandler.post(task);
            try {
                task.get();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }
        }
    }
}
