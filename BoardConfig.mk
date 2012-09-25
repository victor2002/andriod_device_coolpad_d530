# inherit from the proprietary version
#-include vendor/coolpad/D530/D530-vendor.mk

TARGET_PRODUCT:= Coolpad

# Board configuration

TARGET_NO_BOOTLOADER := true
#TARGET_NO_KERNEL := true
TARGET_NO_RADIOIMAGE := true

TARGET_BOARD := D530
TARGET_BOOTLOADER_BOARD_NAME := D530
TARGET_USES_OLD_LIBSENSORS_HAL := true
TARGET_SENSORS_NO_OPEN_CHECK := true
TARGET_PROVIDES_INIT_RC := true

#cpu
TARGET_BOARD_PLATFORM := omap3
TARGET_CPU_ABI  := armeabi-v7a
TARGET_CPU_ABI2 := armeabi
TARGET_ARCH_VARIANT := armv7-a-neon
TARGET_GLOBAL_CFLAGS += -mtune=cortex-a8
TARGET_GLOBAL_CPPFLAGS += -mtune=cortex-a8
ARCH_ARM_HAVE_TLS_REGISTER := true
TARGET_OMAP3 := true
#COMMON_GLOBAL_CFLAGS += -DTARGET_OMAP3 -DOMAP_COMPAT
COMMON_GLOBAL_CFLAGS += -DTARGET_OMAP3

BOARD_KERNEL_BASE := 0x82000000
BOARD_PAGE_SIZE := 0x00000800

BOARD_BOOTIMAGE_PARTITION_SIZE := 0x00400000
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 0x00500000
BOARD_SYSTEMIMAGE_PARTITION_SIZE := 0x10e00000
BOARD_USERDATAIMAGE_PARTITION_SIZE := 0x08200000
BOARD_FLASH_BLOCK_SIZE := 131072

#recovery
TARGET_PREBUILT_RECOVERY_KERNEL := device/coolpad/D530/kernel2.2
TARGET_PREBUILT_KERNEL := device/coolpad/D530/kernel2.2
BOARD_CUSTOM_RECOVERY_KEYMAPPING:= ../../device/coolpad/D530/recovery/D530_recovery_ui.c
#BOARD_CUSTOM_GRAPHICS:= ../../../device/coolpad/D530/recovery/graphics-chs.c
TARGET_RECOVERY_INITRC := device/coolpad/D530/recovery/init.rc
#BOARD_HAS_SMALL_RECOVERY := true

TARGET_HAS_POINTERCAL := true

#sound
#TARGET_PROVIDES_LIBAUDIO :=true
# joy fish open
BOARD_USES_ALSA_AUDIO := true
BUILD_WITH_ALSA_UTILS := true
ALSA_DEFAULT_SAMPLE_RATE := 44100

#gps
#BOARD_GPS_LIBRARIES := libsecgps libsecril-client
#BOARD_USES_GPSSHIM := true

# use samsung libril to get gps
TARGET_PROVIDES_LIBRIL := true

# Wifi
USES_TI_WL1271 := true
BOARD_WPA_SUPPLICANT_DRIVER := CUSTOM
BOARD_WLAN_DEVICE           := wl1271
BOARD_SOFTAP_DEVICE 		:= wl1271
WPA_SUPPLICANT_VERSION      := VER_0_6_X
#WPA_SUPPL_APPROX_USE_RSSI   := true
WIFI_DRIVER_MODULE_PATH     := "/system/etc/wifi/tiwlan_drv.ko"
WIFI_DRIVER_MODULE_NAME     := "tiwlan_drv"
#WIFI_DRIVER_FW_STA_PATH     := "/system/etc/wifi/fw_wlan1271.bin"
WIFI_FIRMWARE_LOADER        := "wlan_loader"
AP_CONFIG_DRIVER_WILINK     := true
#PRODUCT_WIRELESS_TOOLS      := true

#WIFI_DRIVER_FW_AP_PATH := "/system/etc/wifi/fw_tiwlan_ap.bin"

#egl
BOARD_USE_YUV422I_DEFAULT_COLORFORMAT := true
#BOARD_USE_UYVY_CAPTURE_COLORFORMAT := true
# Workaround for eglconfig error
#BOARD_NO_RGBX_8888 := true
#TARGET_LIBAGL_USE_GRALLOC_COPYBITS := true
#TARGET_ELECTRONBEAM_FRAMES := 10

# Bluetooth
BOARD_HAVE_BLUETOOTH := true

# no GPS atm
BOARD_HAVE_FAKE_GPS := false

# FM
OMAP_ENHANCEMENT := true
COMMON_GLOBAL_CFLAGS += -DOMAP_ENHANCEMENT
#BOARD_HAVE_FM_RADIO := true
#BOARD_FM_DEVICE := si4709
#BOARD_GLOBAL_CFLAGS += -DHAVE_FM_RADIO

# Camera
#USE_CAMERA_STUB := true
#BOARD_CAMERA_LIBRARIES := libcamera
#TARGET_PROVIDES_LIBCAMERA := true
BOARD_USE_FROYO_LIBCAMERA := true
BOARD_CAMERA_LIBRARIES := libcamera
TARGET_PROVIDES_LIBCAMERA := false

#dsp
HARDWARE_OMX := true
#BUILD_WITHOUT_PV := false
#use hw ti omx audio codecs
BUILD_WITH_TI_AUDIO := 1
BUILD_PV_VIDEO_ENCODERS := 1
#FW3A := true
#ICAP := true
#IMAGE_PROCESSING_PIPELINE := true 
ifdef HARDWARE_OMX
OMX_JPEG := true
OMX_VENDOR := ti
OMX_VENDOR_INCLUDES := \
   hardware/ti/omap3-compat/omx/system/src/openmax_il/omx_core/inc \
   hardware/ti/omap3-compat/omx/image/src/openmax_il/jpeg_enc/inc
OMX_VENDOR_WRAPPER := TI_OMX_Wrapper
BOARD_OPENCORE_LIBRARIES := libOMX_Core
BOARD_OPENCORE_FLAGS := -DHARDWARE_OMX=1
endif

WITH_JIT := true
ENABLE_JSC_JIT := true

#vold
#BOARD_USE_USB_MASS_STORAGE_SWITCH := true
#BOARD_VOLD_EMMC_SHARES_DEV_MAJOR := true
TARGET_USE_CUSTOM_LUN_FILE_PATH := /sys/devices/platform/musb_hdrc/gadget/lun
#BOARD_UMS_LUNFILE := "/sys/devices/platform/musb_hdrc/gadget/lun2/file"

WITH_DEXPREOPT := true
