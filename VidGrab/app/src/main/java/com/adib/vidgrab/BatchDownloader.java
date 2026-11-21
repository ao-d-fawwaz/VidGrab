package com.adib.vidgrab;

import android.content.Context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mengunduh banyak video sekaligus (jalan di background thread):
 *  - HLS: playlist varian/audio yang sudah tercakup playlist master dibuang (tidak dobel)
 *  - file langsung: dicek ukurannya, yang sangat kecil (iklan/preview) dilewati
 *  - file langsung → DownloadManager (paralel), HLS → digabung satu per satu
 */
final class BatchDownloader implements Runnable {

    interface Listener {
        void onStatus(String message, boolean finished);
    }

    static final long MIN_SIZE = 300 * 1024; // < 300 KB hampir pasti iklan / potongan

    private final Context ctx;
    private final List<VideoItem> items;
    private final String ua;
    private final String base;
    private final boolean filterSmall;
    private final Listener l;

    BatchDownloader(Context ctx, List<VideoItem> items, String ua, String base, boolean filterSmall, Listener l) {
        this.ctx = ctx;
        this.items = items;
        this.ua = ua;
        this.base = base;
        this.filterSmall = filterSmall;
        this.l = l;
    }

    @Override
    public void run() {
        int queued = 0, hlsOk = 0, skipped = 0, failed = 0, unsupported = 0;
        List<VideoItem> hls = new ArrayList<>();
        List<VideoItem> direct = new ArrayList<>();
        for (VideoItem it : items) {
            if ("m3u8".equals(it.type)) hls.add(it);
            else if ("mpd".equals(it.type)) unsupported++;
            else direct.add(it);
        }
        boolean single = items.size() == 1;

        // 1) Buang playlist HLS yang hanya varian/audio dari playlist master lain
        if (hls.size() > 1) {
            l.onStatus("Memeriksa playlist HLS…", false);
            Set<String> referenced = new HashSet<>();
            for (VideoItem it : hls) {
                try {
                    String text = new String(Net.fetchWithRetry(it.url, it.referer, ua, 2), StandardCharsets.UTF_8);
                    collectReferenced(text, it.url, referenced);
                } catch (Exception ignored) {
                }
            }
            List<VideoItem> keep = new ArrayList<>();
            for (VideoItem it : hls) {
                if (referenced.contains(it.url) || referenced.contains(Sniffer.stripQuery(it.url))) skipped++;
                else keep.add(it);
            }
            hls = keep;
        }

        // 2) File langsung → DownloadManager
        int i = 0;
        for (VideoItem it : direct) {
            i++;
            l.onStatus("Menyiapkan video " + i + "/" + direct.size() + "…", false);
            if (filterSmall) {
                Net.Probe p = Net.probe(it.url, it.referer, ua);
                boolean notVideo = p.contentType != null && p.contentType.startsWith("text/");
                boolean tiny = p.size >= 0 && p.size < MIN_SIZE;
                if (notVideo || tiny) {
                    skipped++;
                    continue;
                }
            }
            try {
                Dl.enqueue(ctx, it, ua, single ? base : base + "_" + i);
                queued++;
            } catch (Exception e) {
                failed++;
            }
        }
        if (queued > 0) {
            l.onStatus(queued + " video masuk antrean unduhan (lihat notifikasi)"
                    + (hls.isEmpty() ? "" : ", lanjut HLS…"), false);
        }

        // 3) HLS → satu per satu
        int k = 0;
        for (VideoItem it : hls) {
            k++;
            final String prefix = hls.size() > 1 ? "[HLS " + k + "/" + hls.size() + "] " : "";
            HlsDownloader d = new HlsDownloader(ctx, it.url, it.referer, ua,
                    single || hls.size() == 1 ? base : base + "_" + k,
                    (msg, fin) -> l.onStatus(prefix + msg, false));
            d.run();
            if (d.success) hlsOk++;
            else failed++;
        }

        // Ringkasan
        StringBuilder sb = new StringBuilder();
        if (queued + hlsOk > 0) {
            sb.append("✔ ").append(queued + hlsOk).append(" video ");
            sb.append(queued > 0 ? "sedang/selesai diunduh" : "selesai diunduh");
            sb.append(" → folder Download/").append(Dl.FOLDER);
        } else {
            sb.append("Tidak ada video yang bisa diunduh");
        }
        if (skipped > 0) sb.append("\n").append(skipped).append(" dilewati (iklan/duplikat/bukan video)");
        if (unsupported > 0) sb.append("\n").append(unsupported).append(" format DASH (.mpd) belum didukung");
        if (failed > 0) sb.append("\n").append(failed).append(" gagal");
        l.onStatus(sb.toString(), true);
    }

    /** Kumpulkan semua URL varian/audio yang dirujuk oleh sebuah playlist master. */
    private static void collectReferenced(String text, String base, Set<String> out) {
        boolean expectUri = false;
        for (String raw : text.split("\\r?\\n")) {
            String ln = raw.trim();
            if (ln.isEmpty()) continue;
            if (ln.startsWith("#EXT-X-STREAM-INF")) {
                expectUri = true;
                continue;
            }
            if (ln.startsWith("#EXT-X-MEDIA:") || ln.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                String u = HlsDownloader.attr(ln, "URI");
                if (u != null) addRef(out, base, u);
                continue;
            }
            if (ln.startsWith("#")) continue;
            if (expectUri) {
                addRef(out, base, ln);
                expectUri = false;
            }
        }
    }

    private static void addRef(Set<String> out, String base, String ref) {
        try {
            String r = HlsDownloader.resolve(base, ref);
            out.add(r);
            out.add(Sniffer.stripQuery(r));
        } catch (Exception ignored) {
        }
    }
}
