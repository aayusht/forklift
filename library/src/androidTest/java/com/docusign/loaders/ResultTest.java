package com.docusign.loaders;

import junit.framework.TestCase;

/**
 * Created by chris.sarbora on 3/11/14.
 */
public class ResultTest extends TestCase {

    private static final Object VALUE = new Object();

    public void testSuccess() {
        Result<Object> r = Result.success(VALUE);
        try {
            assertEquals(VALUE, r.get());
            assertEquals(Result.Type.COMPLETE, r.getType());
        } catch (ChainLoaderException e) {
            fail("get() on a successful Result threw a ChainLoaderException: " + e);
        }
    }

    public void testFailure() {
        Result<Object> r = Result.failure(new ChainLoaderException());
        try {
            assertEquals(Result.Type.FAILURE, r.getType());
            r.get();
            fail("get() on a failed Result did NOT throw a ChainLoaderException.");
        } catch (ChainLoaderException e) {
            // success
        }
    }

    public void testPartial() {
        Result<Object> r = Result.partial(VALUE);
        try {
            assertEquals(VALUE, r.get());
            assertEquals(Result.Type.PARTIAL, r.getType());
        } catch (ChainLoaderException e) {
            fail("get() on a partial Result threw a ChainLoaderException: " + e);
        }
    }
}
