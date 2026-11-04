# INSTRUKSI IMPLEMENTASI — MiniVideoOverlay 2.0
## Spesifikasi teknis lengkap untuk Claude AI

> **Peran:** Bertindak sebagai senior Android/Kotlin engineer yang bertanggung jawab mengubah proyek Android yang sudah ada menjadi aplikasi compositor video sederhana dan stabil. Jangan hanya memberi saran atau contoh kode. Kerjakan langsung pada seluruh source project, pertahankan bagian yang masih benar, implementasikan fitur sampai terintegrasi, lalu uji dan serahkan proyek lengkap.

---

# 1. TUJUAN UTAMA

Ubah aplikasi **MiniVideoOverlay** yang ada menjadi aplikasi Android untuk menggabungkan:

1. Satu video utama sebagai sumber gambar bergerak.
2. Satu file PNG transparan sebagai frame/dekorasi di atas video.
3. Audio asli dari video utama.
4. Satu file musik tambahan yang dapat diatur volumenya dan diputar berulang sampai video utama berakhir.

Hasil akhir berupa **satu file MP4** dengan spesifikasi:

- Resolusi: **1920 × 1080 piksel**.
- Rasio kanvas: **16:9 landscape**.
- Background: hitam solid, warna tepat **#000000**.
- Video: memenuhi lebar kanvas dari sisi kiri sampai sisi kanan, tanpa distorsi dan tanpa crop.
- PNG: menutupi seluruh kanvas 16:9, transparansi alpha dipertahankan.
- Durasi: sama dengan durasi video sumber.
- Video codec: H.264/AVC.
- Audio codec: AAC jika audio tersedia.
- Folder penyimpanan: `Movies/MiniVideoOverlay`.

Aplikasi harus tetap berupa aplikasi Android native berbasis Kotlin. Jangan mengubahnya menjadi aplikasi web, proyek Flutter, React Native, atau aplikasi desktop.

# 2. SUMBER PROYEK DAN ATURAN KERJA

Mulai dari source project yang diberikan pengguna, yaitu arsip `MiniVideoOverlay-main.zip`.

Informasi proyek awal yang sudah diketahui:

- Namespace dan application ID: `com.personal.minivideooverlay`.
- Bahasa: Kotlin.
- Android Gradle Plugin menggunakan Kotlin DSL.
- `compileSdk = 35`, `minSdk = 29`, `targetSdk = 35`.
- Java/Kotlin target 17.
- File inti saat ini:
  - `MainActivity.kt`
  - `OverlayConfig.kt`
  - `GlCompositor.kt`
  - `ExportEngine.kt`
- Proyek awal menggunakan Android framework, MediaCodec, dan OpenGL ES tanpa dependency pihak ketiga.

**Wajib dilakukan sebelum mengubah kode:**

1. Baca semua source file, Gradle, manifest, dan workflow GitHub Actions.
2. Petakan alur pemilihan file, rendering, encoding, dan penyimpanan yang sudah ada.
3. Identifikasi kode lama yang khusus untuk blur, foto persegi, shadow, portrait 9:16, dan durasi 15 detik.
4. Buat rencana perubahan internal singkat, kemudian langsung implementasikan.
5. Jangan menghapus fungsi yang masih diperlukan hanya karena ingin menulis ulang dengan cepat.
6. Jangan membuat file contoh, stub, placeholder, TODO, atau tombol yang tidak berfungsi untuk mengklaim fitur sudah selesai.
7. Jangan mengganti package name, application ID, atau minimum SDK tanpa alasan teknis yang kuat. Jika terpaksa, jelaskan dampaknya.
8. Jangan mengarang hasil pengujian. Bedakan dengan tegas antara berhasil dikompilasi, berhasil dijalankan, dan berhasil diuji pada perangkat Android sungguhan.

**Hasil akhir harus berupa source project lengkap**, bukan hanya potongan kode, dokumen arsitektur, atau APK tanpa source.

# 3. SPESIFIKASI KANVAS DAN KOMPOSISI VISUAL

## 3.1 Kanvas output

- Ukuran tetap: `1920 x 1080`.
- Orientasi landscape.
- Background selalu hitam murni `#000000`.
- Tidak ada pilihan rasio lain di UI.
- Tidak ada auto-resize output menjadi portrait.
- Seluruh proses rendering dan encoding harus menggunakan koordinat kanvas output 1920×1080, bukan ukuran layar preview Android.

Konstanta terpusat, misalnya:

```kotlin
const val OUTPUT_WIDTH = 1920
const val OUTPUT_HEIGHT = 1080
const val OUTPUT_BACKGROUND_COLOR = 0xFF000000.toInt()
```

Jangan menyimpan nilai ukuran output yang saling bertentangan di beberapa class.

## 3.2 Video utama — aturan paling penting

Video harus memenuhi lebar kanvas: tepi kiri video mencapai tepi kiri kanvas dan tepi kanan video mencapai tepi kanan kanvas.

**Pertahankan rasio aspek asli video.** Gunakan uniform scaling, bukan scaling X dan Y secara terpisah.

Rumus:

```text
scale = outputWidth / sourceWidth
renderedWidth  = sourceWidth  * scale = 1920
renderedHeight = sourceHeight * scale
```

Posisikan video tepat di tengah secara vertikal:

```text
x = 0
y = (1080 - renderedHeight) / 2
```

Konsekuensi yang disengaja:

- Jika video sumber 16:9, video mengisi seluruh kanvas.
- Jika video lebih lebar atau lebih tinggi rasionya berbeda, video tetap memenuhi lebar 1920 px dan tinggi mengikuti rasio aslinya.
- Jika tinggi hasil render kurang dari 1080 px, area yang tidak tertutup video tetap hitam.
- Jika tinggi hasil render lebih dari 1080 px, **jangan crop**. Pertahankan seluruh tinggi video; komposisi boleh melampaui batas vertikal kanvas dan bagian di luar kanvas tidak akan terlihat karena batas output.
- Jangan pernah meregangkan video agar tepat 1920×1080.
- Jangan menggunakan center-crop, fit-center yang menghasilkan margin kiri/kanan, atau fill-crop.
- Jangan mengubah orientasi video secara salah. Hormati metadata rotasi/orientasi sumber sehingga video tampil dengan arah yang benar. Jika rotasi diterapkan, hitung dimensi efektif setelah rotasi sebelum menentukan skala.
- Jangan melakukan zoom otomatis.
- Jangan menambahkan blur, sharpen, filter, efek, shadow, atau transisi.

Catatan: istilah “video 100%” dalam spesifikasi ini berarti video digunakan utuh dan proporsinya tetap, sementara lebarnya dibuat memenuhi kanvas. Bukan berarti pixel sumber harus dipetakan 1:1 ke output.

## 3.3 PNG overlay — menutupi seluruh kanvas

PNG merupakan frame/dekorasi yang berada di lapisan paling atas.

Aturan wajib:

- Area kerja PNG adalah seluruh kanvas output 1920×1080.
- PNG bukan overlay persegi di tengah.
- PNG bukan overlay yang ukurannya mengikuti ukuran video.
- PNG bukan overlay yang mengikuti posisi atau dimensi video.
- PNG harus dirender pada koordinat `(0,0)` dengan ukuran kanvas penuh `1920×1080`.
- Pertahankan kanal alpha/transparansi PNG.
- Area PNG yang transparan harus memperlihatkan hasil komposisi video dan/atau background hitam di bawahnya.
- Pixel PNG yang opak harus tampil di atas video.
- Jangan menambahkan background putih atau hitam ke bitmap PNG saat decode.
- Jangan menghilangkan alpha dengan mengubah PNG menjadi JPEG.
- Jangan menerapkan shadow otomatis, outline, blur, opacity tambahan, animasi, atau transisi.
- PNG harus tetap tajam dan tidak mengalami rotasi atau distorsi.

Jika PNG yang dipilih bukan 16:9, tampilkan peringatan yang jelas sebelum ekspor bahwa file akan disesuaikan ke kanvas. Untuk penyesuaian, gunakan skala proporsional dan tempatkan di tengah kanvas; jangan merusak rasio PNG. Pilihan default yang disarankan adalah **contain** agar seluruh desain PNG terlihat dan tidak terpotong. Area PNG yang tidak tertutup menjadi transparan. Jangan diam-diam melakukan crop. Jika memungkinkan, tampilkan preview dan penjelasan bahwa PNG 1920×1080 paling ideal.

## 3.4 Urutan layer

Urutan komposisi harus persis:

1. Background hitam `#000000`.
2. Video utama.
3. PNG overlay transparan.

PNG selalu berada di paling atas.

Jangan membuat lapisan foto tambahan. Tidak ada foto kotak, thumbnail foto, atau background blur.

# 4. AUDIO — FITUR WAJIB, BUKAN OPSIONAL

Aplikasi harus menghasilkan audio gabungan dari dua sumber:

- Audio asli video utama.
- Musik tambahan yang dipilih pengguna.

Kedua sumber harus memiliki kontrol volume terpisah.

## 4.1 Pemilihan audio

- Audio asli otomatis diambil dari video utama jika video memiliki audio track.
- Pengguna memilih satu file musik tambahan dari perangkat.
- Musik tambahan harus menerima format audio yang dapat didekode Android secara andal, setidaknya AAC/M4A, MP3, dan WAV jika codec perangkat mendukung.
- Jika format tidak didukung, tampilkan pesan yang jelas. Jangan membuat aplikasi crash.
- Jika video tidak memiliki audio, proses tetap boleh dilanjutkan dengan musik tambahan saja.
- Jika pengguna tidak memilih musik tambahan, video tetap dapat diekspor dengan audio asli saja.
- Jika pengguna memilih musik tambahan tetapi video tidak memiliki audio, musik tetap dimasukkan.

## 4.2 Kontrol volume terpisah

Sediakan dua slider:

1. **Volume video asli**: 0–100%.
2. **Volume musik tambahan**: 0–100%.

Ketentuan:

- Default kedua volume adalah 100%.
- Nilai slider ditampilkan sebagai angka persen.
- Perubahan slider memengaruhi hasil ekspor.
- 0% berarti mute untuk sumber tersebut.
- 100% berarti gain normal (1.0).
- Jangan mengubah volume sistem Android.
- Jangan mengubah file sumber.
- Simpan nilai volume untuk sesi editing saat ini. Persistensi antar sesi boleh ditambahkan, tetapi bukan syarat utama.
- Jangan menerapkan normalisasi otomatis, limiter agresif, ducking otomatis, atau efek audio lain tanpa permintaan pengguna.

## 4.3 Sinkronisasi dan pencampuran

- Audio asli harus sinkron dengan timeline video.
- Musik tambahan dimulai dari timestamp 0 saat video dimulai.
- Musik tambahan diputar dari awal file.
- Jika musik lebih pendek daripada video, musik diulang dari awal secara berurutan sampai video berakhir.
- Jika durasi musik lebih panjang daripada video, potong tepat pada akhir video.
- Jika musik tidak habis tepat saat video berakhir, jangan membuat output lebih panjang.
- Output audio tidak boleh memperpanjang durasi video.
- Terapkan gain masing-masing sumber sebelum mixing.
- Campur kedua PCM stream dengan clipping protection yang wajar agar hasil tidak pecah/clipping. Jangan menaikkan volume otomatis di atas gain yang diminta pengguna.
- Gunakan timestamp/PTS untuk menjaga sinkronisasi; jangan mengandalkan jumlah buffer atau perkiraan durasi.
- Tangani audio source dengan sample rate/channel count berbeda. Konversikan/resample ke format mixing yang konsisten sebelum encoding AAC.
- Perhatikan format PCM, byte order, channel layout, sample rate, dan timestamp.
- Hindari jeda/senyap yang tidak disengaja di antara loop musik. Jika decoder menghasilkan buffer dengan batas loop, sambungkan loop dengan benar; crossfade tidak diperlukan dan jangan menambahkan crossfade jika tidak diminta.
- Tangani audio video yang memiliki delay awal, edit list, atau timestamp non-zero secara benar sejauh didukung API.
- Jika sumber audio rusak atau decoder gagal, tampilkan error yang dapat dimengerti dan jangan menghasilkan file yang tampak sukses tetapi audionya hilang tanpa pemberitahuan.

## 4.4 Arsitektur audio yang disarankan

Pilih implementasi native yang benar-benar dapat bekerja di Android. Jangan membuat klaim bahwa `MediaMuxer` dapat mencampur dua audio track dengan sendirinya: `MediaMuxer` hanya mengemas track, bukan melakukan audio mixing.

Pendekatan yang disarankan:

1. Decode audio video utama dengan `MediaExtractor` + `MediaCodec`.
2. Decode musik tambahan dengan `MediaExtractor` + `MediaCodec`.
3. Konversi hasil decode menjadi PCM yang kompatibel.
4. Atur gain secara terpisah.
5. Mix sampel PCM dengan proteksi clipping.
6. Encode hasil mix menjadi AAC menggunakan `MediaCodec` encoder.
7. Mux track video H.264 dan track audio AAC ke satu MP4 menggunakan `MediaMuxer`.

Alternatif boleh digunakan hanya jika:
- Berfungsi offline di Android.
- Kompatibel dengan minSdk 29.
- Lisensi dependency jelas.
- Tidak mengharuskan server/cloud.
- Hasilnya dapat diuji.
- Tidak mengorbankan sinkronisasi, kualitas, atau durasi.

Jangan menggunakan FFmpeg binary atau library berlisensi tidak jelas tanpa menjelaskan lisensi dan konsekuensi distribusinya. Jika memilih FFmpegKit atau library pihak ketiga lain, verifikasi status pemeliharaan, kompatibilitas Android, ukuran APK, dan lisensi saat ini terlebih dahulu.

# 5. DURASI, FRAME RATE, DAN TIMESTAMP

## 5.1 Durasi output

- Durasi output harus mengikuti durasi video sumber.
- Jangan menetapkan durasi tetap 15 detik.
- Jangan melakukan loop video.
- Jangan memangkas video secara default.
- Jangan menambahkan frame kosong setelah video berakhir.
- Musik tambahan boleh loop, tetapi video tidak.
- Output harus berakhir pada timestamp akhir video sumber, dengan toleransi maksimum satu frame video untuk batas akhir video dan toleransi audio yang wajar sesuai frame AAC.
- Jika video sumber memiliki durasi tidak terbaca atau metadata rusak, hentikan ekspor dengan pesan error yang jelas.

## 5.2 Frame rate

- Pertahankan frame rate sumber jika pipeline mampu melakukannya secara stabil.
- Jika sumber variable frame rate, gunakan timestamp sumber sebagai acuan; jangan mengasumsikan frame rate tetap dari metadata saja.
- Jika encoder memerlukan frame rate tetap, lakukan konversi dengan timestamp yang konsisten dan dokumentasikan kebijakan konversinya.
- Jangan mengklaim output 60 FPS jika tidak benar-benar dihasilkan.
- Jangan menggandakan atau membuang frame secara tidak perlu.
- Pastikan PTS video monoton meningkat.
- Audio dan video harus sinkron dari awal sampai akhir.

## 5.3 Orientasi video

- Baca metadata rotasi dari video.
- Terapkan transformasi rotasi secara tepat di pipeline rendering.
- Hitung ulang ukuran efektif video setelah rotasi.
- Jangan memutar output final menjadi portrait hanya karena metadata sumber.
- Output final tetap landscape 1920×1080.

# 6. FORMAT DAN PENYIMPANAN OUTPUT

Output:

- Container: MP4.
- Video: H.264/AVC.
- Audio: AAC-LC atau profil AAC yang kompatibel luas.
- Resolusi: 1920×1080.
- Background: hitam.
- Nama file: unik, misalnya `MiniVideoOverlay_yyyyMMdd_HHmmss.mp4`.
- Folder: `Movies/MiniVideoOverlay`.

Ketentuan penyimpanan Android:

- Gunakan MediaStore untuk menyimpan ke folder publik Movies.
- Gunakan `RELATIVE_PATH = "Movies/MiniVideoOverlay"`.
- Gunakan MIME type `video/mp4`.
- Gunakan `IS_PENDING = 1` selama file sedang ditulis.
- Setelah ekspor sukses dan file ditutup, ubah `IS_PENDING = 0`.
- Jika ekspor gagal atau dibatalkan, hapus item MediaStore yang belum selesai agar tidak meninggalkan file rusak.
- Jangan meminta izin storage luas yang tidak diperlukan.
- Gunakan Storage Access Framework untuk memilih video, PNG, dan musik.
- Tangani URI dari `ACTION_OPEN_DOCUMENT` dengan benar.
- Jika proses ekspor berlangsung setelah Activity ditutup, pastikan akses URI tetap valid. Ambil persistable URI permission jika tersedia dan diperlukan.
- Jangan mengandalkan path filesystem langsung dari URI `content://`.
- Jangan menyalin seluruh file besar ke RAM.
- Tutup semua descriptor, extractor, codec, EGL, surface, dan muxer pada semua jalur keluar.

# 7. ALUR PENGGUNA DAN UI

Buat UI sederhana, jelas, dan tidak penuh fitur yang tidak diminta. Fokus pada tiga file input, dua kontrol volume, preview, dan ekspor.

Alur utama:

1. Pengguna membuka aplikasi.
2. Pengguna memilih video utama.
3. Pengguna memilih PNG overlay.
4. Pengguna memilih musik tambahan.
5. Pengguna mengatur volume video asli.
6. Pengguna mengatur volume musik.
7. Pengguna melihat preview komposisi.
8. Pengguna menekan tombol **Ekspor Video**.
9. Aplikasi menampilkan progres.
10. Setelah selesai, aplikasi menampilkan lokasi/nama file dan tombol untuk memutar hasil atau membagikannya.

## 7.1 Elemen UI minimal

- Judul aplikasi: MiniVideoOverlay.
- Tombol pilih video.
- Informasi nama file video dan durasi.
- Tombol pilih PNG.
- Preview PNG/komposisi.
- Tombol pilih musik.
- Informasi nama file musik dan durasi.
- Slider volume video asli 0–100%.
- Slider volume musik 0–100%.
- Tombol preview/play-pause.
- Tombol Ekspor Video.
- Progress indicator dan teks status.
- Tombol Batalkan saat ekspor berjalan.
- Hasil ekspor: nama file, ukuran jika tersedia, tombol Putar, tombol Bagikan.

Jangan menambahkan:
- Timeline editing kompleks.
- Multi-track editing.
- Efek visual.
- Filter.
- Transisi.
- Crop editor.
- Text editor.
- Animasi.
- Watermark.
- Login.
- Iklan.
- Upload cloud.
- Fitur yang tidak diminta.

## 7.2 Validasi input

Tombol ekspor tidak aktif sampai video dan PNG dipilih. Musik tambahan boleh tidak dipilih.

Validasi:
- Video URI dapat dibuka dan memiliki track video.
- PNG benar-benar dapat didekode sebagai gambar PNG dan memiliki alpha bila file memang transparan.
- Musik dapat dibuka dan memiliki audio track jika dipilih.
- Metadata durasi video tersedia.
- File sumber tidak kosong.
- Format tidak didukung ditangani dengan pesan yang jelas.

Jika PNG tidak memiliki alpha, beri peringatan bahwa file tidak transparan; jangan mengubahnya diam-diam.

## 7.3 Preview

Preview harus mencerminkan komposisi output:

- Rasio tampilan 16:9.
- Background hitam.
- Video mengikuti aturan full-width dan proporsional.
- PNG menutupi seluruh kanvas.
- Preview boleh menggunakan resolusi lebih rendah demi performa, tetapi posisi, skala, rasio, alpha, dan urutan layer harus sama dengan output.
- Preview tidak boleh mengubah file sumber.
- Jangan menampilkan preview portrait 9:16.

Preview audio boleh menyediakan play/pause sederhana dan volume, tetapi hasil ekspor tetap harus menggunakan pipeline audio yang sama dan akurat.

# 8. ARSITEKTUR IMPLEMENTASI YANG DIHARAPKAN

Pertahankan pemisahan tanggung jawab. Nama class boleh disesuaikan jika lebih baik, tetapi tanggung jawabnya harus jelas.

Contoh pembagian:

- `MainActivity`: UI, pemilihan file, validasi, interaksi pengguna.
- `OverlayConfig`: semua konstanta output dan codec.
- `MediaInput`: metadata dan URI input.
- `VideoDecoder`: decode video source.
- `GlCompositor` atau renderer setara: komposisi background, video, PNG.
- `VideoEncoder`: encode H.264.
- `AudioDecoder`: decode sumber audio menjadi PCM.
- `AudioMixer`: gain dan mixing PCM dua sumber.
- `AudioEncoder`: encode AAC.
- `ExportEngine`: mengorkestrasi keseluruhan proses.
- `MediaStoreOutput`: membuat, menyelesaikan, atau menghapus file output.
- `ExportState`: status/progres/cancel.
- `PreviewController`: preview real-time.

Tidak wajib memakai nama persis di atas, tetapi hindari satu class raksasa yang memuat seluruh logika UI, decoding, rendering, audio mixing, encoding, dan penyimpanan.

## 8.1 Video pipeline

Gunakan pipeline yang efisien untuk file video besar:

- Decode melalui MediaExtractor dan MediaCodec.
- Render frame melalui Surface/SurfaceTexture dan OpenGL ES jika itu pendekatan yang sudah digunakan.
- Render ke EGL input surface encoder.
- Komposisi dilakukan pada ukuran output 1920×1080.
- Hindari memuat seluruh frame video ke memori.
- Hindari membuat Bitmap 1920×1080 untuk setiap frame.
- PNG boleh didekode sekali dan diunggah ke texture GPU.
- Pastikan texture PNG menggunakan alpha blending yang benar.
- Pastikan blending tidak menghasilkan pinggiran hitam/putih pada area transparan.
- Pastikan koordinat texture tidak terbalik vertikal.
- Atur viewport dan aspect ratio dengan benar.
- Tangani context lost dan release resource dengan aman.

## 8.2 Encoder dan muxer

- Gunakan MediaCodec H.264 encoder.
- Konfigurasi output 1920×1080.
- Pilih bitrate yang wajar untuk Full HD; buat konstanta terpusat dan jelaskan nilai default.
- Jangan menganggap semua perangkat mendukung konfigurasi encoder yang sama.
- Periksa codec capability sebelum konfigurasi.
- Jika konfigurasi utama tidak didukung, tampilkan error yang informatif atau fallback yang benar-benar aman.
- Jangan menyimpan file MP4 sebelum muxer menerima format track yang valid.
- Tangani urutan `start`, `stop`, dan `release` dengan benar.
- Jangan memanggil `MediaMuxer.stop()` jika belum berhasil `start()`.
- Jangan menulis sample sebelum track ditambahkan.
- Pastikan flag EOS dan drain output buffer ditangani dengan benar.
- Jangan mengabaikan encoder output buffer yang berisi codec config.
- Jaga urutan timestamp setiap track.

## 8.3 Threading dan lifecycle

- Proses ekspor tidak boleh berjalan di main/UI thread.
- UI harus tetap responsif.
- Progress dikirim ke UI melalui mekanisme thread-safe.
- Tombol cancel harus benar-benar menghentikan decoder, encoder, mixer, dan muxer dengan aman.
- Gunakan coroutine atau executor secara terstruktur jika sesuai; jangan membuat thread liar tanpa lifecycle.
- Jangan kehilangan status atau membocorkan resource saat Activity rotate/ditutup.
- Pertimbangkan foreground service jika ekspor panjang harus tetap berjalan ketika aplikasi berada di background. Jika implementasi ini dipilih, ikuti aturan foreground service Android 14/15 dan deklarasi tipe service yang benar. Jangan menambahkan service yang tidak perlu.
- Tampilkan status jika aplikasi tidak dapat melanjutkan ekspor karena sistem menghentikan proses.

# 9. ERROR HANDLING DAN KEANDALAN

Tangani minimal kasus berikut:

1. Pengguna membatalkan pemilihan file.
2. URI tidak dapat dibuka.
3. Video tidak memiliki video track.
4. Video tidak memiliki audio track.
5. Musik tidak memiliki audio track.
6. PNG rusak atau tidak dapat didekode.
7. PNG tidak memiliki transparansi.
8. Video rusak atau durasi tidak tersedia.
9. Codec perangkat tidak mendukung format input.
10. H.264 encoder tidak tersedia atau gagal konfigurasi.
11. AAC encoder tidak tersedia atau gagal konfigurasi.
12. Ruang penyimpanan tidak cukup.
13. Pengguna membatalkan ekspor.
14. Proses ekspor gagal di tengah jalan.
15. Activity ditutup atau proses aplikasi dihentikan.
16. Audio track lebih pendek/panjang dari video.
17. Audio sumber berbeda sample rate/channel.
18. Video memiliki rotasi metadata.
19. Video variable frame rate.
20. Perangkat kehabisan memori atau GPU resource.

Pesan error harus menjelaskan apa yang terjadi dan tindakan yang bisa dilakukan pengguna. Jangan hanya menampilkan `Export failed`.

Jika gagal:
- Jangan menampilkan notifikasi sukses.
- Jangan meninggalkan MP4 parsial seolah-olah valid.
- Hapus file pending.
- Lepaskan seluruh resource.
- Simpan log teknis yang cukup untuk debugging tanpa membocorkan data pribadi pengguna.

# 10. PENGUJIAN WAJIB

Jangan menyatakan selesai hanya karena kode terlihat benar. Jalankan build dan pengujian yang tersedia.

## 10.1 Build

- Jalankan Gradle build debug.
- Jalankan lint jika tersedia.
- Perbaiki seluruh compile error.
- Pastikan manifest valid.
- Pastikan dependency dan versi Gradle konsisten.
- Pastikan GitHub Actions workflow tidak rusak.
- Build release jika lingkungan mendukung.
- Jika Android SDK atau Gradle tidak tersedia, sebutkan secara eksplisit dan jangan mengklaim build sukses.

## 10.2 Matriks pengujian fungsi

Uji setidaknya:

| Kasus | Hasil yang diharapkan |
|---|---|
| Video 16:9 + PNG 16:9 + musik | Video memenuhi kanvas, PNG di atas, audio tercampur |
| Video 4:3 | Lebar video memenuhi kanvas, rasio tetap, area sisa hitam |
| Video portrait | Lebar memenuhi kanvas, rasio tetap, tidak diregangkan |
| PNG transparan | Bagian transparan memperlihatkan video |
| PNG tanpa alpha | Peringatan ditampilkan |
| Musik lebih pendek | Musik loop sampai video selesai |
| Musik lebih panjang | Musik dipotong pada akhir video |
| Volume video 0% | Audio asli mute |
| Volume musik 0% | Musik tambahan mute |
| Kedua volume 100% | Kedua sumber terdengar tanpa clipping berlebihan |
| Video tanpa audio | Ekspor tetap bisa memakai musik |
| Tanpa musik tambahan | Ekspor tetap bisa memakai audio video |
| Ekspor dibatalkan | Tidak ada file output parsial yang dianggap sukses |
| Video 1 menit | Output berdurasi sama dengan video sumber |
| Video rotasi 90° | Orientasi tampilan benar, output tetap 16:9 |
| Video VFR | Timestamp tidak rusak dan audio tetap sinkron |

## 10.3 Pemeriksaan output

Jika tersedia perangkat/emulator atau alat pemeriksa media:

- Periksa resolusi dengan MediaMetadataRetriever/ffprobe.
- Periksa codec video H.264.
- Periksa codec audio AAC bila audio tersedia.
- Periksa durasi output dibanding sumber.
- Periksa audio stream benar-benar ada.
- Dengarkan hasil mixing untuk memastikan kedua sumber terdengar.
- Periksa musik loop dan potongan akhir.
- Periksa frame awal, tengah, dan akhir untuk memastikan layer PNG dan video benar.
- Periksa sinkronisasi audio-video sepanjang file.

Jangan menyebut hasil uji visual/audio berhasil jika tidak benar-benar memutar atau memeriksa file hasil.

# 11. KRITERIA PENERIMAAN — TIDAK BOLEH ADA YANG TERLEWAT

Implementasi hanya dianggap selesai jika seluruh poin ini terpenuhi:

- [ ] Aplikasi dapat memilih video dari perangkat.
- [ ] Aplikasi dapat memilih PNG dari perangkat.
- [ ] Aplikasi dapat memilih musik dari perangkat.
- [ ] Kanvas output tepat 1920×1080.
- [ ] Background tepat hitam #000000.
- [ ] Video memenuhi lebar kanvas dari kiri ke kanan.
- [ ] Rasio aspek video tidak berubah.
- [ ] Tidak ada crop otomatis atau stretch.
- [ ] PNG menutupi seluruh kanvas, bukan kotak di tengah.
- [ ] Alpha/transparansi PNG dipertahankan.
- [ ] PNG selalu berada di atas video.
- [ ] Tidak ada blur.
- [ ] Tidak ada shadow otomatis.
- [ ] Tidak ada filter, transisi, animasi, atau efek tambahan.
- [ ] Audio asli video dipertahankan.
- [ ] Musik tambahan dapat dicampur dengan audio asli.
- [ ] Volume audio asli dapat diatur 0–100%.
- [ ] Volume musik dapat diatur 0–100%.
- [ ] Musik dimulai dari awal video.
- [ ] Musik otomatis loop jika lebih pendek.
- [ ] Musik dipotong tepat pada akhir video.
- [ ] Durasi output mengikuti durasi video sumber.
- [ ] Output MP4 menggunakan H.264.
- [ ] Audio output menggunakan AAC jika ada audio sumber.
- [ ] File tersimpan di Movies/MiniVideoOverlay.
- [ ] Progres ekspor berfungsi.
- [ ] Cancel ekspor berfungsi.
- [ ] Tidak ada crash pada alur normal.
- [ ] Resource selalu dilepas.
- [ ] Build berhasil atau keterbatasan build dijelaskan secara jujur.
- [ ] Source project lengkap diserahkan.

# 12. LARANGAN DAN HAL YANG TIDAK BOLEH DIASUMSIKAN

1. Jangan mengembalikan desain lama 9:16.
2. Jangan mengembalikan overlay foto persegi.
3. Jangan mengunci durasi menjadi 15 detik.
4. Jangan menghapus audio asli video.
5. Jangan hanya menambahkan musik sebagai track kedua tanpa mixing. Output harus berupa satu audio mix yang berisi kedua sumber.
6. Jangan menganggap MediaMuxer melakukan mixing audio.
7. Jangan mengubah rasio video dengan stretch.
8. Jangan melakukan crop otomatis.
9. Jangan membuat PNG mengikuti ukuran video; PNG mengikuti seluruh kanvas.
10. Jangan menghilangkan transparansi PNG.
11. Jangan menambahkan blur atau efek dekoratif.
12. Jangan memakai jaringan/server/cloud untuk proses ekspor.
13. Jangan membuat UI palsu dengan tombol yang tidak berfungsi.
14. Jangan mengklaim pengujian yang tidak dilakukan.
15. Jangan mengganti identitas proyek atau package name tanpa alasan.
16. Jangan berhenti setelah membuat dokumen rencana. Implementasikan seluruh fitur.

# 13. FORMAT HASIL YANG HARUS DISERAHKAN

Setelah implementasi, serahkan:

1. **ZIP source project lengkap** yang bisa dibuka sebagai proyek Android Studio/Gradle.
2. APK debug yang sudah berhasil dibangun, jika build environment memungkinkan.
3. APK release jika konfigurasi signing memungkinkan. Jangan membuat signing key palsu atau mengklaim APK release siap Play Store jika belum ditandatangani dengan benar.
4. Ringkasan file/class yang diubah.
5. Daftar fitur yang benar-benar sudah berfungsi.
6. Daftar pengujian yang dijalankan beserta hasil aktual.
7. Daftar keterbatasan yang masih ada.
8. Langkah build APK dari Android Studio atau Gradle.

Sebelum membuat ZIP:
- Hapus folder build/cache yang tidak perlu.
- Jangan menghapus source, Gradle wrapper, workflow, atau resource penting.
- Pastikan struktur proyek utuh.
- Pastikan ZIP dapat dibuka dan berisi root project yang benar.
- Jangan menyertakan file output media pengguna.

# 14. INSTRUKSI EKSEKUSI UNTUK CLAUDE

Sekarang kerjakan langsung pada arsip proyek yang saya berikan.

Urutan kerja:
1. Audit proyek.
2. Implementasikan perubahan visual dan komposisi.
3. Implementasikan audio decoder, PCM mixer, AAC encoder, dan muxing.
4. Implementasikan UI pemilihan input, slider volume, preview, ekspor, progress, dan cancel.
5. Implementasikan penyimpanan MediaStore.
6. Jalankan build dan pengujian.
7. Perbaiki bug yang ditemukan.
8. Buat ZIP final source project.
9. Laporkan hasil secara jujur berdasarkan apa yang benar-benar sudah diuji.

Jika ada detail teknis yang tidak ditentukan, pilih perilaku paling aman dan konsisten dengan spesifikasi di atas. **Jangan mengubah kebutuhan produk atau menghilangkan fitur wajib hanya karena implementasinya sulit.** Jika ada keterbatasan Android API/perangkat yang nyata, jelaskan masalah spesifiknya, berikan solusi teknis yang layak, dan jangan diam-diam mengganti perilaku yang diminta.

Prioritas tertinggi adalah hasil ekspor yang benar: komposisi 16:9 sesuai aturan, durasi tepat mengikuti video, PNG transparan full-canvas, dan audio asli + musik tambahan benar-benar tercampur serta sinkron.


---

# 15. OPTIMASI PERFORMA, RAM, BATERAI, DAN UKURAN APLIKASI

Bagian ini wajib diterapkan. Aplikasi harus tetap ringan dan stabil saat memproses video panjang atau file Full HD. Jangan mengorbankan ketepatan hasil demi sekadar mempercepat ekspor.

## 15.1 Prinsip utama

- Gunakan Android MediaCodec untuk decoding dan encoding hardware bila tersedia dan kompatibel.
- Gunakan OpenGL ES untuk komposisi video dan PNG di GPU.
- Gunakan MediaExtractor untuk membaca track media tanpa memuat seluruh file ke memori.
- Proses audio dalam potongan PCM berukuran terbatas.
- Proses video frame demi frame atau melalui pipeline buffer terbatas.
- Jangan pernah membaca seluruh video, seluruh audio, atau seluruh hasil PCM ke RAM.
- Jangan membuat Bitmap baru untuk setiap frame.
- Jangan melakukan konversi warna atau resize berulang yang tidak diperlukan.
- Jangan menyimpan frame sementara ke disk kecuali ada alasan teknis yang kuat.
- Hindari dependency besar jika Android framework sudah menyediakan fungsi yang dibutuhkan.

## 15.2 Pengelolaan memori

Wajib:
- Decode PNG satu kali saat input dipilih atau sebelum ekspor.
- Validasi dimensi PNG sebelum mengalokasikan bitmap/texture besar.
- Upload PNG ke GPU satu kali per sesi ekspor, lalu gunakan texture yang sama untuk setiap frame.
- Gunakan buffer audio yang dapat digunakan ulang.
- Batasi jumlah frame/buffer yang sedang antre.
- Lepaskan bitmap CPU setelah texture berhasil dibuat jika tidak lagi diperlukan.
- Lepaskan texture, EGL context, Surface, codec, extractor, muxer, dan buffer pada jalur sukses, gagal, maupun cancel.
- Jangan menyimpan referensi Activity di worker/export engine dalam waktu lama.
- Jangan mengirim objek Bitmap besar berulang kali melalui StateFlow, LiveData, Intent, atau callback UI.

Tangani `OutOfMemoryError` secara defensif pada titik alokasi yang relevan. Jangan mengandalkan menangkap error tersebut sebagai strategi normal. Tampilkan pesan yang menyarankan pengguna menutup aplikasi lain atau memilih PNG dengan dimensi yang wajar.

## 15.3 Pengelolaan GPU dan rendering

- Gunakan satu pipeline EGL yang konsisten untuk render ke input Surface encoder.
- Jangan membuat EGL context atau shader ulang pada setiap frame.
- Kompilasi shader sekali dan periksa status compile/link.
- Gunakan texture sampling dan alpha blending yang benar.
- Pilih format texture yang mempertahankan alpha PNG.
- Hindari readback GPU ke CPU pada setiap frame.
- Hindari `glFinish()` setiap frame karena dapat memaksa GPU/CPU menunggu; gunakan sinkronisasi yang memang diperlukan.
- Pastikan setiap frame dirender dengan viewport 1920×1080.
- Periksa error OpenGL pada fase inisialisasi dan saat kegagalan, bukan menambahkan pemeriksaan mahal yang tidak perlu pada setiap operasi normal.
- Tangani perangkat dengan keterbatasan EGL/OpenGL ES secara eksplisit. Jangan diam-diam menghasilkan video hitam.

## 15.4 Pengelolaan codec

- Enumerasi codec encoder/decoder yang tersedia melalui `MediaCodecList`.
- Utamakan encoder H.264 hardware yang mendukung 1920×1080 dan frame rate sumber.
- Periksa `VideoCapabilities.areSizeAndRateSupported()` atau pemeriksaan capability yang setara sebelum konfigurasi.
- Jangan menganggap semua perangkat mendukung bitrate, profile, level, color format, atau frame rate yang sama.
- Gunakan color format input Surface untuk encoder video bila didukung.
- Jangan memaksa codec software yang berat tanpa memberi tahu pengguna.
- Jika konfigurasi yang diminta tidak didukung, coba fallback yang telah divalidasi (misalnya bitrate lebih rendah atau frame rate yang didukung) dan tampilkan informasi perubahan tersebut.
- Jangan menurunkan resolusi output dari 1920×1080 secara diam-diam. Jika perangkat benar-benar tidak mampu, hentikan ekspor dengan pesan yang jelas.
- Periksa `MediaFormat` output sebenarnya. Jangan mengasumsikan codec menghasilkan format persis seperti konfigurasi yang diminta.
- Pastikan seluruh output buffer di-drain agar encoder tidak deadlock.

## 15.5 Pengaturan kualitas default

Gunakan nilai awal berikut sebagai konfigurasi awal yang dapat disesuaikan berdasarkan capability perangkat:

| Parameter | Nilai awal yang disarankan |
|---|---|
| Resolusi | 1920×1080 (wajib) |
| Video codec | H.264/AVC |
| Video bitrate | 8 Mbps |
| Frame rate | Mengikuti sumber jika didukung |
| Audio codec | AAC-LC |
| Audio sample rate | 48 kHz jika didukung kedua sumber dan encoder |
| Audio channel | Stereo jika sumber/encoder mendukung; selain itu pertahankan atau konversi secara benar |
| Audio bitrate | 192 kbps untuk stereo sebagai nilai awal |
| Audio PCM internal | PCM signed 16-bit atau float dengan headroom yang cukup |
| Background | #000000 |

Catatan:
- Nilai bitrate adalah default awal, bukan jaminan kualitas identik pada semua jenis video.
- Jangan mengubah resolusi output.
- Jika frame rate sumber tidak didukung, gunakan fallback yang terdokumentasi dan pertahankan durasi/timestamp.
- Jangan meng-upsample audio secara tidak perlu jika semua sumber dan encoder dapat memakai sample rate yang lebih rendah secara konsisten. Pilih satu format mixing yang kompatibel dan jelaskan keputusan implementasinya.

## 15.6 Batas antrean dan backpressure

- Jangan decode video jauh lebih cepat daripada encoder dapat menerima frame.
- Jangan decode audio tanpa batas jika encoder audio tertinggal.
- Gunakan antrean terbatas dan mekanisme backpressure.
- Hindari deadlock ketika video menunggu audio atau sebaliknya.
- Progres ekspor harus berdasarkan timestamp media yang sudah diproses dibanding durasi sumber, bukan berdasarkan waktu dinding.
- Progres tidak boleh melompat ke 100% sebelum semua track selesai di-encode, di-drain, di-mux, dan file berhasil ditutup.
- Jika estimasi progres tidak tersedia, tampilkan indikator proses berjalan tanpa angka palsu.

## 15.7 Suhu dan baterai

- Jangan membuat busy loop ketika menunggu input/output codec.
- Gunakan timeout yang masuk akal dan tangani status try-again-later.
- Jangan menjalankan pekerjaan CPU/GPU yang tidak diperlukan saat aplikasi idle.
- Tampilkan peringatan singkat bahwa ekspor video Full HD dapat memakai baterai dan membuat perangkat hangat.
- Jangan memaksa layar menyala terus kecuali diperlukan dan dijelaskan.
- Jika perangkat mengalami thermal throttling, biarkan sistem mengatur performa; jangan mencoba mengakali thermal management Android.
- Jangan membatalkan ekspor hanya karena perangkat hangat, kecuali ada kondisi fatal atau kebijakan keselamatan perangkat.

## 15.8 Ukuran APK dan dependency

- Prioritaskan Android SDK/AndroidX yang benar-benar dibutuhkan.
- Hindari membawa FFmpeg binary besar jika pipeline native sudah memenuhi kebutuhan.
- Jangan menambahkan library hanya untuk fungsi sederhana yang tersedia di Android framework.
- Aktifkan minification/shrinking untuk release hanya setelah memastikan aturan keep codec/reflection yang diperlukan benar.
- Jangan menghapus ABI atau resource yang diperlukan tanpa pengujian.
- Catat dependency baru, alasan penambahan, ukuran dampak, dan lisensinya.
- Jangan memasukkan file media uji besar ke APK atau source ZIP final.

---

# 16. KETENTUAN TEKNIS DETAIL AGAR IMPLEMENTASI TIDAK AMBIGU

Bagian ini mengikat semua keputusan yang sebelumnya berpotensi ditafsirkan berbeda.

## 16.1 Aturan geometri video dan batas kanvas

- Ukuran kanvas output selalu 1920×1080 landscape.
- Video diskalakan secara seragam agar lebar efektifnya tepat 1920 piksel.
- Perhitungan harus memakai dimensi tampilan efektif setelah metadata rotasi diterapkan.
- Titik tengah video disejajarkan dengan titik tengah vertikal kanvas.
- Tidak ada crop yang dilakukan pada sumber sebelum komposisi.
- Kanvas output adalah batas fisik hasil akhir. Karena itu, jika video portrait atau sangat tinggi setelah diperbesar agar memenuhi lebar 1920, bagian yang secara geometris berada di luar batas atas/bawah kanvas tidak mungkin tersimpan dalam output 1920×1080. Bagian tersebut terpotong oleh batas kanvas, bukan oleh fitur crop atau crop editor.
- Jangan mengecilkan video portrait agar seluruh tingginya masuk ke kanvas, karena itu akan melanggar ketentuan memenuhi lebar.
- Jangan mengubah aturan ini menjadi fit/contain seluruh video.
- Preview harus memperlihatkan batas kanvas final sehingga pengguna memahami bagian yang berada di luar kanvas.

## 16.2 Aturan geometri PNG

- PNG diperlakukan sebagai satu desain overlay yang ditempatkan pada kanvas 1920×1080.
- Untuk PNG rasio 16:9, gunakan ukuran penuh 1920×1080.
- Untuk PNG rasio selain 16:9, gunakan contain proporsional dan pusatkan pada kanvas.
- Jangan melakukan stretch atau crop PNG.
- Area kanvas yang tidak ditempati PNG harus transparan, bukan diisi warna.
- Jangan menganggap ekstensi `.png` menjamin adanya alpha. Periksa konfigurasi bitmap/alpha secara aktual.
- Jika PNG sangat besar, lakukan decode/texture upload dengan batas memori yang masuk akal tanpa mengubah komposisi akhir secara tidak terduga.

## 16.3 Audio: perilaku saat salah satu atau kedua sumber tidak tersedia

| Audio asli video | Musik tambahan | Hasil |
|---|---|---|
| Ada | Ada | Mix keduanya menjadi satu track AAC |
| Ada | Tidak dipilih | Pertahankan audio asli sebagai satu track AAC |
| Tidak ada | Ada | Gunakan musik tambahan sebagai satu track AAC |
| Tidak ada | Tidak dipilih | Ekspor video tanpa audio track dan beri tahu pengguna |

Jika satu sumber dipilih tetapi gagal didekode, jangan diam-diam mengabaikannya. Batalkan ekspor atau minta pengguna mengonfirmasi untuk melanjutkan tanpa sumber tersebut. Default yang aman adalah membatalkan dan menampilkan penyebab.

## 16.4 Aturan timestamp dan loop musik

- Tentukan timeline output berdasarkan timestamp video sumber yang sudah dinormalisasi terhadap waktu mulai video.
- Tangani PTS awal non-zero dengan menggeser timeline secara konsisten.
- Musik tambahan dimulai pada waktu output 0.
- Saat musik mencapai akhir, reset extractor/decoder musik ke awal dan lanjutkan decoding untuk loop berikutnya.
- Pastikan seek/reset decoder benar-benar berhasil sebelum melanjutkan loop.
- Jangan menambahkan durasi jeda berdasarkan waktu pemrosesan.
- Jika musik memiliki durasi sangat pendek, loop tetap harus berjalan tanpa busy loop atau penumpukan buffer.
- Hentikan pembacaan musik ketika timeline output mencapai akhir video.
- Audio mix harus memiliki jumlah sampel yang sesuai dengan durasi video, dengan toleransi pembulatan frame AAC.
- Hindari membuat audio output lebih panjang hanya karena encoder mengeluarkan padding AAC. Gunakan metadata encoder/muxer yang benar dan dokumentasikan toleransi durasi.
- Pastikan audio tidak bergeser secara bertahap (drift) terhadap video.

## 16.5 Gain, clipping, dan headroom

- Konversi slider persen ke gain linear: `gain = percent / 100.0`.
- Terapkan gain secara terpisah pada setiap sumber sebelum dijumlahkan.
- Jangan menaikkan sumber yang volumenya sudah di bawah 100%.
- Gunakan strategi clipping yang tidak menimbulkan distorsi keras. Pilihan utama adalah headroom/mixing float dan pembatasan puncak secara halus atau saturasi aman pada tahap konversi PCM, tanpa auto-normalization.
- Jangan mengubah nilai slider yang ditampilkan kepada pengguna.
- Jika kedua sumber 100% dan sinyal gabungannya melewati batas maksimum, cegah clipping digital. Jelaskan dalam dokumentasi internal bahwa puncak gabungan dapat lebih rendah dari penjumlahan matematis karena perlindungan clipping.
- Jangan memakai kompresor, limiter agresif, equalizer, atau ducking otomatis.

## 16.6 Hak akses dan privasi file

- Semua proses media dilakukan lokal di perangkat.
- Jangan mengunggah video, PNG, musik, metadata, atau hasil ekspor ke server.
- Jangan meminta akses seluruh penyimpanan jika Storage Access Framework cukup.
- Jangan mencatat nama lengkap/path pribadi file ke log produksi jika tidak diperlukan.
- Jangan mengirim URI input ke analytics atau layanan pihak ketiga.
- Jelaskan permission yang diminta dan gunakan permission minimum.
- Jangan meminta internet permission jika tidak ada fitur jaringan yang benar-benar diperlukan.

---

# 17. STATUS EKSPOR, CANCEL, DAN PEMULIHAN

## 17.1 Status ekspor yang eksplisit

Gunakan state yang jelas, minimal:
- `Idle`
- `Validating`
- `Preparing`
- `Exporting`
- `Finalizing`
- `Completed`
- `Cancelled`
- `Failed`

UI harus menampilkan status yang sesuai. Jangan mengubah status menjadi `Completed` sebelum muxer ditutup dengan sukses dan MediaStore item dipublikasikan.

## 17.2 Cancel yang benar-benar aman

Saat pengguna menekan Batalkan:
1. Tandai cancellation token.
2. Hentikan penerimaan input baru.
3. Hentikan loop decode/render/mix secara terkoordinasi.
4. Kirim EOS atau hentikan codec sesuai keadaan pipeline agar tidak deadlock.
5. Drain atau hentikan encoder dengan prosedur aman.
6. Stop muxer hanya jika sudah berhasil start.
7. Release seluruh resource.
8. Hapus output MediaStore yang masih pending.
9. Perbarui UI menjadi `Cancelled`.

Cancel tidak boleh hanya menyembunyikan progress dialog sementara proses tetap berjalan di belakang.

## 17.3 Kegagalan dan retry

- Setiap tahap harus dapat mengembalikan error terstruktur yang menjelaskan tahap dan penyebab.
- Jangan mengulang operasi tanpa batas.
- Retry hanya untuk kondisi yang memang dapat dipulihkan.
- Jika codec atau EGL gagal, lakukan cleanup penuh sebelum retry.
- Jika retry tidak aman, minta pengguna memulai ekspor baru.
- Jangan menimpa file ekspor sebelumnya.
- Gunakan nama output unik agar hasil lama tidak hilang.

## 17.4 Pemulihan saat aplikasi ditutup

- Tentukan secara eksplisit apakah ekspor dijalankan hanya selama Activity aktif atau melalui foreground service.
- Untuk ekspor yang harus bertahan ketika UI ditutup, gunakan foreground service dengan notifikasi yang benar dan deklarasi Android yang sesuai.
- Jika tidak memakai foreground service, batalkan/bersihkan proses secara aman saat proses aplikasi dihentikan dan jangan mengklaim ekspor berlanjut.
- Jangan menyimpan state palsu yang membuat UI mengira ekspor masih berjalan padahal proses sudah mati.
- Setelah aplikasi dibuka kembali, deteksi dan bersihkan output pending milik aplikasi yang tertinggal jika aman dilakukan.

---

# 18. KOMPATIBILITAS PERANGKAT DAN BATASAN ANDROID

- `minSdk 29` harus tetap dipertahankan kecuali ada alasan teknis yang benar-benar kuat.
- Uji pada API 29 dan API terbaru yang tersedia di lingkungan.
- Jangan mengasumsikan semua perangkat Android memiliki codec hardware H.264/AAC dengan capability yang sama.
- Jangan mengasumsikan semua perangkat mendukung ukuran texture maksimum yang sama.
- Periksa batas maksimum texture sebelum upload PNG.
- Periksa kemampuan encoder untuk 1920×1080 dan frame rate target.
- Tangani input HEVC, VP9, AV1, atau format lain berdasarkan codec yang tersedia; jika tidak didukung, beri pesan yang jelas.
- Jangan menjanjikan semua format input dapat diproses pada semua perangkat.
- Untuk format audio yang tidak didukung, jelaskan format yang didukung perangkat dan minta pengguna memilih file lain.
- Pastikan output tetap MP4 H.264 meskipun input memakai codec berbeda.
- Uji minimal pada satu perangkat fisik jika tersedia. Emulator saja tidak cukup untuk menyimpulkan performa codec hardware perangkat nyata.
- Catat model perangkat, versi Android, codec yang dipakai, resolusi, frame rate, durasi file uji, dan hasil aktual saat pengujian perangkat fisik.

---

# 19. LOGGING, DIAGNOSTIK, DAN KUALITAS KODE

- Gunakan tag log konsisten, misalnya `MiniVideoOverlay`.
- Log tahapan pipeline, durasi, format track, codec yang dipilih, dan error teknis.
- Jangan log isi media, audio PCM, gambar, atau data pribadi.
- Jangan menelan exception tanpa log dan tanpa status error untuk UI.
- Jangan menggunakan `runBlocking` pada main thread.
- Jangan memakai polling cepat tanpa jeda untuk menunggu codec.
- Gunakan `try/finally` atau pola resource management yang menjamin release.
- Hindari global mutable state.
- Pisahkan model konfigurasi, pipeline media, penyimpanan, dan UI.
- Tambahkan komentar pada bagian yang rawan bug: timestamp, EOS, muxer lifecycle, EGL lifecycle, alpha blending, dan audio loop.
- Hapus kode lama yang benar-benar tidak dipakai setelah memastikan tidak ada referensi.
- Jangan mempertahankan implementasi lama yang bertentangan dengan spesifikasi baru hanya demi kompatibilitas visual.
- Jangan menambahkan TODO untuk fitur wajib.

---

# 20. PENGUJIAN TAMBAHAN DAN UJI STRES

Selain matriks pengujian pada Bagian 10, lakukan pengujian berikut bila bahan uji dan perangkat tersedia:

1. Video berdurasi 5 detik, 1 menit, dan 10 menit.
2. Video 720p dan 1080p.
3. Video 30 FPS dan 60 FPS jika perangkat mendukung.
4. Video dengan rotasi metadata 90°, 180°, dan 270°.
5. PNG 1920×1080 dengan alpha penuh, alpha sebagian, dan tanpa alpha.
6. PNG dengan rasio bukan 16:9.
7. PNG dengan dimensi sangat besar untuk menguji validasi batas memori.
8. Musik 5 detik untuk menguji loop berulang.
9. Musik lebih panjang daripada video.
10. Audio mono dan stereo.
11. Audio sample rate berbeda.
12. Video tanpa audio.
13. Musik tanpa audio track valid atau file rusak.
14. Pengguna menekan cancel saat persiapan, decoding, encoding, dan finalisasi.
15. Ruang penyimpanan hampir penuh.
16. Aplikasi masuk background ketika ekspor berjalan.
17. Perangkat dengan codec hardware berbeda jika tersedia.

Periksa:
- Tidak ada crash.
- Tidak ada file pending yang tertinggal.
- Tidak ada output hitam akibat kegagalan texture/Surface.
- Tidak ada alpha PNG yang berubah menjadi kotak opak.
- Tidak ada audio hilang tanpa pesan.
- Tidak ada drift sinkronisasi yang terus bertambah.
- Penggunaan RAM tidak meningkat tanpa batas sepanjang durasi video.
- UI tetap responsif.
- Progress dan cancel sesuai keadaan sebenarnya.

---

# 21. FORMAT LAPORAN PENYELESAIAN CLAUDE

Laporan akhir wajib menggunakan format berikut dan harus berdasarkan hasil nyata:

## A. Ringkasan
Jelaskan secara singkat apa yang diubah.

## B. Fitur
Untuk setiap fitur, beri status:
- Selesai dan diuji.
- Selesai tetapi belum diuji pada perangkat.
- Belum selesai.
- Tidak dapat dilakukan, beserta alasan teknis.

## C. Build
Cantumkan perintah Gradle yang dijalankan, varian build, dan hasilnya. Sertakan error jika gagal.

## D. Pengujian
Buat tabel berisi:
- Kasus uji.
- Perangkat/API atau lingkungan.
- Hasil yang diharapkan.
- Hasil aktual.
- Status lulus/gagal/belum diuji.

## E. Artefak
Cantumkan nama dan lokasi ZIP source, APK debug, dan APK release jika tersedia. Pastikan file benar-benar dibuat dan dapat dibuka.

## F. Keterbatasan
Jelaskan semua batasan codec, perangkat, format input, lifecycle, atau pengujian.

## G. Cara menjalankan
Berikan langkah membuka proyek, build, install, dan menguji aplikasi.

Dilarang menggunakan istilah “100% selesai”, “sempurna”, atau “sudah dites semua” jika tidak didukung hasil uji yang lengkap.

---

# 22. CHECKLIST FINAL SEBELUM MENYERAHKAN PROYEK

- [ ] Tidak ada fitur wajib yang hanya berupa placeholder.
- [ ] Tidak ada tombol yang tidak memiliki fungsi.
- [ ] Tidak ada crash compile.
- [ ] Tidak ada TODO untuk fitur inti.
- [ ] Tidak ada output 9:16.
- [ ] Tidak ada durasi tetap 15 detik.
- [ ] Tidak ada crop/stretch video.
- [ ] Video memenuhi lebar kanvas sesuai aturan.
- [ ] PNG 16:9 menutupi seluruh kanvas.
- [ ] PNG non-16:9 menggunakan contain transparan dan diberi peringatan.
- [ ] Alpha PNG benar.
- [ ] Audio asli dan musik benar-benar di-mix menjadi satu track.
- [ ] Slider volume bekerja dan nilai 0% benar-benar mute.
- [ ] Loop musik tidak menambah durasi output.
- [ ] Durasi video output sesuai sumber.
- [ ] PTS audio/video terjaga.
- [ ] Output H.264 1920×1080.
- [ ] AAC tersedia jika ada sumber audio.
- [ ] File tersimpan di Movies/MiniVideoOverlay.
- [ ] File pending dibersihkan saat gagal/cancel.
- [ ] RAM dan antrean buffer dibatasi.
- [ ] Tidak ada Bitmap per frame.
- [ ] Tidak ada upload media ke cloud.
- [ ] Semua resource dilepas.
- [ ] Build dan pengujian dilaporkan jujur.
- [ ] ZIP source lengkap dan dapat dibuka.
- [ ] APK hanya disertakan jika benar-benar berhasil dibuat.
