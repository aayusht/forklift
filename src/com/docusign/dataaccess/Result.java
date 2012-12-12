package com.docusign.dataaccess;

public class Result<T> {
	
	public static <T> Result<T> success(T result) {
		return new Result<T>(result, null);
	}
	
	public static <T> Result<T> failure(DataProviderException err) {
		return new Result<T>(null, err);
	}
	
	private DataProviderException m_Exception;
	private T m_Result;

	protected Result(T result, DataProviderException ex) {
		m_Result = result;
		m_Exception = ex;
	}

	public T get() throws DataProviderException {
		if (m_Exception != null)
			throw m_Exception;
		
		return m_Result;
	}
}
