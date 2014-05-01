package com.docusign.forklift;

import android.os.AsyncTask;
import android.os.SystemClock;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.test.AndroidTestCase;

/**
 * Created by chris.sarbora on 4/30/14.
 */
public class LoaderHelperTest extends AndroidTestCase {
    public static final Object EXPECTED_RESULT = new Object();

    public void testSynchronousLoader() throws Exception {
        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new Loader<Object>(getContext()) {
            @Override
            protected void onStartLoading() {
                deliverResult(EXPECTED_RESULT);
            }
        }));
    }

    // TODO: the expected behavior for this test (the last value delivered is the one we see) is different than testAsyncMultiResultLoader (first delivered is what we see)
    // this is because of the async-ness.. with a sync loader, each new result overwrites the last, *then* LoaderHelper looks at the value.
    // with an async loader, the first result delivered wakes up the waiting LoaderHelper and it proceeds immediately with that value
    public void testSynchronousMultiResultLoader() throws Exception {
        assertNotSame(EXPECTED_RESULT, LoaderHelper.getSync(new Loader<Object>(getContext()) {
            @Override
            protected void onStartLoading() {
                deliverResult(EXPECTED_RESULT);
                deliverResult(new Object());
                deliverResult(new Object());
            }
        }));
        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new Loader<Object>(getContext()) {
            @Override
            protected void onStartLoading() {
                deliverResult(new Object());
                deliverResult(new Object());
                deliverResult(EXPECTED_RESULT);
            }
        }));
    }

    public void testAsyncLoader() throws Exception {
        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new Loader<Object>(getContext()) {
            @Override
            protected void onStartLoading() {
                new AsyncTask<Void, Void, Object>() {
                    @Override
                    protected Object doInBackground(Void... params) {
                        return EXPECTED_RESULT;
                    }

                    @Override
                    protected void onPostExecute(Object o) {
                        deliverResult(o);
                    }
                }.execute();
            }
        }));
    }

    public void testAsyncMultiResultLoader() throws Exception {
        // note: this is expected to be NOT same, see note above testSyncMultiResultLoader
        assertNotSame(EXPECTED_RESULT, LoaderHelper.getSync(new Loader<Object>(getContext()) {
            @Override
            protected void onStartLoading() {
                new AsyncTask<Void, Object, Object>() {
                    @Override
                    protected Object doInBackground(Void... params) {
                        for (int i = 0; i < 5; i++) {
                            publishProgress(new Object());
                        }
                        return EXPECTED_RESULT;
                    }

                    @Override
                    protected void onProgressUpdate(Object... values) {
                        deliverResult(values[0]);
                    }

                    @Override
                    protected void onPostExecute(Object o) {
                        deliverResult(o);
                    }
                }.execute();
            }
        }));
    }

    public void testAsyncTaskLoader() throws Exception {
        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new AsyncTaskLoader<Object>(getContext()) {
            @Override
            public Object loadInBackground() {
                return EXPECTED_RESULT;
            }
        }));
    }

    public void testAsyncChainLoader() throws Exception {
        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), null) {
            @Override
            public Object doLoad() throws ChainLoaderException {
                return EXPECTED_RESULT;
            }
        }).get());
    }

    public void testFailingAsyncChainLoader() throws Exception {
        try {
            LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), null) {
                @Override
                public Object doLoad() throws ChainLoaderException {
                    throw new ChainLoaderException();
                }
            }).get();
        } catch (ChainLoaderException e) {
            return;
        }

        fail("Loader failed but exception was not thrown upon get()");
    }

    public void testChainedAsyncChainLoader() throws Exception {
        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), new AsyncChainLoader<Object>(getContext(), null) {
            @Override
            public Object doLoad() throws ChainLoaderException {
                fail("Chained loader was called");
                return null;
            }
        }) {
            @Override
            public Object doLoad() throws ChainLoaderException {
                return EXPECTED_RESULT;
            }
        }).get());

        assertSame(EXPECTED_RESULT, LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), new AsyncChainLoader<Object>(getContext(), null) {
            @Override
            public Object doLoad() throws ChainLoaderException {
                return EXPECTED_RESULT;
            }
        }) {
            @Override
            public Object doLoad() throws ChainLoaderException {
                throw NO_RESULT;
            }
        }).get());
    }

    public void testFailingChainedAsyncChainLoader() throws Exception {
        try {
            LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), new AsyncChainLoader<Object>(getContext(), null) {
                @Override
                public Object doLoad() throws ChainLoaderException {
                    throw new ChainLoaderException();
                }
            }) {
                @Override
                public Object doLoad() throws ChainLoaderException {
                    throw NO_RESULT;
                }
            }).get();
        } catch (ChainLoaderException e) {
            return;
        }

        fail("Chained loader failed but exception was not thrown upon get()");
    }

    public void testResetAsyncLoader() throws Exception {
        try {
            assertNotSame("Loader was reset halfway through processing, but LoadCancelledException was not thrown.", EXPECTED_RESULT, LoaderHelper.getSync(new Loader<Object>(getContext()) {
                @Override
                protected void onStartLoading() {
                    new AsyncTask<Void, Void, Object>() {
                        @Override
                        protected Object doInBackground(Void... params) {
                            SystemClock.sleep(50);
                            reset(); // simulate someone else calling reset on us, on some other thread
                            SystemClock.sleep(50);
                            return EXPECTED_RESULT;
                        }

                        @Override
                        protected void onPostExecute(Object o) {
                            deliverResult(o);
                        }
                    }.execute();
                }
            }));
        } catch (LoadCancelledException expected) { }
    }

    public void testResetAsyncChainLoader() throws Exception {
        try {
            assertNotSame("Loader was reset during processing, but LoadCancelledException was not thrown.",
                    EXPECTED_RESULT, LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), new AsyncChainLoader<Object>(getContext(), null) {
                @Override
                public Object doLoad() throws ChainLoaderException {
                    return null;
                }
            }) {
                @Override
                public Object doLoad() throws ChainLoaderException {
                    reset();
                    throw NO_RESULT;
                }
            }).get());
        } catch (LoadCancelledException expected) { }

        try {
            assertNotSame("Loader was reset during processing, but LoadCancelledException was not thrown.",
                    EXPECTED_RESULT, LoaderHelper.getSync(new AsyncChainLoader<Object>(getContext(), new AsyncChainLoader<Object>(getContext(), null) {
                        @Override
                        public Object doLoad() throws ChainLoaderException {
                            reset();
                            return null;
                        }
                    }) {
                        @Override
                        public Object doLoad() throws ChainLoaderException {
                            throw NO_RESULT;
                        }
                    }).get());
        } catch (LoadCancelledException expected) { }
    }
}