package com.docusign.dataaccess;

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
	
	public LoaderHelper(Loader<T> loader) {
		m_Loader = loader;
	}

	@Override
	public void onLoadComplete(Loader<T> loader, T data) {
		if (loader != m_Loader)
			throw new UnsupportedOperationException("Cannot manage any but my own loader.");
		
		m_Data = data;
	}
	
	public T getSync() {
		if (m_Loader instanceof AsyncTaskLoader<?>) {
			return ((AsyncTaskLoader<T>)m_Loader).loadInBackground();
		} else {
			m_Loader.registerListener(0, this);
			m_Loader.startLoading();
			m_Loader.abandon();
			return m_Data;
		}
	}
}
