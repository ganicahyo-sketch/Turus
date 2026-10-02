# STASIUN CUACA DESA TURUS

Aplikasi Android ringan/native untuk membaca data terbaru dan mengunduh histori data ThingSpeak.

## Fitur Dashboard
- Judul STASIUN CUACA DESA TURUS.
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
