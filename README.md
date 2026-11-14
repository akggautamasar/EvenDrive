# EvenDrive (Telegram Zero-Disk Virtual Storage for Android)

Stream multi-gigabyte videos and browse folders stored on Telegram directly on your Samsung Galaxy S24 (or any Android 8.0+ phone) with **0 bytes written to internal flash storage**.

## Architecture
- **Storage Access Framework (`DriveDocumentsProvider` + `ProxyFileDescriptorCallback`)**: Exposes your Telegram vault natively to Android's system file picker, **VLC**, **MX Player**, and **Solid Explorer**.
- **RAM-Only Byte-Range Engine (`TelegramRangeChunkCache`)**: Aligns OS FUSE read/seek calls into **1 MiB (`1,048,576` bytes)** blocks stored strictly in a **64 MiB RAM `LruCache`** with automatic next-chunk read-ahead and `limitedParallelism(3)` rate-limit protection.
- **Zero-Temp-File Multi-Part Uploader (`TelegramUploadManager`)**: Streams files directly from `content://` URIs into a private **`Even Vault`** Telegram channel. Files larger than **1900 MiB** are automatically split on exact 1 MiB boundaries and stitched back transparently during playback.
- **Cross-Device Folder Sync (`ChannelIndexSync`)**: Keeps an encrypted/structured `even_index.json` pinned in your private Telegram channel so you can restore your entire folder tree on a new device.

## Setup Instructions
1. Get your Telegram `api_id` and `api_hash` from [https://my.telegram.org](https://my.telegram.org) (**API development tools**).
2. Open this folder in **Android Studio (Ladybug or newer)**.
3. Open `app/build.gradle.kts` and replace:
   ```kotlin
   buildConfigField("int", "TG_API_ID", "12345678")
   buildConfigField("String", "TG_API_HASH", "\"your_telegram_api_hash\"")
   ```
   with your actual credentials.
4. Sync Gradle and run on your **Samsung Galaxy S24**.
5. Sign in with your phone number, Telegram OTP code, and optional 2FA password. The app will automatically create a private **Even Vault** channel in your Telegram account.
6. Tap **Upload** to upload any file, or tap any uploaded video to stream it in **VLC** or **MX Player** with timeline scrubbing and **0 bytes** used on disk.
