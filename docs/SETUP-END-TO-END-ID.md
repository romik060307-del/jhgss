# Panduan aktivasi OHLI: Netlify + Firebase + APK

Paket yang diperlukan:

1. **OHLI Netlify Control** — site tetap `https://ohlisocial.netlify.app`, menyediakan `server.json` dan Netlify Function untuk mengirim FCM.
2. **OHLI Social Client v1.2.0** — APK yang dipakai pengguna.
3. **OHLI Social Server v3.6.28** — aplikasi operator yang menjalankan backend OHLI dan Cloudflare Tunnel.

> Netlify tidak menjalankan backend OHLI dan tidak menyalakan tunnel. Netlify hanya menyimpan alamat tunnel terbaru dan menjadi gateway privat FCM. HP/server operator harus tetap hidup, online, dan menjalankan tunnel.

## A. Pasang Netlify Control pada site yang sudah ada

1. Ekstrak ZIP `OHLI-Netlify-Control-v1.0.0.zip`.
2. Buat repository GitHub, misalnya `ohli-netlify-control`, kemudian upload **isi folder ZIP** ke root repository (file `netlify.toml`, folder `public/`, dan folder `netlify/` harus berada di root).
3. Di Netlify, hubungkan repository tersebut ke **site yang sudah memiliki domain `ohlisocial.netlify.app`**. Jangan membuat site baru dengan nama berbeda jika ingin mempertahankan domain tersebut.
4. `netlify.toml` sudah mengatur publish directory `public` dan folder functions `netlify/functions`; build command dapat dibiarkan kosong.
5. Deploy. Setelah selesai, uji URL `https://ohlisocial.netlify.app/server.json`. Isinya harus JSON dengan properti `server_url`.

### Mengganti alamat Cloudflare ketika tunnel berubah

Edit file `public/server.json` pada repository Netlify menjadi:

```json
{
  "server_url": "https://TUNNEL-BARU.trycloudflare.com/"
}
```

Commit/push ke GitHub dan tunggu Netlify menyelesaikan deploy. URL Netlify tetap sama; APK membaca `server.json` ketika dibuka lagi. Tidak perlu build APK hanya untuk mengganti hostname tunnel.

## B. Siapkan Firebase untuk push notification

1. Buka Firebase Console dan buat/pilih satu Firebase project.
2. Tambahkan Android app dengan package name persis: `com.rk.ohlisocial`.
3. Download `google-services.json` asli, lalu letakkan di proyek client pada `app/google-services.json`. Jangan ganti nama file. Jangan kirim file ini ke chat publik atau commit service-account key.
4. Aktifkan Firebase Cloud Messaging API pada Google Cloud project yang sama.
5. Buat service account Google Cloud yang diberi izin mengirim Firebase Cloud Messaging (misalnya peran **Firebase Cloud Messaging API Admin**), lalu buat key JSON.
6. Di Netlify → Site configuration → Environment variables, isi:
   - `FIREBASE_SERVICE_ACCOUNT_JSON`: seluruh isi file service-account JSON sebagai satu nilai environment variable.
   - `OHLI_PUSH_WEBHOOK_SECRET`: rahasia acak panjang minimal 32 karakter.
7. Simpan rahasia yang sama untuk langkah C. Jangan masukkan rahasia ke `server.json`, website publik, atau APK client.

## C. Aktifkan pengiriman push pada server OHLI

1. Upload isi ZIP `OHLI-Social-Server-v3.6.28-FCM-Push.zip` ke repository GitHub server host yang dipakai untuk membangun APK operator.
2. Repository tersebut harus mempunyai GitHub Actions secret bernama `OHLI_PUSH_SECRET` dengan nilai **persis sama** seperti `OHLI_PUSH_WEBHOOK_SECRET` di Netlify.
3. Jalankan workflow **Build OHLI Social Server APK**. Artifact bernama `OHLI-Social-Server-APK-v3.6.28`.
4. **Jangan uninstall APK server/operator lama dan jangan memasang pembaruan sebelum tanda tangan APK cocok.** APK server menyimpan database akun, chat, dan media di penyimpanan privat aplikasi. Jika sertifikat penandatangan berbeda, Android menolak update; uninstall akan menghapus data privat. Backup data lebih dahulu dan gunakan keystore/signing key yang sama seperti APK server terpasang.
5. APK server/operator hasil build memuat secret push, sehingga hanya boleh dipasang pada HP operator dan jangan dibagikan kepada pengguna.

## D. Build APK pengguna

1. Upload isi ZIP `OHLI-Social-Client-v1.2.0-Native-Notifications.zip` ke repository GitHub client.
2. Pastikan file Firebase `app/google-services.json` sudah ada pada repository sebelum build.
3. Jalankan workflow **Build OHLI Social Client APK**.
4. Download artifact `OHLI-Social-Client-APK-v1.2.0`, pasang di HP pengguna, login, dan izinkan notifikasi Android.
5. Setelah login, aplikasi mendaftarkan token FCM ke backend OHLI.

## E. Uji dua akun/perangkat

Uji ketika penerima berada di background dan ketika aplikasi tidak sedang terbuka:

- Pesan masuk, tampilan heads-up, suara OHLI, dan balasan teks dari notifikasi.
- Panggilan suara dan video: notifikasi serta nada masing-masing, lalu ketuk untuk membuka layar panggilan OHLI dan jawab dari aplikasi.
- Undangan LIVE dan aktivitas sosial/follower.
- Ganti alamat `server_url` pada Netlify, deploy, lalu buka ulang client untuk memastikan host baru terbaca.

## Batasan yang perlu diketahui

- Push membutuhkan: Firebase yang dikonfigurasi, APK client dengan `google-services.json`, server host versi push yang berjalan, Netlify Function yang terdeploy, serta tunnel aktif.
- Jika HP server/operator mati atau server/tunnel berhenti, backend OHLI tidak dapat membuat atau mengirim push. Netlify tidak dapat menggantikan server utama.
- Nada chat, sosial, voice call, video call, dan LIVE memakai channel Android yang terpisah. Pengguna dapat mengubah atau mematikan suara channel melalui Settings Android. Android dapat menahan tampilan heads-up berdasarkan pengaturan pengguna/perangkat.
- Panggilan masuk saat aplikasi di background memberi notifikasi bersuara dan membuka halaman panggilan OHLI saat diketuk. Paket ini belum mengimplementasikan Android Telecom/ConnectionService, layar panggilan penuh di lock-screen, atau ringtone berulang tanpa batas.
- Balas langsung dari notifikasi tersedia untuk pesan teks satu lawan satu. Ini bukan balasan media atau grup.
- Tanpa `google-services.json` yang cocok, proyek client masih bisa dikompilasi tetapi FCM tidak aktif.
- Build dan uji fisik belum dilakukan pada perangkat dari lingkungan ini. Validasi akhir harus melalui GitHub Actions dan uji dua akun/perangkat.
