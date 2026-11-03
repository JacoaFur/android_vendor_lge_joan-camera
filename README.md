# `android_vendor_lge_joan-camera` — LG V30 Stock Camera for LineageOS 22.2 (Android 15)

Pre-patched, self-contained vendor repository bringing the stock **LG V30 Camera (`AiXAiCam` / `com.lge.camera`)** to **LineageOS 22.2 (Android 15)** and other AOSP Android 14/15 ROMs for the LG V30 (`joan`).

All Android 15 compatibility shims, 32-bit JNI libraries (`lib/armeabi-v7a/*.so`), sanitized `camera_config.xml`, and 132 3D LUT filter files (`film`, `food`, `selfie`, `smartcam`) are already integrated—no external patching scripts or `lge-res.apk` framework overlays are required during the ROM build.

---

## Working Features

- **All Physical Lenses**: Rear Main (`16MP`), Rear Ultrawide (`13MP` via `vendor.camera.aux.packagelist`), and Front Selfie Camera (`5MP` with corrected 4:3 / 16:9 / 18:9 / 1:1 aspect ratio cropping).
- **Zoom**: Pinch-to-Zoom, on-screen Zoom Bar slider, and Capture Button drag (Jog Zoom).
- **Video Recording**: 4K UHD (`3840x2160`), 1080p FHD, 18:9 FullVision, and HD video recording with correct aspect ratio.
- **Manual Camera & Manual Video**: Full drum-wheel controls for **White Balance (2400K–7500K)**, **Manual Focus (Macro / Normal / Infinity lens zones)**, **ISO (50–3200)**, **Shutter Speed (1/4000s–30s)**, and **EV Exposure Compensation** (unlocked alongside ISO/SS), plus Manual Video audio gain & Hi-Fi recording controls.
- **AI CAM & Filters**: AI CAM scene recognition, Film Emulator 3D LUT filters, Food mode, and Selfie Beauty / Relighting filters.
- **Special Modes**:
  - **Panorama**: Patched to stitch standard NV21 preview frames (`INPUT_TYPE_PREVIEW`).
  - **Popout**: Picture-in-picture framed shot mode.
  - **Cine Video**: Point Zoom + 17 live color-grading presets.
  - **Graphy**: Manual EXIF preset companion support.
- **Android 15 Compatibility**:
  - Automatic stripping of LG-proprietary MediaStore columns (`camera_mode`, `burst_id`, `storage_type`, `project_id`, etc.) rejected by Android 11–15 `SQLiteQueryBuilder.enforceStrictColumns`.
  - Safe fallbacks for missing `com.lge.internal` (`0x02xxxxxx`) framework resources and dialog layouts.
  - Standard Android `ACTION_VIEW` intent routing when tapping the preview thumbnail to open the default system gallery.

---

## How to Include in Your LineageOS 22.2 Build

### 1. Add to Local Manifest (or Clone Directly)

Add to `.repo/local_manifests/joan_camera.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<manifest>
  <project name="JacoaFur/android_vendor_lge_joan-camera"
           path="vendor/lge/joan-camera"
           remote="github"
           revision="lineage-22.2" />
</manifest>
```

Or clone directly from the root of your LineageOS source tree:

```bash
git clone https://github.com/JacoaFur/android_vendor_lge_joan-camera.git vendor/lge/joan-camera
```

### 2. Inherit in `device/lge/joan/device.mk`

Add the following line to `device/lge/joan/device.mk`:

```makefile
# LG V30 Stock Camera
$(call inherit-product-if-exists, vendor/lge/joan-camera/camera.mk)
```

### 3. Build Normally

```bash
source build/envsetup.sh
breakfast joan
mka bacon
```

`AiXAiCam` will automatically override the default `Aperture` / `Snap` / `Camera2` package (`overrides: ["Aperture", "Camera2", "Snap"]` in `Android.bp` — remove `Aperture` from `overrides` in `Android.bp` if you want to ship both cameras side by side).

---

## Optional ROM Source Patches (`patches/`)

While `AiXAiCam.apk` already contains bytecode hooks that make it work out-of-the-box on an unmodified LineageOS 22.2 tree, `patches/` includes optional source patches if you want native framework support for other LG stock apps (such as LG Gallery or LG Cinemagraph):

- `patches/01-device-lge-joan-camera.patch` — Example `device/lge/joan/device.mk` inheritance and `vendor.camera.aux.packagelist` property setup.
- `patches/02-frameworks-av-camera2client-lg-compat.patch` — Seeds LG HAL1 parameter defaults in `frameworks/av` (`Camera2Client::Parameters::initialize`).
- `patches/03-mediaprovider-ignore-lg-columns.patch` — Strips legacy LG columns in `packages/providers/MediaProvider` before strict column validation.

---

## Repository Structure

```text
vendor/lge/joan-camera/
├── Android.bp                # Soong build targets (AiXAiCam, com.lge.camera.shim, prebuilt_etc XMLs)
├── camera.mk                 # Product makefile inherited by device/lge/joan/device.mk
├── luts.mk                   # PRODUCT_COPY_FILES rules for 132 3D LUTs in /vendor/etc/camera/
├── proprietary/
│   ├── etc/
│   │   ├── default-permissions/default-permissions-lgcamera.xml
│   │   ├── permissions/privapp-permissions-lgcamera.xml
│   │   └── sysconfig/hiddenapi-package-whitelist-lgcamera.xml
│   ├── product/
│   │   ├── etc/camera_config.xml
│   │   └── priv-app/AiXAiCam/AiXAiCam.apk
│   └── vendor/
│       └── etc/camera/{film,food,selfie,smartcam}/*.dat
├── shim/
│   └── src/com/lge/...       # Java source for com.lge.camera.shim (ParamDefaults, LGCamera, etc.)
└── patches/                  # Optional frameworks/av & MediaProvider patches
```
