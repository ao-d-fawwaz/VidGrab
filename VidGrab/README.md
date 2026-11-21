# VidGrab — Video Grabber & Downloader (Android)

Browser kecil yang otomatis "mengendus" link video di halaman mana pun, lalu bisa diunduh sekali tekan.

## Cara kerja
1. **Penyadap request** — setiap request jaringan di halaman (termasuk dari iframe player) dicek; yang berakhiran
   `.mp4 .webm .mkv .mov .m4v .3gp .flv .avi .m3u8` langsung masuk daftar.
2. **Pemindai halaman** — tiap 3 detik, tag `<video>`/`<source>` dan link langsung ke file video dipindai
   (menangkap link video tanpa ekstensi, mis. `/stream?id=123`).
3. **Tombol download situs** — kalau halaman memicu download (tombol "Download" di situs file hosting),
   VidGrab menangkapnya dan menawarkan untuk mengunduh.
4. **Pengunduh**
   - MP4/WebM/dll → Android DownloadManager (ada notifikasi progres, cookie & referer ikut dikirim).
   - HLS `.m3u8` → semua potongan diunduh, didekripsi AES-128 kalau perlu, digabung jadi 1 file `.ts` (atau `.mp4` untuk fMP4).
   - Hasil ada di folder **Download/VidGrab**.

## Cara pakai
- Tempel link di kolom atas → **Buka**, atau dari WhatsApp/Chrome pilih **Bagikan → VidGrab**.
- **Putar videonya dulu**, tunggu beberapa detik → tombol 🎬 jadi merah → tekan → pilih video → **Unduh**.
- Tekan lama 🎬 untuk memindai ulang.

## Build APK tanpa Android Studio (GitHub Actions)
1. Buat repo baru di GitHub, upload semua isi folder ini (termasuk folder `.github` dan `gradle`).
2. Buka tab **Actions** → workflow **Build APK** jalan otomatis (± 3–5 menit).
3. Klik run yang selesai (centang hijau) → bagian **Artifacts** → unduh `VidGrab-apk` (zip berisi `app-debug.apk`).
4. Pindahkan APK ke HP, install (izinkan "Install dari sumber tak dikenal").

## Build dengan Android Studio
Open folder ini → tunggu Gradle sync → **Build > Build APK(s)**.

## Batasan
- Video ber-DRM (Netflix, Disney+, dll.), format DASH `.mpd`, dan YouTube **tidak** didukung.
- HLS yang audionya dipisah dari video bisa menghasilkan file tanpa suara (akan ada peringatan).
- Unduhan HLS berjalan di dalam aplikasi: biarkan aplikasi tetap terbuka sampai selesai.
- File `.ts` paling lancar diputar di VLC atau MX Player.
- Situs yang mendeteksi bot/WebView secara agresif bisa saja menolak.
