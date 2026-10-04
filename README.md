# STASIUN CUACA DESA TURUS

Aplikasi Android ringan/native untuk membaca data terbaru dan mengunduh histori data ThingSpeak.

## Fitur Dashboard
- Judul default **STASIUN CUACA** dan dapat diubah dari Pengaturan.
- Channel ID dapat diubah.
- Read API Key dapat diubah dan ditampilkan/disembunyikan.
- Membaca `feeds/last.json` dari ThingSpeak.
- 8 field = 8 kotak data.
- Hari, tanggal, dan jam WIB berjalan real-time.
- Waktu data terakhir dari ThingSpeak.
- Waktu terakhir aplikasi mengakses ThingSpeak.
- Status ONLINE/OFFLINE/CACHE di bagian bawah.
- Cache data terakhir untuk kondisi jaringan putus.
- Refresh manual dan refresh otomatis setiap 30 detik.
- Tanpa library UI pihak ketiga.

## Export CSV ThingSpeak

Menu **UNDUH DATA CSV** memiliki dua pilihan:

### 1. RENTANG WAKTU
Pengguna memilih tanggal mulai dan tanggal akhir. Aplikasi mengambil semua entry dalam rentang tersebut dan menggabungkannya menjadi satu file CSV.

Permintaan menggunakan endpoint:
`https://api.thingspeak.com/channels/<CHANNEL_ID>/feeds.csv`

dengan parameter `start`, `end`, `results=8000`, `timezone=Asia/Jakarta`, dan `api_key` untuk channel privat.

### 2. SELURUH HISTORI
Mode ini dirancang untuk mengambil histori ThingSpeak **lebih dari 8.000 entry** sampai data terbaru, tanpa berhenti pada batch pertama.

ThingSpeak mendokumentasikan batas maksimum **8.000 entry per request**. Karena itu aplikasi:
1. Memulai dari rentang waktu awal yang aman.
2. Meminta data dengan `results=8000`.
3. Bila respons berisi kurang dari 8.000 entry, batch ditulis ke file.
4. Bila respons tepat 8.000 entry, aplikasi menganggap rentang tersebut dapat terpotong oleh batas API, lalu membelah rentang waktu menjadi dua.
5. Pemecahan dilakukan berulang sampai setiap batch aman untuk diekspor.
6. Boundary antar-batch sengaja sedikit overlap dan `entry_id` yang sudah muncul disaring melalui cache ID terbatas agar tidak terjadi duplikasi.
7. Semua batch ditulis langsung ke file sementara, sehingga seluruh histori **tidak ditampung sebagai byte array besar di RAM**.
8. Setelah selesai, aplikasi meminta lokasi penyimpanan melalui Android **Create Document** dan menyimpan satu file CSV.

Nama file mode seluruh histori:
`STASIUN-CUACA-DESA-TURUS_SELURUH-HISTORI.csv`

Nama file mode rentang:
`STASIUN-CUACA-DESA-TURUS_YYYYMMDD_YYYYMMDD.csv`

## Catatan
- Untuk channel privat, aplikasi membutuhkan **Read API Key channel**, bukan Write API Key.
- Jika Channel ID/Read API Key salah, ThingSpeak dapat menolak akses.
- Jika interval sangat padat sampai 8.000 entry berada dalam rentang yang lebih kecil daripada resolusi waktu API (detik), aplikasi berhenti dengan pesan agar rentang tanggal dipersempit.
- Mode seluruh histori menggunakan batas awal `2000-01-01`. Ini dimaksudkan untuk mencakup histori channel modern tanpa membutuhkan User API Key untuk membaca waktu pembuatan channel.
- File sementara berada di cache aplikasi dan dihapus setelah berhasil disimpan.

## Build
Gunakan Android Studio dengan Android SDK Platform 36 dan Android Gradle Plugin 8.13.0 atau lebih baru. Jalankan `assembleDebug` untuk membuat APK debug.

## Referensi API
Dokumentasi resmi ThingSpeak/MathWorks:
- Read Data: `/channels/<channel_id>/feeds.<format>` dengan `results` maksimum 8.000 dan parameter `start`/`end`.
- Read Settings: `/channels/<channel_id>.<format>`.


## Penyesuaian v1.2

- Judul default dashboard menjadi **STASIUN CUACA** dan dapat diubah dari Pengaturan.
- Nama 8 field otomatis mengikuti nama field pada pengaturan channel ThingSpeak. Nama tampilan dapat dioverride per field tanpa mengubah data sumber.
- Satuan setiap field dapat diatur sendiri di aplikasi.
- Jumlah angka di layar dapat dipilih 0, 1, atau 2 angka di belakang koma; nilai CSV tetap tidak diubah.
- Ditambahkan **Saran AI** berbasis data pengukuran terbaru. AI dipanggil manual agar tidak berjalan setiap 30 detik. API key AI dimasukkan pengguna melalui Pengaturan.
- Prompt AI memprioritaskan bahasa Indonesia yang mudah dipahami, tindakan praktis petani, dan menghindari singkatan/istilah teknis yang tidak perlu.
- Ditambahkan pilihan komoditas/tanaman untuk memberi konteks pada saran AI.

### Catatan AI

Integrasi menggunakan OpenAI Responses API. OpenAI menyatakan Responses API adalah jalur API baru untuk integrasi respons model. API key bersifat rahasia; aplikasi ini menyimpannya di perangkat agar pengguna dapat menggunakannya tanpa menaruh key di source code. Untuk distribusi aplikasi ke banyak pengguna, arsitektur yang lebih aman adalah memakai server perantara sehingga API key tidak berada di APK.

## Info Aplikasi
- Versi aplikasi yang ditampilkan: **1.0** (versi rilis kode Android: 1.2).
- Pembuat: **Gani Cahyo H**.
- **Agroteknologi-Universitas Sebelas Maret**.

## Data tanah 7-in-1

Aplikasi mendukung input manual dan pembacaan sensor tanah 7-in-1 melalui USB/RS485 menggunakan USB-OTG. Setelah izin USB diberikan, pembacaan dilanjutkan otomatis. Untuk sensor pihak ketiga, alamat sensor dan register awal dapat disesuaikan. Parameter yang digunakan:
- Kelembapan tanah (%)
- Suhu tanah (°C)
- pH tanah
- Kadar garam tanah (**µS/cm**)
- Nitrogen, Fosfor, Kalium (mg/kg)

Untuk memudahkan pengguna, kadar garam tanah selalu ditampilkan dan diinput dalam **µS/cm**. Contoh: bila sensor menunjukkan **1500 µS/cm**, masukkan langsung **1500**. Nilai lama dari versi yang menggunakan mS/cm dikonversi otomatis ke µS/cm saat dibuka.

Aplikasi juga melakukan pemeriksaan kondisi tanah sederhana dan memberikan tindakan yang mudah dipahami petani. Nilai batas analisis merupakan panduan awal aplikasi dan tidak menggantikan analisis tanah laboratorium.


## v1.2 — Nama dan umur tanaman + PDF saran AI

- Pengguna dapat mengisi **nama tanaman** pada Pengaturan.
- Pengguna dapat mengisi **umur tanaman** dan memilih satuan **hari, bulan, atau tahun**.
- Nama dan umur tanaman otomatis menjadi bagian dari konteks yang dikirim ke OpenAI Responses API sehingga saran dapat disesuaikan dengan fase tanaman yang diinformasikan pengguna.
- Setelah Saran AI dibuat, tersedia tombol **SIMPAN HASIL AI (PDF)**.
- PDF berisi judul stasiun, nama dan umur tanaman, waktu data ThingSpeak, 8 data cuaca, 7 data tanah, hasil analisis/rekomendasi AI, dan catatan penggunaan.
- Penyimpanan PDF memakai dialog penyimpanan Android (**Create Document**), sehingga pengguna dapat memilih folder penyimpanan dan nama file tanpa membutuhkan izin penyimpanan khusus.
- PDF dibuat menggunakan `android.graphics.pdf.PdfDocument`, tanpa library PDF pihak ketiga.

## GPS, ET₀, dan OpenWeather

- **GPS HP**: menggunakan lokasi perangkat untuk lintang, bujur, dan ketinggian yang tersedia dari perangkat. Izin lokasi diminta hanya saat fitur GPS diaktifkan.
- **ET₀**: ditampilkan sebagai **mm/hari**. Aplikasi menghitung perkiraan dari suhu, rentang suhu harian, lintang, dan hari dalam tahun. Bila data suhu hari berjalan tersedia dari ThingSpeak, aplikasi membentuk rentang suhu minimum-maksimum dari data yang dibaca; bila OpenWeather aktif, rentang suhu dari OpenWeather dipakai.
- **OpenWeather**: data eksternal menjadi data pelengkap. Bila opsi **OpenWeather mengisi data yang kosong** aktif dan field ThingSpeak kosong, aplikasi dapat menampilkan nilai cuaca yang sesuai dari OpenWeather dan menandainya sebagai sumber OpenWeather. Data yang tersedia dapat meliputi suhu, kelembapan, tekanan, angin, hujan 1 jam, tutupan awan, jarak pandang, dan kondisi cuaca.
- **Pengaturan**: GPS, OpenWeather, mode data pelengkap, ET₀, API key OpenWeather, dan interval pembaruan dapat diubah dari Pengaturan.
- **Saran AI** menerima konteks nama tanaman, umur tanaman, data tanah, ET₀, lokasi GPS, serta data pelengkap OpenWeather.

## Deploy melalui GitHub

Repository ini sudah disiapkan untuk GitHub Actions. Project Android harus berada langsung di root repository, dengan `app/`, `build.gradle`, `settings.gradle`, dan `.github/workflows/` pada tingkat teratas.

### Build otomatis

Setiap push ke branch `main` akan menjalankan `Build APK - STASIUN CUACA`. Hasil `app-debug.apk` tersedia pada **Actions → workflow run → Artifacts**.

### Membuat APK pada GitHub Releases

Buat tag, misalnya `v1.2.0`, lalu push tag tersebut ke GitHub. Workflow `Release APK - STASIUN CUACA` akan membangun APK dan membuat GitHub Release berisi `app-debug.apk`.

Contoh:

```bash
git add .
git commit -m "STASIUN CUACA v1.2"
git push origin main
git tag v1.2.0
git push origin v1.2.0
```

### API key

Jangan menyimpan OpenAI API Key, OpenWeather API Key, atau Read API Key ThingSpeak di repository. Semua key dimasukkan oleh pengguna melalui **Pengaturan** di aplikasi. File `.gitignore` juga mencegah file keystore/signing yang umum ikut terunggah.

### Hasil build

Workflow menggunakan JDK 17, Android SDK 36, dan Gradle 8.13 sesuai konfigurasi project. GitHub Actions menyiapkan JDK/Gradle lalu menjalankan build dan mengunggah hasil build sebagai artifact.

APK yang dibuat oleh workflow `release-apk.yml` adalah **debug APK**, sehingga cocok untuk pengujian dan pemasangan langsung. Untuk distribusi Play Store, nantinya perlu build release yang ditandatangani dengan keystore yang disimpan sebagai GitHub Secrets.


## Audit fitur yang dipertahankan

Versi GitHub final ini mempertahankan seluruh fitur aplikasi yang diminta pada pengembangan sebelumnya: dashboard 8 field ThingSpeak; nama field otomatis dari channel; pengaturan nama/satuan/angka desimal; status online/offline/cache; waktu WIB; refresh otomatis dan manual; unduh CSV berdasarkan rentang tanggal; unduh seluruh histori ThingSpeak dengan pemecahan otomatis di batas 8.000 entry; nama dan umur tanaman; analisis tanah 7-in-1; input manual atau USB/RS485; kadar garam tanah dalam µS/cm; GPS HP untuk lintang, bujur, dan ketinggian; ET₀ estimasi; OpenWeather sebagai data pelengkap dan pengisi field cuaca yang kosong; Saran AI manual dengan bahasa sederhana untuk petani; ekspor hasil Saran AI ke PDF; info aplikasi; dan GitHub Actions untuk build serta release APK.

OpenAI API key, OpenWeather API key, dan Read API Key ThingSpeak tetap diisi melalui Pengaturan, bukan disimpan di repository.


## Analisis riwayat
Aplikasi menganalisis riwayat ThingSpeak dan riwayat OpenWeather yang tersedia, termasuk pola kecepatan dan arah angin untuk indikasi risiko OPT. Jika histori OpenWeather tidak dapat diakses pada paket API pengguna, aplikasi memakai histori OpenWeather yang tersimpan di perangkat dari pembaruan sebelumnya.
