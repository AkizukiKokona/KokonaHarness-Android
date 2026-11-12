package com.kokonaharness.android.akizuki;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
            // setAttribute() on html is a style invalidation for every observer in
            // the page, and dsh-better-sidebar keeps a MutationObserver on
            // documentElement that re-measures its dock on each one. Writing the
            // attributes unconditionally (as this used to do, on a 1.2s timer)
            // therefore kept re-triggering that measurement forever, which is what
            // made the settings page lock up once the sidebar and its task panel
            // were installed. Only touch the DOM when the value actually changes.
            "var setAttr=function(root,name,value){" +
            "if(root.getAttribute(name)!==value)root.setAttribute(name,value);};" +
            "var sbState=null,modalState=null;" +
            "var syncSb=function(){var root=document.documentElement;" +
            "var open=sbOpen();if(open!==sbState){sbState=open;" +
            "setAttr(root,'data-kn-sb',open?'open':'closed');}" +
            // While any dialog overlay owns the screen, restore the shell's own
            // icons: the brand takeover replaces a real button's glyph with a
            // background image, which leaves that control looking cut off and
            // unresponsive inside the settings overlay.
            "var hasModal=false,m=null,n=document.querySelectorAll('[role=\"dialog\"]');" +
            "for(var i=0;i<n.length;i++){if(vis(n[i])){m=n[i];break;}}" +
            "hasModal=!!m;if(hasModal!==modalState){modalState=hasModal;" +
            "setAttr(root,'data-kn-modal',hasModal?'1':'0');}};" +
            // dsh-better-sidebar sizes its panel host with `height:100dvh` and then
            // verifies the host covers the viewport: it compares the measured box
            // against `window.innerHeight` and, on a mismatch wider than 8px, flips
            // into a "degraded" mode that rewrites the host transform and
            // re-measures on every animation frame. Android WebView reports
            // `100dvh` as the largest possible viewport, so with a gesture bar or a
            // shown keyboard that check fails for good — a viewport-sized layer is
            // then repainted every frame, and any settings page painted above it
            // comes back torn and stops responding to taps.
            // Feeding the host the live viewport height makes the check pass, so
            // the per-frame repair loop never starts. Only write when it differs.
            "var dockH=-1;" +
            "var fitDock=function(){try{var h=window.innerHeight;" +
            "if(h===dockH)return;dockH=h;" +
            "var nodes=document.querySelectorAll('[data-dsh-panel-host]');" +
            "for(var i=0;i<nodes.length;i++){" +
            "if(nodes[i].getAttribute('data-kn-h')===String(h))continue;" +
            "nodes[i].setAttribute('data-kn-h',String(h));" +
            "nodes[i].style.height=h+'px';}}catch(e){}};" +
            "window.addEventListener('resize',fitDock);" +
            "if(window.visualViewport){window.visualViewport.addEventListener('resize',fitDock);}" +
            // The host is inserted by the plugin well after page load, so pick it up
            // as soon as it appears rather than waiting for the next resize.
            "var dockObs=null;" +
            "var watchDock=function(){try{if(dockObs||!document.body)return;" +
            "dockObs=new MutationObserver(function(){fitDock();});" +
            "dockObs.observe(document.body,{childList:true,subtree:true});" +
            "}catch(e){}};" +
            "watchDock();" +
            "var raf=window.requestAnimationFrame||function(f){return setTimeout(f,50);};" +
            "var queued=false;var syncSoon=function(){if(queued)return;queued=true;" +
            "raf(function(){queued=false;syncSb();});};" +
            // Brand mark on the collapsed-sidebar leading button.
            //
            // This is deliberately the 1.0.37 behaviour, restored verbatim: one
            // selector, no JS anchoring, no state gating. Two later attempts
            // made it worse — `data-kn-brand` gating could hide the mark
            // entirely, and a `[data-sidebar-toggle]` fallback collided with the
            // "open bottom panel" button so two controls painted on top of each
            // other. `data-sidebar-opener` is the attribute the shell actually
            // emits for this control; if a build ever stops emitting it the mark
            // simply does not appear, which is the safe failure.
            // ORDER IS THE FIX. Until 1.0.37 the stylesheet was appended FIRST
            // and the brand URL set afterwards; 1.0.38 moved the 150 KB
            // `--kn-brand` write above the appendChild, and from that release on
            // NONE of this stylesheet reached the page — every white-bar fix
            // silently did nothing while the sidebar mark vanished. A throw from
            // setProperty() (a ~150 KB data URL, twice-encoded because brand.svg
            // embedded a PNG) skipped the whole function body, so the <style>
            // was never created.
            //
            // Now: create + append the stylesheet first, with the brand image
            // inlined into the rule text instead of smuggled through a CSS
            // variable, and no `return` that can be reached before the append.
            "window.__KOKONA_APPLY_BRAND__=function(){try{" +
            "if(document.getElementById('kn-sidebar-brand'))return;" +
            "var b=window.__KOKONA_BRAND_URL__||'';" +
            "var styleEl=document.createElement('style');styleEl.id='kn-sidebar-brand';" +
            "styleEl.textContent=(b?('[data-sidebar-opener]{width:auto!important;" +
            "height:30px!important;padding:0 4px!important;}'+" +
            "'[data-sidebar-opener] svg{display:none!important;}'+" +
            "'[data-sidebar-opener]::after{content:\"\";display:block;width:88px;" +
            "height:24px;background-image:url(\"'+b+'\");background-position:center;" +
            "background-size:contain;background-repeat:no-repeat;}'):'')+" +
            // Portrait: better-sidebar docks against the conversation column, which
            // is the full viewport on a phone; keep its panes inside the screen and
            // let the bottom workbench use the whole width under the IME.
            "'@media (max-width:600px){[data-dsh-panel-host] [data-dsh-panel]{max-width:100vw!important;}'+" +
            "'[data-dsh-panel-host] [data-dsh-bottom-panel]{left:0!important;right:0!important;}}'+" +
            // The dock declares its own z-index (25) and its own overlays use
            // 1000-1003. Forcing a different value here fought those layers and
            // left the settings page painted in the wrong order, so the dock's
            // stacking is left entirely to dsh-better-sidebar.
            // Overlay paint safety net. Android WebView will happily rasterise a
            // full-screen backdrop blur on top of live content, and on some GPUs
            // that texture comes back torn — the settings page looked like a
            // screen of coloured interference. Dropping only the blur keeps every
            // element's containing block untouched, which matters because
            // dsh-better-sidebar anchors its viewport-sized dock with
            // `position:fixed;inset:0` inside the overlay subtree: anything that
            // establishes a containing block there (contain:paint, filter,
            // transform) makes the whole dock collapse and the page freeze.
            // So: reset the expensive *paint* property only, and never touch
            // layout or containment here.
            "'[role=\"dialog\"] *,[aria-modal=\"true\"] *{backdrop-filter:none!important;" +
            "-webkit-backdrop-filter:none!important;}'+" +
            "'[role=\"dialog\"],[aria-modal=\"true\"]{backdrop-filter:none!important;" +
            "-webkit-backdrop-filter:none!important;}'+" +
            // Portrait: never let a cramped column wrap CJK labels one glyph per
            // line. writing-mode is horizontal by default, so forcing it is safe;
            // nowrap plus a minimum nav-cell width keeps the labels readable.
            "'[role=\"dialog\"] *,[aria-modal=\"true\"] *{writing-mode:horizontal-tb!important;}'+" +
            "'[role=\"dialog\"] [class*=\"Label\"],[aria-modal=\"true\"] [class*=\"Label\"]{white-space:nowrap!important;}'+" +
            "'[role=\"dialog\"] [class*=\"navCell\"],[aria-modal=\"true\"] [class*=\"navCell\"]{min-width:96px!important;}'+" +
            // Every "chrome" bar above a scrolling list paints itself with an
            // opaque token so content cannot show through while sticky:
            //   * the built-in plugin manager uses `.pageBar[data-natural]:before`
            //     (the band above 已安装 / 插件市场 + the 刷新/＋/⌄ row);
            //   * dshmarket uses `_stickyHead`, `_head` and `_cats`
            //     (the 发现 / 主题 / 收藏 / 已安装 categories).
            //
            // These are set to transparent, not to another translucent white.
            // The settings panel already sits on its own
            // `.VOzbGW_panel::before{background:var(--token);opacity:.82}`
            // layer; another 82% white on top of an 82% white layer composites
            // to 1-0.18*0.18 ≈ 96.8% — visually indistinguishable from solid
            // white, which is exactly what "纯白，一点半透明都没有" described.
            // Transparent lets the panel's own layer show through, so the bar
            // and its surroundings become the same surface by construction.
            // (Plain `transparent`, not color-mix(): that is Chrome 111+ and an
            // older WebView silently drops the declaration.)
            "'[class*=\"_stickyHead\"],[class*=\"_stickyHead\"]::before,'+" +
            "'[class*=\"_pageBar\"],[class*=\"_pageBar\"]::before,'+" +
            "'[class*=\"_pageBar\"]::after,'+" +
            "'[class*=\"_pageHead\"],[class*=\"_pageTabs\"],[class*=\"_pageTabList\"],'+" +
            "'[data-dsh-market-root] [class*=\"_body\"],'+" +
            "'.X_2TxG_pageBar,.X_2TxG_pageHead,.X_2TxG_pageTabs,'+" +
            "'.nUhMVa_head,.nUhMVa_cats,.nUhMVa_stickyHead,.nUhMVa_body,'+" +
            // dshmarket's own root carries `data-dsh-market-root` in BOTH
            // surfaces; `data-dsh-market-plugins-tab` only exists when the
            // market is rendered as the standalone plugin page. Keying the
            // rules off the latter left every bar opaque inside the settings
            // dialog, which is exactly where they were reported.
            "'[data-dsh-market-root] [class*=\"_head\"],'+" +
            "'[data-dsh-market-root] [class*=\"_cats\"],'+" +
            "'[data-dsh-market-root] [class*=\"_stickyHead\"],'+" +
            "'[data-dsh-market-plugins-tab] [class*=\"_head\"],'+" +
            "'[data-dsh-market-plugins-tab] [class*=\"_cats\"]{" +
            "background:transparent!important;background-image:none!important;}'+" +
            // The settings panel already paints its own translucent layer:
            // `.VOzbGW_panel::before{position:absolute;inset:0;
            // background:var(--dsw-alias-bg-layer-2);opacity:.82}`. Adding a
            // background here stacks a second layer on top of it, which is why
            // the dialog drifted towards solid white. Hand the surface back to
            // upstream and let the ::before own the translucency.
            "'.VOzbGW_panel{background:transparent!important;}'+" +
            // Frosted glass for the drawer and the settings dialog.
            //
            // These come AFTER the blanket `backdrop-filter:none` safety net
            // above, and they are written with a higher-specificity selector
            // (`[role=dialog] .VOzbGW_panel` beats `[role=dialog] *`), because
            // that net exists to stop the GPU from tearing a full-screen blur on
            // some devices. A bounded 14 px blur on two panels is cheap; a blur
            // on every descendant of the overlay was not.
            //
            // Without the blur the translucent drawer sits on top of the home
            // page at the same brightness and the two read as one surface —
            // which is exactly what was reported.
            // A 72%-white sheet over a white page is white: alpha alone cannot
            // read as translucency when the thing behind it has the same
            // brightness, which is why the drawer still looked opaque. Two
            // changes make the effect visible instead of nominal:
            //   * the fill is tinted cool and dropped to 58%, so it differs from
            //     the page even where the page is empty;
            //   * the drawer's own mask is dimmed and blurred, so the content
            //     behind is visibly *behind glass* rather than just dimmer.
            "'[data-sidebar-drawer] .pI_x6G_sidebarCol{background:rgba(246,247,252,.58)!important;" +
            "-webkit-backdrop-filter:blur(22px) saturate(150%)!important;" +
            "backdrop-filter:blur(22px) saturate(150%)!important;" +
            "box-shadow:0 8px 32px rgba(15,17,26,.18)!important;}'+" +
            "'[data-sidebar-drawer] .pI_x6G_drawerMask{background:rgba(12,14,22,.26)!important;" +
            "-webkit-backdrop-filter:blur(2px)!important;backdrop-filter:blur(2px)!important;}'+" +
            // The drawer leaves the app's own backdrop visible, so the dark
            // palette needs its own fill; the blur itself is colour-neutral.
            "'body[data-ds-dark-theme] .pI_x6G_frame[data-sidebar-drawer] " +
            ".pI_x6G_sidebarCol{background:rgba(22,23,28,.58)!important;}'+" +
            "'body[data-ds-dark-theme] [data-sidebar-drawer] .pI_x6G_drawerMask{" +
            "background:rgba(0,0,0,.42)!important;}'+" +
            "'[role=\"dialog\"] .VOzbGW_panel,[aria-modal=\"true\"] .VOzbGW_panel{" +
            "-webkit-backdrop-filter:blur(22px) saturate(140%)!important;" +
            "backdrop-filter:blur(22px) saturate(140%)!important;}'+" +
            // The panel paints its surface with ::before at 82%; dropping it to
            // 70% is what lets the blurred backdrop read through instead of
            // being covered by a near-opaque sheet.
            "'.VOzbGW_panel::before{opacity:.58!important;}'+" +
            // No dark-theme counterpart is needed: those bars are transparent in
            // both schemes now, and the panel token behind them already flips.
            // dshmarket hides the settings <nav> on narrow screens with
            // [role=dialog]:has([data-dsh-market-root])>nav{display:none};
            // while its root is still in the DOM the section tabs stay gone
            // until the dialog is closed and reopened. Keep them available.
            "'[role=\"dialog\"]>nav,[aria-modal=\"true\"]>nav{display:flex!important;'+" +
            "'visibility:visible!important;}'+" +
            // The upstream "open settings document" action is a desktop Host
            // feature; on Android it only ever produces a red error message.
            // Its CSS-module class is stable, so hide the whole action container.
            "'.me01iq_action{display:none!important;}';" +
            // A document that has not produced <head> or <html> yet (the
            // onPageStarted injection) must not throw: the next sync retries,
            // because the id lookup still misses.
            "var host=document.head||document.documentElement||document.body;" +
            "if(host)host.appendChild(styleEl);" +
            // The brand variable is written once, above, before the early
            // return: re-writing it here would touch documentElement.style on
            // every sync, which is itself a mutation the sidebar's observer
            // watches.
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
            // Keep the filter minimal: watching 'style' too made this observer fire
            // on every paint-related tweak the sidebar makes while laying out its
            // dock, which turned into a feedback loop between the two observers.
            "window.__KOKONA_SB_OBS__=new MutationObserver(syncSoon);" +
            "window.__KOKONA_SB_OBS__.observe(document.body,{subtree:true,childList:true," +
            "attributes:true,attributeFilter:['class','aria-modal','aria-expanded']});" +
            "}catch(e){}};" +
            // Re-assert the brand on every sync as well: the stylesheet is
            // cheap to look up, and a page that swaps <head> or remounts the
            // shell used to leave the mark gone until the next launch.
            "window.__KOKONA_SYNC_SB__=function(){observeSb();syncSb();fitDock();" +
            "window.__KOKONA_APPLY_BRAND__();};" +
            "window.__KOKONA_SYNC_SB__();setTimeout(window.__KOKONA_SYNC_SB__,600);" +
            // Report the computed values of the two surfaces this shim styles.
            // Without it, "the panel is still opaque" is a symptom that takes a
            // remote debugger to turn into a cause; with it the settings page can
            // print the actual background/backdrop-filter and the answer is one
            // glance away. Re-runs on a timer because the drawer only exists some
            // of the time.
            "window.__KOKONA_UI_PROBE__=function(){try{" +
            "var cs=window.getComputedStyle;" +
            "var dr=document.querySelector('[data-sidebar-drawer] .pI_x6G_sidebarCol')||" +
            "document.querySelector('.pI_x6G_sidebarCol');" +
            "var pn=document.querySelector('.VOzbGW_panel');" +
            "var body=document.body||document.documentElement;" +
            "var out={sheet:!!document.getElementById('kn-sidebar-brand')," +
            "blur:(window.CSS&&CSS.supports)?CSS.supports('backdrop-filter','blur(1px)'):false," +
            "dark:!!(body&&body.hasAttribute)&&body.hasAttribute('data-ds-dark-theme')," +
            "drawerBg:dr?cs(dr).backgroundColor:''," +
            "drawerFilter:dr?((cs(dr).backdropFilter||cs(dr).webkitBackdropFilter)||''):''," +
            "panelBg:pn?cs(pn).backgroundColor:''," +
            "panelFilter:pn?((cs(pn).backdropFilter||cs(pn).webkitBackdropFilter)||''):''," +
            "panelBefore:pn?cs(pn,'::before').opacity:''};" +
            "if(window.KokonaBridge&&KokonaBridge.uiProbe)KokonaBridge.uiProbe(JSON.stringify(out));" +
            "}catch(e){}};" +
            "setTimeout(window.__KOKONA_UI_PROBE__,2500);" +
            "setInterval(window.__KOKONA_UI_PROBE__,4000);" +
            // No permanent timer here. The observers above already fire on the
            // mutations that matter, and a 1.2s poll re-ran the dialog scan — and
            // with it a forced layout — forever, which fought the sidebar's own
            // polling and could wedge the page.
            // Keyboard avoidance is driven by the native layer: it measures the IME
            // height from window insets and calls window.onKeyboardHeight(), which is
            // reliable even on the old system WebView (below M139) where the
            // visualViewport resize can fail to fire. The page applies the inset
            // itself (padding-bottom + fixed-bar offset), so the native layer never
            // pads the WebView -- single source of truth, no double inset.
            "var kbLast=-1;" +
            "window.onKeyboardHeight=function(h){try{h=Math.max(0,h|0);" +
            "if(h===kbLast)return;kbLast=h;" +
            "document.documentElement.style.setProperty('--kn-keyboard-inset',h+'px');" +
            "if(document.body)document.body.style.paddingBottom=h+'px';" +
            "}catch(e){}};" +
            "document.addEventListener('focusin',function(e){setTimeout(function(){try{" +
            "e.target.scrollIntoView({block:'center',behavior:'smooth'});}catch(x){}},260);},true);" +
            "if(window.onKeyboardHeight)window.onKeyboardHeight(0);" +
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
    private Button copyButton;
    private Button shareButton;
    private Button renderButton;
    private WebView authWeb;
    private View authClose;
    private TerminalOverlay terminalOverlay;

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

    /** Last IME (keyboard) height in px reported by the window insets; re-sent to the page after it loads. */
    private int lastImeHeight = 0;

    /** The live activity; the loopback bridge uses it to run MediaProjection capture. */
    private static volatile MainActivity currentActivity;

    /** Current activity, or null. Used by LocalBridgeServer for /screenshot on API < 30. */
    public static MainActivity current() {
        return currentActivity;
    }

    static final String APP_VERSION = "1.1.6";

    private EngineService.Listener engineListener;

    /** Set when the user picked accessibility mode and was sent to grant it. */
    private boolean pendingAccessibilityMode;

    /**
     * Last CSS probe reported by the shim. Kept so the settings page can show
     * whether a rule was applied instead of asking the user to describe the
     * screen -- "the panel is not translucent" is a symptom, the computed
     * background is the diagnosis.
     */
    private static volatile String uiProbe = "";

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
        currentActivity = this;
        // Allows measuring the real page from adb during development. Gated on the
        // debuggable flag so a release build never exposes its WebView.
        // Allows inspecting the page from chrome://inspect. A release build is
        // not debuggable, which made this unreachable exactly when it was needed
        // (a rendering bug that only shows on a real device). Settings can turn
        // it on explicitly; the default is still off.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                || getSharedPreferences("kha", MODE_PRIVATE).getBoolean("web_debug", false)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
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
        maybeCheckUpdates();
    }

    /**
     * Startup update check. Silent when nothing is newer, when the user skipped
     * that tag, or when the network is unreachable -- an update must never be the
     * reason the app looks broken.
     */
    private void maybeCheckUpdates() {
        boolean auto = getSharedPreferences("kha_update", MODE_PRIVATE)
                .getBoolean("auto", true);
        if (!auto) return;
        UpdateChecker.checkAsync(this, APP_VERSION, false, new Runnable() {
            @Override
            public void run() {
                UpdateChecker.Result result = UpdateChecker.cached();
                if (result.newer && !result.skipped) {
                    showUpdateDialog();
                }
            }
        });
    }

    private String updateSourceLabel() {
        return UpdateChecker.SOURCE_GITHUB.equals(UpdateChecker.source(this))
                ? "GitHub" : "Codeberg";
    }

    /**
     * The update notice: notes first, then exactly three choices. Rendered with
     * {@link KhaDialog} so it looks like the runtime's own prompts instead of a
     * platform dialog dropped into a DSH screen.
     */
    private void showUpdateDialog() {
        final UpdateChecker.Result result = UpdateChecker.cached();
        if (result.tag == null || result.tag.isEmpty()) return;
        String notes = result.notes == null ? "" : result.notes.trim();
        if (notes.length() > 1400) notes = notes.substring(0, 1400) + "…";
        String message = (notes.isEmpty() ? "本次更新没有附加说明。" : notes)
                + "\n\n当前 " + APP_VERSION + " → " + result.tag
                + "（" + updateSourceLabel() + "）";
        KhaDialog.create(this, Notices.TITLE, message)
                .primary("立即更新", new Runnable() {
                    @Override
                    public void run() {
                        openUrlExternally(result.assetUrl);
                    }
                })
                .extra("暂不更新", null)
                .extra("跳过这个版本", new Runnable() {
                    @Override
                    public void run() {
                        UpdateChecker.skip(MainActivity.this, result.tag);
                        ToastCompat.show(MainActivity.this, "已跳过 " + result.tag);
                    }
                })
                .show();
    }

    /** Opens an http(s) link in whatever browser the device has. */
    private void openUrlExternally(String url) {
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

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xff0b0b0f);

        web = new WebView(this);
        // dsh's UI is light (its CSS falls back to #fff, and forced darkening
        // is disabled below). A near-black WebView background showed through
        // any transparent region as an ugly black patch, so match the page.
        web.setBackgroundColor(0xffffffff);
        // Some GPUs hand WebView a stale texture buffer for full-screen overlays,
        // which shows up as torn/interference-covered dialogs (the settings page).
        // A software layer for those pages is slower but always correct, so it is
        // available as an explicit opt-out rather than being forced everywhere.
        if (softwareRenderingPreferred()) {
            web.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
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
        statusText.setTextIsSelectable(true);
        statusText.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copyStatus();
                return true;
            }
        });
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

        // A failing start is the only case where the boot text matters after
        // the fact, so offer a one-tap copy of the full message.
        copyButton = new Button(this);
        copyButton.setText("复制错误");
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyStatus();
            }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = dp(8);
        overlay.addView(copyButton, copyLp);

        // Copying can fail silently (some ROMs gate the clipboard), and a huge
        // string is awkward to move on a phone, so also offer a share sheet.
        shareButton = new Button(this);
        shareButton.setText("发送错误");
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareStatus();
            }
        });
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.topMargin = dp(8);
        overlay.addView(shareButton, shareLp);

        // Escape hatch for the torn-overlay rendering bug: switching the WebView
        // to software painting fixes it on affected GPUs. Kept on the error page
        // so it is reachable even when the engine never comes up.
        renderButton = new Button(this);
        renderButton.setVisibility(View.GONE);
        renderButton.setText(softwareRenderingPreferred() ? "改用硬件渲染" : "改用软件渲染");
        renderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSoftwareRendering();
            }
        });
        LinearLayout.LayoutParams renderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        renderLp.topMargin = dp(8);
        overlay.addView(renderButton, renderLp);

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The terminal sheet is a native overlay on top of the WebView. Its
        // launcher lives in the settings header: the kha-setthings bundle
        // registers it through the official settings.action slot, so no native
        // floating button is needed and the header layout stays upstream.
        terminalOverlay = new TerminalOverlay(this, root);

        setContentView(root);
    }

    /** Pushes the live IME height (px) to the page so it can avoid the keyboard. */
    private void bridgeKeyboardHeight(int height) {
        if (web == null) return;
        web.evaluateJavascript(
                "if(window.onKeyboardHeight)window.onKeyboardHeight(" + height + ");", null);
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
        // Keyboard avoidance: measure the IME height from the window insets and
        // bridge it to the page via window.onKeyboardHeight(). Relying on window
        // insets (not the page's visualViewport resize) works even on the old
        // system WebView (below M139) where that resize can fail to fire. The page
        // applies the inset itself, so the native layer must NOT also pad the
        // WebView -- single source of truth, no double inset.
        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int ime;
                if (Build.VERSION.SDK_INT >= 30) {
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    // Legacy: below API 30 the IME is folded into the system-window
                    // bottom inset rather than exposed as its own type.
                    ime = insets.getSystemWindowInsetBottom();
                }
                if (ime < 0) ime = 0;
                lastImeHeight = ime;
                bridgeKeyboardHeight(ime);
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
                bridgeKeyboardHeight(lastImeHeight);
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
        // Two phases, two different kinds of feedback:
        //   * unpacking has a REAL continuous 0..1 progress (byte counts), so
        //     a percentage is honest and useful here;
        //   * starting the engine has no measurable progress -- the old
        //     weighted values jumped 0 -> 88 -> 100 and looked like a stall.
        //     That phase gets a plain spinner plus the reassurance instead.
        // The parenthetical therefore belongs to STARTING only.
        switch (snapshot.state) {
            case INSTALLING: {
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.GONE);
                copyButton.setVisibility(View.GONE);
                shareButton.setVisibility(View.GONE);
                renderButton.setVisibility(View.GONE);
                String label = snapshot.message != null ? snapshot.message
                        : getString(R.string.engine_installing);
                if (snapshot.progress >= 0f) {
                    int pct = Math.min(100, Math.round(snapshot.progress * 100f));
                    progress.setIndeterminate(false);
                    progress.setProgress(pct);
                    statusText.setText(label + " " + pct + "%");
                } else {
                    progress.setIndeterminate(true);
                    progress.setProgress(0);
                    statusText.setText(label);
                }
                break;
            }
            case STARTING: {
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.GONE);
                copyButton.setVisibility(View.GONE);
                shareButton.setVisibility(View.GONE);
                renderButton.setVisibility(View.GONE);
                String label = snapshot.message != null ? snapshot.message
                        : getString(R.string.engine_starting);
                progress.setIndeterminate(true);
                progress.setProgress(0);
                statusText.setText(label + "（没有卡死喵）");
                break;
            }
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
                copyButton.setVisibility(View.VISIBLE);
                shareButton.setVisibility(View.VISIBLE);
                renderButton.setVisibility(View.VISIBLE);
                renderButton.setText(softwareRenderingPreferred() ? "改用硬件渲染" : "改用软件渲染");
                statusText.setText(snapshot.message != null
                        ? snapshot.message : getString(R.string.engine_error));
                break;
            case STOPPED:
                overlay.setVisibility(View.VISIBLE);
                retryButton.setVisibility(View.VISIBLE);
                copyButton.setVisibility(View.GONE);
                shareButton.setVisibility(View.GONE);
                renderButton.setVisibility(View.GONE);
                statusText.setText("引擎已停止");
                break;
            default:
                break;
        }
    }

    private void shareStatus() {
        String payload = buildReport();
        if (payload.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "KHA 启动失败");
        intent.putExtra(Intent.EXTRA_TEXT, payload);
        try {
            startActivity(Intent.createChooser(intent, "发送错误到…"));
        } catch (Exception error) {
            Toast.makeText(this, "没有可用的发送应用", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildReport() {
        CharSequence text = statusText.getText();
        StringBuilder payload = new StringBuilder();
        if (text != null) payload.append(text);
        // The overlay only has room for a few lines, so append the tail of the
        // engine log; that is where node's real stderr ends up.
        try {
            File log = new File(getFilesDir(), "logs/engine.log");
            if (log.isFile()) {
                String tail = readTail(log, 60000);
                if (tail.length() > 0) {
                    payload.append("\n\n===== engine.log (tail) =====\n").append(tail);
                }
            }
        } catch (Exception ignored) {
        }
        return payload.toString();
    }

    private void copyStatus() {
        String payload = buildReport();
        if (payload.isEmpty()) return;
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("KHA", payload));
        Toast.makeText(this, "已复制，含 engine.log 尾部", Toast.LENGTH_SHORT).show();
    }

    /**
     * Whether the WebView should paint through a software layer. Full-screen
     * dialogs (settings, account) came back torn on some devices even though the
     * page itself is fine, which points at the GPU losing the composited texture
     * for those overlays rather than at the markup. Software painting removes
     * that class of artefact entirely at some cost in scroll smoothness, so it
     * is a sticky user choice rather than an unconditional downgrade.
     */
    private boolean softwareRenderingPreferred() {
        SharedPreferences prefs = getSharedPreferences("kha", MODE_PRIVATE);
        return prefs.getBoolean("software_rendering", false);
    }

    /** Flips the software-rendering preference and recreates the WebView to apply it. */
    private void toggleSoftwareRendering() {
        SharedPreferences prefs = getSharedPreferences("kha", MODE_PRIVATE);
        boolean next = !prefs.getBoolean("software_rendering", false);
        prefs.edit().putBoolean("software_rendering", next).apply();
        Toast.makeText(this,
                next ? "已切换为软件渲染，正在重新载入" : "已恢复硬件渲染，正在重新载入",
                Toast.LENGTH_SHORT).show();
        recreate();
    }

    private static String readTail(File file, int maxBytes) {
        try {
            long length = file.length();
            long from = Math.max(0L, length - maxBytes);
            FileInputStream input = new FileInputStream(file);
            try {
                long skipped = 0;
                while (skipped < from) {
                    long step = input.skip(from - skipped);
                    if (step <= 0) break;
                    skipped += step;
                }
                byte[] buffer = new byte[(int) Math.max(0L, length - skipped)];
                int read = 0;
                while (read < buffer.length) {
                    int step = input.read(buffer, read, buffer.length - read);
                    if (step <= 0) break;
                    read += step;
                }
                return new String(buffer, 0, read, StandardCharsets.UTF_8);
            } finally {
                input.close();
            }
        } catch (Exception error) {
            return "";
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
        if (requestCode == MediaProjectionCapture.REQUEST_CODE) {
            MediaProjectionCapture.deliverResult(resultCode, data);
            return;
        }
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
            if (terminalOverlay != null && terminalOverlay.isShown()) {
                terminalOverlay.hide();
                return true;
            }
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
        // Coming back from the system accessibility page. If the user granted
        // it, finish the switch they asked for; if not, stay on normal without
        // nagging. Android drops the grant when the app process restarts, so
        // that case also simply shows normal mode until they open it again.
        if (pendingAccessibilityMode) {
            pendingAccessibilityMode = false;
            if (RunMode.isAccessibilityEnabled(this)) {
                RunMode.setMode(this, RunMode.ACCESSIBILITY);
                ToastCompat.show(this, "已切换到无障碍模式");
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (currentActivity == this) currentActivity = null;
        if (terminalOverlay != null) terminalOverlay.hide();
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

    /** JS bridge: directory picking plus the native terminal sheet opener. */
    private class Bridge {
        /** Snapshot the 检查更新 page polls. */
        @JavascriptInterface
        public String updateInfo() {
            return UpdateChecker.cached().toJson(
                    APP_VERSION,
                    UpdateChecker.source(MainActivity.this),
                    UpdateChecker.lastError(),
                    getSharedPreferences("kha_update", MODE_PRIVATE).getBoolean("auto", true));
        }

        @JavascriptInterface
        public void setUpdateSource(final String source) {
            UpdateChecker.setSource(MainActivity.this, source);
            UpdateChecker.checkAsync(MainActivity.this, APP_VERSION, true, new Runnable() {
                @Override
                public void run() {
                    }
            });
        }

        @JavascriptInterface
        public void checkUpdate() {
            UpdateChecker.checkAsync(MainActivity.this, APP_VERSION, true, new Runnable() {
                @Override
                public void run() {
                    }
            });
        }

        @JavascriptInterface
        public void setAutoUpdate(final boolean enabled) {
            getSharedPreferences("kha_update", MODE_PRIVATE).edit()
                    .putBoolean("auto", enabled).apply();
        }

        @JavascriptInterface
        public void skipUpdate(final String tag) {
            UpdateChecker.skip(MainActivity.this, tag);
        }

        @JavascriptInterface
        public void showUpdate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    showUpdateDialog();
                }
            });
        }

        @JavascriptInterface
        public void openUpdatePage() {
            UpdateChecker.Result result = UpdateChecker.cached();
            String url = result.assetUrl != null && !result.assetUrl.isEmpty()
                    ? result.assetUrl : result.pageUrl;
            if (url != null && !url.isEmpty()) openExternal(url);
        }

        @JavascriptInterface
        public void setWebDebug(final boolean enabled) {
            getSharedPreferences("kha", MODE_PRIVATE).edit()
                    .putBoolean("web_debug", enabled).apply();
            ToastCompat.show(MainActivity.this, enabled ? "已开启界面调试，重启后生效" : "已关闭界面调试");
        }

        @JavascriptInterface
        public String diagnosticsUi() {
            return uiProbe;
        }

        /** Filled by the shim: what the page actually computed for our rules. */
        @JavascriptInterface
        public void uiProbe(final String payload) {
            uiProbe = payload == null ? "" : payload;
        }

        @JavascriptInterface
        public void openTerminal() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (terminalOverlay != null) terminalOverlay.show();
                }
            });
        }

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
            openUrlExternally(url);
        }

        @JavascriptInterface
        public String version() {
            return APP_VERSION;
        }

        /** Bundled DSH release, shown next to the KHA version. */
        @JavascriptInterface
        public String dshVersion() {
            return RuntimeInstaller.dshVersion();
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

        /**
         * Mode switching is tiered by what it grants, not by the fact that it
         * changes something.
         *
         * <ul>
         *   <li>back to normal: takes privileges away, applies immediately and
         *       never asks -- a confirmation here was pure friction;</li>
         *   <li>accessibility without the service on: falls back to normal
         *       silently. The service has to be granted in the system settings
         *       anyway (and re-granted after the app restarts), so a dialog in
         *       between adds a step without adding information; the settings
         *       row states both facts instead;</li>
         *   <li>advanced: hands the engine Root/Shizuku, so this one confirms.</li>
         * </ul>
         */
        @JavascriptInterface
        public void setRunMode(final String mode) {
            if (!RunMode.NORMAL.equals(mode) && !RunMode.ACCESSIBILITY.equals(mode)
                    && !RunMode.ADVANCED.equals(mode)) {
                return;
            }
            if (RunMode.NORMAL.equals(mode)) {
                applyRunMode(mode);
                return;
            }
            if (RunMode.ACCESSIBILITY.equals(mode)
                    && !RunMode.isAccessibilityEnabled(MainActivity.this)) {
                // Not granted yet: the only useful next step is the system
                // accessibility page. Staying silently on normal here left no
                // way to ever turn the service on. No custom dialog (the grant
                // itself is the decision); on return apply the mode if granted.
                pendingAccessibilityMode = true;
                ToastCompat.show(MainActivity.this, "请在系统设置里开启 KHA 无障碍服务");
                RunMode.openAccessibilitySettings(MainActivity.this);
                return;
            }
            if (RunMode.ADVANCED.equals(mode)
                    && !confirmSensitive("高级模式会通过 Root/Shizuku 执行命令，切换后引擎会重启。继续？")) {
                return;
            }
            applyRunMode(mode);
        }

        private void applyRunMode(final String mode) {
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
