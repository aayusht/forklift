package com.docusign.dataaccess;

import java.util.concurrent.LinkedBlockingQueue;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;

import com.docusign.dataaccess.Result.Type;

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
				ret = m_Loader.doLoad();
				return Result.success(ret);
			} catch (NoResultException e) {
				if (m_Loader.getChainLoader() == null)
					throw new UnsupportedOperationException("If there is no chained loader, doLoad() must return a result.");
				
				try {
					ret = LoaderHelper.getSync(m_Loader.getChainLoader()).get();
					m_Loader.onFallbackDelivered(ret, null); // TODO: this doesn't understand PARTIAL!
					return Result.success(ret);
				} catch (DataProviderException chainEx) {
					return Result.failure(chainEx);
				}
			} catch (DataProviderException e) {
				return Result.failure(e);
			}
		}
	}
	
	private class FallbackDeliveredAsyncTask extends AsyncTask<Void, Result<T>, Void> {
		
		private final LinkedBlockingQueue<Result<T>> mResults = new LinkedBlockingQueue<Result<T>>();
		
		public void addResult(Result<T> result) {
			mResults.offer(result);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		protected Void doInBackground(Void... params) {
			Result<T> data;
			
			while (!isCancelled()) {
				data = null;
				while (data == null) {
					try {
						data = mResults.take();
					} catch (InterruptedException ignored) { }
				}
				
				try {
					data = new Result<T>(AsyncChainLoader.this.onFallbackDelivered(data.get(), data.getType()), null, data.getType());
				} catch (NoResultException nores) {
					data = null;
				} catch (DataProviderException e) {
					data = Result.failure(e);
				}
				
				publishProgress(data);
			}
			
			return null;
		}

		@Override
		protected void onProgressUpdate(Result<T>... result) {
			AsyncChainLoader.this.deliverResult(result[0]);
		}
	}
	
	private static class NoResultException extends DataProviderException {
		private static final long serialVersionUID = 2596920468208815208L;
	}
	
	protected static final NoResultException NO_RESULT = new NoResultException(); 
	
	private static final int INITIALIZED 		= 0;
	private static final int LOADING_SELF 		= INITIALIZED + 1;
	private static final int LOADING_CHAIN		= LOADING_SELF + 1;
	@SuppressWarnings("unused")
	private static final int ALL_LOADS_COMPLETE = LOADING_CHAIN + 1; // this should always be "last + 1"
	
	private final Loader<Result<T>> m_Chain;
	private FallbackDeliveredAsyncTask mFallbackDeliveryBoy;
	private Result<T> m_Data;
	private int m_State;

	public AsyncChainLoader(Context context, Loader<Result<T>> chain) {
		super(context);
		
		m_Chain = chain;
		
		m_State = INITIALIZED;
		
		if (m_Chain != null)
			m_Chain.registerListener(0, this);
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
		
		if (mFallbackDeliveryBoy != null) {
			mFallbackDeliveryBoy.cancel(false);
			mFallbackDeliveryBoy = null;
		}
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
		if (mFallbackDeliveryBoy != null)
			mFallbackDeliveryBoy.cancel(true);
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
		
		if (mFallbackDeliveryBoy == null) {
			mFallbackDeliveryBoy = new FallbackDeliveredAsyncTask();
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
				mFallbackDeliveryBoy.execute();
			else
				mFallbackDeliveryBoy.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
		}
		
		mFallbackDeliveryBoy.addResult(data);
	}
	
	protected T onFallbackDelivered(T data, Type type) throws DataProviderException {
		return data;
	}
	
	@Override
	public Result<T> loadInBackground() {
		try {
			return Result.success(doLoad());
		} catch (NoResultException nores) {
			if (m_Chain == null)
				throw new UnsupportedOperationException("If there is no chained loader, doLoad() must return a result.");
			
			return null;
		} catch (DataProviderException err) {
			return Result.failure(err);
		}
	}
	
	public abstract T doLoad() throws DataProviderException;
	
	protected Loader<Result<T>> getChainLoader() {
		return m_Chain;
	}
}