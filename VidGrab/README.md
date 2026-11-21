# VidGrab — Video Grabber & Downloader (Android)

Browser kecil yang **otomatis mendeteksi semua video** di halaman, lalu **sekali tekan "⬇ UNDUH SEMUA"** semuanya diunduh.

## Cara pakai
1. Tempel link di kolom atas → **Buka** (atau dari WhatsApp/Chrome: **Bagikan → VidGrab**).
2. Lihat tombol merah di bawah: **⬇ UNDUH SEMUA (3 video + 2 halaman)**.
3. Tekan sekali → semua video diunduh ke folder **Download/VidGrab** (progres di notifikasi & bar biru di atas).

Tombol **📋** = daftar video kalau mau unduh satu-satu / salin link.

## Apa yang dilakukan tombol UNDUH SEMUA
- **Video yang terdeteksi otomatis** di halaman: request jaringan (termasuk player di iframe), tag `<video>`,
  link langsung ke `.mp4 .webm .mkv .mov .m4v .3gp .flv .avi .m3u8`.
- **Halaman berisi video** (mis. folder file hosting yang berisi "kumite.mp4", "kata.mov"): tiap halaman dibuka
  di latar belakang, player-nya dipancing, link video aslinya diambil, lalu ikut diunduh (maks. 30 halaman sekali tekan).
- Kalau belum ada video terdeteksi: aplikasi otomatis **memancing player** (memutar video tanpa suara / menekan tombol play) lalu mencari lagi.
- **Anti sampah**: file < 300 KB (iklan/preview) dan duplikat dilewati; HLS master + varian tidak diunduh dobel
  (otomatis pilih kualitas tertinggi). Video yang sudah diunduh tidak diunduh ulang kalau tombol ditekan lagi.
- Tombol "Download" milik situs langsung diproses.

## Build APK
Push ke GitHub → tab **Actions** → run **Build APK** → **Artifacts → VidGrab-apk**.
Atau buka folder ini di Android Studio → **Build > Build APK(s)**.

## Batasan
- Video ber-DRM (Netflix, Disney+, dll.), format DASH `.mpd`, dan YouTube tidak didukung.
- File hosting yang butuh captcha / hitung mundur / klik tombol Download manual belum bisa otomatis penuh —
  tekan tombol Download situsnya, VidGrab akan langsung menangkap filenya.
- HLS yang audionya dipisah bisa menghasilkan video tanpa suara (akan ada peringatan).
- Unduhan HLS berjalan di dalam aplikasi: biarkan aplikasi terbuka sampai selesai. File `.ts` paling lancar di VLC / MX Player.
- Kalau situs punya beberapa kualitas MP4 terpisah (360p/720p), semuanya ikut terunduh.
