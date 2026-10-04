# Cara deploy STASIUN CUACA melalui GitHub

1. Hapus isi repository `Turus` yang lama agar tidak ada project ganda.
2. Upload seluruh isi project ini langsung ke root repository.
3. Pastikan struktur teratas adalah:

```text
.github/workflows/build-apk.yml
.github/workflows/release-apk.yml
app/
build.gradle
gradle.properties
settings.gradle
README.md
```

4. Commit ke branch `main`.
5. Buka tab **Actions**. Workflow **Build APK - STASIUN CUACA** akan berjalan otomatis.
6. Setelah selesai, buka hasil workflow lalu **Artifacts** → `STASIUN-CUACA-v1.2-debug` untuk mengambil APK.

Untuk membuat APK pada **GitHub Releases**, buat tag versi dan push:

```bash
git tag v1.2.0
git push origin v1.2.0
```

Workflow release akan membuat release dan melampirkan `app-debug.apk`.

API key tidak diletakkan di GitHub. Masukkan OpenAI API key, OpenWeather API key, dan Read API Key ThingSpeak melalui menu **Pengaturan** pada aplikasi.

APK release pada workflow ini adalah debug APK untuk pengujian/pemasangan langsung. Untuk distribusi resmi, buat release APK yang ditandatangani dengan keystore yang disimpan sebagai GitHub Secrets.


## Analisis riwayat
Aplikasi menganalisis riwayat ThingSpeak dan riwayat OpenWeather yang tersedia, termasuk pola kecepatan dan arah angin untuk indikasi risiko OPT. Jika histori OpenWeather tidak dapat diakses pada paket API pengguna, aplikasi memakai histori OpenWeather yang tersimpan di perangkat dari pembaruan sebelumnya.
