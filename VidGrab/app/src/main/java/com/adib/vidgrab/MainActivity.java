package com.adib.vidgrab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    static final String HOME = "https://www.google.com";
    static final int MAX_PAGES = 30;

    private WebView web;
    private EditText urlBar;
    private Button allBtn;
    private Button listBtn;
    private TextView status;
    private ProgressBar progress;
    private LinearLayout mainUi;
    private FrameLayout content;
    private FrameLayout fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    /** Video yang terdeteksi di halaman saat ini. */
    private final LinkedHashMap<String, VideoItem> found = new LinkedHashMap<>();
    /** Link ke halaman yang berisi video (mis. daftar file di file hosting). */
    private final LinkedHashSet<String> pages = new LinkedHashSet<>();
    /** Yang sudah pernah diunduh, supaya tekan lagi tidak dobel. */
    private final HashSet<String> doneKeys = new HashSet<>();
    private final HashSet<String> donePages = new HashSet<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String pageUrl = "";
    private volatile String pageTitle = "";
    private String userAgent;
    private boolean toastShown;
    private DeepScanner scanner;
    private Runnable afterPermission;

    private final Runnable scanLoop = new Runnable() {
        @Override
        public void run() {
            scanNow();
            ui.postDelayed(this, 3000);
        }
    };

    private final Runnable hideStatus = () -> status.setVisibility(View.GONE);

    private final WebSetup.Sink mainSink = new WebSetup.Sink() {
        @Override
        public void onVideo(String url, String type, String referer) {
            ui.post(() -> addFound(new VideoItem(url, type, referer)));
        }

        @Override
        public void onPage(String url) {
            ui.post(() -> addPage(url));
        }

        @Override
        public String currentPage() {
            return pageUrl;
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        setupWebView();
        if (needsStoragePermission()) {
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
        ui.removeCallbacks(scanLoop);
        ui.postDelayed(scanLoop, 2000);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(scanLoop);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.cancel();
        ui.removeCallbacksAndMessages(null);
        worker.shutdown(); // unduhan yang sedang jalan dibiarkan selesai
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
        Runnable r = afterPermission;
        afterPermission = null;
        if (r != null) {
            if (ok) r.run();
            else toast("Izin penyimpanan ditolak, video tidak bisa disimpan.");
        }
    }

    private boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
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

        // Bar alamat
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
        status.setOnClickListener(v -> status.setVisibility(View.GONE));

        content = new FrameLayout(this);
        web = new WebView(this);
        content.addView(web, new FrameLayout.LayoutParams(MATCH, MATCH));

        // Bar bawah: tombol besar UNDUH SEMUA + tombol daftar
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(6), dp(6), dp(6), dp(6));
        bottom.setBackgroundColor(0xFF1E1E1E);

        allBtn = new Button(this);
        allBtn.setTextColor(Color.WHITE);
        allBtn.setTextSize(15);
        allBtn.setAllCaps(false);
        allBtn.setPadding(dp(10), dp(12), dp(10), dp(12));
        allBtn.setOnClickListener(v -> downloadAll(false));

        listBtn = new Button(this);
        listBtn.setTextColor(Color.WHITE);
        listBtn.setTextSize(14);
        listBtn.setAllCaps(false);
        listBtn.setBackgroundColor(0xFF424242);
        listBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        listBtn.setOnClickListener(v -> showFoundList());

        LinearLayout.LayoutParams lpAll = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lpAll.setMarginEnd(dp(6));
        bottom.addView(allBtn, lpAll);
        bottom.addView(listBtn, new LinearLayout.LayoutParams(WRAP, WRAP));

        mainUi.addView(bar, new LinearLayout.LayoutParams(MATCH, WRAP));
        mainUi.addView(progress, new LinearLayout.LayoutParams(MATCH, dp(3)));
        mainUi.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
        mainUi.addView(content, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        mainUi.addView(bottom, new LinearLayout.LayoutParams(MATCH, WRAP));

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);

        root.addView(mainUi, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(fullscreen, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
        updateButtons();
    }

    private List<VideoItem> pendingVideos() {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem it : found.values()) {
            if (!doneKeys.contains(it.key())) out.add(it);
        }
        return out;
    }

    private List<String> pendingPages() {
        List<String> out = new ArrayList<>();
        for (String p : pages) {
            if (!donePages.contains(p)) out.add(p);
        }
        return out;
    }

    private void updateButtons() {
        int v = pendingVideos().size();
        int p = pendingPages().size();
        if (scanner != null) {
            allBtn.setText("⏳ Sedang memeriksa halaman…");
            allBtn.setBackgroundColor(0xFF616161);
        } else if (v == 0 && p == 0) {
            allBtn.setText("⬇ UNDUH SEMUA  (mencari video…)");
            allBtn.setBackgroundColor(0xFF616161);
        } else {
            StringBuilder sb = new StringBuilder("⬇ UNDUH SEMUA  (");
            if (v > 0) sb.append(v).append(" video");
            if (v > 0 && p > 0) sb.append(" + ");
            if (p > 0) sb.append(p).append(" halaman");
            sb.append(")");
            allBtn.setText(sb.toString());
            allBtn.setBackgroundColor(0xFFD32F2F);
        }
        listBtn.setText("📋 " + found.size());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showStatus(String msg, boolean finished) {
        status.setText(msg);
        status.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideStatus);
        if (finished) ui.postDelayed(hideStatus, 10000);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView() {
        userAgent = WebSetup.configure(web, mainSink);

        web.setWebViewClient(new WebSetup.SniffClient(mainSink) {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                onNavigate(url);
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageUrl = url;
                if (view.getTitle() != null) pageTitle = view.getTitle();
                if (!urlBar.hasFocus()) urlBar.setText(url);
                scanNow();
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

        // Tombol "Download" milik situs (file hosting) → langsung unduh kalau itu video
        web.setDownloadListener((url, ua, contentDisposition, mimetype, contentLength) -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
            String t = Sniffer.classify(url, mimetype);
            if (t == null && Sniffer.looksLikeVideoName(name)) t = "video";
            final VideoItem it = new VideoItem(url, t != null ? t : "file", pageUrl);
            it.name = name;
            if (t != null) {
                addFound(it);
                downloadSingle(it);
            } else {
                String size = contentLength > 0
                        ? String.format(Locale.US, "\nUkuran: %.1f MB", contentLength / 1048576.0) : "";
                new AlertDialog.Builder(this)
                        .setTitle("Unduh file ini?")
                        .setMessage(name + size)
                        .setPositiveButton("Unduh", (d, w) -> downloadSingle(it))
                        .setNegativeButton("Batal", null)
                        .show();
            }
        });
    }

    /** Halaman baru → daftar video direset (yang sudah diunduh tetap diingat). */
    private void onNavigate(String url) {
        if (!Sniffer.stripFragment(url).equals(Sniffer.stripFragment(pageUrl))) {
            found.clear();
            pages.clear();
            toastShown = false;
            String t = Sniffer.classify(url, null);
            if (t != null) found.put(Sniffer.keyFor(url), new VideoItem(url, t, url));
            updateButtons();
        }
        pageUrl = url;
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
        if (web != null) web.evaluateJavascript(Sniffer.SCAN_JS, null);
    }

    private void addFound(VideoItem it) {
        String key = it.key();
        if (found.containsKey(key) || found.size() >= 300) return;
        found.put(key, it);
        updateButtons();
        if (!toastShown) {
            toastShown = true;
            toast("Video terdeteksi! Tekan ⬇ UNDUH SEMUA");
        }
    }

    private void addPage(String url) {
        String u = Sniffer.stripFragment(url);
        if (u.equals(Sniffer.stripFragment(pageUrl)) || pages.contains(u) || pages.size() >= 100) return;
        if (found.containsKey(Sniffer.keyFor(u))) return;
        pages.add(u);
        updateButtons();
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

    // ------------------------------------------------------------------ unduh

    private String titleBase() {
        String t = Dl.sanitize(pageTitle);
        if (t.isEmpty()) t = "video";
        return t + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    /** SATU TOMBOL: unduh semua video yang terdeteksi + cek halaman yang berisi video. */
    private void downloadAll(boolean afterTrigger) {
        if (needsStoragePermission()) {
            afterPermission = () -> downloadAll(afterTrigger);
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            return;
        }
        if (scanner != null) {
            toast("Sedang memeriksa halaman, tunggu sebentar…");
            return;
        }
        final List<VideoItem> vids = pendingVideos();
        List<String> pgs = pendingPages();

        if (vids.isEmpty() && pgs.isEmpty()) {
            if (afterTrigger) {
                status.setVisibility(View.GONE);
                new AlertDialog.Builder(this)
                        .setTitle("Belum ada video")
                        .setMessage("Video belum terdeteksi di halaman ini.\n\n"
                                + "• Coba tekan PLAY di videonya, tunggu beberapa detik, lalu tekan UNDUH SEMUA lagi.\n"
                                + "• Di file hosting, coba tekan tombol Download milik situsnya.\n"
                                + "• Video ber-DRM (Netflix dll.) memang tidak bisa diunduh.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            // Pancing player agar link videonya termuat, lalu coba lagi
            showStatus("Mencari video… memancing player agar videonya termuat", false);
            web.evaluateJavascript(Sniffer.TRIGGER_JS, null);
            ui.postDelayed(() -> {
                scanNow();
                ui.postDelayed(() -> downloadAll(true), 1500);
            }, 6000);
            return;
        }

        for (VideoItem it : vids) doneKeys.add(it.key());
        final String base = titleBase();

        if (pgs.isEmpty()) {
            runBatch(vids, base, true);
            return;
        }

        if (pgs.size() > MAX_PAGES) pgs = new ArrayList<>(pgs.subList(0, MAX_PAGES));
        donePages.addAll(pgs);
        scanner = new DeepScanner(this, content, pgs, new DeepScanner.Callback() {
            @Override
            public void onProgress(String message) {
                showStatus(message, false);
            }

            @Override
            public void onDone(List<VideoItem> list) {
                scanner = null;
                List<VideoItem> all = new ArrayList<>(vids);
                for (VideoItem it : list) {
                    if (doneKeys.add(it.key())) all.add(it);
                }
                updateButtons();
                if (all.isEmpty()) {
                    showStatus("Tidak ada video yang bisa diambil dari halaman-halaman tersebut.", true);
                } else {
                    runBatch(all, base, true);
                }
            }
        });
        updateButtons();
        scanner.start();
        updateButtons();
    }

    private void downloadSingle(VideoItem it) {
        if (needsStoragePermission()) {
            afterPermission = () -> downloadSingle(it);
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            return;
        }
        doneKeys.add(it.key());
        runBatch(Collections.singletonList(it), titleBase(), false);
    }

    private void runBatch(List<VideoItem> items, String base, boolean filterSmall) {
        showStatus("Memulai unduhan " + items.size() + " video…", false);
        updateButtons();
        worker.execute(new BatchDownloader(getApplicationContext(), new ArrayList<>(items), userAgent, base,
                filterSmall, (msg, fin) -> ui.post(() -> showStatus(msg, fin))));
    }

    // ------------------------------------------------------------------ daftar (unduh satu-satu)

    private static String shortLabel(VideoItem it, boolean done) {
        Uri u = Uri.parse(it.url);
        String seg = u.getLastPathSegment();
        if (seg == null || seg.isEmpty()) seg = "(tanpa nama)";
        if (seg.length() > 40) seg = seg.substring(0, 37) + "...";
        String host = u.getHost() == null ? "" : u.getHost();
        return (done ? "✓ " : "") + "[" + it.type.toUpperCase(Locale.ROOT) + "] " + seg + "\n" + host;
    }

    private void showFoundList() {
        if (found.isEmpty()) {
            int p = pendingPages().size();
            new AlertDialog.Builder(this)
                    .setTitle("Belum ada video langsung")
                    .setMessage(p > 0
                            ? "Ada " + p + " halaman berisi video. Tekan ⬇ UNDUH SEMUA untuk memeriksa & mengunduhnya."
                            : "Tekan ⬇ UNDUH SEMUA — aplikasi akan mencoba memancing player supaya videonya terdeteksi.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        final List<VideoItem> items = new ArrayList<>(found.values());
        Collections.reverse(items); // yang terbaru di atas
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = shortLabel(items.get(i), doneKeys.contains(items.get(i).key()));
        }
        new AlertDialog.Builder(this)
                .setTitle("Video ditemukan (" + items.size() + ") — pilih satu")
                .setItems(labels, (d, which) -> confirmItem(items.get(which)))
                .setNeutralButton("Bersihkan", (d, w) -> {
                    found.clear();
                    pages.clear();
                    updateButtons();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void confirmItem(VideoItem it) {
        String note = "";
        if ("m3u8".equals(it.type)) note = "\n\nFormat HLS: potongan video akan digabung jadi 1 file.";
        if ("mpd".equals(it.type)) note = "\n\nFormat DASH (.mpd) belum didukung.";
        new AlertDialog.Builder(this)
                .setTitle("Unduh video ini?")
                .setMessage(it.url + note)
                .setPositiveButton("Unduh", (d, w) -> downloadSingle(it))
                .setNeutralButton("Salin link", (d, w) -> {
                    ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cb != null) cb.setPrimaryClip(ClipData.newPlainText("video", it.url));
                    toast("Link disalin");
                })
                .setNegativeButton("Batal", null)
                .show();
    }
}
