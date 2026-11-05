package com.knh.akizukikokona;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native, user-facing confirmation for state-changing phone operations.
 *
 * <p>The MCP layer calls this before touching the device. Requests are
 * serialized so the user never sees two dialogs at once, and they resolve to
 * deny when the app is not visible or the user does not answer in time.</p>
 */
public final class ConfirmationGate {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final long MIN_INTERVAL_MS = 1200L;
    private static final int MAX_PENDING = 3;

    private static WeakReference<Activity> activityRef;
    private static AlertDialog currentDialog;
    private static volatile boolean visible;
    private static volatile long lastDialogAt;

    private ConfirmationGate() {
    }

    static void attach(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }

    /** Tracks real foreground visibility; a paused activity must not host a dialog. */
    static void setVisible(Activity activity, boolean shown) {
        if (shown) activityRef = new WeakReference<>(activity);
        visible = shown;
        if (!shown) dismissCurrent();
    }

    static void detach(Activity activity) {
        Activity current = activityRef == null ? null : activityRef.get();
        if (current == activity) activityRef = null;
        visible = false;
        dismissCurrent();
    }

    private static void dismissCurrent() {
        AlertDialog dialog = currentDialog;
        currentDialog = null;
        if (dialog != null) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    /**
     * @return TRUE when allowed, FALSE when denied or timed out, NULL when the
     * app has no visible activity to host the dialog.
     */
    static Boolean request(final String detail, final boolean critical, final int timeoutSeconds) {
        if (!visible) return null;

        int pending = PENDING.incrementAndGet();
        if (pending > MAX_PENDING) {
            PENDING.decrementAndGet();
            return false;
        }
        try {
            synchronized (LOCK) {
                final Activity activity = activityRef == null ? null : activityRef.get();
                if (!visible || activity == null || activity.isFinishing() || isDestroyed(activity)) {
                    return null;
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastDialogAt < MIN_INTERVAL_MS) return false;
                lastDialogAt = now;

                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicBoolean allowed = new AtomicBoolean(false);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || isDestroyed(activity)) {
                            latch.countDown();
                            return;
                        }
                        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                                .setTitle(critical ? "高风险手机操作" : "允许手机操作？")
                                .setMessage(detail + "\n\n确认后将直接作用于当前手机界面。")
                                .setCancelable(false)
                                .setPositiveButton("允许", (dialog, which) -> {
                                    allowed.set(true);
                                    latch.countDown();
                                })
                                .setNegativeButton("拒绝", (dialog, which) -> latch.countDown());
                        final AlertDialog dialog = builder.create();
                        currentDialog = dialog;
                        dialog.setOnDismissListener(d -> latch.countDown());
                        dialog.show();
                        MAIN.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (dialog.isShowing()) {
                                    try {
                                        dialog.dismiss();
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        }, Math.max(1, timeoutSeconds) * 1000L);
                    }
                });
                try {
                    latch.await(Math.max(1, timeoutSeconds) + 2L, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                AlertDialog dialog = currentDialog;
                currentDialog = null;
                if (dialog != null && dialog.isShowing()) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                dialog.dismiss();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }
                return allowed.get();
            }
        } finally {
            PENDING.decrementAndGet();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isDestroyed(Activity activity) {
        return Build.VERSION.SDK_INT >= 17 && activity.isDestroyed();
    }
}
