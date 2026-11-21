package com.adib.vidgrab;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mengunduh video HLS (.m3u8): pilih kualitas tertinggi, unduh semua segmen,
 * dekripsi AES-128 kalau ada, lalu gabungkan jadi satu file di Download/VidGrab.
 */
public class HlsDownloader implements Runnable {

    public interface Listener {
        void onProgress(String message, boolean finished);
    }

    static class Segment {
        String url;
        String method;
        String keyUrl;
        byte[] iv;
        long seq;
    }

    private final Context ctx;
    private final String playlistUrl;
    private final String referer;
    private final String userAgent;
    private final String baseName;
    private final Listener listener;
    private final Map<String, byte[]> keyCache = new HashMap<>();
    volatile boolean success;

    public HlsDownloader(Context ctx, String playlistUrl, String referer, String userAgent,
                         String baseName, Listener listener) {
        this.ctx = ctx;
        this.playlistUrl = playlistUrl;
        this.referer = referer;
        this.userAgent = userAgent;
        this.baseName = baseName;
        this.listener = listener;
    }

    @Override
    public void run() {
        Uri outUri = null;
        File outFile = null;
        OutputStream out = null;
        try {
            listener.onProgress("Membaca playlist...", false);
            String mediaUrl = playlistUrl;
            String text = new String(Net.fetchWithRetry(mediaUrl, referer, userAgent, 2), StandardCharsets.UTF_8);
            boolean separateAudio = false;

            // Playlist master → pilih varian dengan bandwidth tertinggi
            for (int depth = 0; text.contains("#EXT-X-STREAM-INF") && depth < 3; depth++) {
                if (text.contains("TYPE=AUDIO") && text.contains("URI=")) separateAudio = true;
                String best = pickBestVariant(text, mediaUrl);
                if (best == null) throw new IOException("playlist master tanpa varian");
                mediaUrl = best;
                text = new String(Net.fetchWithRetry(mediaUrl, referer, userAgent, 2), StandardCharsets.UTF_8);
            }
            if (!text.contains("#EXTINF")) throw new IOException("bukan playlist video HLS yang valid");

            String initUrl = null;
            List<Segment> segs = new ArrayList<>();
            long seq = 0;
            String method = "NONE";
            String keyUrl = null;
            byte[] iv = null;
            for (String raw : text.split("\\r?\\n")) {
                String ln = raw.trim();
                if (ln.isEmpty()) continue;
                if (ln.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        seq = Long.parseLong(ln.substring("#EXT-X-MEDIA-SEQUENCE:".length()).trim());
                    } catch (NumberFormatException ignored) {
                    }
                } else if (ln.startsWith("#EXT-X-KEY:")) {
                    String m = attr(ln, "METHOD");
                    method = m == null ? "NONE" : m.toUpperCase(Locale.ROOT);
                    String u = attr(ln, "URI");
                    keyUrl = u == null ? null : resolve(mediaUrl, u);
                    String ivs = attr(ln, "IV");
                    iv = ivs == null ? null : hexToBytes(ivs);
                } else if (ln.startsWith("#EXT-X-MAP:")) {
                    String u = attr(ln, "URI");
                    if (u != null && initUrl == null) initUrl = resolve(mediaUrl, u);
                } else if (!ln.startsWith("#")) {
                    Segment s = new Segment();
                    s.url = resolve(mediaUrl, ln);
                    s.method = method;
                    s.keyUrl = keyUrl;
                    s.iv = iv;
                    s.seq = seq++;
                    segs.add(s);
                }
            }
            if (segs.isEmpty()) throw new IOException("tidak ada segmen video");
            for (Segment s : segs) {
                if (!s.method.equals("NONE") && !s.method.equals("AES-128")) {
                    throw new IOException("video dilindungi DRM (" + s.method + "), tidak bisa diunduh");
                }
            }

            boolean fmp4 = initUrl != null;
            String name = baseName + (fmp4 ? ".mp4" : ".ts");
            String mime = fmp4 ? "video/mp4" : "video/mp2t";

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VidGrab");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                outUri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (outUri == null) throw new IOException("tidak bisa membuat file di Download");
                OutputStream os = ctx.getContentResolver().openOutputStream(outUri);
                if (os == null) throw new IOException("tidak bisa menulis file");
                out = new BufferedOutputStream(os, 1 << 16);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidGrab");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                outFile = new File(dir, name);
                out = new BufferedOutputStream(new FileOutputStream(outFile), 1 << 16);
            }

            if (initUrl != null) out.write(fetchWithRetry(initUrl));

            int total = segs.size();
            long bytes = 0;
            for (int i = 0; i < total; i++) {
                Segment s = segs.get(i);
                byte[] data = fetchWithRetry(s.url);
                if ("AES-128".equals(s.method)) data = decrypt(data, s);
                out.write(data);
                bytes += data.length;
                int pct = (int) ((i + 1) * 100L / total);
                listener.onProgress(String.format(Locale.US, "Mengunduh HLS %d/%d (%d%%) • %.1f MB",
                        i + 1, total, pct, bytes / 1048576.0), false);
            }
            out.close();
            out = null;

            if (outUri != null) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(outUri, done, null, null);
            } else {
                MediaScannerConnection.scanFile(ctx, new String[]{outFile.getAbsolutePath()}, null, null);
            }
            String msg = "Selesai ✔ Download/VidGrab/" + name;
            if (separateAudio) msg += "\n(Peringatan: situs ini memisah audio, hasil mungkin tanpa suara)";
            success = true;
            listener.onProgress(msg, true);
        } catch (Exception e) {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (outUri != null) {
                try {
                    ctx.getContentResolver().delete(outUri, null, null);
                } catch (Exception ignored) {
                }
            }
            if (outFile != null) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            listener.onProgress("Gagal unduh HLS: " + e.getMessage(), true);
        }
    }

    // ------------------------------------------------------------------ helper

    private static String pickBestVariant(String text, String base) throws IOException {
        String[] lines = text.split("\\r?\\n");
        long bestBw = -1;
        String best = null;
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i].trim();
            if (!ln.startsWith("#EXT-X-STREAM-INF")) continue;
            long bw = 0;
            try {
                String b = attr(ln, "BANDWIDTH");
                if (b != null) bw = Long.parseLong(b.trim());
            } catch (NumberFormatException ignored) {
            }
            for (int j = i + 1; j < lines.length; j++) {
                String n = lines[j].trim();
                if (n.isEmpty() || n.startsWith("#")) continue;
                if (bw > bestBw) {
                    bestBw = bw;
                    best = resolve(base, n);
                }
                break;
            }
        }
        return best;
    }

    static String attr(String line, String key) {
        Matcher m = Pattern.compile("(?:^|[:,])" + Pattern.quote(key) + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    static String resolve(String base, String ref) throws IOException {
        return new URL(new URL(base), ref).toString();
    }

    static byte[] hexToBytes(String hex) {
        String h = hex.trim();
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        while (h.length() < 32) h = "0" + h;
        if (h.length() > 32) h = h.substring(h.length() - 32);
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private byte[] decrypt(byte[] data, Segment s) throws Exception {
        if (s.keyUrl == null) throw new IOException("kunci AES tidak ditemukan");
        byte[] key = keyCache.get(s.keyUrl);
        if (key == null) {
            key = fetchWithRetry(s.keyUrl);
            if (key.length != 16) throw new IOException("kunci AES tidak valid (" + key.length + " byte)");
            keyCache.put(s.keyUrl, key);
        }
        byte[] iv = s.iv != null ? s.iv : ByteBuffer.allocate(16).putLong(8, s.seq).array();
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private byte[] fetchWithRetry(String url) throws IOException {
        return Net.fetchWithRetry(url, referer, userAgent, 3);
    }
}
