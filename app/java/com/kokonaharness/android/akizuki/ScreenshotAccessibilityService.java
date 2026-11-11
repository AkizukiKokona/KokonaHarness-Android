package com.kokonaharness.android.akizuki;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal accessibility service used by the "accessibility" run mode.
 *
 * <p>Android 11+ lets an enabled accessibility service capture the screen
 * without a per-session MediaProjection consent dialog. The runtime exposes
 * this through a loopback endpoint, so model screenshot requests go through
 * the system instead of the unusable {@code screencap} binary.</p>
 */
public class ScreenshotAccessibilityService extends AccessibilityService {
    private static volatile ScreenshotAccessibilityService instance;
    private static volatile String foregroundPackage;
    private static volatile String foregroundClass;
    private static volatile long foregroundSince = -1;

    public static boolean isConnected() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || pkg.length() == 0) return;
        if (foregroundPackage == null || !foregroundPackage.contentEquals(pkg)
                || foregroundClass == null || (cls != null && !foregroundClass.contentEquals(cls))) {
            foregroundSince = android.os.SystemClock.uptimeMillis();
        }
        foregroundPackage = pkg.toString();
        if (cls != null) foregroundClass = cls.toString();
    }

    /** Foreground app package as observed from window-state events, or null. */
    public static String foregroundPackage() {
        return foregroundPackage;
    }

    public static String foregroundClass() {
        return foregroundClass;
    }

    public static long foregroundSince() {
        return foregroundSince;
    }

    /** All interactive windows known to the service, or null when disabled. */
    public static List<AccessibilityWindowInfo> windows() {
        ScreenshotAccessibilityService service = instance;
        return service == null ? null : service.getWindows();
    }

    /** The currently focused window, or null. */
    public static AccessibilityWindowInfo activeWindow() {
        ScreenshotAccessibilityService service = instance;
        if (service == null) return null;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.isActive()) return window;
        }
        return null;
    }

    @Override
    public void onInterrupt() {
    }

    /** Capture the default display as PNG bytes, or throw with a user-facing reason. */
    public static byte[] capture() throws Exception {
        ScreenshotAccessibilityService service = instance;
        if (service == null) {
            throw new IllegalStateException("无障碍服务未开启，请到 设置 > 无障碍 中启用 KHA");
        }
        if (Build.VERSION.SDK_INT < 30) {
            throw new IllegalStateException("当前系统 Android " + Build.VERSION.SDK_INT
                    + " 低于 Android 11，无障碍截图不可用；应改走 MediaProjection 路径");
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmap = new AtomicReference<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        service.takeScreenshot(Display.DEFAULT_DISPLAY,
                service.getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        try {
                            Bitmap hardware = Bitmap.wrapHardwareBuffer(
                                    buffer, result.getColorSpace());
                            if (hardware == null) {
                                failure.set("系统返回了空截图");
                            } else {
                                bitmap.set(hardware.copy(Bitmap.Config.ARGB_8888, false));
                                hardware.recycle();
                            }
                        } catch (Throwable error) {
                            failure.set("截图解码失败：" + error.getMessage());
                        } finally {
                            buffer.close();
                            done.countDown();
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        failure.set("系统截图失败（" + errorCode + "）");
                        done.countDown();
                    }
                });
        if (!done.await(6, TimeUnit.SECONDS)) {
            throw new IllegalStateException("系统截图超时");
        }
        Bitmap image = bitmap.get();
        if (image == null) throw new IllegalStateException(failure.get());
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } finally {
            image.recycle();
        }
    }

    /** Expose the root window node for UI automation helpers. */
    public static AccessibilityNodeInfo rootNode() {
        ScreenshotAccessibilityService service = instance;
        return service == null ? null : service.getRootInActiveWindow();
    }

    public static boolean globalAction(int action) {
        ScreenshotAccessibilityService service = instance;
        return service != null && service.performGlobalAction(action);
    }

    /**
     * Dispatch a stroke gesture (tap, long press or swipe) and wait for its
     * completion so tool callers observe a settled screen.
     */
    public static void gesture(int startX, int startY, int endX, int endY, long durationMs)
            throws Exception {
        List<android.graphics.Path> paths = new java.util.ArrayList<>();
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(startX, startY);
        if (startX != endX || startY != endY) path.lineTo(endX, endY);
        paths.add(path);
        gesture(paths, new long[]{0}, new long[]{durationMs});
    }

    /** Dispatch a multi-pointer gesture (tap, swipe, pinch, two-finger drag). */
    public static void gesture(List<android.graphics.Path> paths, long[] startDelays,
                               long[] durations) throws Exception {
        ScreenshotAccessibilityService service = instance;
        if (service == null) throw new IllegalStateException("无障碍服务未开启");
        if (Build.VERSION.SDK_INT < 24) throw new IllegalStateException("系统版本过低，无法模拟手势");
        if (paths == null || paths.isEmpty()) throw new IllegalArgumentException("缺少手势路径");
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (int index = 0; index < paths.size(); index++) {
            long start = index < startDelays.length ? startDelays[index] : 0;
            long duration = index < durations.length ? durations[index] : 1;
            builder.addStroke(new GestureDescription.StrokeDescription(
                    paths.get(index), Math.max(0, start), Math.max(1, duration)));
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>();
        boolean dispatched = service.dispatchGesture(builder.build(),
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription description) {
                        done.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription description) {
                        failure.set("手势被系统取消");
                        done.countDown();
                    }
                }, null);
        if (!dispatched) throw new IllegalStateException("系统拒绝执行手势");
        if (!done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("手势执行超时");
        if (failure.get() != null) throw new IllegalStateException(failure.get());
    }
}
