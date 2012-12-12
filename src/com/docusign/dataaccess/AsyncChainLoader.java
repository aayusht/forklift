package com.docusign.dataaccess;

import android.content.Context;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

public abstract class AsyncChainLoader<T, E extends Throwable> extends AsyncTaskLoader<LoaderResult<T, E>> 
															   implements Loader.OnLoadCompleteListener<LoaderResult<T, E>> {
	
	private static final int INITIALIZED 		= 0;
	private static final int LOADING_SELF 		= INITIALIZED + 1;
	private static final int LOADING_CHAIN		= LOADING_SELF + 1;
	@SuppressWarnings("unused")
	private static final int ALL_LOADS_COMPLETE = LOADING_CHAIN + 1; // this should always be "last + 1"
	
	private LoaderResult<T, E> m_Data;
	private Loader<LoaderResult<T, E>> m_Chain;
	private int m_State;

	public AsyncChainLoader(Context context, Loader<LoaderResult<T, E>> chain) {
		super(context);
		
		m_Chain = chain;
		m_State = INITIALIZED;
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
	public void onCanceled(LoaderResult<T, E> data) {
		super.onCanceled(data);
		// TODO: should we be resetting anything here? state, specifically?
		releaseData(data);
	}

	@Override
	public final void deliverResult(LoaderResult<T, E> data) {
		if (isReset()) {
			releaseData(data);
		}
		
		LoaderResult<T, E> oldData = m_Data;
		m_Data = data;
		
		if (isStarted() && !isAbandoned())
			super.deliverResult(m_Data);
		
		releaseData(oldData);
		
		performLoad();
	}
	
	protected final void releaseData(LoaderResult<T, E> data) {
		if (data != null)
			onReleaseData(data);
	}
	
	protected void onReleaseData(LoaderResult<T, E> data) {
		
	}

	@Override
	public final void onLoadComplete(Loader<LoaderResult<T, E>> loader, LoaderResult<T, E> data) {
		if (loader != m_Chain)
			throw new UnsupportedOperationException("ChainAsyncTaskLoader must only handle callbacks for its chained loader.");
		
		deliverResult(onFallbackDelivered(data));
	}
	
	protected LoaderResult<T, E> onFallbackDelivered(LoaderResult<T, E> data) {
		return data;
	}
	
//	@Override
//	public LoaderResult<T, E> loadInBackground() {
//		try {
//			return LoaderResult.success(doLoad());
//		} catch (Exception err) {
//			return LoaderResult.failure(err);
//		}
//	}
//	
//	public abstract T doLoad() throws E;
}