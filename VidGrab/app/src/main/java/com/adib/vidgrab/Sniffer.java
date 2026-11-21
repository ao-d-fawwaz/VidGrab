package com.adib.vidgrab;

import java.util.Locale;
import java.util.regex.Pattern;

/** Aturan deteksi video + script yang disuntikkan ke halaman. */
final class Sniffer {
    private Sniffer() {
    }

    static final String[] VIDEO_EXT = {".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".flv", ".avi"};

    private static final Pattern VIDEO_NAME =
            Pattern.compile("\\.(mp4|webm|mkv|mov|m4v|3gp|avi|flv)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Memindai halaman dan melapor ke VidGrab.report():
     *  V|url = sumber tag video, A|url = link langsung ke file video,
     *  P|url = link ke HALAMAN yang namanya berisi file video (mis. "kumite.mp4" di file hosting).
     */
    static final String SCAN_JS =
            "(function(){try{var o=[],seen={};" +
            "function add(p,u){if(!u)return;try{u=new URL(u,location.href).href}catch(x){return}" +
            "if(seen[p+u])return;seen[p+u]=1;o.push(p+u);}" +
            "document.querySelectorAll('video,source').forEach(function(e){" +
            "add('V|',e.currentSrc);add('V|',e.src);add('V|',e.getAttribute('data-src'));});" +
            "var re=/\\.(mp4|webm|mkv|mov|m4v|3gp|avi|flv|m3u8)(\\?|#|$)/i;" +
            "var nm=/\\.(mp4|webm|mkv|mov|m4v|3gp|avi|flv)\\b/i;" +
            "document.querySelectorAll('a[href]').forEach(function(a){var h=a.href;" +
            "if(!/^https?:/i.test(h))return;" +
            "if(re.test(h)){add('A|',h);}" +
            "else if(nm.test(a.textContent||'')||nm.test(a.getAttribute('title')||'')||nm.test(a.getAttribute('download')||'')){add('P|',h);}});" +
            "if(o.length&&window.VidGrab)VidGrab.report(JSON.stringify(o.slice(0,400)));" +
            "}catch(x){}})();";

    /** "Memancing" player: putar semua video (tanpa suara) & tekan tombol play umum supaya link aslinya termuat. */
    static final String TRIGGER_JS =
            "(function(){try{" +
            "document.querySelectorAll('video').forEach(function(v){try{v.muted=true;" +
            "var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(x){}});" +
            "['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container'," +
            "'.plyr__control--overlaid','.fp-play','.mejs__overlay-button'," +
            "'button[aria-label*=play i]','[class*=play-button]','[class*=playButton]','[class*=btn-play]']" +
            ".forEach(function(s){try{var e=document.querySelector(s);if(e)e.click();}catch(x){}});" +
            "}catch(x){}})();";

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

        String path = stripQuery(low);
        if (path.endsWith(".m3u8")) return "m3u8";
        if (path.endsWith(".mpd")) return "mpd";
        for (String e : VIDEO_EXT) {
            if (path.endsWith(e)) return e.substring(1);
        }
        return null;
    }

    static boolean looksLikeVideoName(String s) {
        return s != null && VIDEO_NAME.matcher(s).find();
    }

    static String stripQuery(String url) {
        String p = url;
        int h = p.indexOf('#');
        if (h >= 0) p = p.substring(0, h);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        return p;
    }

    static String stripFragment(String url) {
        if (url == null) return "";
        int h = url.indexOf('#');
        return h >= 0 ? url.substring(0, h) : url;
    }

    /** Kunci anti-duplikat: file video yang sama dengan token berbeda dianggap satu. */
    static String keyFor(String url) {
        String t = classify(url, null);
        if (t != null && !t.equals("m3u8") && !t.equals("mpd")) return stripQuery(url);
        return stripFragment(url);
    }
}
