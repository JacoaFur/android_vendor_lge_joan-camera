# LineageOS 22.2 (Android 15) ROM Source-Level Integration Guide

This directory contains optional source-level patches for LineageOS 22.2 (`lineage-22.2`) so the LG V30 Stock Camera (`AiXAiCam`) builds natively into the ROM image and benefits from native HAL1 & `MediaProvider` support.

## 1. Vendor Tree Setup (`vendor/lge/camera`)

From the `lgcamera-port` directory, run:

```bash
APKTOOL_JAR=/usr/share/java/android-apktool/apktool.jar \
  ./tools/export_rom_tree.sh ./system /path/to/lineage-22.2
```

This populates `vendor/lge/camera/` inside your LineageOS 22.2 tree with:
- `Android.bp` — Soong targets (`AiXAiCam`, `com.lge.camera.shim`, `privapp-permissions-lgcamera.xml`, `default-permissions-lgcamera.xml`, `hiddenapi-package-whitelist-lgcamera.xml`, `lgcamera_config.xml`)
- `camera.mk` — Product makefile setting `PRODUCT_PACKAGES`, `PRODUCT_COPY_FILES`, and `vendor.camera.aux.packagelist`
- `prebuilt/AiXAiCam.apk`, `prebuilt/camera_config.xml`, and `prebuilt/luts/`

## 2. Source Tree Patches (`rom_patches/*.patch`)

1. **`01-device-lge-joan-camera.patch`** (apply in `device/lge/joan` or `device/lge/msm8998-common`):
   - Inherits `vendor/lge/camera/camera.mk`
   - Adds `com.lge.camera` to `vendor.camera.aux.packagelist` and `persist.vendor.camera.privapp.list` so the secondary wide-angle camera (`cameraId=2`) is exposed without needing Magisk `resetprop`.
2. **`02-frameworks-av-camera2client-lg-compat.patch`** (apply in `frameworks/av`):
   - Seeds LG HAL1 parameter defaults (`video-hdr-supported`, `supported-live-snapshot-sizes`, `lg-iso`, `lg-wb`, `shutter-speed`, `manualfocus_step`) directly in `Camera2Client`'s `Parameters::initialize()`.
3. **`03-mediaprovider-ignore-lg-columns.patch`** (apply in `packages/providers/MediaProvider`):
   - Strips LG-proprietary MediaStore columns (`camera_mode`, `burst_id`, `storage_type`, `project_id`, etc.) before `SQLiteQueryBuilder.enforceStrictColumns` so any stock LG media app (Camera, Gallery, Cinemagraph, QuickVideoEditor) can insert/query/update MediaStore rows natively.
