package com.knh.akizukikokona;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import rikka.shizuku.Shizuku;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The single screen of KokonaHarness: a full-screen WebView over the local
 * dsh Web UI, plus a boot overlay that tracks the engine service.
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK_DIR = 21;
    private static final int REQ_FILE = 22;
    private static final int REQ_PERM = 23;
    private static final int REQ_SAVE = 24;

    private static final String SHIM =
            "(function(){" +
            // The runtime index.html sets __KOKONA_SHIM__ for its own download/picker
            // shim, so this native-side shim needs a separate guard or it never runs.
            "if(window.__KOKONA_NATIVE_SHIM__){try{" +
            "if(window.__KOKONA_APPLY_BRAND__)window.__KOKONA_APPLY_BRAND__();" +
            "if(window.__KOKONA_SYNC_SB__)window.__KOKONA_SYNC_SB__();" +
            "}catch(e){}return;}" +
            "window.__KOKONA_NATIVE_SHIM__=true;" +
            "window.__KOKONA_PICK_CALLBACKS__={};" +
            "window.__KOKONA_PICK_RESOLVE__=function(id,path,err){" +
            "var c=window.__KOKONA_PICK_CALLBACKS__[id];if(!c)return;" +
            "delete window.__KOKONA_PICK_CALLBACKS__[id];" +
            "if(err){c.reject(new Error(err));}else if(path){c.resolve(path);}else{c.resolve(null);}};" +
            "window.__DSH_DIRECTORY_PICKER__={pick:function(){" +
            "return new Promise(function(resolve,reject){" +
            "var id='k'+Date.now()+'_'+Math.floor(Math.random()*1000000);" +
            "window.__KOKONA_PICK_CALLBACKS__[id]={resolve:resolve,reject:reject};" +
            "try{KokonaBridge.pickDirectory(id);}catch(e){delete window.__KOKONA_PICK_CALLBACKS__[id];reject(e);}" +
            "});}};" +
            // Sidebar state: only the fully hidden sidebar gets the Kokona signature
            // opener; the expanded drawer keeps its normal collapse button.
            "var vis=function(el){try{if(!el)return false;var r=el.getBoundingClientRect();" +
            "return r.width>1&&r.height>1&&r.right>0&&r.left<window.innerWidth&&r.bottom>0;" +
            "}catch(e){return false;}};" +
            "var sbOpen=function(){var lead=document.querySelector('[data-shell-leading]');" +
            "if(lead)return lead.hasAttribute('inert');" +
            "var t=document.querySelector('[data-sidebar-toggle]');" +
            "return !!(t&&t.closest('[aria-modal=\"true\"]'));};" +
            "var firstVis=function(sel){var n=document.querySelectorAll(sel);" +
            "for(var i=0;i<n.length;i++){if(vis(n[i]))return n[i];}return null;};" +
            "var syncSb=function(){var root=document.documentElement;try{root.setAttribute('data-kn-sb'," +
            "sbOpen()?'open':'closed');}catch(e){}" +
            // While any dialog overlay owns the screen, restore the shell's own
            // icons: the brand takeover replaces a real button's glyph with a
            // background image, which leaves that control looking cut off and
            // unresponsive inside the settings overlay.
            "try{var m=null,n=document.querySelectorAll('[role=\"dialog\"]');" +
            "for(var i=0;i<n.length;i++){if(vis(n[i])){m=n[i];break;}}" +
            "root.setAttribute('data-kn-modal',m?'1':'0');}catch(e){}};" +
            "var raf=window.requestAnimationFrame||function(f){return setTimeout(f,50);};" +
            "var queued=false;var syncSoon=function(){if(queued)return;queued=true;" +
            "raf(function(){queued=false;syncSb();});};" +
            "window.__KOKONA_APPLY_BRAND__=function(){try{" +
            "if(document.getElementById('kn-sidebar-brand'))return;" +
            "var styleEl=document.createElement('style');styleEl.id='kn-sidebar-brand';" +
            "styleEl.textContent='html[data-kn-sb=\"closed\"][data-kn-modal=\"0\"] [data-sidebar-opener]{width:auto!important;" +
            "height:30px!important;padding:0 4px!important;}'+" +
            "'html[data-kn-sb=\"closed\"][data-kn-modal=\"0\"] [data-sidebar-opener] svg{display:none!important;}'+" +
            "'html[data-kn-sb=\"closed\"][data-kn-modal=\"0\"] [data-sidebar-opener]::after{content:\"\";display:block;" +
            "width:88px;height:24px;background:var(--kn-brand,none) center/contain no-repeat;}'+" +
            // Portrait: better-sidebar docks against the conversation column, which
            // is the full viewport on a phone; keep its panes inside the screen and
            // let the bottom workbench use the whole width under the IME.
            "'@media (max-width:600px){[data-dsh-panel-host] [data-dsh-panel]{max-width:100vw!important;}'+" +
            "'[data-dsh-panel-host] [data-dsh-bottom-panel]{left:0!important;right:0!important;}}'+" +
            // The dock is a fixed, viewport-sized layer. Keep it *below* the
            // settings/account overlays (z-index 1001) instead of raising
            // dialogs: a raised dialog container reorders its own backdrop above
            // the content, and on a phone the drawer itself is a role=dialog
            // element that then dims the settings page and eats every tap.
            "'[data-dsh-panel-host]{z-index:20!important;}';" +
            "(document.head||document.documentElement).appendChild(styleEl);" +
            "var b=window.__KOKONA_BRAND_URL__;" +
            "if(b){document.documentElement.style.setProperty('--kn-brand','url(\"'+b+'\")');}" +
            "}catch(e){}};window.__KOKONA_APPLY_BRAND__();" +
            // Edge swipe: left-to-right opens the drawer from the left third, right-to-left
            // closes it while open. Short, mostly horizontal swipes only.
            "var sx=0,sy=0,st0=0,tr=false,stEl=null;" +
            "var inDock=function(el){try{return !!(el&&el.closest&&" +
            "el.closest('[data-dsh-panel-host],[data-dsh-better-sidebar]'));}catch(e){return false;}};" +
            "document.addEventListener('touchstart',function(e){" +
            "if(e.touches.length!==1){tr=false;return;}var t=e.touches[0];" +
            "sx=t.clientX;sy=t.clientY;st0=Date.now();tr=true;stEl=e.target;},{passive:true,capture:true});" +
            "document.addEventListener('touchend',function(e){if(!tr)return;tr=false;" +
            "var t=e.changedTouches[0];if(!t)return;var dx=t.clientX-sx,dy=t.clientY-sy,dt=Date.now()-st0;" +
            // The docked editor/terminal/chat owns horizontal gestures inside it;
            // leave those to the plugin instead of toggling the left drawer.
            "if(dt>700||Math.abs(dy)>70||inDock(stEl))return;" +
            "if(dx>70&&sx<window.innerWidth*0.35&&!sbOpen()){" +
            "var o=firstVis('[data-sidebar-opener]');if(o){try{o.click();}catch(x){}}}" +
            "else if(dx<-70&&sbOpen()){var c=firstVis('[data-sidebar-toggle]');" +
            "if(c){try{c.click();}catch(x){}}}},{passive:true,capture:true});" +
            "var observeSb=function(){try{if(window.__KOKONA_SB_OBS__||!document.body)return;" +
            "window.__KOKONA_SB_OBS__=new MutationObserver(syncSoon);" +
            "window.__KOKONA_SB_OBS__.observe(document.body,{subtree:true,childList:true," +
            "attributes:true,attributeFilter:['class','style','aria-modal','aria-expanded']});" +
            "}catch(e){}};" +
            "window.__KOKONA_SYNC_SB__=function(){observeSb();syncSb();};" +
            "window.__KOKONA_SYNC_SB__();setTimeout(window.__KOKONA_SYNC_SB__,600);" +
            "setInterval(syncSb,1200);" +
            // Keyboard: keep the composer above the IME and expose the inset as a CSS
            // variable for layouts that position themselves against the visual viewport.
            "var kb=function(){try{var v=window.visualViewport;" +
            "var h=Math.max(0,(window.innerHeight-(v?v.height:window.innerHeight))+(v?v.offsetTop:0));" +
            "document.documentElement.style.setProperty('--kn-keyboard-inset',h+'px');}catch(e){}};" +
            "window.addEventListener('resize',kb);" +
            "if(window.visualViewport){window.visualViewport.addEventListener('resize',kb);" +
            "window.visualViewport.addEventListener('scroll',kb);}" +
            "document.addEventListener('focusin',function(e){setTimeout(function(){try{" +
            "e.target.scrollIntoView({block:'center',behavior:'smooth'});}catch(x){}},260);},true);" +
            "kb();" +
            "})();";

    /** The shim plus the bundled signature used by the collapsed-sidebar opener. */
    private String shimScript() {
        if (brandDataUrl == null) {
            brandDataUrl = loadBrandDataUrl();
        }
        return "window.__KOKONA_BRAND_URL__='" + brandDataUrl + "';" + SHIM;
    }

    private String loadBrandDataUrl() {
        try {
            InputStream input = getAssets().open("brand.svg");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            input.close();
            return "data:image/svg+xml;base64,"
                    + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } catch (Exception error) {
            return "";
        }
    }

    private FrameLayout root;
    private WebView web;
    private String brandDataUrl;
    private LinearLayout overlay;
    private ProgressBar progress;
    private TextView statusText;
    private Button retryButton;
    private WebView authWeb;
    private View authClose;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;
    private String pickRequestId;
    private String pendingPickResult;
    private String pendingSaveName;
    private String pendingSaveMime;
    private byte[] pendingSaveData;
    private boolean windowReady;
    private boolean webLoaded;
    private String activeUrl;

    private static final String APP_VERSION = "1.2.2";

    private EngineService.Listener engineListener;

    /** Shizuku returns the grant on a binder thread; only reboot the engine on a real channel change. */
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (grantResult != PackageManager.PERMISSION_GRANTED) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!RunMode.ADVANCED.equals(RunMode.mode(MainActivity.this))) return;
                            if (!"shizuku".equals(
                                    RunMode.effectiveChannel(MainActivity.this))) return;
                            ToastCompat.show(MainActivity.this, "Shizuku 已授权");
                            EngineService.restart(MainActivity.this);
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConfirmationGate.attach(this);
        buildViews();
        initWebView();
        requestMissingPermissions();
        RunMode.firstLaunch(this);
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
            // Shizuku not installed / binder unavailable: normal mode keeps working.
        }

        engineListener = new EngineService.Listener() {
            @Override
            public void onState(final EngineService.Snapshot snapshot) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyState(snapshot);
                    }
                });
            }
        };
        EngineService.addListener(engineListener);
        EngineService.start(this);

        applyState(EngineService.current());
    }

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff0b0b0f);

        web = new WebView(this);
        web.setBackgroundColor(0xff0b0b0f);
        FrameLayout.LayoutParams webLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(web, webLp);

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackground(bootBackground());
        overlay.setPadding(dp(24), dp(24), dp(24), dp(24));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(96), dp(96));
        logoLp.bottomMargin = dp(16);
        overlay.addView(logo, logoLp);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(
                android.content.res.ColorStateList.valueOf(0xffe8c06a));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        progressLp.topMargin = dp(10);
        overlay.addView(progress, progressLp);

        statusText = new TextView(this);
        statusText.setTextColor(0xffe8e2d2);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(12);
        overlay.addView(statusText, statusLp);

        retryButton = new Button(this);
        retryButton.setText(R.string.retry);
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EngineService.restart(MainActivity.this);
            }
        });
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryLp.topMargin = dp(16);
        overlay.addView(retryButton, retryLp);

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void initWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " KokonaHarness/" + APP_VERSION);
        // targetSdk is below 29, so Android's WebView applies algorithmic
        // darkening to the light UI in dark mode. That washes out labels and
        // makes dialogs look greyed out, so opt out explicitly.
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        // Fallback for ROMs that ignore adjustResize: pad the WebView by the IME
        // inset. When adjustResize works the inset is already consumed (bottom=0),
        // so this stays a no-op there.
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                view.setPadding(0, 0, 0, Math.max(0, insets.getSystemWindowInsetBottom()));
                return insets;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                view.evaluateJavascript(shimScript(), null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(shimScript(), null);
                webLoaded = true;
                if (pendingPickResult != null) {
                    deliverPick(pendingPickResult);
                    pendingPickResult = null;
                }
                if (overlay.getVisibility() == View.VISIBLE
                        && EngineService.current().state == EngineService.State.READY) {
                    overlay.setVisibility(View.GONE);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                final WebView popup = new WebView(MainActivity.this);
                WebSettings popupSettings = popup.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setDatabaseEnabled(true);
                popupSettings.setSupportMultipleWindows(false);
                popup.setBackgroundColor(0xffffffff);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView popupView, String url) {
                        if (url != null && url.contains("/oauth/callback")) {
                            popupView.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    closeAuthWindow();
                                }
                            }, 700);
                        }
                        return false;
                    }
                });
                popup.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView window) {
                        closeAuthWindow();
                    }
                });
                showAuthWindow(popup);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "KokonaBridge");
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "无法打开下载链接", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
                || url.startsWith("about:") || url.startsWith("blob:") || url.startsWith("data:")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
        return true;
    }

    private void applyState(EngineService.Snapshot snapshot) {
        switch (snapshot.state) {
            case INSTALLING:
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.GONE);
                String installing = snapshot.message != null
                        ? snapshot.message : getString(R.string.engine_installing);
                if (snapshot.progress >= 0f) {
                    installing = installing + " "
                            + Math.min(100, (int) Math.round(snapshot.progress * 100f)) + "%";
                }
                statusText.setText(installing);
                break;
            case STARTING:
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.GONE);
                statusText.setText(snapshot.message != null
                        ? snapshot.message : getString(R.string.engine_starting));
                break;
            case READY:
                statusText.setText("正在加载界面…");
                if (snapshot.url != null && snapshot.url.equals(activeUrl) && webLoaded) {
                    overlay.setVisibility(View.GONE);
                } else if (snapshot.url != null) {
                    activeUrl = snapshot.url;
                    webLoaded = false;
                    web.loadUrl(snapshot.url);
                }
                break;
            case ERROR:
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.VISIBLE);
                statusText.setText(snapshot.message != null
                        ? snapshot.message : getString(R.string.engine_error));
                break;
            case STOPPED:
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.VISIBLE);
                statusText.setText("引擎已停止");
                break;
            default:
                break;
        }
    }

    private void requestMissingPermissions() {
        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT <= 32) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (Build.VERSION.SDK_INT <= 28
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add("android.permission.POST_NOTIFICATIONS");
        }
        if (!wanted.isEmpty()) {
            requestPermissions(wanted.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    result = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        if (requestCode == REQ_PICK_DIR) {
            String path = null;
            String error = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri tree = data.getData();
                try {
                    getContentResolver().takePersistableUriPermission(tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                path = treeToPath(tree);
                if (path == null) {
                    error = "无法把所选目录转换为文件路径，请选择内部存储中的目录";
                }
            }
            resolvePick(path, error);
            return;
        }
        if (requestCode == REQ_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && pendingSaveData != null) {
                try {
                    java.io.OutputStream out =
                            getContentResolver().openOutputStream(data.getData());
                    if (out != null) {
                        out.write(pendingSaveData);
                        out.close();
                        Toast.makeText(this, "已保存 " + pendingSaveName,
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception error) {
                    Toast.makeText(this, "保存失败：" + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            }
            pendingSaveData = null;
            pendingSaveName = null;
            pendingSaveMime = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private String treeToPath(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            String[] parts = id.split(":", 2);
            String volume = parts[0];
            String relative = parts.length > 1 ? parts[1] : "";
            String base;
            if ("primary".equalsIgnoreCase(volume)) {
                base = Environment.getExternalStorageDirectory().getAbsolutePath();
            } else {
                base = "/storage/" + volume;
            }
            File dir = relative.isEmpty() ? new File(base) : new File(base, relative);
            return dir.isDirectory() && dir.canRead() ? dir.getAbsolutePath() : null;
        } catch (Exception error) {
            return null;
        }
    }

    private void resolvePick(String path, String error) {
        String id = pickRequestId;
        pickRequestId = null;
        if (id == null) return;
        String script = "window.__KOKONA_PICK_RESOLVE__(" + js(id) + ","
                + (path == null ? "null" : js(path)) + ","
                + (error == null ? "null" : js(error)) + ");";
        if (webLoaded && windowReady) {
            web.evaluateJavascript(script, null);
        } else {
            pendingPickResult = script;
        }
    }

    private void deliverPick(String script) {
        if (webLoaded && windowReady) {
            web.evaluateJavascript(script, null);
        }
    }

    private static String js(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /**
     * Boot overlay backdrop: the same picture and the same 22% opacity the Web
     * UI uses, scaled to cover the screen and anchored at the top centre.
     */
    private Drawable bootBackground() {
        try (java.io.InputStream input = getAssets().open("kokona-bg.jpg")) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap != null) return new BootBackgroundDrawable(bitmap);
        } catch (Exception ignored) {
        }
        return new ColorDrawable(0xff0b0b0f);
    }

    private static final class BootBackgroundDrawable extends Drawable {
        private static final int IMAGE_ALPHA = 56;
        private static final int BASE_COLOR = 0xff0b0b0f;

        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Rect source;
        private final RectF destination = new RectF();

        BootBackgroundDrawable(Bitmap bitmap) {
            this.bitmap = bitmap;
            this.source = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            paint.setAlpha(IMAGE_ALPHA);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawColor(BASE_COLOR);
            Rect bounds = getBounds();
            if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
            float scale = Math.max((float) bounds.width() / bitmap.getWidth(),
                    (float) bounds.height() / bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            float left = bounds.left + (bounds.width() - width) / 2f;
            float top = bounds.top;
            destination.set(left, top, left + width, top + height);
            canvas.drawBitmap(bitmap, source, destination, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showAuthWindow(final WebView popup) {
        closeAuthWindow();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(popup, lp);

        // A quiet round close chip instead of the platform button: translucent
        // ink background, gold glyph, matching the app palette.
        final TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(17);
        close.setTextColor(0xffe8c06a);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        background.setColor(0xcc15151b);
        background.setStroke(dp(1), 0x66e8c06a);
        close.setBackground(background);
        close.setElevation(dp(6));
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.TOP | Gravity.END);
        closeLp.topMargin = dp(14);
        closeLp.rightMargin = dp(14);
        root.addView(close, closeLp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeAuthWindow();
            }
        });
        authClose = close;
        authWeb = popup;
    }

    private void closeAuthWindow() {
        if (authClose != null) {
            root.removeView(authClose);
            authClose = null;
        }
        if (authWeb != null) {
            root.removeView(authWeb);
            authWeb.destroy();
            authWeb = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (authWeb != null) {
                closeAuthWindow();
                return true;
            }
            if (overlay.getVisibility() == View.VISIBLE) {
                moveTaskToBack(true);
                return true;
            }
            web.evaluateJavascript("(function(){try{var open=0;var nodes=document.querySelectorAll('[role=\\\"dialog\\\"],.VOzbGW_panel');"
                    + "for(var i=0;i<nodes.length;i++){var e=nodes[i],r=e.getBoundingClientRect();"
                    + "if(r.width>0&&r.height>0&&getComputedStyle(e).visibility!=='hidden'){open=1;break;}}"
                    + "if(!open)return '0';var target=document.activeElement||document.body;"
                    + "var key={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true};"
                    + "target.dispatchEvent(new KeyboardEvent('keydown',key));"
                    + "document.dispatchEvent(new KeyboardEvent('keydown',key));return '1';}catch(err){return '0';}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value != null && value.contains("1")) return;
                            if (web.canGoBack()) {
                                web.goBack();
                                return;
                            }
                            moveTaskToBack(true);
                        }
                    });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ConfirmationGate.setVisible(this, false);
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConfirmationGate.setVisible(this, true);
        windowReady = true;
        if (web != null) web.onResume();
        if (pendingPickResult != null) {
            String script = pendingPickResult;
            pendingPickResult = null;
            deliverPick(script);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        EngineService.removeListener(engineListener);
        ConfirmationGate.detach(this);
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** JS bridge: only directory picking is exposed, nothing else. */
    private class Bridge {
        @JavascriptInterface
        public void pickDirectory(final String requestId) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pickRequestId = requestId;
                    try {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(intent, REQ_PICK_DIR);
                    } catch (Exception error) {
                        resolvePick(null, "无法打开系统目录选择器");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openExternal(final String url) {
            if (url == null) return;
            final String target = url.trim();
            if (!target.startsWith("http://") && !target.startsWith("https://")) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(MainActivity.this, "无法打开浏览器，请复制链接后手动访问",
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public String version() {
            return APP_VERSION;
        }

        /** Sandbox availability for the permission-preset UI. */
        @JavascriptInterface
        public boolean sandboxSupported() {
            return RunMode.sandboxSupported(MainActivity.this);
        }

        @JavascriptInterface
        public void toast(final String message) {
            ToastCompat.show(MainActivity.this, message);
        }

        /** JSON snapshot the Settings run-mode row polls. */
        @JavascriptInterface
        public String runModeInfo() {
            return RunMode.infoJson(MainActivity.this);
        }

        /** JSON snapshot of capability grants for the Settings permissions page. */
        @JavascriptInterface
        public String androidPermissions() {
            return AndroidPermissions.json(MainActivity.this);
        }

        /**
         * Any script inside the WebView can call these methods, so the ones that
         * change security posture ask the user in a native dialog first. The
         * JavaScript side cannot forge the result.
         */
        private boolean confirmSensitive(String detail) {
            return ConfirmationGate.request(detail, true, 30) == Boolean.TRUE;
        }

        @JavascriptInterface
        public void setAndroidPermission(final String capability, final String mode) {
            if (!AndroidPermissions.isCapability(capability)) return;
            if (!"allow".equals(mode) && !"ask".equals(mode) && !"deny".equals(mode)) return;
            String modeLabel = "allow".equals(mode) ? "允许"
                    : "ask".equals(mode) ? "询问" : "禁止";
            String detail = "将「" + AndroidPermissions.label(capability) + "」改为「" + modeLabel + "」？";
            if (!confirmSensitive(detail)) return;
            AndroidPermissions.setMode(MainActivity.this, capability, mode);
        }

        @JavascriptInterface
        public void resetAndroidPermissions() {
            if (!confirmSensitive("恢复全部手机权限的默认值？")) return;
            AndroidPermissions.reset(MainActivity.this);
        }

        @JavascriptInterface
        public void setRunMode(final String mode) {
            if (!confirmSensitive("切换到运行模式「" + mode + "」？")) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String previous = RunMode.mode(MainActivity.this);
                    String previousChannel = RunMode.effectiveChannel(MainActivity.this);
                    RunMode.setMode(MainActivity.this, mode);
                    String current = RunMode.mode(MainActivity.this);
                    if (!current.equals(previous)) {
                        String currentChannel = RunMode.effectiveChannel(MainActivity.this);
                        if (RunMode.ADVANCED.equals(current)
                                && "normal".equals(currentChannel)) {
                            ToastCompat.show(MainActivity.this,
                                    "未检测到可用的 Root 或 Shizuku，仍以普通模式运行");
                        }
                        // Only a real privilege-channel change needs a new engine.
                        // normal <-> accessibility (and advanced that fell back to
                        // normal) keep the same engine running.
                        if (!currentChannel.equals(previousChannel)) {
                            EngineService.restart(MainActivity.this);
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void requestRoot() {
            if (!confirmSensitive("允许网页请求 Root 权限？")) return;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String before = RunMode.effectiveChannel(MainActivity.this);
                    boolean granted = RunMode.probeRoot(MainActivity.this, true);
                    ToastCompat.show(MainActivity.this, granted ? "Root 已授权" : "未获得 Root 授权");
                    String after = RunMode.effectiveChannel(MainActivity.this);
                    if (granted && RunMode.ADVANCED.equals(RunMode.mode(MainActivity.this))
                            && !after.equals(before)) {
                        EngineService.restart(MainActivity.this);
                    }
                }
            }, "kokona-root-request").start();
        }

        @JavascriptInterface
        public void requestShizuku() {
            if (!confirmSensitive("允许网页请求 Shizuku 授权？")) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    RunMode.requestShizukuPermission(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void openShizuku() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    RunMode.openShizuku(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void openAccessibility() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    RunMode.openAccessibilitySettings(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void openNotificationAccess() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AndroidCapabilities.openNotificationAccess(MainActivity.this);
                }
            });
        }

        @JavascriptInterface
        public void openWriteSettings() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AndroidCapabilities.openWriteSettings(MainActivity.this);
                }
            });
        }

        /** Saves a browser blob (exported file, image, log) through the system document picker. */
        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String base64) {
            if (base64 == null || base64.length() > 26_000_000) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "文件过大，暂时无法保存",
                                Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            final byte[] bytes;
            try {
                bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            } catch (IllegalArgumentException error) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingSaveData = bytes;
                    pendingSaveName = name == null || name.isEmpty() ? "download" : name;
                    pendingSaveMime = mime == null || mime.isEmpty()
                            ? "application/octet-stream" : mime;
                    try {
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(pendingSaveMime);
                        intent.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
                        startActivityForResult(intent, REQ_SAVE);
                    } catch (Exception error) {
                        pendingSaveData = null;
                        Toast.makeText(MainActivity.this, "没有可用的保存位置",
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }
}
