package com.adib.vidgrab;

/** Satu video (atau file) yang terdeteksi. */
final class VideoItem {
    final String url;
    final String type;     // mp4, webm, m3u8, mpd, video, file, ...
    final String referer;  // halaman/iframe asal, dikirim saat mengunduh
    String name;           // nama file dari server (kalau ada)

    VideoItem(String url, String type, String referer) {
        this.url = url;
        this.type = type;
        this.referer = referer;
    }

    String key() {
        return Sniffer.keyFor(url);
    }
}
