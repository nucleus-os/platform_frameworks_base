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
import android.view.Display;
import android.view.DisplayShape;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.Keep;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.utils.DebugTransactionDetails;

/**
 * Owns the SurfaceFlinger virtual output that is Android's logical default
 * display when Android is hosted by Nucleus.
 */
final class NucleusHostDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "NucleusHostDisplay";
    private static final String UNIQUE_ID = "nucleus:host-display";

    static {
        System.loadLibrary("nucleus_host_display_jni");
    }

    private static native long nativeCreate(long presentationId);
    private static native long[] nativeGetInitialConfiguration(long handle);
    private static native Surface nativeGetSurface(long handle);
    private static native void nativeStart(long handle, NucleusHostDisplayAdapter adapter);
    private static native void nativeApplyConfiguration(
            long handle, long generation, int width, int height);
    private static native void nativeDestroy(long handle);

    private long mNativeHandle;
    private HostDisplayDevice mDevice;

    NucleusHostDisplayAdapter(
            DisplayManagerService.SyncRoot syncRoot,
            Context context,
            Handler handler,
            Listener listener,
            DisplayManagerFlags flags) {
        super(syncRoot, context, handler, listener, TAG, flags);
    }

    @Override
    public void registerLocked() {
        mNativeHandle = nativeCreate(0);
        if (mNativeHandle == 0) {
            throw new IllegalStateException(
                    "Nucleus host-display transport is unavailable");
        }
        final long[] configuration =
                nativeGetInitialConfiguration(mNativeHandle);
        if (configuration == null || configuration.length != 5) {
            nativeDestroy(mNativeHandle);
            mNativeHandle = 0;
            throw new IllegalStateException(
                    "Nucleus host-display configuration is invalid");
        }
        final int width = Math.toIntExact(configuration[1]);
        final int height = Math.toIntExact(configuration[2]);
        final int densityDpi = Math.toIntExact(configuration[3]);
        final int refreshMillihertz = Math.toIntExact(configuration[4]);
        final Surface surface = nativeGetSurface(mNativeHandle);
        if (surface == null) {
            nativeDestroy(mNativeHandle);
            mNativeHandle = 0;
            throw new IllegalStateException(
                    "Nucleus host-display surface is unavailable");
        }
        final IBinder token = DisplayControl.createVirtualDisplay(
                "Nucleus host display",
                false,
                false,
                UNIQUE_ID,
                Process.SYSTEM_UID,
                refreshMillihertz / 1000.0f);
        mDevice = new HostDisplayDevice(
                token,
                surface,
                configuration[0],
                width,
                height,
                densityDpi,
                refreshMillihertz);
        nativeStart(mNativeHandle, this);
        sendDisplayDeviceEventLocked(mDevice, DISPLAY_DEVICE_EVENT_ADDED);
    }

    @Keep // Called from JNI.
    private void onHostDisplayConfiguration(
            long generation,
            int width,
            int height,
            int densityDpi,
            int refreshMillihertz) {
        getHandler().post(() -> {
            synchronized (getSyncRoot()) {
                if (mDevice == null
                        || generation <= mDevice.mGeneration
                        || width <= 0
                        || height <= 0
                        || densityDpi <= 0
                        || refreshMillihertz <= 0) {
                    return;
                }
                nativeApplyConfiguration(
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

    @Override
    public void stop() {
        final long handle;
        synchronized (getSyncRoot()) {
            handle = mNativeHandle;
            mNativeHandle = 0;
            if (mDevice != null) {
                DisplayControl.destroyVirtualDisplay(
                        mDevice.getDisplayTokenLocked());
                mDevice = null;
            }
        }
        if (handle != 0) {
            nativeDestroy(handle);
        }
    }

    private final class HostDisplayDevice extends DisplayDevice {
        private final Surface mSurface;
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
                long generation,
                int width,
                int height,
                int densityDpi,
                int refreshMillihertz) {
            super(NucleusHostDisplayAdapter.this, token, UNIQUE_ID, getContext());
            mSurface = surface;
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
                mInfo.name = "Nucleus host display";
                mInfo.uniqueId = UNIQUE_ID;
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.modeId = mMode.getModeId();
                mInfo.defaultModeId = mMode.getModeId();
                mInfo.supportedModes = new Display.Mode[] {mMode};
                mInfo.renderFrameRate = mMode.getRefreshRate();
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos =
                        1_000_000_000L * 1000L / mRefreshMillihertz;
                mInfo.flags =
                        DisplayDeviceInfo.FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY
                        | DisplayDeviceInfo.FLAG_TRUSTED
                        | DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT;
                mInfo.type = Display.TYPE_INTERNAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                mInfo.state = Display.STATE_ON;
                mInfo.ownerUid = Process.SYSTEM_UID;
                mInfo.ownerPackageName = "android";
                mInfo.displayShape = DisplayShape.createDefaultDisplayShape(
                        mWidth, mHeight, false);
            }
            return mInfo;
        }

        void resizeLocked(
                long generation,
                int width,
                int height,
                int densityDpi,
                int refreshMillihertz) {
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
                setDisplaySizeLocked(
                        transaction, mWidth, mHeight, null);
                mSizePending = false;
            }
        }

        @Override
        public void configureDisplaySizeLocked(
                SurfaceControl.Transaction transaction,
                DebugTransactionDetails details) {
            if (mSizePending) {
                setDisplaySizeLocked(
                        transaction, mWidth, mHeight, details);
                mSizePending = false;
            }
        }
    }
}
