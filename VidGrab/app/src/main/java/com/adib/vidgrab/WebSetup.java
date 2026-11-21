package com.adib.vidgrab;

import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.util.Locale;
import java.util.Map;

/** Pengaturan WebView "pengendus" yang dipakai browser utama & pemindai halaman tersembunyi. */
final class WebSetup {
    private WebSetup() {
    }

    /** Penerima hasil deteksi. Bisa dipanggil dari thread mana pun. */
    interface Sink {
        void onVideo(String url, String type, String referer);

        void onPage(String url);

        String currentPage();
    }

    /** Mengatur WebView dan mengembalikan User-Agent yang dipakai. */
    static String configure(WebView w, Sink sink) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);               // target=_blank dibuka di tab yang sama
        s.setJavaScriptCanOpenWindowsAutomatically(false); // blok popup iklan
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        String ua = s.getUserAgentString().replace("; wv", ""); // jangan ketahuan sebagai WebView
        s.setUserAgentString(ua);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);

        w.addJavascriptInterface(new Bridge(sink), "VidGrab");
        return ua;
    }

    /** WebViewClient yang menyadap semua request video. Subclass boleh menambah onPageStarted dll. */
    static class SniffClient extends WebViewClient {
        private final Sink sink;

        SniffClient(Sink sink) {
            this.sink = sink;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            if (scheme == null) return true;
            scheme = scheme.toLowerCase(Locale.ROOT);
            // Hanya http/https; intent://, market:// dll. (biasanya iklan) diblok
            return !(scheme.equals("http") || scheme.equals("https") || scheme.equals("about"));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String u = request.getUrl().toString();
            String t = Sniffer.classify(u, null);
            if (t != null) {
                String ref = null;
                Map<String, String> h = request.getRequestHeaders();
                if (h != null) {
                    ref = h.get("Referer");
                    if (ref == null) ref = h.get("referer");
                }
                sink.onVideo(u, t, ref != null ? ref : sink.currentPage());
            }
            return null; // tetap dimuat seperti biasa
        }
    }

    /** Dipanggil dari JavaScript halaman (SCAN_JS). */
    static final class Bridge {
        private final Sink sink;

        Bridge(Sink sink) {
            this.sink = sink;
        }

        @JavascriptInterface
        public void report(String json) {
            try {
                JSONArray a = new JSONArray(json);
                String page = sink.currentPage();
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (s.length() < 4 || s.charAt(1) != '|') continue;
                    char kind = s.charAt(0);
                    String u = s.substring(2);
                    if (!(u.startsWith("http://") || u.startsWith("https://"))) continue;
                    if (kind == 'P') {
                        sink.onPage(u);
                        continue;
                    }
                    String t = Sniffer.classify(u, null);
                    if (t == null && kind == 'V') t = "video"; // <video src> tanpa ekstensi
                    if (t != null) sink.onVideo(u, t, page);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
