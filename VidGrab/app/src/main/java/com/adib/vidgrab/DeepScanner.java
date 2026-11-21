package com.adib.vidgrab;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Membuka halaman-halaman yang berisi video (mis. daftar file di file hosting) satu per satu
 * di WebView tersembunyi, memancing player-nya, lalu mengumpulkan link video aslinya.
 * Semua method dipanggil di UI thread.
 */
final class DeepScanner {

    interface Callback {
        void onProgress(String message);

        void onDone(List<VideoItem> found);
    }

    private static final long MAX_PER_PAGE_MS = 20000;
    private static final long MIN_PER_PAGE_MS = 5000;
    private static final long QUIET_MS = 4000;

    private final Activity act;
    private final FrameLayout host;
    private final List<String> pages;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, VideoItem> current = new LinkedHashMap<>();
    private final List<VideoItem> results = new ArrayList<>();

    private WebView w;
    private int idx = -1;
    private int ticks;
    private long pageStart;
    private long lastFound;
    private volatile String curPage = "";
    private boolean finished;

    DeepScanner(Activity act, FrameLayout host, List<String> pages, Callback cb) {
        this.act = act;
        this.host = host;
        this.pages = pages;
        this.cb = cb;
    }

    private final WebSetup.Sink sink = new WebSetup.Sink() {
        @Override
        public void onVideo(String url, String type, String referer) {
            ui.post(() -> add(new VideoItem(url, type, referer)));
        }

        @Override
        public void onPage(String url) {
            // tidak menjelajah lebih dalam
        }

        @Override
        public String currentPage() {
            return curPage;
        }
    };

    void start() {
        w = new WebView(act);
        int px = Math.max(2, Math.round(2 * act.getResources().getDisplayMetrics().density));
        host.addView(w, new FrameLayout.LayoutParams(px, px));
        w.setAlpha(0.01f);
        WebSetup.configure(w, sink);
        w.setWebViewClient(new WebSetup.SniffClient(sink));
        w.setDownloadListener((url, ua, cd, mime, len) -> {
            String name = URLUtil.guessFileName(url, cd, mime);
            String t = Sniffer.classify(url, mime);
            if (t == null && Sniffer.looksLikeVideoName(name)) t = "video";
            if (t != null) {
                VideoItem it = new VideoItem(url, t, curPage);
                it.name = name;
                add(it);
            }
        });
        next();
    }

    void cancel() {
        idx = pages.size();
        finish();
    }

    private void add(VideoItem it) {
        if (finished) return;
        if (!current.containsKey(it.key())) {
            current.put(it.key(), it);
            lastFound = SystemClock.uptimeMillis();
        }
    }

    private void next() {
        results.addAll(current.values());
        current.clear();
        idx++;
        if (idx >= pages.size()) {
            finish();
            return;
        }
        curPage = pages.get(idx);
        pageStart = lastFound = SystemClock.uptimeMillis();
        ticks = 0;
        cb.onProgress("Memeriksa halaman " + (idx + 1) + "/" + pages.size() + " untuk mencari videonya…");
        w.stopLoading();
        w.loadUrl(curPage);
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1000);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (w == null || finished) return;
            ticks++;
            w.evaluateJavascript(Sniffer.SCAN_JS, null);
            if (ticks % 3 == 0) w.evaluateJavascript(Sniffer.TRIGGER_JS, null);
            long now = SystemClock.uptimeMillis();
            long elapsed = now - pageStart;
            boolean gotIt = !current.isEmpty() && elapsed > MIN_PER_PAGE_MS && now - lastFound > QUIET_MS;
            if (elapsed > MAX_PER_PAGE_MS || gotIt) {
                next();
            } else {
                ui.postDelayed(this, 1000);
            }
        }
    };

    private void finish() {
        if (finished) return;
        finished = true;
        results.addAll(current.values());
        current.clear();
        ui.removeCallbacks(tick);
        if (w != null) {
            w.stopLoading();
            host.removeView(w);
            w.destroy();
            w = null;
        }
        cb.onDone(results);
    }
}
