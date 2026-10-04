# STASIUN CUACA

Aplikasi Android native untuk membaca data terbaru dan histori ThingSpeak, dilengkapi data lahan, riwayat pemupukan, riwayat pengendalian organisme pengganggu tanaman (OPT), serta analisis AI untuk membantu tindakan lapang.

## Dashboard
- Judul default **STASIUN CUACA** dan dapat diubah melalui Pengaturan.
- Channel ID dan Read API Key ThingSpeak dapat diubah.
- Read API Key dapat ditampilkan/disembunyikan.
- 8 field tampil dalam 8 kotak. Nama field otomatis mengikuti nama channel ThingSpeak.
- Nama tampilan dan satuan tiap field dapat disesuaikan dari Pengaturan tanpa mengubah data asli ThingSpeak.
- Jumlah angka di layar dapat diatur 0, 1, atau 2 angka di belakang koma. Maksimum 2.
- Waktu lokal WIB, waktu data ThingSpeak, waktu akses terakhir, cache, refresh manual, dan refresh otomatis 30 detik.
- Menu unduh CSV tetap mempertahankan rentang tanggal dan seluruh histori >8.000 entry.

## Data lahan & agronomi

Menu **DATA LAHAN & AGRONOMI** menyimpan:
- Jenis tanaman dan umur tanaman (bulan).
- Luas lahan (ha).
- pH tanah.
- N, P, K tersedia (mg/kg, sesuai hasil uji).
- EC tanah (uS/cm).
- Kelembapan tanah (%).
- Target nitrogen (N), fosfor sebagai P2O5, kalium sebagai K2O, dolomit, pupuk kandang, dan POC.

### Riwayat pemupukan

Setiap catatan menyimpan tanggal, produk, jenis pupuk, dosis per ha, satuan, kadar N/P2O5/K2O, cara pemberian, fase/umur tanaman, tanaman, dan catatan hasil. Maksimal 200 catatan tersimpan.

### Perhitungan dosis pupuk berikutnya

Perhitungan dibuat transparan agar bisa diaudit oleh pengguna:
1. Target dosis/hara per hektar berasal dari rekomendasi spesifik tanaman dan lokasi yang menjadi acuan pengguna.
2. Riwayat pupuk untuk tanaman yang sama dalam 180 hari terakhir dikreditkan.
3. Sisa kebutuhan dihitung sebagai target dikurangi hara yang sudah tercatat, dengan batas bawah nol.
4. Untuk pupuk tunggal, aplikasi mengonversi sisa hara memakai kadar produk yang dicatat; contoh bawaan adalah urea 46% untuk nitrogen, SP-36 36% untuk P2O5, dan KCl 60% untuk K2O.
5. Dosis total lapangan dihitung dari dosis per ha dikalikan luas lahan.
6. Dolomit, pupuk kandang, dan POC dihitung dari target dosis/volume yang dimasukkan dan riwayat produk yang tercatat.

Aplikasi **tidak mengarang target pupuk baru** hanya dari pH, N/P/K, EC, atau kelembapan. Parameter tanah dipakai untuk pemeriksaan kondisi dan bahan analisis AI. Kebutuhan dolomit tidak boleh dibuat hanya dari pH karena kebutuhan pengapuran memerlukan dasar rekomendasi/analisis kemasaman yang memadai.

Pemupukan mengikuti prinsip **4 tepat: tepat jenis, tepat dosis, tepat waktu, tepat cara**. Kementerian Pertanian menekankan pemupukan berimbang berdasarkan kebutuhan tanaman dan status hara tanah.

## Riwayat pengendalian OPT

Catatan OPT dapat menyimpan sasaran, proporsi/luas serangan, gejala, metode manual/mekanis/kimia, produk, bahan aktif, dosis label, satuan dosis, konsentrasi, volume air, interval minimum label, status pengecekan label, cara aplikasi, hasil, dan catatan.

Untuk metode kimia, aplikasi memeriksa **5 tepat sesuai kebutuhan pengguna: tepat sasaran, tepat jenis, tepat dosis/konsentrasi, tepat waktu, tepat cara** dan memberi pengingat tambahan untuk mengecek **mutu, label, dan status pendaftaran**. Sumber teknis pemerintah Indonesia menjelaskan prinsip 6 tepat pestisida yang memasukkan unsur mutu; aplikasi sengaja tetap menampilkan 5 tepat yang diminta pengguna sambil menjaga pemeriksaan mutu/label sebagai pengaman.

Aplikasi tidak mengarang dosis pestisida. Bila dosis label belum dicatat, rekomendasi harus kembali ke label produk yang terdaftar. Riwayat bahan aktif yang sama pada sasaran yang sama dalam sekitar 30 hari diberi peringatan agar tidak diulang otomatis tanpa evaluasi hasil dan petunjuk label.

## Analisis AI agronomi

Tombol **ANALISIS LENGKAP DENGAN AI** membaca data ThingSpeak terbaru, jenis dan umur tanaman, luas lahan, pH, N/P/K tersedia, EC, kelembapan, target hara, hasil hitung, riwayat pupuk, dan riwayat OPT. AI diarahkan untuk:
- memberikan langkah yang bisa dikerjakan petani di lapangan;
- menjelaskan istilah teknis dengan kata sederhana;
- tidak mengarang hasil uji tanah, dosis pestisida, atau kebutuhan dolomit;
- menjaga angka dosis pupuk tetap konsisten dengan hasil hitung aplikasi;
- menyebutkan data yang masih kurang sebelum membuat keputusan yang terlalu pasti;
- memberi urutan tindakan: apa yang dilakukan sekarang, pemupukan berikutnya, pemeriksaan OPT, dan kapan dievaluasi lagi.

AI dipanggil **manual**, bukan setiap refresh ThingSpeak. Integrasi menggunakan **OpenAI Responses API**. Respons AI diminta tanpa penyimpanan respons (`store=false`) untuk mengurangi penyimpanan data percakapan pada sisi API.

## Ekspor CSV ThingSpeak

Fitur CSV dari versi sebelumnya dipertahankan. Mode **RENTANG WAKTU** mengambil rentang tanggal yang dipilih. Mode **SELURUH HISTORI** memecah rentang saat diperlukan dan menggabungkan data menjadi satu CSV tanpa menampung seluruh histori sebagai byte array besar di RAM. Batas 8.000 entry per request tetap ditangani.

## Info aplikasi
- Versi aplikasi yang ditampilkan: **1.0**
- Pembuat: **Gani Cahyo H**
- **Agroteknologi-Universitas Sebelas Maret**
- **Created with OpenAI @2026**

## Toolchain build
- Android Gradle Plugin: **9.4.0**
- Gradle: **9.6.0**
- JDK/Java: **17 (Temurin)**
- compileSdk: **37 (Android 17)**
- targetSdk: **37**
- minSdk: **26**
- SDK Build Tools: **36.0.0**

AGP 9.4 secara resmi mendukung maksimum API 37, membutuhkan Gradle minimal 9.6 dan JDK 17, dengan Build Tools default 36.0.0. Android 17 adalah API level 37.

## Referensi teknis
- Android Developers: Android 17 dan behavior changes Android 17.
- Android Developers: Android Gradle Plugin 9.4.0 compatibility.
- OpenAI Developers: Responses API dan model GPT-6 Luna.
- Kementerian Pertanian Republik Indonesia: pemupukan berimbang dan prinsip 4 tepat.
- Kementerian Pertanian Republik Indonesia / repository pertanian: prinsip penggunaan pestisida 6 tepat dan pengendalian hama terpadu.
