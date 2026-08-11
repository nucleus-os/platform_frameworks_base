/*
 * Copyright 2026 Nucleus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.server.display;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.IBinder;
import android.testing.TestableContext;
import android.view.Display;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class NucleusHostDisplayAdapterTest {
    private static final long[] INITIAL_CONFIGURATION = {
        1, 1280, 720, 160, 60_000,
    };

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getInstrumentation().getContext());

    @Mock private DisplayAdapter.Listener mListener;
    @Mock private DisplayManagerFlags mFlags;
    @Mock private NucleusHostDisplayAdapter.NativeDisplayFactory mNativeDisplays;
    @Mock private NucleusHostDisplayAdapter.SurfaceControlDisplayFactory mSurfaceDisplays;
    @Mock private Surface mDesktopSurface;
    @Mock private Surface mFirstSurface;
    @Mock private Surface mSecondSurface;
    @Mock private Surface mFailedSurface;
    @Mock private IBinder mDesktopToken;
    @Mock private IBinder mFirstToken;
    @Mock private IBinder mSecondToken;

    private TestHandler mHandler;
    private NucleusHostDisplayAdapter mAdapter;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mHandler = new TestHandler(null);
        when(mNativeDisplays.create(0)).thenReturn(100L);
        when(mNativeDisplays.create(1)).thenReturn(101L);
        when(mNativeDisplays.create(2)).thenReturn(102L);
        when(mNativeDisplays.getInitialConfiguration(anyLong())).thenReturn(INITIAL_CONFIGURATION);
        when(mNativeDisplays.getSurface(100L)).thenReturn(mDesktopSurface);
        when(mNativeDisplays.getSurface(101L)).thenReturn(mFirstSurface);
        when(mNativeDisplays.getSurface(102L)).thenReturn(mSecondSurface);
        when(mSurfaceDisplays.create(anyString(), anyString(), anyFloat()))
                .thenReturn(mDesktopToken, mFirstToken, mSecondToken);
        mAdapter =
                new NucleusHostDisplayAdapter(
                        new DisplayManagerService.SyncRoot(),
                        mContext,
                        mHandler,
                        mListener,
                        mFlags,
                        mNativeDisplays,
                        mSurfaceDisplays);
    }

    @Test
    public void twoApplicationPresentationsHaveIndependentDevicesAndTeardown() {
        mAdapter.registerLocked();
        mAdapter.createApplicationPresentationLocked(1);
        mAdapter.createApplicationPresentationLocked(2);

        assertThat(mAdapter.getPresentationCountLocked()).isEqualTo(3);
        assertThat(mAdapter.getPresentationDeviceLocked(0).getDisplayDeviceInfoLocked().uniqueId)
                .isEqualTo("nucleus:host-display");
        assertThat(mAdapter.getPresentationDeviceLocked(1).getDisplayDeviceInfoLocked().uniqueId)
                .isEqualTo("nucleus:host-display:1");
        assertThat(mAdapter.getPresentationDeviceLocked(2).getDisplayDeviceInfoLocked().uniqueId)
                .isEqualTo("nucleus:host-display:2");
        assertThat(mAdapter.getPresentationDeviceLocked(0).getDisplayDeviceInfoLocked().type)
                .isEqualTo(Display.TYPE_INTERNAL);
        assertThat(mAdapter.getPresentationDeviceLocked(1).getDisplayDeviceInfoLocked().type)
                .isEqualTo(Display.TYPE_VIRTUAL);
        assertThat(
                        mAdapter.getPresentationDeviceLocked(1).getDisplayDeviceInfoLocked().flags
                                & DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY)
                .isNotEqualTo(0);
        assertThat(
                        mAdapter.getPresentationDeviceLocked(1).getDisplayDeviceInfoLocked().flags
                                & DisplayDeviceInfo.FLAG_DESTROY_CONTENT_ON_REMOVAL)
                .isNotEqualTo(0);
        assertThat(
                        mAdapter.getPresentationDeviceLocked(0).getDisplayDeviceInfoLocked().flags
                                & DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY)
                .isEqualTo(0);

        DisplayDevice removed = mAdapter.removeApplicationPresentationLocked(1);
        mHandler.flush();

        assertThat(mAdapter.getPresentationCountLocked()).isEqualTo(2);
        assertThat(mAdapter.getPresentationDeviceLocked(1)).isNull();
        assertThat(mAdapter.getPresentationDeviceLocked(2)).isNotNull();
        assertThat(removed.getDisplayDeviceInfoLocked().uniqueId)
                .isEqualTo("nucleus:host-display:1");
        verify(mFirstSurface).release();
        verify(mSecondSurface, never()).release();
        verify(mNativeDisplays).destroy(101L);
        verify(mNativeDisplays, never()).destroy(102L);
        verify(mSurfaceDisplays).destroy(mFirstToken);
        verify(mSurfaceDisplays, never()).destroy(mSecondToken);
    }

    @Test(expected = IllegalArgumentException.class)
    public void desktopPresentationCannotBeRemoved() {
        mAdapter.registerLocked();
        mAdapter.removeApplicationPresentationLocked(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateLivePresentationIsRejected() {
        mAdapter.registerLocked();
        mAdapter.createApplicationPresentationLocked(1);
        mAdapter.createApplicationPresentationLocked(1);
    }

    @Test
    public void failedPresentationCreationReleasesPartialResources() {
        when(mNativeDisplays.create(3)).thenReturn(103L);
        when(mNativeDisplays.getSurface(103L)).thenReturn(mFailedSurface);
        when(mSurfaceDisplays.create(anyString(), eq("nucleus:host-display:3"), anyFloat()))
                .thenReturn(null);

        assertThrows(
                IllegalStateException.class, () -> mAdapter.createApplicationPresentationLocked(3));

        assertThat(mAdapter.getPresentationDeviceLocked(3)).isNull();
        verify(mFailedSurface).release();
        verify(mNativeDisplays).destroy(103L);
    }
}
