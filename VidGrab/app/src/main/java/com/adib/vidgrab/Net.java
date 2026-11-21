package com.adib.vidgrab;

import android.net.Uri;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** HTTP sederhana yang membawa cookie, User-Agent dan Referer dari browser. */
final class Net {
    private Net() {
    }

    static final class Probe {
        long size = -1;
        String contentType;
    }

    static HttpURLConnection open(String url, String referer, String ua, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs * 2);
        c.setInstanceFollowRedirects(true);
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
        } catch (Exception ignored) {
        }
        return c;
    }

    static byte[] fetchBytes(String url, String referer, String ua) throws IOException {
        HttpURLConnection c = open(url, referer, ua, 20000);
        try {
            if (referer != null && !referer.isEmpty()) {
                Uri r = Uri.parse(referer);
                if (r.getScheme() != null && r.getHost() != null) {
                    c.setRequestProperty("Origin", r.getScheme() + "://" + r.getHost());
                }
            }
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("server menolak (HTTP " + code + ")");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] fetchWithRetry(String url, String referer, String ua, int attempts) throws IOException {
        IOException last = new IOException("gagal mengambil data");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return fetchBytes(url, referer, ua);
            } catch (IOException e) {
                last = e;
                if (attempt < attempts) {
                    try {
                        Thread.sleep(1000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("dibatalkan");
                    }
                }
            }
        }
        throw last;
    }

    /** Cek ukuran & jenis file tanpa mengunduh semuanya. size = -1 kalau tidak diketahui. */
    static Probe probe(String url, String referer, String ua) {
        Probe p = new Probe();
        HttpURLConnection c = null;
        try {
            c = open(url, referer, ua, 10000);
            c.setRequestMethod("HEAD");
            int code = c.getResponseCode();
            if (code < 400) {
                p.size = c.getContentLengthLong();
                p.contentType = lower(c.getContentType());
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        if (p.size >= 0) return p;

        // Sebagian server menolak HEAD → minta 1 byte saja
        try {
            c = open(url, referer, ua, 10000);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code < 400) {
                if (p.contentType == null) p.contentType = lower(c.getContentType());
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.contains("/")) {
                    String total = cr.substring(cr.lastIndexOf('/') + 1).trim();
                    try {
                        p.size = Long.parseLong(total);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (code == 200) {
                    p.size = c.getContentLengthLong();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return p;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
