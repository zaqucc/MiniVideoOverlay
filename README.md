# MiniVideoOverlay 2.2.1

Android native Kotlin video compositor. All media processing is performed locally using Android MediaExtractor, MediaCodec, OpenGL ES and MediaMuxer. No media is uploaded.

## Implemented source features
- Fixed 1920x1080 landscape H.264 output with opaque #000000 background.
- Video uniformly scaled to fill canvas width, centered vertically; no source crop/stretch. Areas outside the video image remain black or are clipped only by the fixed output canvas.
- Rotation metadata is applied in the GL sampling shader.
- Full-canvas PNG overlay with alpha blending. Non-16:9 PNG is proportionally contained on a transparent 1920x1080 canvas. PNG without alpha is rejected.
- Source video duration drives the export timeline.
- Optional source-video audio and optional added music are decoded, converted to 48 kHz stereo PCM, gain-adjusted, mixed, and AAC-encoded. Added music loops to the video duration.
- H.264 video and one mixed AAC track are muxed into one MP4.
- Separate 0–100% volume controls, progress, cancellation, preview, playback and share actions.
- MediaStore output at `Movies/MiniVideoOverlay`, using pending publication and failure cleanup.
- Bounded PCM processing with temporary app-cache files; no whole video/PCM stream is held in RAM.

## Build
Requirements: JDK 17, Android SDK platform 35, Gradle 8.7, Android Gradle Plugin 8.6.1. Open the project root in Android Studio and run `:app:assembleDebug`.

## Important validation notes
This source has not been built or exercised on a physical Android device in the current environment. Codec support varies by device. The PCM conversion currently assumes Android decoder output is signed 16-bit PCM; unsupported decoder PCM formats fail with a visible error. Audio timestamp gaps/edit lists and variable-frame-rate behavior require device media test coverage before release.


## Versi 2.1.0 — Ekspor latar belakang

Proses rendering kini dijalankan oleh Android Foreground Service, bukan thread milik Activity. Pengguna dapat berpindah aplikasi atau mengunci layar tanpa Activity membatalkan pekerjaan. Notifikasi menampilkan tahap/persentase progres dan menyediakan aksi Batalkan. Saat selesai, notifikasi menyatakan video tersimpan; ketika aplikasi dibuka kembali, URI hasil terakhir dipulihkan agar tombol Putar Hasil dan Bagikan Hasil dapat digunakan. Android tetap dapat menghentikan proses dalam kondisi sistem ekstrem, sehingga fitur ini meningkatkan ketahanan tetapi bukan jaminan mutlak terhadap penghentian paksa oleh sistem atau pengguna.


## Fitur 2.2 — Playlist Musik
Gunakan tombol **Tambah Musik ke Playlist (Bisa Banyak)** untuk memilih beberapa file audio dalam satu pemilihan. File diputar sesuai urutan pilihan, tanpa jeda, lalu playlist berulang sampai video selesai. Volume playlist terpisah dari volume audio gameplay.


## Versi 2.2.1 — Music Track Notification
- Setiap pergantian lagu pada playlist memicu kartu judul musik yang dibakar langsung ke frame video hasil ekspor.
- Kartu berada di pojok kiri atas, berukuran ringkas, berlatar gelap transparan, dengan aksen biru, judul lagu, dan nama artis.
- Kartu tampil selama 3 detik sejak track dimulai dengan fade-in/fade-out singkat, lalu menghilang sampai track berikutnya.
- Jika playlist mengulang, notifikasi ikut muncul kembali mengikuti urutan track.
- Lapisan notifikasi dirender terpisah di atas video dan PNG pengguna; file PNG sumber tidak dimodifikasi.
- Nama artis yang tidak tersedia pada metadata ditampilkan sebagai “Unknown artist”; judul memakai metadata atau nama file.
