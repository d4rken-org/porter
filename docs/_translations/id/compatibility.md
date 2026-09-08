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
| Shizuku saja | Porter dan Porter Compatibility; hapus Shizuku dahulu |
| Keduanya, dengan pemilih layanan | Porter, lalu pilih Porter di aplikasi |

Pendamping membantu aplikasi Shizuku menemukan Porter. Porter tetap menjalankan layanan, menampilkan permintaan izin, dan mengelola persetujuan. Biarkan pendamping terpasang saat menggunakan aplikasi yang memerlukannya.

## Beralih dari Shizuku
{: #switch-from-shizuku }

Untuk aplikasi dengan dukungan langsung Porter, Shizuku boleh tetap terpasang. Jalankan Porter, pilih di aplikasi, lalu setujui permintaan baru. Jika aplikasi meminta mulai ulang, paksa berhenti melalui Setelan Android dan buka kembali.

Untuk aplikasi yang hanya mendukung Shizuku:

1. Hentikan Shizuku dan hapus aplikasi pengelolanya. Aplikasi yang menggunakan Shizuku boleh tetap terpasang.
2. Pasang Porter dan APK Porter Compatibility dari rilis yang sama.
3. Jalankan Porter.
4. Paksa berhenti aplikasi klien di Setelan Android, buka kembali, lalu aktifkan integrasi Shizuku.
5. Setujui permintaan akses yang ditampilkan Porter.

Persetujuan Shizuku sebelumnya tidak dipindahkan. Pilih kembali aplikasi yang boleh menggunakan Porter.

## Bisakah Porter dan Shizuku berjalan bersama?
{: #can-porter-and-shizuku-run-together }

Ya. Keduanya dapat terpasang dan berjalan bersamaan. Aplikasi yang mendukung pemilihan layanan terhubung ke satu layanan pada satu waktu.

**Porter Compatibility tidak dapat dipasang bersamaan dengan Shizuku.** Pendamping memakai identitas aplikasi Android Shizuku untuk mendukung aplikasi lama. Android menganggap keduanya sebagai pemasangan yang saling menggantikan, bukan aplikasi terpisah. Ini juga berlaku bagi fork dengan identitas yang sama.

Untuk kembali, hapus Porter Compatibility dan pasang Shizuku lagi. Mulai ulang aplikasi klien dan izinkan aksesnya di Shizuku. Porter mandiri boleh tetap terpasang.

## Aplikasi masih meminta Shizuku
{: #an-app-still-asks-for-shizuku }

Setelan aplikasi lama mungkin tetap menampilkan nama Shizuku meskipun Porter yang menyediakan akses. Ini normal.

Pendamping mendukung metode penemuan Shizuku yang umum. Aplikasi yang bergantung pada layar tertentu, komponen internal, atau API sangat lama mungkin perlu diperbarui. Jika aplikasi tidak dapat terhubung, cantumkan nama dan versinya dalam [laporan masalah Porter](https://github.com/d4rken-org/porter/issues).
