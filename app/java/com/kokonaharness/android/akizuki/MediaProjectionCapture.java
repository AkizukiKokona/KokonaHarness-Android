package com.kokonaharness.android.akizuki;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Screen capture through MediaProjection, for API 28-29 where
 * {@code AccessibilityService.takeScreenshot()} does not exist yet (API 30+).
 *
 * <p>The consent token is cached for the app session: the first capture shows
 * the system dialog, later captures reuse the same {@link MediaProjection} (no
 * second dialog) until the user revokes it or the app is destroyed. Every
 * capture still releases its own VirtualDisplay/ImageReader; the projection
 * itself stays alive for reuse.</p>
 *
 * <p>Windows with {@code FLAG_SECURE} capture as black — a system behaviour,
 * not a failure of this path.</p>
 */
public final class MediaProjectionCapture {
    /** Request code used by MainActivity.onActivityResult. */
    public static final int REQUEST_CODE = 5001;

    private static final Object LOCK = new Object();
    private static volatile MediaProjection cachedProjection;
    private static volatile CountDownLatch permissionLatch;
    private static volatile Intent permissionData;
    private static volatile int permissionResult = Activity.RESULT_CANCELED;

    private MediaProjectionCapture() {
    }

    /** Called from MainActivity.onActivityResult when REQUEST_CODE comes back. */
    static void deliverResult(int resultCode, Intent data) {
        permissionResult = resultCode;
        permissionData = data;
        CountDownLatch latch = permissionLatch;
        if (latch != null) latch.countDown();
    }

    /** Stop and forget the cached projection (call from Activity.onDestroy). */
    public static void release() {
        synchronized (LOCK) {
            MediaProjection projection = cachedProjection;
            cachedProjection = null;
            if (projection != null) {
                try {
                    projection.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Capture one frame. Must be called off the UI thread: it blocks while the
     * consent dialog is up (first call only) and while the frame arrives.
     */
    public static byte[] capture(Activity activity) throws Exception {
        synchronized (LOCK) {
            if (activity == null) throw new IllegalStateException("无法截图：当前没有可用的界面");
            MediaProjectionManager manager =
                    (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("系统不支持屏幕捕获");

            MediaProjection projection = cachedProjection;
            if (projection == null) {
                projection = requestProjection(activity, manager);
                cachedProjection = projection;
            }
            try {
                return grabFrame(activity, projection);
            } catch (Exception error) {
                // A dead/failed session would fail every future call; drop it so
                // the next capture re-asks for consent instead of looping.
                dropCached(projection);
                throw error;
            }
        }
    }

    private static MediaProjection requestProjection(Activity activity,
                                                     MediaProjectionManager manager) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        permissionLatch = latch;
        permissionData = null;
        permissionResult = Activity.RESULT_CANCELED;
        final Intent prompt = manager.createScreenCaptureIntent();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.startActivityForResult(prompt, REQUEST_CODE);
                } catch (Throwable error) {
                    CountDownLatch pending = permissionLatch;
                    if (pending != null) pending.countDown();
                }
            }
        });
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw new IllegalStateException("等待屏幕捕获授权超时");
        }
        Intent data = permissionData;
        int result = permissionResult;
        if (result != Activity.RESULT_OK || data == null) {
            throw new IllegalStateException("已取消屏幕捕获授权");
        }
        final MediaProjection projection = manager.getMediaProjection(result, data);
        if (projection == null) throw new IllegalStateException("无法创建屏幕捕获会话");
        // The system fires onStop when the user revokes or the session dies.
        try {
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    synchronized (LOCK) {
                        if (cachedProjection == projection) cachedProjection = null;
                    }
                }
            }, null);
        } catch (Throwable ignored) {
        }
        return projection;
    }

    private static byte[] grabFrame(Activity activity, MediaProjection projection) throws Exception {
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        final int width = metrics.widthPixels;
        final int height = metrics.heightPixels;
        final int density = metrics.densityDpi;

        ImageReader reader = null;
        VirtualDisplay display = null;
        Image image = null;
        Bitmap padded = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("kha-capture", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);

            long deadline = System.currentTimeMillis() + 4000L;
            while (image == null && System.currentTimeMillis() < deadline) {
                image = reader.acquireLatestImage();
                if (image == null) {
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (image == null) throw new IllegalStateException("屏幕捕获超时，未取到画面");

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * width;
            padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
                return out.toByteArray();
            } finally {
                if (cropped != padded) cropped.recycle();
            }
        } finally {
            if (image != null) image.close();
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (padded != null && !padded.isRecycled()) padded.recycle();
        }
    }

    private static void dropCached(MediaProjection projection) {
        synchronized (LOCK) {
            if (cachedProjection == projection) {
                cachedProjection = null;
                try {
                    projection.stop();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
