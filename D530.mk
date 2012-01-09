#$(call inherit-product, build/target/product/full_base.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/core.mk)

# The gps config appropriate for this device
$(call inherit-product, device/common/gps/gps_us_supl.mk)

$(call inherit-product-if-exists, vendor/coolpad/D530/D530-vendor.mk)

#$(call inherit-product, vendor/cyanogen/products/common-vic.mk)

DEVICE_PACKAGE_OVERLAYS += device/coolpad/D530/overlay

PRODUCT_COPY_FILES += $(shell \
    find device/coolpad/D530/modules -name '*.ko' \
    | sed -r 's/^\/?(.*\/)([^/ ]+)$$/\1\2:system\/lib\/modules\/\2/' \
    | tr '\n' ' ')

# D530 uses middle-density artwork where available
PRODUCT_LOCALES += mdpi hdpi

# DSP Bridge userspace samples
PRODUCT_PACKAGES += \
        dmmcopy.out \
        scale.out \
        scale_dyn.out \
        dynreg.out \
        strmcopy.out \
        strmcopy_dyn.out \
        instutility.out \
        zerocopymsg.out \
        cexec.out \
        ping.out \
        qostest.out

PRODUCT_PACKAGES += \
	libOMX_Core \
	libLCML \
	libOMX.TI.Video.Decoder \
	libOMX.TI.Video.encoder \
	libOMX.TI.WBAMR.decode \
	libOMX.TI.WBAMR.encode \
	libOMX.TI.AAC.encode \
	libOMX.TI.AAC.decode \
	libOMX.TI.MP3.decode \
	libOMX.TI.WMA.decode \
	libOMX.TI.VPP \
	libOMX.TI.JPEG.encoder \
	libOMX.TI.JPEG.decoder \
	libOMX.TI.AMR.encode \
	libOMX.TI.AMR.decode \
    libomap_mm_library_jni \
    tiomxplayer     

FRAMEWORKS_BASE_SUBDIRS += omapmmlib

# SkiaHW
PRODUCT_PACKAGES += \
        libskiahw \
	alsa.omap3

# system/etc/wifi
PRODUCT_COPY_FILES += \
    device/coolpad/D530/prebuilt/etc/wifi/firmware.bin:system/etc/wifi/firmware.bin \
    device/coolpad/D530/prebuilt/etc/wifi/nvs_map.bin:system/etc/wifi/nvs_map.bin \
    device/coolpad/D530/prebuilt/etc/wifi/tiwlan_drv.ko:system/etc/wifi/tiwlan_drv.ko \
    device/coolpad/D530/prebuilt/etc/wifi/wpa_supplicant.conf:system/etc/wifi/wpa_supplicant.conf \
    device/coolpad/D530/prebuilt/etc/wifi/tiwlan.ini:system/etc/wifi/tiwlan.ini


# system/media/
#PRODUCT_COPY_FILES += \
#    device/coolpad/D530/prebuilt/media/bootanimation.zip:system/media/bootanimation.zip


# permissions/ Install the features available on this device.
PRODUCT_COPY_FILES += \
    frameworks/base/data/etc/platform.xml:system/etc/permissions/platform.xml \
    frameworks/base/data/etc/handheld_core_hardware.xml:system/etc/permissions/handheld_core_hardware.xml \
    frameworks/base/data/etc/android.hardware.camera.front.xml:system/etc/permissions/android.hardware.camera.front.xml \
    frameworks/base/data/etc/android.hardware.camera.flash-autofocus.xml:system/etc/permissions/android.hardware.camera.flash-autofocus.xml \
    frameworks/base/data/etc/android.hardware.telephony.gsm.xml:system/etc/permissions/android.hardware.telephony.gsm.xml \
    frameworks/base/data/etc/android.software.sip.voip.xml:system/etc/permissions/android.software.sip.voip.xml \
    frameworks/base/data/etc/android.hardware.wifi.xml:system/etc/permissions/android.hardware.wifi.xml \
    frameworks/base/data/etc/android.hardware.touchscreen.multitouch.distinct.xml:system/etc/permissions/android.hardware.touchscreen.multitouch.distinct.xml \
    frameworks/base/data/etc/android.hardware.sensor.proximity.xml:system/etc/permissions/android.hardware.sensor.proximity.xml \
    frameworks/base/data/etc/android.hardware.sensor.light.xml:system/etc/permissions/android.hardware.sensor.light.xml \
    frameworks/base/data/etc/android.hardware.usb.accessory.xml:system/etc/permissions/android.hardware.usb.accessory.xml \
    frameworks/base/data/etc/android.hardware.location.gps.xml:system/etc/permissions/android.hardware.location.gps.xml

# Prebuilt kl keymaps
PRODUCT_COPY_FILES += \
    device/coolpad/D530/TWL4030_Keypad.kl:system/usr/keylayout/TWL4030_Keypad.kl \
    device/coolpad/D530/gpio-keys.kl:system/usr/keylayout/gpio-keys.kl

# root/
PRODUCT_COPY_FILES += \
	device/coolpad/D530/recovery/g_via_usermode.ko:recovery/root/lib/g_via_usermode.ko \
	device/coolpad/D530/init.rc:root/init.rc \
	device/coolpad/D530/init_download.rc:root/init_download.rc \
	device/coolpad/D530/ueventd.rc:root/ueventd.rc \
	device/coolpad/D530/env.txt:root/env.txt

#kernel
ifeq ($(TARGET_PREBUILT_KERNEL),)
	LOCAL_KERNEL := device/coolpad/D530/kernel2.2
else
	LOCAL_KERNEL := $(TARGET_PREBUILT_KERNEL)
endif
PRODUCT_COPY_FILES += \
    $(LOCAL_KERNEL):kernel

PRODUCT_NAME := D530
PRODUCT_DEVICE := D530
PRODUCT_MODEL := coolpad D530
PRODUCT_BRAND := coolpad
PRODUCT_MANUFACTURER := coolpad
