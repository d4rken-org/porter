---
title: Kompatibilitas aplikasi
lang: id
translation_key: compatibility
language_name: Bahasa Indonesia
description: Porter mendukung API Shizuku yang digunakan aplikasi kompatibel. Kebutuhan pendamping opsional bergantung pada cara aplikasi terhubung.
---
# Kompatibilitas aplikasi
{: #app-compatibility }

Porter mendukung API Shizuku yang digunakan aplikasi kompatibel. Kebutuhan pendamping opsional bergantung pada cara aplikasi terhubung.

| Dukungan aplikasi | Yang perlu dipasang |
| --- | --- |
| Porter langsung | Porter |
| Shizuku saja | Porter dan Porter Compatibility; Porter menggantikan Shizuku jika terpasang |
| Keduanya, dengan pemilih layanan | Porter, lalu pilih Porter di aplikasi |

Pendamping membantu aplikasi Shizuku menemukan Porter. Porter tetap menjalankan layanan, menampilkan permintaan izin, dan mengelola persetujuan. Biarkan pendamping terpasang saat menggunakan aplikasi yang memerlukannya.

## Beralih dari Shizuku
{: #switch-from-shizuku }

Untuk aplikasi dengan dukungan langsung Porter, Shizuku boleh tetap terpasang. Jalankan Porter, pilih di aplikasi, lalu setujui permintaan baru. Jika aplikasi meminta mulai ulang, paksa berhenti melalui Setelan Android dan buka kembali.

Untuk aplikasi yang hanya mendukung Shizuku, build FOSS menyertakan APK Porter Compatibility yang sesuai:

1. Pasang dan jalankan Porter. Biarkan Shizuku tetap terpasang sampai Anda meninjau penggantiannya.
2. Buka **Kompatibilitas Shizuku** dari layar beranda atau dari **Setelan**.
3. Jika Shizuku terpasang, pilih **Ganti**. Konfirmasi **Beralih ke Porter**. Porter menghentikan Shizuku, mengganti aplikasinya, dan otomatis membawa keputusan akses yang memenuhi syarat. Jika Porter tidak dapat menghentikan layanan, Porter meminta Anda menghentikannya di Shizuku lalu mencoba lagi.
4. Jika tidak, pilih **Pasang otomatis**.
5. Kembali ke aplikasi klien. Setujui akses jika Anda tidak mengimpor keputusan yang sudah ada. Jika klien tetap tidak dapat terhubung, paksa berhenti di Setelan Android lalu buka kembali.

Porter otomatis mengimpor keputusan yang dapat diperiksa terhadap aplikasi terpasang dan akses mereka saat ini. Keputusan Porter yang sudah ada lebih diutamakan. Setelan aplikasi dan konfigurasi penyambungan Shizuku tidak diimpor. Jika basis data akses tidak dapat dibaca, Anda dapat melanjutkan dan menyetujui aplikasi lagi. Porter menyimpan keputusan akses yang memenuhi syarat sebelum menghapus Shizuku; jika pemasangan gagal, coba lagi atau gunakan **Manual**, lalu **Impor izin tersimpan**.

Penggantian terintegrasi tersedia dari pengguna Android utama. Jika Shizuku terpasang untuk pengguna atau profil lain, tangani pemasangan itu secara terpisah. Porter tidak menghapus aplikasi milik pengguna lain secara otomatis.

APK pendamping tetap tersedia sebagai unduhan terpisah dari rilis yang sama. Untuk pemasangan manual, hentikan dan hapus Shizuku, pasang Porter Compatibility, lalu jalankan Porter. Gunakan penggantian terintegrasi Porter untuk memindahkan keputusan akses yang memenuhi syarat; membuka dialog penggantian saja tidak menyimpan impor. Pembatasan pemasangan perangkat juga dapat berlaku pada pemasang terintegrasi; tindakan **Manual** membuka pemasang Android.

Setelah terpasang, layar beranda menampilkan versi aplikasi kompatibilitas dan berapa banyak aplikasi terpasang yang terhubung melaluinya. Ketuk kartunya untuk melihat detail, memasang ulang salinan yang disertakan, atau menghapusnya. Porter mencoba menghapus melalui layanannya terlebih dahulu, dan membuka pelepas pemasangan Android jika gagal. Menghapusnya mengganggu aplikasi yang membutuhkan dukungan kompatibilitas; aplikasi dengan dukungan Porter langsung tetap berjalan. Layar kompatibilitas memeriksa perubahan secara otomatis selama terbuka.
## Bisakah Porter dan Shizuku berjalan bersama?
{: #can-porter-and-shizuku-run-together }

Ya. Keduanya dapat terpasang dan berjalan bersamaan. Aplikasi yang mendukung pemilihan layanan terhubung ke satu layanan pada satu waktu.

**Porter Compatibility tidak dapat dipasang bersamaan dengan Shizuku.** Pendamping memakai identitas aplikasi Android Shizuku untuk mendukung aplikasi lama. Android menganggap keduanya sebagai pemasangan yang saling menggantikan, bukan aplikasi terpisah. Ini juga berlaku bagi fork dengan identitas yang sama.

Untuk kembali, hapus Porter Compatibility dan pasang Shizuku lagi. Mulai ulang aplikasi klien dan izinkan aksesnya di Shizuku. Porter mandiri boleh tetap terpasang.

## Aplikasi masih meminta Shizuku
{: #an-app-still-asks-for-shizuku }

Setelan aplikasi lama mungkin tetap menampilkan nama Shizuku meskipun Porter yang menyediakan akses. Ini normal.

Pendamping mendukung metode penemuan Shizuku yang umum. Aplikasi yang bergantung pada layar tertentu, komponen internal, atau API sangat lama mungkin perlu diperbarui. Jika aplikasi tidak dapat terhubung, cantumkan nama dan versinya dalam [laporan masalah Porter](https://github.com/d4rken-org/porter/issues).
