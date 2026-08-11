/*
 * Copyright 2026 Nucleus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.display;

import static com.android.server.display.DisplayModeFactory.createMode;

import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.util.LongSparseArray;
import android.view.Display;
import android.view.DisplayShape;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.Keep;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.utils.DebugTransactionDetails;

/**
 * Owns the SurfaceFlinger virtual output that is Android's logical default display when Android is
 * hosted by Nucleus.
 */
final class NucleusHostDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "NucleusHostDisplay";
    private static final long DESKTOP_PRESENTATION_ID = 0;
    private static final String UNIQUE_ID_PREFIX = "nucleus:host-display";

    private static native long nativeCreate(long presentationId);

    private static native long[] nativeGetInitialConfiguration(long handle);

    private static native Surface nativeGetSurface(long handle);

    private static native void nativeStart(long handle, HostDisplay display);

    private static native void nativeApplyConfiguration(
            long handle, long generation, int width, int height);

    private static native void nativeDestroy(long handle);

    private final LongSparseArray<HostDisplay> mDisplays = new LongSparseArray<>();
    private final NativeDisplayFactory mNativeDisplays;
    private final SurfaceControlDisplayFactory mSurfaceControlDisplays;

    interface NativeDisplayFactory {
        long create(long presentationId);

        long[] getInitialConfiguration(long handle);

        Surface getSurface(long handle);

        void start(long handle, HostDisplay display);

        void applyConfiguration(long handle, long generation, int width, int height);

        void destroy(long handle);
    }

    interface SurfaceControlDisplayFactory {
        IBinder create(String name, String uniqueId, float refreshRate);

        void destroy(IBinder token);
    }

    private static final class JniNativeDisplayFactory implements NativeDisplayFactory {
        static {
            System.loadLibrary("nucleus_host_display_jni");
        }

        @Override
        public long create(long presentationId) {
            return nativeCreate(presentationId);
        }

        @Override
        public long[] getInitialConfiguration(long handle) {
            return nativeGetInitialConfiguration(handle);
        }

        @Override
        public Surface getSurface(long handle) {
            return nativeGetSurface(handle);
        }

        @Override
        public void start(long handle, HostDisplay display) {
            nativeStart(handle, display);
        }

        @Override
        public void applyConfiguration(long handle, long generation, int width, int height) {
            nativeApplyConfiguration(handle, generation, width, height);
        }

        @Override
        public void destroy(long handle) {
            nativeDestroy(handle);
        }
    }

    private static final class ProductionSurfaceControlDisplayFactory
            implements SurfaceControlDisplayFactory {
        @Override
        public IBinder create(String name, String uniqueId, float refreshRate) {
            return DisplayControl.createVirtualDisplay(
                    name, false, false, uniqueId, Process.SYSTEM_UID, refreshRate);
        }

        @Override
        public void destroy(IBinder token) {
            DisplayControl.destroyVirtualDisplay(token);
        }
    }

    NucleusHostDisplayAdapter(
            DisplayManagerService.SyncRoot syncRoot,
            Context context,
            Handler handler,
            Listener listener,
            DisplayManagerFlags flags) {
        this(
                syncRoot,
                context,
                handler,
                listener,
                flags,
                new JniNativeDisplayFactory(),
                new ProductionSurfaceControlDisplayFactory());
    }

    NucleusHostDisplayAdapter(
            DisplayManagerService.SyncRoot syncRoot,
            Context context,
            Handler handler,
            Listener listener,
            DisplayManagerFlags flags,
            NativeDisplayFactory nativeDisplays,
            SurfaceControlDisplayFactory surfaceControlDisplays) {
        super(syncRoot, context, handler, listener, TAG, flags);
        mNativeDisplays = nativeDisplays;
        mSurfaceControlDisplays = surfaceControlDisplays;
    }

    @Override
    public void registerLocked() {
        final HostDisplay display = createPresentationLocked(DESKTOP_PRESENTATION_ID);
        sendDisplayDeviceEventLocked(display.mDevice, DISPLAY_DEVICE_EVENT_ADDED);
    }

    DisplayDevice createApplicationPresentationLocked(long presentationId) {
        if (presentationId == DESKTOP_PRESENTATION_ID) {
            throw new IllegalArgumentException("Application presentation identity must be nonzero");
        }
        return createPresentationLocked(presentationId).mDevice;
    }

    private HostDisplay createPresentationLocked(long presentationId) {
        if (presentationId < 0 || mDisplays.get(presentationId) != null) {
            throw new IllegalArgumentException(
                    "Invalid or duplicate Nucleus presentation " + presentationId);
        }
        final HostDisplay display = new HostDisplay(presentationId);
        try {
            display.initializeLocked();
        } catch (RuntimeException error) {
            display.destroyNative();
            throw error;
        }
        mDisplays.put(presentationId, display);
        return display;
    }

    DisplayDevice removeApplicationPresentationLocked(long presentationId) {
        if (presentationId == DESKTOP_PRESENTATION_ID) {
            throw new IllegalArgumentException(
                    "The Nucleus desktop presentation cannot be removed");
        }
        final HostDisplay display = mDisplays.get(presentationId);
        if (display == null) {
            throw new IllegalArgumentException("Unknown Nucleus presentation " + presentationId);
        }
        mDisplays.remove(presentationId);
        final DisplayDevice device = display.mDevice;
        final long handle = display.detachLocked(false);
        getHandler().post(() -> mNativeDisplays.destroy(handle));
        return device;
    }

    DisplayDevice getPresentationDeviceLocked(long presentationId) {
        final HostDisplay display = mDisplays.get(presentationId);
        return display == null ? null : display.mDevice;
    }

    int getPresentationCountLocked() {
        return mDisplays.size();
    }

    private static String uniqueId(long presentationId) {
        return presentationId == DESKTOP_PRESENTATION_ID
                ? UNIQUE_ID_PREFIX
                : UNIQUE_ID_PREFIX + ":" + presentationId;
    }

    final class HostDisplay {
        private final long mPresentationId;
        private long mNativeHandle;
        private IBinder mDisplayToken;
        private Surface mSurface;
        private HostDisplayDevice mDevice;

        HostDisplay(long presentationId) {
            mPresentationId = presentationId;
        }

        void initializeLocked() {
            mNativeHandle = mNativeDisplays.create(mPresentationId);
            if (mNativeHandle == 0) {
                throw new IllegalStateException("Nucleus host-display transport is unavailable");
            }
            final long[] configuration = mNativeDisplays.getInitialConfiguration(mNativeHandle);
            if (configuration == null || configuration.length != 5) {
                throw new IllegalStateException("Nucleus host-display configuration is invalid");
            }
            final int width = Math.toIntExact(configuration[1]);
            final int height = Math.toIntExact(configuration[2]);
            final int densityDpi = Math.toIntExact(configuration[3]);
            final int refreshMillihertz = Math.toIntExact(configuration[4]);
            if (configuration[0] <= 0
                    || width <= 0
                    || height <= 0
                    || densityDpi <= 0
                    || refreshMillihertz <= 0) {
                throw new IllegalStateException("Nucleus host-display configuration is invalid");
            }
            mSurface = mNativeDisplays.getSurface(mNativeHandle);
            if (mSurface == null) {
                throw new IllegalStateException("Nucleus host-display surface is unavailable");
            }
            final String uniqueId = uniqueId(mPresentationId);
            mDisplayToken =
                    mSurfaceControlDisplays.create(
                            mPresentationId == DESKTOP_PRESENTATION_ID
                                    ? "Nucleus host display"
                                    : "Nucleus application presentation " + mPresentationId,
                            uniqueId,
                            refreshMillihertz / 1000.0f);
            if (mDisplayToken == null) {
                throw new IllegalStateException("Creating Nucleus virtual display failed");
            }
            mDevice =
                    new HostDisplayDevice(
                            mDisplayToken,
                            mSurface,
                            uniqueId,
                            mPresentationId == DESKTOP_PRESENTATION_ID,
                            configuration[0],
                            width,
                            height,
                            densityDpi,
                            refreshMillihertz);
            mNativeDisplays.start(mNativeHandle, this);
        }

        @Keep // Called from JNI.
        private void onHostDisplayConfiguration(
                long generation, int width, int height, int densityDpi, int refreshMillihertz) {
            getHandler()
                    .post(
                            () -> {
                                synchronized (getSyncRoot()) {
                                    if (mDisplays.get(mPresentationId) != this
                                            || mNativeHandle == 0
                                            || generation <= mDevice.mGeneration
                                            || width <= 0
                                            || height <= 0
                                            || densityDpi <= 0
                                            || refreshMillihertz <= 0) {
                                        return;
                                    }
                                    mNativeDisplays.applyConfiguration(
                                            mNativeHandle, generation, width, height);
                                    mDevice.resizeLocked(
                                            generation,
                                            width,
                                            height,
                                            densityDpi,
                                            refreshMillihertz);
                                }
                            });
        }

        long detachLocked(boolean notifyRemoval) {
            final long handle = mNativeHandle;
            mNativeHandle = 0;
            if (mDevice != null) {
                if (notifyRemoval) {
                    sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_REMOVED);
                }
                mDevice = null;
            }
            if (mDisplayToken != null) {
                mSurfaceControlDisplays.destroy(mDisplayToken);
                mDisplayToken = null;
            }
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            return handle;
        }

        void destroyNative() {
            final long handle;
            synchronized (getSyncRoot()) {
                handle = detachLocked(false);
            }
            if (handle != 0) {
                mNativeDisplays.destroy(handle);
            }
        }
    }

    @Override
    public void stop() {
        final long[] handles;
        synchronized (getSyncRoot()) {
            handles = new long[mDisplays.size()];
            for (int index = 0; index < mDisplays.size(); index++) {
                handles[index] = mDisplays.valueAt(index).detachLocked(false);
            }
            mDisplays.clear();
        }
        for (long handle : handles) {
            if (handle != 0) {
                mNativeDisplays.destroy(handle);
            }
        }
    }

    private final class HostDisplayDevice extends DisplayDevice {
        private final Surface mSurface;
        private final boolean mDesktop;
        private final String mUniqueId;
        private long mGeneration;
        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        private int mRefreshMillihertz;
        private Display.Mode mMode;
        private DisplayDeviceInfo mInfo;
        private boolean mSurfacePending = true;
        private boolean mSizePending = true;

        HostDisplayDevice(
                IBinder token,
                Surface surface,
                String uniqueId,
                boolean desktop,
                long generation,
                int width,
                int height,
                int densityDpi,
                int refreshMillihertz) {
            super(NucleusHostDisplayAdapter.this, token, uniqueId, getContext());
            mSurface = surface;
            mUniqueId = uniqueId;
            mDesktop = desktop;
            mGeneration = generation;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
            mRefreshMillihertz = refreshMillihertz;
            mMode = createMode(width, height, refreshMillihertz / 1000.0f);
        }

        @Override
        public boolean hasStableUniqueId() {
            return true;
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mDesktop ? "Nucleus host display" : "Nucleus application presentation";
                mInfo.uniqueId = mUniqueId;
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.modeId = mMode.getModeId();
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] {mMode};
                mInfo.renderFrameRate = mMode.getRefreshRate();
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = 1_000_000_000L * 1000L / mRefreshMillihertz;
                mInfo.flags =
                        DisplayDeviceInfo.FLAG_TRUSTED
                                | DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                if (mDesktop) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY;
                } else {
                    mInfo.flags |=
                            DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY
                                    | DisplayDeviceInfo.FLAG_DESTROY_CONTENT_ON_REMOVAL;
                }
                mInfo.type = mDesktop ? Display.TYPE_INTERNAL : Display.TYPE_VIRTUAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                mInfo.state = Display.STATE_ON;
                mInfo.ownerUid = Process.SYSTEM_UID;
                mInfo.ownerPackageName = "android";
                mInfo.displayShape = DisplayShape.createDefaultDisplayShape(mWidth, mHeight, false);
            }
            return mInfo;
        }

        void resizeLocked(
                long generation, int width, int height, int densityDpi, int refreshMillihertz) {
            mGeneration = generation;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
            mRefreshMillihertz = refreshMillihertz;
            mMode = createMode(width, height, refreshMillihertz / 1000.0f);
            mInfo = null;
            mSizePending = true;
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
            sendTraversalRequestLocked();
        }

        @Override
        public void configureSurfaceLocked(SurfaceControl.Transaction transaction) {
            if (mSurfacePending) {
                setSurfaceLocked(transaction, mSurface);
                mSurfacePending = false;
            }
            if (mSizePending) {
                setDisplaySizeLocked(transaction, mWidth, mHeight, null);
                mSizePending = false;
            }
        }

        @Override
        public void configureDisplaySizeLocked(
                SurfaceControl.Transaction transaction, DebugTransactionDetails details) {
            if (mSizePending) {
                setDisplaySizeLocked(transaction, mWidth, mHeight, details);
                mSizePending = false;
            }
        }
    }
}
