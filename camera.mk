# Product Makefile for LG V30 Stock Camera (`vendor/lge/joan-camera`)
#
# Inherit from `device/lge/joan/device.mk`:
#   $(call inherit-product-if-exists, vendor/lge/joan-camera/camera.mk)

LOCAL_CAMERA_PATH := $(call my-dir)

PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_CAMERA_PATH)

PRODUCT_PACKAGES += \
    AiXAiCam \
    com.lge.camera.shim \
    privapp-permissions-lgcamera.xml \
    default-permissions-lgcamera.xml \
    hiddenapi-package-whitelist-lgcamera.xml \
    lgcamera_config.xml

# Install permissions & hiddenapi whitelist to /system/etc as well as /product/etc
# so Android 15 partition-scoped SystemConfig checks always match
PRODUCT_COPY_FILES += \
    $(LOCAL_CAMERA_PATH)/proprietary/etc/permissions/privapp-permissions-lgcamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-lgcamera.xml \
    $(LOCAL_CAMERA_PATH)/proprietary/etc/default-permissions/default-permissions-lgcamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/default-permissions/default-permissions-lgcamera.xml \
    $(LOCAL_CAMERA_PATH)/proprietary/etc/sysconfig/hiddenapi-package-whitelist-lgcamera.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/sysconfig/hiddenapi-package-whitelist-lgcamera.xml \
    $(LOCAL_CAMERA_PATH)/proprietary/product/etc/camera_config.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/camera_config.xml

# Expose Qualcomm auxiliary / wide-angle camera (Camera 2) to com.lge.camera
PRODUCT_PROPERTY_OVERRIDES += \
    vendor.camera.aux.packagelist=org.codeaurora.snapcam,com.android.camera,com.lge.camera \
    persist.vendor.camera.privapp.list=org.codeaurora.snapcam,com.android.camera,com.lge.camera

# Copy 3D LUTs (.dat) for Film / Food / Selfie / AI CAM filters into /vendor/etc/camera
-include $(LOCAL_CAMERA_PATH)/luts.mk
