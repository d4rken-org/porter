---
title: Pemecahan masalah
lang: id
translation_key: troubleshooting
language_name: Bahasa Indonesia
description: Saat memakai akses debug, wajar jika Porter harus dijalankan lagi setelah perangkat dimulai ulang. Buka Porter dan gunakan metode menjalankan layanan. Penyambungan nirkabel sendiri tidak menjalankan layanan.
---
# Pemecahan masalah
{: #troubleshooting }

## Porter tidak berjalan
{: #porter-is-not-running }

Saat memakai akses debug, wajar jika Porter harus dijalankan lagi setelah perangkat dimulai ulang. Buka Porter dan gunakan [metode menjalankan layanan](/setup). Penyambungan nirkabel sendiri tidak menjalankan layanan.

## Mulai otomatis tidak berfungsi
{: #automatic-start-does-not-work }

Jalankan Porter secara manual sekali setelah pemasangan sebelum mengandalkan **Mulai saat boot**. Menjalankan melalui debug dengan sukses memberikan izin setelan Android untuk mulai otomatis berikutnya. Jika notifikasi menyebut `WRITE_SECURE_SETTINGS`, jalankan Porter menggunakan komputer, lalu coba lagi.

Mulai otomatis tetap bergantung pada Android yang menyediakan akses debug dan mengizinkan Porter berjalan di latar belakang. Jika gagal, gunakan cara manual.

## Penyambungan nirkabel tidak selesai
{: #wireless-pairing-does-not-finish }

- Biarkan perangkat terhubung ke Wi-Fi dan pastikan proses debug nirkabel aktif.
- Izinkan notifikasi Porter agar kode dapat dimasukkan. Izinkan juga perangkat di sekitar atau jaringan lokal jika diminta Android.
- Biarkan dialog kode Android terbuka saat memasukkan kode di notifikasi Porter. Jika kedaluwarsa, buka dialog baru.
- Dalam dialog Porter, salin port penyambungan dari dialog kode, bukan port koneksi di layar utama proses debug nirkabel.
- Jika VPN atau pembatasan jaringan lokal menghalangi penemuan, coba jaringan yang mengizinkan komunikasi antarperangkat.

Jika proses debug nirkabel tidak tersedia atau tidak andal, [jalankan lewat komputer](/setup#with-a-computer).

## Komputer tidak menemukan perangkat
{: #the-computer-cannot-find-the-device }

Jalankan `adb devices`. Jika tertulis `unauthorized`, buka kunci perangkat dan setujui permintaan debug. Jika tidak muncul apa pun, periksa proses debug USB, coba kabel USB data dan port lain, serta periksa kebutuhan driver USB dari produsen.

Jika perintah menyebut file tidak ditemukan, salin perintah baru dari **Lihat perintah** di Porter yang terpasang.

## Porter terus berhenti
{: #porter-keeps-stopping }

Periksa terlebih dahulu apakah perangkat dimulai ulang atau Android mematikan proses debug. Jalankan Porter lagi jika perlu.

Jika berhenti saat perangkat tetap menyala, periksa pengaturan baterai dan aplikasi latar belakang dari produsen untuk Porter. Izinkan operasi latar belakang jika dibatasi. Perubahan jaringan dan modifikasi Android oleh produsen dapat memengaruhi akses debug.

Laporkan kejadian berulang dengan model perangkat, versi Android, metode menjalankan layanan, dan kejadian tepat sebelum Porter berhenti.

## Aplikasi tidak dapat terhubung
{: #an-app-cannot-connect }

1. Pastikan Porter menunjukkan bahwa layanan berjalan.
2. Periksa [apakah aplikasi memerlukan Porter Compatibility](/compatibility).
3. Jika ada pemilih layanan, pilih Porter, paksa berhenti aplikasi di Setelan Android, lalu buka kembali.
4. Aktifkan integrasi dalam aplikasi dan setujui permintaan Porter.
5. Periksa aplikasi di **Aplikasi** pada Porter dan pastikan **Izinkan akses aplikasi** aktif.

Jika memakai pendamping, kedua APK Porter harus berasal dari sumber rilis yang sama dan memiliki sertifikat penandatanganan yang cocok. Menghapus pendamping membuat klien lama tidak dapat memakai Porter.

## Android menolak memasang APK
{: #android-wont-install-an-apk }

Untuk memasang **Porter Compatibility**, hapus Shizuku dahulu. Pendamping tidak dapat memperbarui Shizuku dengan tanda tangan berbeda, meskipun Android melihat identitas aplikasi yang sama.

Untuk Porter sendiri, build pengembangan lama mungkin memiliki tanda tangan berbeda dari rilis publik. Android tidak dapat memasang salah satunya di atas yang lain. Menghapus pemasangan lama juga menghapus data aplikasi; catat pengaturan sebelum melakukannya. Pasang ulang dari sumber yang diinginkan dan izinkan aplikasi kembali.

## Akses diizinkan, tetapi operasi tetap gagal
{: #access-is-allowed-but-an-operation-still-fails }

Akses debug lebih terbatas daripada root. Versi Android dan produsen menerapkan batas tambahan, dan Porter tidak dapat menyediakan setiap operasi aplikasi. Periksa juga persyaratan aplikasi klien.

Di perangkat Xiaomi/POCO dengan MIUI, Opsi developer mungkin memiliki sakelar terpisah **Debugging USB (Setelan keamanan)**. Mengaktifkan proses debug USB biasa saja dapat tetap membatasi pengelolaan aplikasi. Aktifkan setelan tambahan tersebut jika diperlukan, lalu mulai ulang Porter. Nama dan ketersediaannya berbeda menurut versi sistem.

Beberapa sistem OPPO/OnePlus memiliki **Pemantauan izin** di Opsi developer yang membatasi debug. Menonaktifkannya dapat memungkinkan operasi, tetapi mengubah perlindungan produsen. Cara khusus produsen ini berasal dari [panduan Shizuku asli](https://shizuku.rikka.app/guide/setup/) dan belum diverifikasi dengan Porter pada perangkat fisik.

## Versi yang ditampilkan saat berjalan berbeda
{: #the-version-shown-while-running-is-different }

Entri **Versi** di **Setelan** menunjukkan versi aplikasi Porter. Ketuk kartu layanan yang berjalan untuk melihat versi aplikasi terpasang, layanan Porter aktif, dan API Shizuku yang kompatibel. Versi API menjelaskan kompatibilitas, bukan nomor rilis Porter. Jika Porter meminta layanan dimulai ulang setelah pembaruan, hentikan lalu jalankan kembali.

## Melaporkan masalah
{: #report-a-problem }

Ketuk ikon setelan Porter, lalu buka **Bantuan & dukungan**. Hubungi dukungan melalui email, kunjungi [komunitas Discord](https://discord.gg/5hXXgwKNgm), atau [buat laporan](https://github.com/d4rken-org/porter/issues).

Untuk menyertakan log, pilih **Rekam log debug**, ulangi masalahnya, lalu **Hentikan perekaman**. Pilih rekaman tersimpan di formulir kontak atau bagikan dari **Log tersimpan**. Log tetap berada di perangkat hingga dibagikan. Isinya dapat mencakup nama aplikasi, detail perangkat, dan tindakan melalui Porter.

Sertakan:

- Versi Porter dan apakah Porter Compatibility terpasang.
- Model perangkat dan versi Android.
- Cara menjalankan Porter: nirkabel, komputer, atau root.
- Aplikasi terkait dan versinya.
- Tindakan Anda, hasil yang diharapkan, dan hasil sebenarnya.

Periksa tangkapan layar dan log untuk informasi pribadi sebelum dilampirkan. Jangan sertakan kode penyambungan nirkabel atau kunci pribadi.
