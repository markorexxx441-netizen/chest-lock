# ChestLock

Plugin Paper untuk mengunci chest dengan sign `[KUNCI]`, lengkap dengan limit jumlah kunci per rank LuckPerms.

## Cara pakai (player)

1. Taruh sign menempel di chest (wall sign), atau standing sign di atas chest.
2. Tulis `[KUNCI]` di baris pertama, lalu simpan.
3. Sign otomatis berubah jadi `[TERKUNCI]` + nama pemilik. Hanya pemilik yang bisa membuka chest.
4. Untuk membuka kunci: hancurkan sign-nya, atau pakai `/chestlock unlock` sambil menatap chest.

Rank default hanya bisa mengunci **1 chest**. Kalau mencoba lagi, muncul pesan limit beserta info upgrade rank ke Admin lewat ticket Discord.

## Fitur

- Chest, trapped chest, dan barrel (bisa diubah di `config.yml`)
- Chest ganda dihitung 1 kunci, kedua separuhnya ikut terkunci
- Limit per rank lewat permission LuckPerms
- Orang lain tidak bisa membuka, menghancurkan chest, atau menghancurkan/mengedit sign kunci
- Sign kunci di-wax, jadi tidak bisa diedit atau diwarnai
- Tahan ledakan (creeper/TNT/wither), piston, dan hopper (bisa dimatikan)
- Menaruh chest di sebelah chest terkunci milik orang lain ditolak
- Kunci yang sign/chest-nya sudah hilang (misalnya kena WorldEdit) dibersihkan otomatis, jadi tidak memakan slot limit
- Semua pesan, isi sign, dan link Discord bisa diubah di `config.yml`
- Data tersimpan di `plugins/ChestLock/locks.yml`

## Command

| Command | Fungsi | Permission |
|---|---|---|
| `/chestlock` (alias `/kunci`, `/clock`) | Bantuan | - |
| `/chestlock info` | Lihat pemilik chest yang ditatap | - |
| `/chestlock list` | Daftar chest yang kamu kunci | - |
| `/chestlock unlock` | Buka kunci chest yang ditatap | pemilik, atau `chestlock.admin` |
| `/chestlock list <player>` | Daftar kunci player lain | `chestlock.admin` |
| `/chestlock reload` | Reload config | `chestlock.admin` |

## Permission & setup LuckPerms

| Permission | Fungsi | Default |
|---|---|---|
| `chestlock.use` | Boleh mengunci chest | semua |
| `chestlock.unlimited` | Tanpa batas jumlah kunci | op |
| `chestlock.limit.<n>` | Maksimal n kunci (mis. `chestlock.limit.5`) | - |
| `chestlock.bypass` | Boleh buka/hancurkan chest terkunci orang lain | op |
| `chestlock.admin` | Command admin | op |

Limit rank default diatur di `config.yml` (`limits.default: 1`), jadi rank default tidak perlu permission apa pun.

Contoh setup untuk rank Admin:

```
/lp group admin permission set chestlock.unlimited true
```

Rank tengah, misalnya VIP boleh 5 chest:

```
/lp group vip permission set chestlock.limit.5 true
```

OP otomatis bisa membuka chest siapa pun. Untuk staff non-OP (rank Admin di LuckPerms), beri permission ini:

```
/lp group admin permission set chestlock.bypass true
```

## Build

Butuh JDK 17+ dan Maven.

```
mvn clean package
```

Hasilnya `target/ChestLock-1.0.0.jar`, taruh di folder `plugins/` server lalu restart.

Dikompilasi terhadap Paper API 1.20.4 dan jalan di Paper 1.20.4 ke atas. Kalau server kamu versi lain, ganti versi `paper-api` di `pom.xml`.

## Konfigurasi penting

Setelah server jalan pertama kali, buka `plugins/ChestLock/config.yml` dan ganti `discord-link` dengan link Discord servermu.
