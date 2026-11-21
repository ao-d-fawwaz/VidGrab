package com.adib.vidgrab;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Penamaan file + memasukkan unduhan biasa ke Android DownloadManager. */
final class Dl {
    private Dl() {
    }

    static final String FOLDER = "VidGrab";

    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "downloadfile", "index", "video", "videos", "playlist", "master", "file", "stream",
            "download", "get", "view", "play", "media", "source", "watch", "embed"));

    static String sanitize(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s;
    }

    static String fileNameFor(VideoItem it, String fallbackBase) {
        String name = it.name;
        if (name == null || name.isEmpty()) name = URLUtil.guessFileName(it.url, null, null);
        name = sanitize(name);

        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        if (!"file".equals(it.type)) {
            boolean known = false;
            for (String e : Sniffer.VIDEO_EXT) {
                if (e.substring(1).equals(ext)) known = true;
            }
            if (!known) ext = ("video".equals(it.type) || it.type.length() > 5) ? "mp4" : it.type;
        }
        if (base.isEmpty() || GENERIC.contains(base.toLowerCase(Locale.ROOT))) base = fallbackBase;
        return ext.isEmpty() ? base : base + "." + ext;
    }

    /** Masukkan ke antrean DownloadManager. Mengembalikan nama file. */
    static String enqueue(Context ctx, VideoItem it, String ua, String fallbackBase) {
        String name = fileNameFor(it, fallbackBase);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(it.url));
        String cookie = CookieManager.getInstance().getCookie(it.url);
        if (cookie != null) r.addRequestHeader("Cookie", cookie);
        if (ua != null) r.addRequestHeader("User-Agent", ua);
        if (it.referer != null && !it.referer.isEmpty()) r.addRequestHeader("Referer", it.referer);
        r.setTitle(name);
        r.setDescription("VidGrab");
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, FOLDER + "/" + name);
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        dm.enqueue(r);
        return name;
    }
}
