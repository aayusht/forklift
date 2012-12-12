package com.docusign.dataaccess;

public class LoaderResult<R, E extends Throwable> {
	
	public static <R, E extends Throwable> LoaderResult<R, E> success(R result) {
		return new LoaderResult<R, E>(result, null);
	}
	
	public static <R, E extends Throwable> LoaderResult<R, E> failure(E err) {
		return new LoaderResult<R, E>(null, err);
	}
	
	private E m_Exception;
	private R m_Result;

	protected LoaderResult(R result, E ex) {
		m_Result = result;
		m_Exception = ex;
	}

	public R get() throws E {
		if (m_Exception != null)
			throw m_Exception;
		
		return m_Result;
	}
}
