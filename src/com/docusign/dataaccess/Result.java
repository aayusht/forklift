package com.docusign.dataaccess;

public class Result<T> {
	
	public enum Type {
		FAILURE,
		PARTIAL,
		COMPLETE
	}
	
	public static <T> Result<T> success(T result) {
		return new Result<T>(result, null, Type.COMPLETE);
	}
	
	public static <T> Result<T> partial(T result) {
		return new Result<T>(result, null, Type.PARTIAL);
	}
	
	public static <T> Result<T> failure(DataProviderException err) {
		return new Result<T>(null, err, Type.FAILURE);
	}
	
	private final DataProviderException m_Exception;
	private final T m_Result;
	private final Type mType;

	protected Result(T result, DataProviderException ex, Type type) {
		if (type == null)
			throw new IllegalArgumentException("Type must not be null.");
		
		m_Result = result;
		m_Exception = ex;
		mType = type;
	}

	public T get() throws DataProviderException {
		if (m_Exception != null)
			throw m_Exception;
		
		return m_Result;
	}

	public Type getType() {
		return mType;
	}
}
