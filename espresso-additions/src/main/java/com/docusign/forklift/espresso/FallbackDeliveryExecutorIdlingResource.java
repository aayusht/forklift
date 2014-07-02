package com.docusign.forklift.espresso;

import com.google.android.apps.common.testing.ui.espresso.IdlingResource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by chris.sarbora on 3/13/14.
 */
public class FallbackDeliveryExecutorIdlingResource implements IdlingResource {
    private ThreadPoolExecutor mExecutor;
    private ResourceCallback mCallback;
    private final Class<?> mFDEClazz;
    private final Field mInstanceField;
    private final Constructor<?> mCtor;
    private final Object mLock;

    public FallbackDeliveryExecutorIdlingResource() {
        try {
            mFDEClazz = Class.forName("com.docusign.forklift.FallbackDeliveryExecutor");
            mInstanceField = mFDEClazz.getDeclaredField("sInstance");
            mInstanceField.setAccessible(true);
            mCtor = mFDEClazz.getDeclaredConstructor(new Class[0]);
            mCtor.setAccessible(true);
            mExecutor = (ThreadPoolExecutor)mInstanceField.get(null);
            mLock = new Object();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getName() {
        return "Fallback Delivery Executor";
    }

    @Override
    public boolean isIdleNow() {
        return mExecutor == null;
    }

    @Override
    public void registerIdleTransitionCallback(ResourceCallback callback) {
        synchronized (mLock) {
            mCallback = callback;
        }
    }

    // possible race condition here if we call this more than once.. might be resolved with WeakReference or smarter checking.. just don't call it more than once
    public FallbackDeliveryExecutorIdlingResource initiateIdling() {
        if (mExecutor == null) {
            if (mCallback != null)
                mCallback.onTransitionToIdle();
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        mExecutor.awaitTermination(60, TimeUnit.SECONDS);
                        mInstanceField.set(null, mCtor.newInstance((Object[])null));
                        synchronized (mLock) {
                            if (mCallback != null)
                                mCallback.onTransitionToIdle();
                        }
                        mExecutor = null;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }).start();

            mExecutor.shutdown();
        }

        return this;
    }
}
