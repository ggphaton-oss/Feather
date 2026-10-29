package app.feather.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static class Tab {
        WebView wv;
        String url = "";
        String title = "New tab";
        boolean priv;
        boolean desktop;
    }

    static final String HOME = "feather://home";
    static final int ACC = 0xFF1FA3A9;
    static final int BRAND = 0xFF1565A8;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final String[] ENG_N = {"DuckDuckGo", "Google", "Bing", "Brave", "Startpage", "Ecosia"};
    static final String[] ENG_U = {
            "https://duckduckgo.com/?q=%s",
            "https://www.google.com/search?q=%s",
            "https://www.bing.com/search?q=%s",
            "https://search.brave.com/search?q=%s",
            "https://www.startpage.com/do/search?q=%s",
            "https://www.ecosia.org/search?q=%s"};

    static final String[] BLOCK = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "2mdn.net", "adnxs.com", "adsrvr.org", "advertising.com",
            "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "scorecardresearch.com",
            "quantserve.com", "moatads.com", "pubmatic.com", "rubiconproject.com", "openx.net",
            "casalemedia.com", "amazon-adsystem.com", "hotjar.com", "mathtag.com", "bluekai.com",
            "krxd.net", "adform.net", "smartadserver.com", "yieldmo.com", "teads.tv", "zedo.com",
            "adcolony.com", "applovin.com", "inmobi.com", "popads.net", "propellerads.com",
            "exoclick.com", "juicyads.com", "trafficjunky.net", "adroll.com", "demdex.net",
            "everesttech.net", "serving-sys.com", "contextweb.com", "sharethrough.com",
            "media.net", "revcontent.com", "mgid.com", "yandexadexchange.net"};

    static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*");
    static final Pattern LOCAL = Pattern.compile("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$");

    SharedPreferences sp;
    final ArrayList<Tab> tabs = new ArrayList<>();
    final ArrayDeque<String> closed = new ArrayDeque<>();
    Tab cur;
    boolean dark, loading;
    int cBg, cBar, cText, cSub, cField, cChip;

    FrameLayout rootFrame, content;
    LinearLayout root, strip, findBar;
    HorizontalScrollView stripScroll;
    android.widget.EditText urlBox, findBox;
    ProgressBar bar;
    TextView reloadBtn, starBtn, backBtn, fwdBtn, findCount;
    View customView;
    WebChromeClient.CustomViewCallback customCb;
    ValueCallback<Uri[]> fileCb;
    String mobileUa, desktopUa, homeLogo;
    static final int RQ_GEO = 78, RQ_MEDIA = 79;
    GeolocationPermissions.Callback geoCb;
    String geoOrigin;
    PermissionRequest mediaReq;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        sp = getSharedPreferences("feather", MODE_PRIVATE);
        String def = WebSettings.getDefaultUserAgent(this);
        mobileUa = def.replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        CookieManager.getInstance().setAcceptCookie(true);
        buildUi();
        restoreTabs();
        Uri d = getIntent().getData();
        if (d != null && saved == null) newTab(d.toString(), false, true);
        if (tabs.isEmpty()) newTab(null, false, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri d = intent.getData();
        if (d != null) newTab(d.toString(), false, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTabs();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) {
            if (t.wv != null) {
                t.wv.destroy();
                t.wv = null;
            }
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { hideCustom(); return; }
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        if (cur != null && cur.wv != null && cur.wv.canGoBack()) { cur.wv.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        if (rc == 77) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(rc, res, data);
    }

    // ------------------------------------------------------------------ UI

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    GradientDrawable rr(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    TextView btn(String glyph, int sizeSp, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(sizeSp);
        t.setTextColor(cText);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(l);
        return t;
    }

    void buildUi() {
        dark = sp.getBoolean("dark", systemDark());
        cBg = dark ? 0xFF0E161D : 0xFFF4F8FB;
        cBar = dark ? 0xFF17222C : 0xFFFFFFFF;
        cText = dark ? 0xFFE8F1F5 : 0xFF16313F;
        cSub = dark ? 0xFF8FA6B3 : 0xFF5F7886;
        cField = dark ? 0xFF223140 : 0xFFE6EFF4;
        cChip = dark ? 0xFF2B4458 : 0xFFCFE6F1;

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(cBar);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cBg);
        rootFrame.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(cBar);
        top.setPadding(dp(8), dp(6), dp(4), dp(6));

        urlBox = new android.widget.EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setHint("Search or enter address");
        urlBox.setHintTextColor(cSub);
        urlBox.setTextColor(cText);
        urlBox.setTextSize(15);
        urlBox.setBackground(rr(cField, 21));
        urlBox.setPadding(dp(16), 0, dp(16), 0);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setSelectAllOnFocus(true);
        urlBox.setOnEditorActionListener((tv, action, ev) -> {
            if (action == EditorInfo.IME_ACTION_GO
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go();
                return true;
            }
            return false;
        });
        urlBox.setOnFocusChangeListener((vw, has) -> {
            if (cur == null) return;
            if (has) {
                urlBox.setText(cur.url);
                urlBox.selectAll();
            } else {
                urlBox.setText(pretty(cur.url));
            }
        });
        top.addView(urlBox, new LinearLayout.LayoutParams(0, dp(42), 1f));

        reloadBtn = btn("\u21BB", 20, x -> reloadOrStop());
        top.addView(reloadBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        top.addView(btn("\uFF0B", 22, x -> newTab(null, false, true)), new LinearLayout.LayoutParams(dp(42), dp(42)));
        root.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(ACC));
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(3)));

        // tab strip
        stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setBackgroundColor(cBar);
        strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(6), dp(2), dp(6), dp(6));
        stripScroll.addView(strip, new ViewGroup.LayoutParams(WRAP, WRAP));
        root.addView(stripScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        // page area
        content = new FrameLayout(this);
        content.setBackgroundColor(cBg);
        root.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // find bar
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(cBar);
        findBar.setPadding(dp(10), dp(4), dp(4), dp(4));
        findBar.setVisibility(View.GONE);
        findBox = new android.widget.EditText(this);
        findBox.setSingleLine(true);
        findBox.setHint("Find in page");
        findBox.setHintTextColor(cSub);
        findBox.setTextColor(cText);
        findBox.setTextSize(15);
        findBox.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { doFind(s.toString()); }
            public void afterTextChanged(Editable e) { }
        });
        findBar.addView(findBox, new LinearLayout.LayoutParams(0, WRAP, 1f));
        findCount = new TextView(this);
        findCount.setTextColor(cSub);
        findCount.setTextSize(13);
        findCount.setPadding(dp(6), 0, dp(6), 0);
        findBar.addView(findCount, new LinearLayout.LayoutParams(WRAP, WRAP));
        findBar.addView(btn("\u25B2", 14, x -> { if (cur != null && cur.wv != null) cur.wv.findNext(false); }),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        findBar.addView(btn("\u25BC", 14, x -> { if (cur != null && cur.wv != null) cur.wv.findNext(true); }),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        findBar.addView(btn("\u2715", 16, x -> closeFind()), new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(findBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        // bottom bar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setBackgroundColor(cBar);
        backBtn = btn("\u2039", 30, x -> { if (cur != null && cur.wv != null && cur.wv.canGoBack()) cur.wv.goBack(); });
        fwdBtn = btn("\u203A", 30, x -> { if (cur != null && cur.wv != null && cur.wv.canGoForward()) cur.wv.goForward(); });
        TextView home = btn("\u2302", 22, x -> { if (cur != null) loadHome(cur); });
        starBtn = btn("\u2606", 22, x -> toggleBookmark());
        TextView menu = btn("\u22EE", 24, x -> menu());
        TextView[] bs = {backBtn, fwdBtn, home, starBtn, menu};
        for (TextView b : bs) bottom.addView(b, new LinearLayout.LayoutParams(0, dp(46), 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));

        setContentView(rootFrame);
        applySystemBars();
    }

    void applySystemBars() {
        getWindow().setStatusBarColor(cBar);
        getWindow().setNavigationBarColor(cBar);
        int f = 0;
        if (!dark) {
            f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    void updateChips() {
        strip.removeAllViews();
        stripScroll.setVisibility(tabs.size() > 1 ? View.VISIBLE : View.GONE);
        for (final Tab t : tabs) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.HORIZONTAL);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setBackground(rr(t == cur ? cChip : cField, 17));
            c.setPadding(dp(12), 0, dp(2), 0);
            TextView tt = new TextView(this);
            tt.setText((t.priv ? "\uD83D\uDD76 " : "") + t.title);
            tt.setTextColor(t == cur ? cText : cSub);
            tt.setTextSize(13);
            tt.setSingleLine(true);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            tt.setMaxWidth(dp(130));
            TextView x = new TextView(this);
            x.setText("\u2715");
            x.setTextSize(12);
            x.setTextColor(cSub);
            x.setPadding(dp(10), dp(10), dp(10), dp(10));
            x.setOnClickListener(vw -> closeTab(t));
            c.addView(tt);
            c.addView(x);
            c.setOnClickListener(vw -> { if (t != cur) switchTo(t); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, dp(34));
            lp.setMargins(dp(3), 0, dp(3), 0);
            strip.addView(c, lp);
        }
        stripScroll.post(() -> {
            int i = tabs.indexOf(cur);
            View vw = i >= 0 ? strip.getChildAt(i) : null;
            if (vw != null) stripScroll.smoothScrollTo(Math.max(0, vw.getLeft() - dp(40)), 0);
        });
    }

    String pretty(String u) {
        if (u == null || u.isEmpty()) return "";
        String s = u;
        if (s.startsWith("https://")) s = s.substring(8);
        if (s.startsWith("www.")) s = s.substring(4);
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    void updateUrlBox() {
        if (cur == null) return;
        if (!urlBox.hasFocus()) urlBox.setText(pretty(cur.url));
        boolean bm = !cur.url.isEmpty() && isBookmarked(cur.url);
        starBtn.setText(bm ? "\u2605" : "\u2606");
        starBtn.setTextColor(bm ? ACC : cText);
        boolean cb = cur.wv != null && cur.wv.canGoBack();
        boolean cf = cur.wv != null && cur.wv.canGoForward();
        backBtn.setAlpha(cb ? 1f : 0.35f);
        fwdBtn.setAlpha(cf ? 1f : 0.35f);
    }

    void setLoading(boolean l) {
        loading = l;
        reloadBtn.setText(l ? "\u2715" : "\u21BB");
        if (!l) bar.setVisibility(View.GONE);
    }

    void hideKb() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    AlertDialog.Builder dlg() {
        return new AlertDialog.Builder(this, dark
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    // ------------------------------------------------------------------ tabs

    Tab addTab(String url, boolean priv, boolean activate) {
        Tab t = new Tab();
        t.url = url == null ? "" : url;
        t.priv = priv;
        t.title = t.url.isEmpty() ? "New tab" : hostOf(t.url);
        tabs.add(t);
        if (activate) switchTo(t);
        else updateChips();
        return t;
    }

    void newTab(String url, boolean priv, boolean activate) {
        addTab(url, priv, activate);
    }

    void switchTo(Tab t) {
        if (cur != null && cur.wv != null) {
            cur.wv.onPause();
            content.removeView(cur.wv);
        }
        cur = t;
        if (t.wv == null) {
            createWebView(t);
            if (t.url.isEmpty()) loadHome(t);
            else t.wv.loadUrl(t.url);
        }
        content.addView(t.wv, new FrameLayout.LayoutParams(MATCH, MATCH));
        t.wv.onResume();
        setLoading(false);
        updateChips();
        updateUrlBox();
        findCount.setText("");
    }

    void closeTab(Tab t) {
        if (t == null) return;
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        if (!t.priv && !t.url.isEmpty()) {
            closed.push(t.url);
            while (closed.size() > 15) closed.removeLast();
        }
        boolean wasCur = (t == cur);
        if (t.wv != null) {
            content.removeView(t.wv);
            t.wv.destroy();
            t.wv = null;
        }
        tabs.remove(t);
        if (tabs.isEmpty()) {
            cur = null;
            newTab(null, false, true);
        } else if (wasCur) {
            cur = null;
            switchTo(tabs.get(Math.min(idx, tabs.size() - 1)));
        } else {
            updateChips();
        }
    }

    void closeAllTabs() {
        for (Tab t : new ArrayList<>(tabs)) {
            if (t.wv != null) {
                content.removeView(t.wv);
                t.wv.destroy();
                t.wv = null;
            }
        }
        tabs.clear();
        cur = null;
        newTab(null, false, true);
    }

    void saveTabs() {
        try {
            JSONArray a = new JSONArray();
            int act = 0;
            for (Tab t : tabs) {
                if (t.priv) continue;
                if (t == cur) act = a.length();
                a.put(t.url);
            }
            JSONObject o = new JSONObject();
            o.put("tabs", a);
            o.put("active", act);
            sp.edit().putString("session", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    void restoreTabs() {
        try {
            JSONObject o = new JSONObject(sp.getString("session", "{}"));
            JSONArray a = o.optJSONArray("tabs");
            if (a == null || a.length() == 0) return;
            for (int i = 0; i < a.length(); i++) addTab(a.getString(i), false, false);
            int act = Math.max(0, Math.min(o.optInt("active", 0), tabs.size() - 1));
            switchTo(tabs.get(act));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ webview

    void createWebView(final Tab t) {
        final WebView w = new WebView(this);
        w.setBackgroundColor(cBg);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(sp.getBoolean("js", true));
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(sp.getInt("zoom", 100));
        s.setCacheMode(t.priv ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
        s.setSaveFormData(!t.priv);
        s.setUserAgentString(t.desktop ? desktopUa : mobileUa);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, !sp.getBoolean("block3p", false));
        t.wv = w;

        w.setDownloadListener((url, ua, cd, mime, len) -> download(url, ua, cd, mime));
        w.setFindListener((active, total, done) -> {
            if (t == cur) findCount.setText(total == 0 ? "0" : (active + 1) + "/" + total);
        });
        w.setOnLongClickListener(vw -> {
            WebView.HitTestResult r = w.getHitTestResult();
            String ex = r.getExtra();
            int ty = r.getType();
            if (ex == null) return false;
            if (ty == WebView.HitTestResult.SRC_ANCHOR_TYPE) { linkMenu(t, ex, false); return true; }
            if (ty == WebView.HitTestResult.IMAGE_TYPE || ty == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                linkMenu(t, ex, true);
                return true;
            }
            return false;
        });

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(t, r.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame() && sp.getBoolean("adblock", true) && blocked(r.getUrl().getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap fav) {
                if (url.startsWith(HOME)) {
                    t.url = "";
                    t.title = "New tab";
                } else if (!url.startsWith("data:")) {
                    t.url = url;
                }
                if (t == cur) {
                    setLoading(true);
                    bar.setProgress(5);
                    bar.setVisibility(View.VISIBLE);
                    updateUrlBox();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (t.wv == null) return;
                String ti = v.getTitle();
                if (url.startsWith(HOME)) {
                    t.title = "New tab";
                } else if (ti != null && !ti.isEmpty() && !ti.startsWith("feather://") && !ti.equals("about:blank")) {
                    t.title = ti;
                } else if (!t.url.isEmpty()) {
                    t.title = hostOf(t.url);
                }
                if (!t.priv && url.startsWith("http")) addHistory(t.title, url);
                if (sp.getBoolean("darkpages", false)) injectDark(v);
                if (t == cur) {
                    setLoading(false);
                    updateUrlBox();
                }
                updateChips();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (!r.isForMainFrame()) return;
                String d = String.valueOf(e.getDescription());
                if (d.contains("ERR_ABORTED") || d.contains("ERR_UNKNOWN_URL_SCHEME")) return;
                String u = r.getUrl().toString();
                v.loadDataWithBaseURL(u, errorHtml(u, d), "text/html", "utf-8", u);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (t == cur && loading) {
                    bar.setProgress(p);
                    bar.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                }
            }

            @Override
            public void onReceivedTitle(WebView v, String ti) {
                if (ti == null || ti.startsWith("feather://") || ti.equals("about:blank") || t.url.isEmpty()) return;
                t.title = ti;
                updateChips();
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean userGesture, Message msg) {
                if (!userGesture) return false; // popup blocker
                Tab nt = addTab("", t.priv, false);
                createWebView(nt);
                switchTo(nt);
                WebView.WebViewTransport tr = (WebView.WebViewTransport) msg.obj;
                tr.setWebView(nt.wv);
                msg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), 77);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                askGeo(origin, cb);
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                askMedia(req);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                view.setBackgroundColor(Color.BLACK);
                rootFrame.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }
        });
    }

    // ------------------------------------------------------------------ site permissions

    boolean has(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    void askGeo(final String origin, final GeolocationPermissions.Callback cb) {
        dlg().setTitle("Location")
                .setMessage(hostOf(origin) + " wants to know your location.")
                .setPositiveButton("Allow", (d, w) -> {
                    if (has(Manifest.permission.ACCESS_FINE_LOCATION)
                            || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                        cb.invoke(origin, true, false);
                    } else {
                        geoOrigin = origin;
                        geoCb = cb;
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, RQ_GEO);
                    }
                })
                .setNegativeButton("Block", (d, w) -> cb.invoke(origin, false, false))
                .setOnCancelListener(d -> cb.invoke(origin, false, false))
                .show();
    }

    void askMedia(final PermissionRequest req) {
        boolean cam = false, mic = false;
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) cam = true;
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) mic = true;
        }
        if (!cam && !mic) { req.deny(); return; }
        String what = cam && mic ? "camera and microphone" : cam ? "camera" : "microphone";
        dlg().setTitle("Allow " + what + "?")
                .setMessage(hostOf(req.getOrigin().toString()) + " wants to use your " + what + ".")
                .setPositiveButton("Allow", (d, w) -> {
                    ArrayList<String> need = new ArrayList<>();
                    for (String r : req.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA))
                            need.add(Manifest.permission.CAMERA);
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO))
                            need.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (need.isEmpty()) {
                        finishMedia(req);
                    } else {
                        mediaReq = req;
                        requestPermissions(need.toArray(new String[0]), RQ_MEDIA);
                    }
                })
                .setNegativeButton("Block", (d, w) -> req.deny())
                .setOnCancelListener(d -> req.deny())
                .show();
    }

    // grants only the resources whose Android permission is actually held
    void finishMedia(PermissionRequest req) {
        ArrayList<String> ok = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
        }
        if (ok.isEmpty()) req.deny();
        else req.grant(ok.toArray(new String[0]));
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] perms, int[] results) {
        super.onRequestPermissionsResult(rc, perms, results);
        if (rc == RQ_GEO && geoCb != null) {
            boolean ok = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
            geoCb.invoke(geoOrigin, ok, false);
            geoCb = null;
            geoOrigin = null;
        } else if (rc == RQ_MEDIA && mediaReq != null) {
            finishMedia(mediaReq);
            mediaReq = null;
        }
    }

    void hideCustom() {
        if (customView == null) return;
        rootFrame.removeView(customView);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        applySystemBars();
    }

    boolean handleUrl(Tab t, String url) {
        if (url.startsWith("feather://search")) {
            String q = Uri.parse(url).getQueryParameter("q");
            if (q != null) load(t, resolve(q));
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")
                || url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("javascript:")) {
            return false;
        }
        if (url.startsWith("feather://")) return true;
        try {
            if (url.startsWith("intent:")) {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fb = i.getStringExtra("browser_fallback_url");
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    if (fb != null) load(t, fb);
                }
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        } catch (Exception e) {
            toast("No app can open this link");
        }
        return true;
    }

    void injectDark(WebView v) {
        v.evaluateJavascript("(function(){if(document.getElementById('__fd'))return;"
                + "var s=document.createElement('style');s.id='__fd';"
                + "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}"
                + "img,video,picture,canvas,iframe{filter:invert(1) hue-rotate(180deg)!important}';"
                + "(document.head||document.documentElement).appendChild(s)})();", null);
    }

    boolean blocked(String host) {
        if (host == null) return false;
        for (String b : BLOCK) {
            if (host.equals(b) || host.endsWith("." + b)) return true;
        }
        return false;
    }

    void download(String url, String ua, String cd, String mime) {
        try {
            DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
            String name = URLUtil.guessFileName(url, cd, mime);
            if (mime != null) rq.setMimeType(mime);
            String ck = CookieManager.getInstance().getCookie(url);
            if (ck != null) rq.addRequestHeader("Cookie", ck);
            if (ua != null) rq.addRequestHeader("User-Agent", ua);
            rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
            toast("Downloading " + name);
        } catch (Exception e) {
            toast("Download failed");
        }
    }

    // ------------------------------------------------------------------ navigation helpers

    void go() {
        String u = resolve(urlBox.getText().toString());
        hideKb();
        if (cur == null) return;
        if (cur.wv != null) cur.wv.requestFocus();
        load(cur, u);
    }

    void load(Tab t, String u) {
        if (t.wv == null) return;
        if (u == null || u.isEmpty()) loadHome(t);
        else t.wv.loadUrl(u);
    }

    void reloadOrStop() {
        if (cur == null || cur.wv == null) return;
        if (loading) {
            cur.wv.stopLoading();
            setLoading(false);
        } else if (cur.url.isEmpty()) {
            loadHome(cur);
        } else {
            cur.wv.reload();
        }
    }

    void loadHome(Tab t) {
        if (t.wv == null) return;
        t.url = "";
        t.title = "New tab";
        t.wv.loadDataWithBaseURL(HOME, homeHtml(), "text/html", "utf-8", HOME);
    }

    String resolve(String in) {
        String s = in == null ? "" : in.trim();
        if (s.isEmpty()) return "";
        if (HAS_SCHEME.matcher(s).matches() || s.startsWith("about:")) return s;
        if (!s.contains(" ")) {
            if (LOCAL.matcher(s).matches()) return "http://" + s;
            if (s.contains(".")) return "https://" + s;
        }
        int e = sp.getInt("eng", 0);
        if (e < 0 || e >= ENG_U.length) e = 0;
        return ENG_U[e].replace("%s", Uri.encode(s));
    }

    String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return url;
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return url;
        }
    }

    // ------------------------------------------------------------------ storage (local only)

    ArrayList<String[]> readList(String k) {
        ArrayList<String[]> r = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONArray o = a.getJSONArray(i);
                r.add(new String[]{o.getString(0), o.getString(1)});
            }
        } catch (Exception ignored) { }
        return r;
    }

    void writeList(String k, List<String[]> l) {
        JSONArray a = new JSONArray();
        for (String[] s : l) {
            JSONArray o = new JSONArray();
            o.put(s[0]);
            o.put(s[1]);
            a.put(o);
        }
        sp.edit().putString(k, a.toString()).apply();
    }

    void addHistory(String title, String url) {
        ArrayList<String[]> h = readList("history");
        for (int i = h.size() - 1; i >= 0; i--) if (h.get(i)[1].equals(url)) h.remove(i);
        h.add(0, new String[]{title == null ? "" : title, url});
        while (h.size() > 300) h.remove(h.size() - 1);
        writeList("history", h);
    }

    boolean isBookmarked(String url) {
        for (String[] b : readList("bookmarks")) if (b[1].equals(url)) return true;
        return false;
    }

    void toggleBookmark() {
        if (cur == null || cur.url.isEmpty()) { toast("Open a page first"); return; }
        ArrayList<String[]> b = readList("bookmarks");
        boolean found = false;
        for (int i = b.size() - 1; i >= 0; i--) {
            if (b.get(i)[1].equals(cur.url)) { b.remove(i); found = true; }
        }
        if (!found) b.add(0, new String[]{cur.title, cur.url});
        writeList("bookmarks", b);
        toast(found ? "Bookmark removed" : "Bookmarked");
        updateUrlBox();
    }

    void listDialog(String title, final String key, String clearLabel) {
        final ArrayList<String[]> items = readList(key);
        if (items.isEmpty()) { toast("Nothing here yet"); return; }
        final ListView lv = new ListView(this);
        final ArrayAdapter<String[]> ad = new ArrayAdapter<String[]>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int p, View c, ViewGroup g) {
                View row = super.getView(p, c, g);
                String[] it = getItem(p);
                TextView a = row.findViewById(android.R.id.text1);
                TextView b = row.findViewById(android.R.id.text2);
                a.setText(it[0].isEmpty() ? it[1] : it[0]);
                a.setSingleLine(true);
                a.setEllipsize(TextUtils.TruncateAt.END);
                a.setTextColor(cText);
                b.setText(it[1]);
                b.setSingleLine(true);
                b.setEllipsize(TextUtils.TruncateAt.END);
                b.setTextColor(cSub);
                return row;
            }
        };
        lv.setAdapter(ad);
        final AlertDialog[] ref = new AlertDialog[1];
        lv.setOnItemClickListener((parent, vw, pos, id) -> {
            String u = items.get(pos)[1];
            ref[0].dismiss();
            if (cur != null) load(cur, u);
        });
        lv.setOnItemLongClickListener((parent, vw, pos, id) -> {
            items.remove(pos);
            writeList(key, items);
            ad.notifyDataSetChanged();
            updateUrlBox();
            return true;
        });
        ref[0] = dlg().setTitle(title + "  (hold to delete)").setView(lv)
                .setNeutralButton(clearLabel, (d, w) -> {
                    writeList(key, new ArrayList<String[]>());
                    updateUrlBox();
                })
                .setPositiveButton("Close", null).create();
        ref[0].show();
    }

    // ------------------------------------------------------------------ menus

    void item(List<String> l, List<Runnable> a, String s, Runnable r) {
        l.add(s);
        a.add(r);
    }

    String onoff(boolean b) {
        return b ? "On" : "Off";
    }

    void showItems(String title, List<String> l, final List<Runnable> a) {
        AlertDialog.Builder b = dlg();
        if (title != null) b.setTitle(title);
        b.setItems(l.toArray(new String[0]), (d, i) -> a.get(i).run()).show();
    }

    void menu() {
        ArrayList<String> l = new ArrayList<>();
        ArrayList<Runnable> a = new ArrayList<>();
        item(l, a, "New private tab", () -> newTab(null, true, true));
        item(l, a, "Bookmarks", () -> listDialog("Bookmarks", "bookmarks", "Clear all"));
        item(l, a, "History", () -> listDialog("History", "history", "Clear all"));
        item(l, a, "Downloads", () -> {
            try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
            catch (Exception e) { toast("Can't open downloads"); }
        });
        item(l, a, "Find in page", this::openFind);
        item(l, a, "Share page", () -> {
            if (cur == null || cur.url.isEmpty()) return;
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cur.url);
            startActivity(Intent.createChooser(i, "Share link"));
        });
        item(l, a, "Copy link", () -> { if (cur != null && !cur.url.isEmpty()) copy(cur.url); });
        item(l, a, "Print / save as PDF", this::printPage);
        item(l, a, "Desktop site: " + onoff(cur != null && cur.desktop), () -> {
            if (cur == null || cur.wv == null) return;
            cur.desktop = !cur.desktop;
            cur.wv.getSettings().setUserAgentString(cur.desktop ? desktopUa : mobileUa);
            if (cur.url.isEmpty()) loadHome(cur);
            else cur.wv.reload();
        });
        item(l, a, "Dark web pages: " + onoff(sp.getBoolean("darkpages", false)), () -> {
            boolean on = !sp.getBoolean("darkpages", false);
            sp.edit().putBoolean("darkpages", on).apply();
            if (cur != null && cur.wv != null) {
                if (on) injectDark(cur.wv);
                else cur.wv.reload();
            }
        });
        item(l, a, "Reopen closed tab", () -> {
            if (closed.isEmpty()) toast("No closed tabs");
            else newTab(closed.pop(), false, true);
        });
        item(l, a, "Close all tabs", this::closeAllTabs);
        item(l, a, "Settings", this::settings);
        showItems(null, l, a);
    }

    void settings() {
        ArrayList<String> l = new ArrayList<>();
        ArrayList<Runnable> a = new ArrayList<>();
        int e = sp.getInt("eng", 0);
        item(l, a, "Search engine: " + ENG_N[e], this::pickEngine);
        item(l, a, "Dark interface: " + onoff(dark), () -> {
            sp.edit().putBoolean("dark", !dark).apply();
            recreate();
        });
        item(l, a, "Block ads & trackers: " + onoff(sp.getBoolean("adblock", true)), () -> {
            sp.edit().putBoolean("adblock", !sp.getBoolean("adblock", true)).apply();
            settings();
        });
        item(l, a, "Block third-party cookies: " + onoff(sp.getBoolean("block3p", false)), () -> {
            boolean on = !sp.getBoolean("block3p", false);
            sp.edit().putBoolean("block3p", on).apply();
            for (Tab t : tabs) {
                if (t.wv != null) CookieManager.getInstance().setAcceptThirdPartyCookies(t.wv, !on);
            }
            settings();
        });
        item(l, a, "JavaScript: " + onoff(sp.getBoolean("js", true)), () -> {
            boolean on = !sp.getBoolean("js", true);
            sp.edit().putBoolean("js", on).apply();
            for (Tab t : tabs) {
                if (t.wv != null) t.wv.getSettings().setJavaScriptEnabled(on);
            }
            if (cur != null && cur.wv != null) cur.wv.reload();
            settings();
        });
        item(l, a, "Text size: " + sp.getInt("zoom", 100) + "%", () -> {
            int z = sp.getInt("zoom", 100);
            z = z < 100 ? 100 : z < 120 ? 120 : z < 140 ? 140 : 80;
            sp.edit().putInt("zoom", z).apply();
            for (Tab t : tabs) {
                if (t.wv != null) t.wv.getSettings().setTextZoom(sp.getInt("zoom", 100));
            }
            settings();
        });
        item(l, a, "Clear history", () -> { writeList("history", new ArrayList<String[]>()); toast("History cleared"); });
        item(l, a, "Clear cache & cookies", () -> {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            for (Tab t : tabs) {
                if (t.wv != null) t.wv.clearCache(true);
            }
            toast("Cache and cookies cleared");
        });
        item(l, a, "Make Feather the default browser", () -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
            catch (Exception ex) { toast("Open Android Settings > Apps > Default apps"); }
        });
        item(l, a, "About Feather", () -> dlg().setTitle("Feather 1.0")
                .setMessage("A tiny, fast browser. No account, no tracking. Tabs, bookmarks, history and settings stay on this device only.")
                .setPositiveButton("OK", null).show());
        showItems("Settings", l, a);
    }

    void pickEngine() {
        dlg().setTitle("Search engine").setItems(ENG_N, (d, i) -> {
            sp.edit().putInt("eng", i).apply();
            if (cur != null && cur.url.isEmpty()) loadHome(cur);
            settings();
        }).show();
    }

    void linkMenu(final Tab t, final String url, boolean image) {
        ArrayList<String> l = new ArrayList<>();
        ArrayList<Runnable> a = new ArrayList<>();
        item(l, a, image ? "Open image in new tab" : "Open in new tab", () -> newTab(url, t.priv, true));
        if (!image) item(l, a, "Open in private tab", () -> newTab(url, true, true));
        if (image) item(l, a, "Download image", () -> download(url, t.wv == null ? mobileUa : t.wv.getSettings().getUserAgentString(), null, null));
        item(l, a, "Copy link", () -> copy(url));
        item(l, a, "Share", () -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
            startActivity(Intent.createChooser(i, "Share"));
        });
        showItems(url.length() > 60 ? url.substring(0, 60) + "\u2026" : url, l, a);
    }

    void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("link", s));
            toast("Copied");
        }
    }

    void printPage() {
        if (cur == null || cur.wv == null) return;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        String n = cur.title == null ? "Feather page" : cur.title;
        pm.print(n, cur.wv.createPrintDocumentAdapter(n), new PrintAttributes.Builder().build());
    }

    void openFind() {
        findBar.setVisibility(View.VISIBLE);
        findBox.setText("");
        findBox.requestFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(findBox, 0);
    }

    void closeFind() {
        findBar.setVisibility(View.GONE);
        if (cur != null && cur.wv != null) {
            cur.wv.clearMatches();
            cur.wv.requestFocus();
        }
        findCount.setText("");
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(findBox.getWindowToken(), 0);
    }

    void doFind(String q) {
        if (cur == null || cur.wv == null) return;
        if (q.isEmpty()) {
            cur.wv.clearMatches();
            findCount.setText("");
        } else {
            cur.wv.findAllAsync(q);
        }
    }

    // ------------------------------------------------------------------ built-in pages

    String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    String themeCss() {
        return dark
                ? "--bg:#0E161D;--tx:#E8F1F5;--sub:#8FA6B3;--card:#17222C;--line:#2B4458;"
                : "--bg:#F4F8FB;--tx:#16313F;--sub:#5F7886;--card:#FFFFFF;--line:#D3E1E9;";
    }

    String logoUri() {
        if (homeLogo != null) return homeLogo;
        homeLogo = "";
        try {
            InputStream in = getAssets().open("logo.png");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            homeLogo = "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) { }
        return homeLogo;
    }

    String homeHtml() {
        ArrayList<String[]> bm = readList("bookmarks");
        if (bm.isEmpty()) {
            bm.add(new String[]{"Wikipedia", "https://wikipedia.org"});
            bm.add(new String[]{"YouTube", "https://m.youtube.com"});
            bm.add(new String[]{"GitHub", "https://github.com"});
            bm.add(new String[]{"Reddit", "https://reddit.com"});
            bm.add(new String[]{"Maps", "https://www.openstreetmap.org"});
            bm.add(new String[]{"News", "https://news.ycombinator.com"});
            bm.add(new String[]{"Weather", "https://wttr.in"});
            bm.add(new String[]{"Translate", "https://translate.google.com"});
        }
        StringBuilder tiles = new StringBuilder();
        int c = 0;
        for (String[] b : bm) {
            if (c++ >= 12) break;
            String host = hostOf(b[1]);
            String nm = b[0].isEmpty() ? host : b[0];
            String letter = host.isEmpty() ? "\u2022" : host.substring(0, 1).toUpperCase();
            tiles.append("<a class='t' href='").append(esc(b[1])).append("'><div class='i'>")
                    .append(esc(letter)).append("</div><div class='n'>").append(esc(nm)).append("</div></a>");
        }
        String eng = ENG_N[Math.max(0, Math.min(sp.getInt("eng", 0), ENG_N.length - 1))];
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>:root{" + themeCss() + "--ac:#1FA3A9;--br:#1565A8}"
                + "*{-webkit-tap-highlight-color:transparent}"
                + "body{margin:0;background:var(--bg);color:var(--tx);font-family:sans-serif;display:flex;"
                + "flex-direction:column;align-items:center;padding:36px 16px 24px}"
                + ".logo{width:92px;height:92px;border-radius:50%;background:#fff;box-shadow:0 4px 18px rgba(21,101,168,.25)}"
                + "h1{margin:14px 0 2px;font-size:28px;letter-spacing:.5px;background:linear-gradient(90deg,var(--br),var(--ac));"
                + "-webkit-background-clip:text;color:transparent}"
                + "#g{color:var(--sub);font-size:14px;margin-bottom:22px}"
                + "form{width:100%;max-width:520px}"
                + "input{width:100%;box-sizing:border-box;padding:14px 20px;border-radius:30px;border:1px solid var(--line);"
                + "background:var(--card);color:var(--tx);font-size:16px;outline:none}"
                + "input:focus{border-color:var(--ac)}"
                + ".grid{display:grid;grid-template-columns:repeat(4,1fr);gap:18px 10px;max-width:520px;width:100%;margin-top:30px}"
                + ".t{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:var(--tx);font-size:12px}"
                + ".i{width:54px;height:54px;border-radius:17px;background:linear-gradient(135deg,var(--br),var(--ac));color:#fff;"
                + "display:flex;align-items:center;justify-content:center;font-size:22px;font-weight:700;margin-bottom:7px}"
                + ".n{max-width:76px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                + "</style></head><body>"
                + "<img class='logo' src='" + logoUri() + "'>"
                + "<h1>Feather</h1><div id='g'></div>"
                + "<form onsubmit='go();return false'><input id='q' placeholder='Search with " + esc(eng)
                + " or type a URL' autocomplete='off' autocapitalize='off'></form>"
                + "<div class='grid'>" + tiles + "</div>"
                + "<script>var h=new Date().getHours();document.getElementById('g').textContent="
                + "h<5?'Night owl mode':h<12?'Good morning':h<18?'Good afternoon':'Good evening';"
                + "function go(){var v=document.getElementById('q').value.trim();"
                + "if(v)location.href='feather://search?q='+encodeURIComponent(v)}</script>"
                + "</body></html>";
    }

    String errorHtml(String url, String desc) {
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>:root{" + themeCss() + "}body{margin:0;background:var(--bg);color:var(--tx);"
                + "font-family:sans-serif;padding:64px 28px;text-align:center}"
                + "h2{margin:0 0 8px}p{color:var(--sub);word-break:break-all}"
                + "a{display:inline-block;margin-top:20px;padding:12px 28px;border-radius:26px;color:#fff;"
                + "text-decoration:none;background:linear-gradient(90deg,#1565A8,#1FA3A9)}</style></head><body>"
                + "<h2>Can't reach this page</h2><p>" + esc(url) + "</p><p>" + esc(desc) + "</p>"
                + "<a href='" + esc(url) + "'>Try again</a></body></html>";
    }
}
