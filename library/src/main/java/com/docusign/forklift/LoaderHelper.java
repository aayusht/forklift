package com.docusign.forklift;

import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

public class LoaderHelper<T> implements Loader.OnLoadCompleteListener<T> {
	
	@SuppressWarnings("unchecked")
	public static <T> T getSync(Loader<T> loader) {
		if (loader instanceof AsyncChainLoader<?>)
			return (T)new AsyncChainLoader.AsyncChainLoaderHelper<T>((AsyncChainLoader<T>)loader).getSync();
		else
			return new LoaderHelper<T>(loader).getSync();
	}
	
	private Loader<T> m_Loader;
	private T m_Data;
	private final Object m_Lock = new Object();
	
	public LoaderHelper(Loader<T> loader) {
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
	
	public T getSync() {
		if (m_Loader instanceof AsyncTaskLoader<?>) {
			T t = ((AsyncTaskLoader<T>)m_Loader).loadInBackground();
			m_Loader.reset();
			return t;
		} else {
			synchronized (m_Lock) {
				m_Loader.registerListener(0, this);
				m_Loader.startLoading();
				try {
					m_Lock.wait();
				} catch (InterruptedException ignored) { }
				m_Loader.reset();
				m_Loader.unregisterListener(this);
				return m_Data;
			}
		}
	}
}