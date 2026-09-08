---
title: Memasang dan menjalankan Porter
lang: id
translation_key: setup
language_name: Bahasa Indonesia
description: Unduh Porter dari GitHub Releases, lalu ikuti langkah berikut.
---
# Memasang dan menjalankan Porter
{: #install-and-start-porter }

Unduh Porter dari [GitHub Releases](https://github.com/d4rken-org/porter/releases), lalu ikuti langkah berikut.

## Pemasangan
{: #install }

1. Unduh APK Porter dari bagian **Assets** pada rilis. Arsip kode sumber ZIP dan TAR bukan aplikasi Android.
2. Buka APK di perangkat. Jika diminta Android, izinkan browser atau pengelola file memasang aplikasi dari sumber tersebut.
3. Buka Porter.

Untuk aplikasi yang mendukung Porter secara langsung, Porter saja sudah cukup. Jika hanya mendukung Shizuku, pasang juga **Porter Compatibility** dari rilis yang sama. Hapus Shizuku terlebih dahulu: pendamping tidak dapat dipasang bersamaan dengannya. Biarkan pendamping terpasang selama menggunakan aplikasi tersebut dengan Porter. Lihat [panduan kompatibilitas](/compatibility).

## Memilih cara menjalankan
{: #choose-how-to-start }

| Perangkat Anda | Metode |
| --- | --- |
| Android 11 atau lebih baru dengan proses debug nirkabel | [Proses debug nirkabel](#wireless-debugging) |
| Android 7.0 atau lebih baru dan komputer | [Proses debug USB](#with-a-computer) |
| Sudah di-root | [Root](#root) |

## Proses debug nirkabel
{: #wireless-debugging }

Anda memerlukan Wi-Fi dan Android 11 atau lebih baru. Beberapa produsen membatasi proses debug nirkabel; gunakan komputer jika fitur ini tidak tersedia.

1. Aktifkan **Opsi developer** di Setelan Android. Biasanya, buka **Tentang ponsel** dan ketuk **Nomor build** tujuh kali. Letaknya berbeda menurut perangkat.
2. Di Opsi developer, aktifkan **Proses debug USB** dan **Proses debug nirkabel**. Setujui permintaan otorisasi jaringan Android jika muncul.
3. Di Porter, ketuk **Penyambungan** pada bagian memulai melalui proses debug nirkabel. Izinkan notifikasi dan, jika diminta, akses perangkat di sekitar atau jaringan lokal.
4. Buka setelan **Proses debug nirkabel** Android, lalu ketuk **Sambungkan perangkat dengan kode penyambungan**. Biarkan dialog tetap terbuka.
5. Perluas notifikasi penyambungan Porter dan masukkan kode dari Android. Tunggu hingga penyambungan berhasil.
6. Kembali ke Porter dan ketuk **Mulai** pada bagian proses debug nirkabel.
7. Pastikan Porter menunjukkan bahwa layanan sedang berjalan.

Porter membiarkan setelan proses debug Android aktif saat berhenti. Anda dapat menonaktifkannya di Opsi developer ketika akses tersebut tidak lagi diperlukan.

Penyambungan biasanya cukup dilakukan sekali. Menjalankan layanan adalah langkah terpisah yang harus diulang setelah perangkat dimulai ulang. Jika Android melupakan penyambungan, ulangi langkah-langkah ini.

Jika Anda memilih dialog dalam aplikasi di **Setelan**, **Memulai Porter**, **Metode penyambungan**, tunggu sampai dialog Porter menemukan layanan penyambungan, lalu masukkan kode di sana. Jika diminta port, gunakan port penyambungan dari dialog kode Android, bukan port koneksi di layar utama proses debug nirkabel.

## Dengan komputer
{: #with-a-computer }

1. Pasang [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) dari Google di komputer.
2. Aktifkan Opsi developer dan **Proses debug USB** di perangkat Android.
3. Hubungkan perangkat dengan kabel USB data. Buka kuncinya dan setujui koneksi debug. Hanya izinkan komputer yang Anda percaya.
4. Buka terminal di folder Platform-Tools dan jalankan `adb devices`. Di Windows PowerShell gunakan `./adb.exe devices`; di macOS atau Linux gunakan `./adb devices` jika ADB tidak ada di PATH.
5. Di Porter, cari bagian memulai dengan komputer dan ketuk **Lihat perintah**. Jalankan perintah itu persis di komputer, dengan menyesuaikan awalan program `adb` seperti di atas jika diperlukan.
6. Pastikan Porter berjalan. Setelah itu kabel dapat dilepas.

Jika beberapa perangkat terhubung, sisipkan `-s DEVICE_SERIAL` tepat setelah `adb` dalam perintah. Gunakan nomor seri dari `adb devices` untuk perangkat yang menjalankan Porter.

Ambil perintah baru dari Porter setelah memperbarui atau memasangnya ulang. Jalur file dapat berubah. Jangan gunakan perintah yang disalin dari Shizuku atau pemasangan lain.

## Root
{: #root }

Metode ini untuk perangkat yang sudah memiliki akses root berfungsi. Memasang Porter tidak melakukan root pada perangkat.

1. Buka Porter dan ketuk **Mulai** pada bagian root.
2. Setujui permintaan Porter di pengelola root.
3. Pastikan Porter berjalan dan menunjukkan root sebagai mode layanan.

Hentikan layanan Porter sebelum beralih antara root dan akses debug.

## Mengizinkan aplikasi
{: #allow-an-app }

Buka aplikasi yang ingin digunakan, aktifkan integrasi Porter atau Shizuku, lalu setujui permintaan akses Porter. Hanya izinkan aplikasi tepercaya: aplikasi tersebut dapat menjalankan tugas dengan hak debug atau root Porter.

Untuk mencabut akses, ketuk **Aplikasi** di Porter dan nonaktifkan izin aplikasi terkait. Porter dan Shizuku menyimpan persetujuan secara terpisah.

Untuk menjeda akses semua aplikasi, nonaktifkan **Izinkan akses aplikasi** di bagian atas layar itu. Izin masing-masing aplikasi tetap tersimpan. Aktifkan kembali untuk melanjutkan akses. Perintah shell yang sudah dimulai dapat tetap berjalan selama jeda.

Jika aplikasi menyediakan pemilih layanan, pilih Porter. Ikuti petunjuk aplikasi tersebut. Jika perubahan memerlukan mulai ulang aplikasi, gunakan **Paksa berhenti** di Setelan aplikasi Android lalu buka kembali.

## Menghentikan Porter
{: #stop-porter }

Ketuk kartu yang menunjukkan Porter sedang berjalan, lalu pilih **Hentikan Porter**. Aplikasi yang terhubung kehilangan akses sampai Anda menjalankan Porter kembali.

## Memperbarui Porter
{: #update-porter }

Pasang APK baru di atas aplikasi yang ada, lalu jalankan Porter kembali. Jika menggunakan Porter Compatibility, perbarui dari rilis yang sama. Android memerlukan tanda tangan yang cocok untuk pembaruan; lihat [masalah pemasangan](/troubleshooting#android-wont-install-an-apk) jika ditolak.
