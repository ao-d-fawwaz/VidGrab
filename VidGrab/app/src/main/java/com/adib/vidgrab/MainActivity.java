package com.adib.vidgrab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final String[] VIDEO_EXT = {".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".flv", ".avi"};
    static final String HOME = "https://www.google.com";

    /** Script yang disuntikkan berkala: cari <video>/<source> dan link langsung ke file video. */
    static final String SCAN_JS =
            "(function(){try{var o=[];" +
            "document.querySelectorAll('video,source').forEach(function(e){" +
            "[e.currentSrc,e.src,e.getAttribute('data-src')].forEach(function(s){" +
            "if(s){try{o.push('V|'+new URL(s,location.href).href);}catch(x){}}});});" +
            "document.querySelectorAll('a[href]').forEach(function(a){" +
            "if(/\\.(mp4|webm|mkv|mov|m4v|3gp|m3u8)(\\?|#|$)/i.test(a.href))o.push('A|'+a.href);});" +
            "if(o.length&&window.VidGrab)VidGrab.report(JSON.stringify(o));" +
            "}catch(x){}})();";

    static class VideoItem {
        final String url;
        final String type;
        final String referer;
        String name;

        VideoItem(String url, String type, String referer) {
            this.url = url;
            this.type = type;
            this.referer = referer;
        }
    }

    private WebView web;
    private EditText urlBar;
    private Button grabBtn;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout mainUi;
    private FrameLayout fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    private final LinkedHashMap<String, VideoItem> found = new LinkedHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String pageUrl = "";
    private volatile String pageTitle = "";
    private String userAgent;
    private VideoItem pendingDownload;

    private final Runnable scanner = new Runnable() {
        @Override
        public void run() {
            scanNow();
            ui.postDelayed(this, 3000);
        }
    };

    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        setupWebView();

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
        }

        String fromIntent = extractUrl(getIntent());
        loadUrl(fromIntent != null ? fromIntent : HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String u = extractUrl(intent);
        if (u != null) loadUrl(u);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        ui.removeCallbacks(scanner);
        ui.postDelayed(scanner, 3000);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(scanner);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingDownload != null) {
            VideoItem it = pendingDownload;
            pendingDownload = null;
            if (ok) startDownload(it);
            else toast("Izin penyimpanan ditolak, tidak bisa menyimpan video.");
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
        final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

        FrameLayout root = new FrameLayout(this);
        mainUi = new LinearLayout(this);
        mainUi.setOrientation(LinearLayout.VERTICAL);
        mainUi.setBackgroundColor(0xFF121212);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.setBackgroundColor(0xFF1E1E1E);

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("Tempel link / cari...");
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(0xFF888888);
        urlBar.setTextSize(14);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            go();
            return true;
        });

        Button goBtn = new Button(this);
        goBtn.setText("Buka");
        goBtn.setOnClickListener(v -> go());

        bar.addView(urlBar, new LinearLayout.LayoutParams(0, WRAP, 1f));
        bar.addView(goBtn, new LinearLayout.LayoutParams(WRAP, WRAP));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);

        status = new TextView(this);
        status.setBackgroundColor(0xFF0D47A1);
        status.setTextColor(Color.WHITE);
        status.setTextSize(13);
        status.setPadding(dp(10), dp(6), dp(10), dp(6));
        status.setVisibility(View.GONE);

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this);
        content.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));

        grabBtn = new Button(this);
        grabBtn.setTextColor(Color.WHITE);
        grabBtn.setTextSize(16);
        grabBtn.setBackgroundColor(0xFF616161);
        grabBtn.setPadding(dp(18), dp(10), dp(18), dp(10));
        grabBtn.setOnClickListener(v -> showFoundList());
        grabBtn.setOnLongClickListener(v -> {
            scanNow();
            toast("Memindai ulang halaman...");
            return true;
        });
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(dp(16), dp(16), dp(16), dp(24));
        content.addView(grabBtn, blp);

        mainUi.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        mainUi.addView(progress, new LinearLayout.LayoutParams(MATCH, dp(3)));
        mainUi.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
        mainUi.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);

        root.addView(mainUi, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(fullscreen, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
        updateBtn();
    }

    private void updateBtn() {
        int n = found.size();
        grabBtn.setText("🎬 " + n);
        grabBtn.setBackgroundColor(n > 0 ? 0xFFD32F2F : 0xFF616161);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showStatus(String msg, boolean finished) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideStatus);
        if (finished) ui.postDelayed(hideStatus, 8000);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);           // link target=_blank dibuka di tab yang sama
        s.setJavaScriptCanOpenWindowsAutomatically(false); // blok popup iklan
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Hapus penanda "; wv" supaya situs tidak memblokir WebView
        userAgent = s.getUserAgentString().replace("; wv", "");
        s.setUserAgentString(userAgent);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "VidGrab");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if (scheme == null) return true;
                scheme = scheme.toLowerCase(Locale.ROOT);
                // Hanya izinkan http/https; intent://, market:// dll. (biasanya iklan) diblok
                return !(scheme.equals("http") || scheme.equals("https") || scheme.equals("about"));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageUrl = url;
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                pageTitle = view.getTitle() == null ? "" : view.getTitle();
                if (!urlBar.hasFocus()) urlBar.setText(url);
                scanNow();
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Dipanggil di background thread untuk SETIAP request (termasuk dari iframe player)
                final String u = request.getUrl().toString();
                final String t = classify(u, null);
                if (t != null) {
                    String ref = null;
                    Map<String, String> h = request.getRequestHeaders();
                    if (h != null) {
                        ref = h.get("Referer");
                        if (ref == null) ref = h.get("referer");
                    }
                    final String referer = ref != null ? ref : pageUrl;
                    ui.post(() -> addFound(u, t, referer));
                }
                return null; // tetap biarkan WebView memuatnya seperti biasa
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                pageTitle = title == null ? "" : title;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = callback;
                fullscreen.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreen.setVisibility(View.VISIBLE);
                mainUi.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        // Situs yang memicu "download" langsung (tombol Download di file hosting)
        web.setDownloadListener((url, ua, contentDisposition, mimetype, contentLength) -> {
            String t = classify(url, mimetype);
            final VideoItem it = new VideoItem(url, t != null ? t : "file", pageUrl);
            it.name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            if (t != null) addFound(url, t, pageUrl);
            String size = contentLength > 0 ? String.format(Locale.US, "\nUkuran: %.1f MB", contentLength / 1048576.0) : "";
            new AlertDialog.Builder(this)
                    .setTitle("Unduh file?")
                    .setMessage(it.name + size)
                    .setPositiveButton("Unduh", (d, w) -> startDownload(it))
                    .setNegativeButton("Batal", null)
                    .show();
        });
    }

    private void hideCustomView() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        mainUi.setVisibility(View.VISIBLE);
        customView = null;
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    private void scanNow() {
        if (web != null) web.evaluateJavascript(SCAN_JS, null);
    }

    /** Dipanggil dari JavaScript halaman. */
    class Bridge {
        @JavascriptInterface
        public void report(String json) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (s.length() < 4) continue;
                    boolean fromVideoTag = s.startsWith("V|");
                    String u = s.substring(2);
                    String t = classify(u, null);
                    if (t == null && fromVideoTag && (u.startsWith("http://") || u.startsWith("https://"))) {
                        t = "video"; // sumber <video> tanpa ekstensi, mis. /stream?id=123
                    }
                    if (t != null) {
                        final String tt = t;
                        final String ref = pageUrl;
                        ui.post(() -> addFound(u, tt, ref));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ deteksi

    /** Kembalikan jenis video ("mp4", "m3u8", ...) atau null kalau bukan video. */
    static String classify(String url, String mime) {
        if (url == null) return null;
        String low = url.toLowerCase(Locale.ROOT);
        if (!(low.startsWith("http://") || low.startsWith("https://"))) return null;

        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("mpegurl")) return "m3u8";
            if (m.contains("dash+xml")) return "mpd";
            if (m.startsWith("video/")) {
                String e = m.substring(6);
                int sc = e.indexOf(';');
                if (sc >= 0) e = e.substring(0, sc);
                e = e.trim();
                if (e.equals("mp2t")) return null; // potongan segmen .ts
                if (e.equals("quicktime")) return "mov";
                if (e.equals("x-matroska")) return "mkv";
                if (e.equals("3gpp")) return "3gp";
                if (e.equals("x-flv")) return "flv";
                return e.isEmpty() ? "video" : e;
            }
        }

        String path = low;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);

        if (path.endsWith(".m3u8")) return "m3u8";
        if (path.endsWith(".mpd")) return "mpd";
        for (String e : VIDEO_EXT) {
            if (path.endsWith(e)) return e.substring(1);
        }
        return null;
    }

    private void addFound(String url, String type, String referer) {
        if (found.containsKey(url) || found.size() >= 300) return;
        found.put(url, new VideoItem(url, type, referer));
        updateBtn();
        if (found.size() == 1) toast("Video terdeteksi! Tekan tombol 🎬 untuk unduh");
    }

    // ------------------------------------------------------------------ navigasi

    private void go() {
        String in = urlBar.getText().toString().trim();
        if (in.isEmpty()) return;
        hideKeyboard();
        String url;
        if (in.matches("(?i)^https?://.*")) {
            url = in;
        } else if (in.contains(".") && !in.contains(" ")) {
            url = "https://" + in;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(in);
        }
        loadUrl(url);
    }

    private void loadUrl(String url) {
        found.clear();
        updateBtn();
        urlBar.setText(url);
        web.loadUrl(url);
    }

    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private String extractUrl(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return null;
            Matcher m = URL_IN_TEXT.matcher(text);
            return m.find() ? m.group() : null;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        return null;
    }

    // ------------------------------------------------------------------ daftar & unduh

    private static String shortLabel(VideoItem it) {
        Uri u = Uri.parse(it.url);
        String seg = u.getLastPathSegment();
        if (seg == null || seg.isEmpty()) seg = "(tanpa nama)";
        if (seg.length() > 40) seg = seg.substring(0, 37) + "...";
        String host = u.getHost() == null ? "" : u.getHost();
        return "[" + it.type.toUpperCase(Locale.ROOT) + "] " + seg + "\n" + host;
    }

    private void showFoundList() {
        if (found.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Belum ada video")
                    .setMessage("Tekan PLAY pada videonya dulu, tunggu beberapa detik, lalu tekan tombol ini lagi.\n\n"
                            + "Banyak situs (termasuk file hosting) baru memuat link video asli setelah video diputar "
                            + "atau setelah kamu menekan tombol Download di halamannya.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final List<VideoItem> items = new ArrayList<>(found.values());
        Collections.reverse(items); // yang terbaru di atas
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = shortLabel(items.get(i));

        new AlertDialog.Builder(this)
                .setTitle("Video ditemukan (" + items.size() + ")")
                .setItems(labels, (d, which) -> confirmItem(items.get(which)))
                .setNeutralButton("Bersihkan", (d, w) -> {
                    found.clear();
                    updateBtn();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void confirmItem(VideoItem it) {
        String note = "";
        if ("m3u8".equals(it.type)) note = "\n\nFormat streaming HLS: akan digabung jadi 1 file (.ts / .mp4). Biarkan aplikasi terbuka sampai selesai.";
        if ("mpd".equals(it.type)) note = "\n\nFormat DASH (.mpd) belum didukung.";
        new AlertDialog.Builder(this)
                .setTitle("Unduh video ini?")
                .setMessage(it.url + note)
                .setPositiveButton("Unduh", (d, w) -> startDownload(it))
                .setNeutralButton("Salin link", (d, w) -> {
                    ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("video", it.url));
                    toast("Link disalin");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s;
    }

    private String titleBaseName() {
        String t = sanitize(pageTitle);
        if (t.isEmpty()) t = "video";
        return t + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private String fileNameFor(VideoItem it) {
        String name = it.name;
        if (name == null || name.isEmpty()) name = URLUtil.guessFileName(it.url, null, null);
        name = sanitize(name);
        boolean isVideoType = !"file".equals(it.type);
        if (isVideoType) {
            String low = name.toLowerCase(Locale.ROOT);
            boolean hasVideoExt = false;
            for (String e : VIDEO_EXT) if (low.endsWith(e)) hasVideoExt = true;
            if (!hasVideoExt) {
                if (low.endsWith(".bin")) name = name.substring(0, name.length() - 4);
                String ext = "video".equals(it.type) ? "mp4" : it.type;
                if (ext.length() > 5) ext = "mp4";
                // nama generik seperti "downloadfile" diganti judul halaman
                if (name.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith("downloadfile")) name = titleBaseName();
                name = name + "." + ext;
            }
        }
        if (name.isEmpty()) name = titleBaseName() + ".mp4";
        return name;
    }

    private void startDownload(VideoItem it) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = it;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            return;
        }
        if ("mpd".equals(it.type)) {
            toast("Format DASH (.mpd) belum didukung. Coba cari link MP4/M3U8 lain di daftar.");
            return;
        }

        if ("m3u8".equals(it.type)) {
            showStatus("Menyiapkan unduhan HLS...", false);
            new Thread(new HlsDownloader(getApplicationContext(), it.url, it.referer, userAgent,
                    titleBaseName(), (msg, finished) -> ui.post(() -> showStatus(msg, finished))),
                    "hls-download").start();
            return;
        }

        String name = fileNameFor(it);
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(it.url));
            String cookie = CookieManager.getInstance().getCookie(it.url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.addRequestHeader("User-Agent", userAgent);
            if (it.referer != null && !it.referer.isEmpty()) r.addRequestHeader("Referer", it.referer);
            r.setTitle(name);
            r.setDescription("VidGrab");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "VidGrab/" + name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(r);
            toast("Mengunduh: " + name + "\nLihat progres di notifikasi. Hasil: folder Download/VidGrab");
        } catch (Exception e) {
            toast("Gagal mulai unduh: " + e.getMessage());
        }
    }
}
