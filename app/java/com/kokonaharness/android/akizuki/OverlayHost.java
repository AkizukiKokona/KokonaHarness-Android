package com.kokonaharness.android.akizuki;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One system overlay window that plugin pages can draw into.
 *
 * <p>Desktop pets and HUDs need a window that survives switching apps, which a
 * WebView inside this activity cannot provide: it is clipped to the activity
 * and dies with it. This host puts a single transparent WebView on
 * {@link WindowManager} with {@code TYPE_APPLICATION_OVERLAY} instead.</p>
 *
 * <p>Only one window exists at a time -- two pets stacked on top of each other
 * is never what the caller meant, and a slot makes {@code hide} unambiguous.
 * Everything here runs on the main thread; the bridge arrives from a worker.</p>
 */
final class OverlayHost {
    private static final String TAG = "KokonaOverlay";

    /** Overlay windows need this grant; it is not a runtime permission. */
    private static final Object LOCK = new Object();
    private static WindowManager manager;
    private static WebView view;
    private static WindowManager.LayoutParams params;
    private static String currentUrl = "";
    private static Context appContext;

    private OverlayHost() {
    }

    static boolean granted(Context context) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(context.getApplicationContext());
    }

    private static int dp(Context context) {
        return Math.max(1, Math.round(context.getResources().getDisplayMetrics().density));
    }

    /** JSON snapshot for status rows and the bridge. */
    static JSONObject statusJson(Context context) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", true);
            json.put("granted", granted(context));
            synchronized (LOCK) {
                // view and params are published together under LOCK; check both
                // so a status poll racing with hide() cannot NPE on params.
                boolean visible = view != null && params != null && view.isAttachedToWindow();
                json.put("visible", visible);
                if (visible) {
                    json.put("url", currentUrl);
                    json.put("widthDp", Math.round(params.width / (float) dp(context)));
                    json.put("heightDp", Math.round(params.height / (float) dp(context)));
                    json.put("x", params.x);
                    json.put("y", params.y);
                    json.put("alpha", params.alpha);
                }
            }
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            json.put("screenWidth", metrics.widthPixels);
            json.put("screenHeight", metrics.heightPixels);
            json.put("density", metrics.density);
            if (!granted(context)) {
                json.put("hint", "未授予悬浮窗权限：用 op=request 拉起系统设置，"
                        + "或在 App 设置 → 手机权限 → 悬浮窗 里开启");
            }
        } catch (Exception ignored) {
        }
        return json;
    }

    /** Settings screen for "display over other apps". */
    static void openSettings(Context context) {
        Context app = context.getApplicationContext();
        try {
            android.content.Intent intent = new android.content.Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + app.getPackageName()));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Throwable error) {
            try {
                app.startActivity(new android.content.Intent(Settings.ACTION_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Shows (or updates) the overlay. Options:
     * url (required), widthDp, heightDp, x, y, alpha, draggable, touchable,
     * reload.
     */
    static JSONObject show(Context context, JSONObject options) {
        if (!granted(context)) {
            JSONObject json = statusJson(context);
            try {
                json.put("ok", false);
                json.put("error", "未授予悬浮窗权限：先 op=request 拉起系统设置并允许"
                        + "「显示在其他应用上层」，再重试");
            } catch (Exception ignored) {
            }
            return json;
        }
        final Context app = context.getApplicationContext();
        appContext = app;
        final JSONObject opts = options == null ? new JSONObject() : options;
        final String url = opts.optString("url", "");
        if (url.isEmpty()) {
            JSONObject json = new JSONObject();
            try {
                json.put("ok", false);
                json.put("error", "缺少 url：可以是 http(s)、file:// 或 data:text/html,...");
            } catch (Exception ignored) {
            }
            return json;
        }
        return onMain(new OnMain() {
            @Override
            public JSONObject run() {
                return showOnMain(app, url, opts);
            }
        });
    }

    static JSONObject hide(Context context) {
        final Context app = context.getApplicationContext();
        return onMain(new OnMain() {
            @Override
            public JSONObject run() {
                return hideOnMain(app);
            }
        });
    }

    /** Drops the window without touching the bridge thread (used on shutdown). */
    static void hideQuietly(Context context) {
        try {
            final Context app = context.getApplicationContext();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    hideOnMain(app);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private interface OnMain {
        JSONObject run();
    }

    private static JSONObject onMain(final OnMain body) {
        if (Looper.myLooper() == Looper.getMainLooper()) return body.run();
        final AtomicReference<JSONObject> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(body.run());
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(6, TimeUnit.SECONDS)) {
                JSONObject json = new JSONObject();
                try {
                    json.put("ok", false);
                    json.put("error", "悬浮窗操作超时（主线程 6 秒无响应）");
                } catch (Exception ignored) {
                }
                return json;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        JSONObject done = result.get();
        if (done != null) return done;
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", "悬浮窗操作未返回结果");
        } catch (Exception ignored) {
        }
        return json;
    }

    private static JSONObject showOnMain(Context app, String url, JSONObject opts) {
        JSONObject json = new JSONObject();
        try {
            WindowManager windows = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            if (windows == null) {
                json.put("ok", false);
                json.put("error", "缺少 WindowManager");
                return json;
            }
            DisplayMetrics metrics = app.getResources().getDisplayMetrics();
            int screenW = metrics.widthPixels;
            int screenH = metrics.heightPixels;
            int scale = dp(app);
            int width = clamp(opts.optInt("widthDp", 160) * scale, 24 * scale, screenW);
            int height = clamp(opts.optInt("heightDp", 160) * scale, 24 * scale, screenH);
            int x = clamp(opts.optInt("x", screenW - width - 8 * scale), -width / 2, screenW);
            int y = clamp(opts.optInt("y", Math.round(screenH * 0.35f)), -height / 2, screenH);
            float alpha = (float) Math.max(0.1, Math.min(1.0, opts.optDouble("alpha", 1.0)));
            boolean draggable = opts.optBoolean("draggable", false);
            boolean touchable = opts.optBoolean("touchable", true);

            if (view == null) {
                WebView created = new WebView(app);
                created.setBackgroundColor(Color.TRANSPARENT);
                created.setVerticalScrollBarEnabled(false);
                created.setHorizontalScrollBarEnabled(false);
                created.setOverScrollMode(View.OVER_SCROLL_NEVER);
                WebSettings settings = created.getSettings();
                settings.setJavaScriptEnabled(true);
                settings.setDomStorageEnabled(true);
                settings.setAllowFileAccess(true);
                settings.setAllowContentAccess(true);
                // A pet page normally loads its own assets next to the HTML it
                // came from; without these two flags file:// pages cannot read
                // their own images on modern WebView.
                settings.setAllowFileAccessFromFileURLs(true);
                settings.setAllowUniversalAccessFromFileURLs(true);
                settings.setMediaPlaybackRequiresUserGesture(false);
                created.setWebViewClient(new WebViewClient());
                created.addJavascriptInterface(new OverlayBridge(), "KokonaOverlay");
                view = created;
            }

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

            if (params == null || manager != windows) {
                WindowManager.LayoutParams fresh = new WindowManager.LayoutParams(
                        width, height,
                        // minSdk is 27, so the legacy TYPE_PHONE path cannot be
                        // reached and is not worth a dead branch.
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        flags,
                        PixelFormat.TRANSLUCENT);
                fresh.gravity = Gravity.TOP | Gravity.START;
                fresh.x = x;
                fresh.y = y;
                fresh.alpha = alpha;
                fresh.setTitle("KokonaHarness overlay");
                params = fresh;
                manager = windows;
            } else {
                params.width = width;
                params.height = height;
                params.x = x;
                params.y = y;
                params.alpha = alpha;
                params.flags = flags;
            }

            if (draggable) {
                view.setOnTouchListener(new DragListener());
            } else {
                view.setOnTouchListener(null);
            }

            boolean attached = view.isAttachedToWindow();
            if (!attached) {
                windows.addView(view, params);
            } else {
                windows.updateViewLayout(view, params);
            }

            boolean reload = opts.optBoolean("reload", false) || !url.equals(currentUrl);
            if (reload) {
                view.loadUrl(url);
                currentUrl = url;
            }

            json.put("ok", true);
            json.put("visible", true);
            json.put("url", url);
            json.put("widthDp", Math.round(width / (float) scale));
            json.put("heightDp", Math.round(height / (float) scale));
            json.put("x", x);
            json.put("y", y);
            json.put("alpha", alpha);
            json.put("draggable", draggable);
            json.put("touchable", touchable);
            json.put("note", "页面可用 window.KokonaOverlay.moveBy(dx,dy) / moveTo(x,y) / "
                    + "resize(widthDp,heightDp) / hide() 自行控制窗口");
        } catch (Throwable error) {
            try {
                json.put("ok", false);
                json.put("error", "显示悬浮窗失败：" + (error.getMessage() == null
                        ? error.toString() : error.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return json;
    }

    private static JSONObject hideOnMain(Context app) {
        JSONObject json = new JSONObject();
        boolean had;
        try {
            synchronized (LOCK) {
                had = view != null;
                if (view != null && manager != null) {
                    try {
                        manager.removeView(view);
                    } catch (Throwable ignored) {
                    }
                }
                if (view != null) {
                    try {
                        view.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                view = null;
                manager = null;
                params = null;
                currentUrl = "";
            }
            json.put("ok", true);
            json.put("visible", false);
            json.put("removed", had);
        } catch (Throwable error) {
            try {
                json.put("ok", false);
                json.put("error", String.valueOf(error.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return json;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Moves the window with a finger. Only used when {@code draggable} is set:
     * intercepting touches means the page inside stops receiving them, which is
     * why it is off by default -- most pets handle their own pointer events and
     * call {@code KokonaOverlay.moveBy} instead.
     */
    private static final class DragListener implements View.OnTouchListener {
        private int downX;
        private int downY;
        private float startX;
        private float startY;

        @Override
        public boolean onTouch(View target, MotionEvent event) {
            WindowManager windows = manager;
            WindowManager.LayoutParams layout = params;
            if (windows == null || layout == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = (int) event.getRawX();
                    downY = (int) event.getRawY();
                    startX = layout.x;
                    startY = layout.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    layout.x = (int) (startX + (event.getRawX() - downX));
                    layout.y = (int) (startY + (event.getRawY() - downY));
                    try {
                        windows.updateViewLayout(target, layout);
                    } catch (Throwable ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Small API the hosted page can call to drive its own window. */
    private static final class OverlayBridge {
        @JavascriptInterface
        public void moveBy(final int dx, final int dy) {
            postLayout(new LayoutEdit() {
                @Override
                public void apply(WindowManager.LayoutParams layout) {
                    layout.x += dx;
                    layout.y += dy;
                }
            });
        }

        @JavascriptInterface
        public void moveTo(final int x, final int y) {
            postLayout(new LayoutEdit() {
                @Override
                public void apply(WindowManager.LayoutParams layout) {
                    layout.x = x;
                    layout.y = y;
                }
            });
        }

        @JavascriptInterface
        public void resize(final int widthDp, final int heightDp) {
            postLayout(new LayoutEdit() {
                @Override
                public void apply(WindowManager.LayoutParams layout) {
                    Context app = appContext;
                    if (app == null) return;
                    int scale = dp(app);
                    layout.width = Math.max(24 * scale, widthDp * scale);
                    layout.height = Math.max(24 * scale, heightDp * scale);
                }
            });
        }

        @JavascriptInterface
        public void hide() {
            Context app = appContext;
            if (app != null) hideQuietly(app);
        }
    }

    private interface LayoutEdit {
        void apply(WindowManager.LayoutParams layout);
    }

    private static void postLayout(final LayoutEdit edit) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                WindowManager windows = manager;
                WindowManager.LayoutParams layout = params;
                WebView target = view;
                if (windows == null || layout == null || target == null) return;
                try {
                    edit.apply(layout);
                    windows.updateViewLayout(target, layout);
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
