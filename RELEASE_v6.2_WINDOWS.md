# 🎵 MyPlayer for Windows v6.2

> **High-Fidelity Music Streaming & Local Playback Engine for Windows 10 & 11 (64-bit)**  
> *Built with Compose Multiplatform & Hardware-Accelerated 48kHz PCM Audio*

---

## 🚀 What's New in v6.2

### ✨ Custom Native Branding & Modern App Icon
- **Native Squircle App Icon**: Replaced default Kotlin development launcher with the official MyPlayer high-resolution icon (`MyPlayer.ico`), containing all standard Windows sizes (16x16, 24x24, 32x32, 48x48, 64x64, 128x128, 256x256) with alpha corner masking.
- **Taskbar & Window Icon Stamping**: Window title bar, taskbar thumbnail, and desktop shortcuts now consistently display the custom MyPlayer brand disc.

### 🛠️ Interactive Installer with Desktop Icon Prompt
- **Desktop Shortcut Option**: Both `.msi` and `.exe` installers now present an interactive prompt allowing users to choose whether to create Desktop and Start Menu shortcuts during installation (`--win-shortcut-prompt`).
- **Seamless In-Place Upgrades**: Fixed installer versioning so updating from previous releases replaces existing binaries cleanly with zero downgrade/reinstall warnings.

### 🔗 Search & Stream via YouTube Links
- **Direct Link Resolution**: Paste any YouTube or YouTube Music song URL directly into the search bar to stream, match metadata, and fetch lyrics on the fly.

### 📜 What's New In-App Changelog
- **Automatic Version Highlights**: Built-in startup changelog dialog showing key feature additions upon upgrading to v6.2.

### 🎧 DualBud — Split-Earbud Dual Audio Player
- **Independent Channel Playback**: Play two completely different tracks simultaneously through Left and Right earbuds with dedicated volume sliders, seek controls, and instant earbud swap ($\text{Left} \leftrightarrow \text{Right}$).

### ☕ Support Development (UPI / Coffee)
- **Direct Contributor Support**: Support ongoing ad-free open-source development directly via GPay, PhonePe, or UPI QR code.

---

## 📦 Downloads & Packages

| Asset | Format | Description |
| :--- | :--- | :--- |
| **`MyPlayer-6.2.0.msi`** | Windows MSI Installer | Standard Windows Installer with desktop icon prompt and directory chooser. |
| **`MyPlayer-6.2.0.exe`** | Windows Executable Installer | Self-contained executable setup wizard with desktop shortcut option. |
| **`MyPlayer-v6.2-Windows-Portable.zip`** | Standalone Portable Archive | Zero-install standalone archive. Extract anywhere and launch `MyPlayer.exe` directly. |

---

## 💻 System Requirements
- **OS**: Windows 10 / Windows 11 (64-bit, x64)
- **Runtime**: Bundled OpenJDK 21 (zero external Java installation required)
- **Audio Output**: Direct Windows `SourceDataLine` 48,000 Hz, 16-bit signed PCM
- **Optional**: [VLC Media Player 64-bit](https://www.videolan.org/vlc/) for extended codec hardware acceleration

