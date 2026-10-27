<div align="center">
  <img src="assets/icon.png" width="128" height="128" alt="YaMusicLsposed Icon" />
  
  # YaMusicLsposed
  
  **A powerful LSPosed module for the Yandex Music app**

  [![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue.svg?logo=kotlin)](http://kotlinlang.org)
  [![LSPosed](https://img.shields.io/badge/LSPosed-Supported-brightgreen.svg)](https://github.com/LSPosed/LSPosed)

  [English](README.md) | [Русский](README_RU.md)
</div>

---

**YaMusicLsposed** is an Xposed/LSPosed module that adds music downloading capabilities and advanced features to the official Yandex Music application. The module works entirely "under the hood" and integrates seamlessly into the app's UI with a stunning Liquid Glass design.

## Features

* **Direct MP3 Download**: Download individual tracks, entire albums, or playlists in original quality.
* **Embedded Metadata & Covers**: Tracks are saved with correct ID3 tags (Title, Artist, Album) and high-quality album art embedded.
* **Parallel Downloading**: Super-fast playlist downloads using multiple threads.
* **Playlist Sorting**: The module smartly manipulates file creation dates so that when sorting files by "Date Added" in your player, they align perfectly with the original playlist order.
* **Liquid Glass UI**: Premium settings and overlay menus built with Jetpack Compose featuring a translucent glass effect, fluid animations, and native back gesture support.
* **Active Downloads Monitor**: A convenient real-time tracker showing the progress of each individual track currently being downloaded.

## Installation

1. Ensure your device is rooted with **Magisk / KernelSU** and has the **LSPosed** framework installed.
2. Install the [Yandex Music](https://play.google.com/store/apps/details?id=ru.yandex.music) app from Google Play.
3. Download the latest **YaMusicLsposed** from the [Releases](../../releases) page.
4. Install the module's APK.
5. Open the LSPosed Manager, enable the module, and check the "Yandex Music" app.
6. Restart Yandex Music (or force stop it from system settings).

## Usage

* **Download a track**: Tap the download icon in the player (next to playback controls).
* **Download a playlist/album**: Tap the download icon on the playlist or album page.
* **Settings**: Long-press the single-track download button. Here you can configure the save folder, audio quality, and token.
* **Active Downloads**: Long-press the playlist/album download button. This opens a beautiful overlay showing all currently downloading tracks.

## Contact Me (Windyx0)

- [Telegram Channel](https://t.me/WindyxChannel)
- [Telegram DM](https://t.me/Windyx0)
- [TikTok](https://www.tiktok.com/@windyx_edits)

## License

This project is licensed under the MIT License. You are free to use, modify, and distribute the code. See the [LICENSE](LICENSE) file for more details.

*Disclaimer: This project is created for educational purposes. Please respect copyright laws and use this module only to download content to which you have legal access.*

