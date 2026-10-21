# android_VCAM-pro

[![Version](https://img.shields.io/badge/version-1.1-blue.svg)](https://github.com/codeGV/android_VCAM-Pro/releases)
[![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green.svg)](https://github.com/codeGV/android_VCAM-Pro)

An Xposed-based virtual camera module that replaces Android camera preview and photo capture with custom videos and images. It now includes a built-in video editor, so you can pick, trim, crop and export the replacement video without leaving the app.

Based on [android_virtual_cam](https://github.com/w2016561536/android_virtual_cam) by w2016561536.

## ⚠️ Disclaimer

**DO NOT USE FOR ANY ILLEGAL PURPOSE! YOU MUST TAKE ALL RESPONSIBILITY AND CONSEQUENCES!**

This tool is for learning and research purposes only. Do not use it for any illegal activities. Users must assume all consequences and responsibilities arising from using this tool.

## 🌟 Key Features

- ✅ **Dual API Support**: Supports both Camera1 and Camera2 APIs
- ✅ **Video Replacement**: Replace camera preview with custom MP4 videos
- ✅ **Built-in Video Editor** *(new)*: Pick any video from your device and turn it into `virtual.mp4`
  - Trim with a thumbnail timeline and live preview
  - Crop with presets (Original, Free, 1:1, 4:3, 3:4, 16:9, 9:16)
  - Output resolution: 480p, 720p or 1080p (H.264 MP4)
  - Keep or drop the audio track
  - **Rotate 180°** for target apps that show the feed upside down
- ✅ **Virtual Camera Video Card** *(new)*: Looping preview of the active `virtual.mp4` with resolution, duration, size, source clip and a one-tap Remove
- ✅ **Photo Replacement**: Replace captured photos with custom BMP images
- ✅ **Audio Playback**: Optional video audio playback
- ✅ **App Configuration UI**: 5 visual switches, no need to create files manually
- ✅ **Private Directory Support**: Separate video configuration for each app
- ✅ **Real-time Effect**: Configuration changes take effect immediately without restart
- ✅ **Toast Message Control**: Option to disable toast notifications

## 📱 Supported Platforms

- Android 5.0+ (API 21+)
- Requires Xposed framework (such as LSPosed, EdXposed, etc.)

## 📦 Download & Installation

### Download

- **GitHub Releases**: [Latest Version](https://github.com/codeGV/android_VCAM-Pro/releases)

### Installation Steps

1. Download the latest APK file
2. Install the APK on your Android device
3. Enable the module in Xposed framework
4. Reboot device to activate the module

> ⚠️ **Upgrading from the original VCAM**: the package name is now `com.codegv.android.vcam`, so this installs as a separate app. Uninstall the old `com.example.vcam` module, then enable the new one in LSPosed and select your target apps again.

## 📖 Usage

### Basic Configuration

1. **Enable Module**
   - Enable this module in Xposed framework (such as LSPosed)
   - For scoped frameworks like LSPosed, select the target app, **no need to select System Framework**
   - Reboot target app or device

2. **Grant Permissions**
   - In system settings, grant storage read permission to target app
   - Force stop the target app
   - If the app doesn't request this permission, see step 3

3. **Determine Working Directory**
   - Open target app; if it lacks storage permission, a toast message will appear
   - Private directory mode: `/[Internal Storage]/Android/data/[package name]/files/Camera1/`
   - Shared directory mode: `/[Internal Storage]/DCIM/Camera1/`
   - Create the directory manually if it doesn't exist

   > 💡 **Tip**: `Camera1` in private directory only affects that specific app

### Video Configuration

4. **Check the Required Resolution**
   - Open camera preview in target app
   - Note the resolution (width × height) shown in the toast message
   - If no toast message appears, no need to adjust video resolution

5. **Create the Replacement Video**

   **Option A: Built-in editor (recommended)**
   1. Open the VCAM app and tap **Choose video**
   2. Drag the trim handles to select the part you want; tap play to preview
   3. Pick a **Crop** preset that matches the target app's aspect ratio and adjust the crop box
   4. Pick a **Resolution** (480p / 720p / 1080p); the exact output size is shown under the options
   5. Untick **Keep audio** if you don't need sound
   6. Tick **Rotate 180°** if the target app showed the video upside down last time
   7. Tap **Use for virtual camera**. The video is saved as `/[Internal Storage]/DCIM/Camera1/virtual.mp4`
   8. Reopen the camera in the target app

   **Option B: Manual**
   - Create a video matching the toast resolution with any editor
   - Name it `virtual.mp4` and place it in the `Camera1` directory

   The **Virtual camera video** card on the main screen shows what the hook will play. Use **Remove** to delete it.

6. **Prepare Replacement Photo (Optional)**
   - When taking photos in target app, if the real photo shows and the toast message says "Photo capture detected"
   - Check the photo resolution in toast message
   - Prepare an image with the same resolution, name it as `1000.bmp`
   - Place it in `Camera1` directory (supports other formats renamed to bmp)
   - If no toast message when taking photos, no need for `1000.bmp`

### Function Switch Configuration

This module provides 5 function switches with two configuration methods:

#### Method 1: Use App Interface (Recommended)

Open the VCAM app and configure using visual switches:

- **Force Show Permission Warning**: Repeatedly show directory redirection toast
- **Temporarily Disable Module**: Temporarily stop video replacement
- **Play Video Sound**: Play audio from replacement video
- **Force Private Directory**: Separate video for each app
- **Disable Toast Messages**: Turn off all toast notifications

#### Method 2: Manual File Creation

Create corresponding files in `/[Internal Storage]/DCIM/Camera1/` directory:

| Function | Filename | Description |
|----------|----------|-------------|
| Play video sound | `no-silent.jpg` | Enable video audio playback |
| Temporarily disable | `disable.jpg` | Temporarily stop replacement |
| Disable toast | `no_toast.jpg` | Turn off toast notifications |
| Force show warning | `force_show.jpg` | Repeatedly show redirection message |
| Force private dir | `private_dir.jpg` | Force all apps to use private directory |

> 💡 **Tip**: All configuration switches take effect globally in real-time without restart

## ❓ FAQ

### Q1. The video is upside down or sideways in the target app?
**A1.** The hook passes frames to the app as if they came from the real camera, and the app rotates them by its camera's sensor orientation. Front and back sensors are usually mounted 180° apart, so the same video can be upright on one and upside down on the other.
- **Upside down**: re-export the video in the editor with **Rotate 180°** ticked.
- **Front camera**: in most cases the video also needs to be flipped horizontally and rotated 90° clockwise, and the resolution ***after processing*** should match the toast message. Do this in an external editor for now. Sometimes this is not needed; check on your device.

### Q2. Black screen or camera fails to start?
**A2.** Currently some apps cannot be hooked successfully (especially system camera). Or it's due to wrong video path (whether two levels of Camera1 directory were created, like `./DCIM/Camera1/Camera1/virtual.mp4`, but only one level is needed).

### Q3. Blurred screen?
**A3.** Wrong video resolution. Pick a crop preset and resolution in the editor that match the toast message.

### Q4. Distorted or deformed picture?
**A4.** The video's aspect ratio doesn't match the app's preview. Re-export it in the editor with the matching **Crop** preset.

### Q5. Creating `disable.jpg` doesn't work?
**A5.**
- If app version `<=4.0`, files in `[Internal Storage]/DCIM/Camera1` directory work for apps **with storage access permission**; apps without permission should create in **private directory**
- If app version `>=4.1`, should create in `[Internal Storage]/DCIM/Camera1` regardless of whether target app has permission

### Q6. Export failed in the editor?
**A6.** The editor needs a video the device can decode, and it only copies AAC audio (other audio formats are dropped). Very large output sizes are scaled down to what the device's H.264 encoder supports. If it still fails, try a lower resolution or a shorter clip.

## 🔧 Development & Build

### Version Info

- **Current Version**: v1.1 (versionCode 29)
- **Package Name**: `com.codegv.android.vcam`
- **Compile SDK**: Android 12 (API 31)
- **Minimum Support**: Android 5.0 (API 21)
- **Target SDK**: Android 8.0 (API 26)

### Local Build

```bash
# Clone repository
git clone https://github.com/codeGV/android_VCAM-Pro.git
cd android_VCAM-Pro

# Build debug version
./gradlew assembleDebug

# Build release version (requires signing configuration)
./gradlew assembleRelease
```

## 🐛 Report Issues

Please report directly in [Issues](https://github.com/codeGV/android_VCAM-Pro/issues).

For bug reports, please include:
- Xposed **module** log information (lines start with `[VCAM]`)
- Android system version
- Xposed framework type and version
- Target app information
- Steps to reproduce

## 🙏 Credits

- Original project: [android_virtual_cam](https://github.com/w2016561536/android_virtual_cam) by w2016561536
- HOOK method reference: [CameraHook](https://github.com/wangwei1237/CameraHook)
- H264 hardware decode: [Android-VideoToImages](https://github.com/zhantong/Android-VideoToImages)
- JPEG to YUV conversion: [Blog post](https://blog.csdn.net/jacke121/article/details/73888732)

## 📜 License

This project is for learning and research purposes only. Do not use for any illegal activities. See [LICENSE](LICENSE).

## ⭐ Support

If this project helps you, please give it a Star ⭐️
