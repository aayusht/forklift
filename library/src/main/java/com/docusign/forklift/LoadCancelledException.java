package com.docusign.forklift;

/**
* Created by chris.sarbora on 4/30/14.
*/
public class LoadCancelledException extends ChainLoaderException {
    public LoadCancelledException()
    {
        super();
    }

    public LoadCancelledException(String message)
    {
        super(message);
    }

    public LoadCancelledException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public LoadCancelledException(Throwable cause)
    {
        super(cause);
    }
}
