package com.docusign.forklift;

import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

public class Forklift<T> implements Loader.OnLoadCompleteListener<T> {

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
	
	private Forklift(Loader<T> loader) {
		m_Loader = loader;
	}

	@Override
	public void onLoadComplete(Loader<T> loader, T data) {
		if (loader != m_Loader)
			throw new UnsupportedOperationException("Cannot manage any but my own loader.");
		
		synchronized (m_Lock) {
			m_Data = data;
			m_Lock.notifyAll();
		}
	}
	
	private T getSync() throws LoadCancelledException {
        m_Loader.reset(); // start in a fresh state TODO: this violates the documented contract that onReset() is always called from main thread
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
            m_Loader.reset(); // leave in a fresh state TODO: this violates the documented contract that onReset() is always called from main thread
        }
	}
}
