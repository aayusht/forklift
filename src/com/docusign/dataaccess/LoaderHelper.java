package com.docusign.dataaccess;

import android.support.v4.content.Loader;

public class LoaderHelper<T> implements Loader.OnLoadCompleteListener<T> {
	
	public static <T> T getSync(Loader<T> loader) {
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
		synchronized (m_Lock) {
			m_Loader.registerListener(0, this);
			m_Loader.startLoading();
			try {
				m_Lock.wait();
			} catch (InterruptedException ignored) { }
			m_Loader.abandon();
			return m_Data;
		}
	}
}
