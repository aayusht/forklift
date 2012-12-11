package com.docusign.dataaccess;

import android.content.Context;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

public abstract class AsyncChainLoader<T> extends AsyncTaskLoader<T> implements Loader.OnLoadCompleteListener<T> {
	
	private static final int INITIALIZED 		= 0;
	private static final int LOADING_SELF 		= INITIALIZED + 1;
	private static final int LOADING_CHAIN		= LOADING_SELF + 1;
	@SuppressWarnings("unused")
	private static final int ALL_LOADS_COMPLETE = LOADING_CHAIN + 1; // this should always be "last + 1"
	
	private T m_Data;
	private Loader<T> m_Chain;
	private int m_State;

	public AsyncChainLoader(Context context, Loader<T> chain) {
		super(context);
		
		m_Chain = chain;
		m_State = INITIALIZED;
	}

	@Override
	protected void onReset() {
		cancelLoad();
		releaseData(m_Data);
		m_Data = null;
		m_State = INITIALIZED;
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
			m_Chain.registerListener(0, this);
			m_Chain.startLoading();
		}
		
		m_State++;
	}
	
	@Override
	protected void onAbandon() {
		cancelLoad();
	}
	
	@Override
	public void onCanceled(T data) {
		super.onCanceled(data);
		// TODO: should we be resetting anything here? state, specifically?
		releaseData(data);
	}

	@Override
	public final void deliverResult(T data) {
		if (isReset()) {
			releaseData(data);
		}
		
		T oldData = m_Data;
		m_Data = data;
		
		if (isStarted() && !isAbandoned())
			super.deliverResult(m_Data);
		
		releaseData(oldData);
		
		performLoad();
	}
	
	protected final void releaseData(T data) {
		if (data != null)
			onReleaseData(data);
	}
	
	protected void onReleaseData(T data) {
		
	}

	@Override
	public final void onLoadComplete(Loader<T> loader, T data) {
		if (loader != m_Chain)
			throw new UnsupportedOperationException("ChainAsyncTaskLoader must only handle callbacks for its chained loader.");
		
		deliverResult(data);
	}
}