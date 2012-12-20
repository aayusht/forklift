package com.docusign.dataaccess;

import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

public class LoaderHelper<T> implements Loader.OnLoadCompleteListener<T> {
	
	public static <T> T getSync(Loader<T> loader) {
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
	
	@SuppressWarnings("unchecked")
	public T getSync() {
		if (m_Loader instanceof AsyncTaskLoader<?>) {
			T ret = ((AsyncTaskLoader<T>)m_Loader).loadInBackground();
			if (ret == null && m_Loader instanceof AsyncChainLoader<?>) {
				AsyncChainLoader<?> acl = (AsyncChainLoader<?>)m_Loader;
				if (acl.getChainLoader() != null)
					ret = (T)LoaderHelper.getSync(acl.getChainLoader());
			}
			return ret;
		} else {
			m_Loader.registerListener(0, this);
			m_Loader.startLoading();
			m_Loader.abandon();
			return m_Data;
		}
	}
}
