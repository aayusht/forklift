package com.docusign.loaders;

public class ChainLoaderException extends Exception {

	public ChainLoaderException()
	{
		super();
	}
	
	public ChainLoaderException(String message)
	{
		super(message);
	}
	
	public ChainLoaderException(String message, Throwable cause)
	{
		super(message, cause);
	}
	
	public ChainLoaderException(Throwable cause)
	{
		super(cause);
	}
}
