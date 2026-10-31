# Mini Video Overlay

Aplikasi Android pribadi untuk menggabungkan **satu video vertikal** dan **satu foto overlay** menjadi satu file MP4.

## Spesifikasi yang ditetapkan

| Item | Nilai |
|---|---|
| Output | 720 × 1280, MP4, H.264/AVC |
| Rasio | 9:16 vertikal |
| Overlay | 640 × 640 pada X=40, Y=320 |
| Foto | Center-crop, tanpa distorsi |
| Video | Center-crop bila diperlukan |
| Durasi | Mengikuti video utama |
| Audio | Audio asli video dipertahankan bila tersedia |
| Penyimpanan | `Movies/MiniVideoOverlay` |
| Minimum Android | 10 / API 29 |

## Build APK tanpa PC atau Android Studio

Project ini dilengkapi GitHub Actions. GitHub akan menjalankan Gradle di cloud dan menyediakan APK debug untuk diunduh.

### Langkah melalui browser HP

1. Buat akun atau masuk ke [GitHub](https://github.com/).
2. Buat repository baru, misalnya `MiniVideoOverlay`. Pilih **Public** atau **Private** sesuai keinginan. Jangan centang opsi untuk membuat README, `.gitignore`, atau license tambahan.
3. Buka repository tersebut di browser.
4. Pilih **Add file → Upload files**.
5. Ekstrak ZIP project ini terlebih dahulu di HP menggunakan aplikasi pengelola file. Upload **isi folder `MiniVideoOverlay_cloud`**, bukan file ZIP-nya. Pastikan folder `.github/workflows` ikut terunggah. Jika folder tersembunyi tidak terlihat, aktifkan opsi tampilkan file tersembunyi di aplikasi pengelola file.
6. Commit/upload file ke branch `main`.
7. Buka tab **Actions** pada repository. Pilih workflow **Build Android APK**.
8. Tunggu sampai job selesai dengan tanda centang hijau.
9. Buka halaman run yang berhasil. Di bagian **Artifacts**, unduh `MiniVideoOverlay-debug-apk`.
10. Ekstrak artifact ZIP. Di dalamnya ada `app-debug.apk`. Buka APK tersebut untuk memasangnya di HP. Jika Android meminta izin instalasi dari sumber ini, izinkan browser/file manager yang digunakan.

### Jika workflow tidak berjalan otomatis

- Buka tab **Actions** dan aktifkan Actions jika GitHub meminta konfirmasi.
- Pilih workflow **Build Android APK**.
- Tekan **Run workflow**, pilih branch `main`, lalu jalankan.

## Catatan penting

- Workflow membangun **debug APK**. APK ini ditandatangani dengan debug key oleh Android Gradle Plugin dan dapat dipasang untuk pengujian pribadi.
- Ini bukan release APK final untuk distribusi publik.
- Artifact disimpan GitHub selama 14 hari. Unduh sebelum masa tersebut berakhir.
- Build cloud tidak membuktikan fungsi video sudah benar. Uji hasil ekspor di HP: foto persegi, potret, lanskap; video dengan dan tanpa audio; pastikan hasil 720 × 1280.
- APK belum diuji pada perangkat pengguna hanya karena proses cloud build berhasil.
- Workflow mengunduh Gradle/dependency yang dibutuhkan saat build. Pengguna tidak perlu memasang Android Studio atau Gradle di HP.

## Jika upload lewat browser HP

Jika tampilan GitHub mobile tidak menampilkan **Add file → Upload files**, aktifkan **Situs desktop** dari menu browser, lalu buka ulang repository.

## Build lokal (opsional)

Untuk pengguna yang memiliki komputer dan Android SDK, project menggunakan JDK 17 dan Gradle 8.7 dengan Android Gradle Plugin 8.5.2:

```bash
gradle --no-daemon :app:assembleDebug
```

APK debug akan berada di:

```text
app/build/outputs/apk/debug/app-debug.apk
```
