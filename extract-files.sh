#!/bin/sh

# Copyright (C) 2010 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

DEVICE=D530

mkdir -p ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/gps.via.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libaudio.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libcamera.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/librpcril.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/egl/libEGL_POWERVR_SGX530_125.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/egl/libGLESv1_CM_POWERVR_SGX530_125.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/egl/libGLESv2_POWERVR_SGX530_125.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/hw/gralloc.omap3.so ../../../vendor/coolpad/$DEVICE/proprietary

adb pull /system/app/CP_FactoryPattern.apk ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/app/CP_ModemMode.apk ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/app/CP_EngMode.apk ../../../vendor/coolpad/$DEVICE/proprietary

#adb pull /system/lib/libbattd.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/libglslcompiler.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/libHPImgApi.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libIMGegl.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/libinterstitial.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libLCML.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/liblvmxipc.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libcdma_via_ril.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libcdmaapi.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libicamera.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libOMX.TI.AAC.decode.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libOMX.TI.AMR.encode.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libOMX.TI.MP3.decode.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libOMX.TI.WBAMR.decode.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libOMX.TI.WMA.decode.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libopencore_asflocal.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libopencore_asflocalreg.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/libpppd_plugin-ril.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libpvr2d.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libpvrANDROID_WSEGL.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/libspeech.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libsrv_um.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libVendor_ti_omx.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libVendor_ti_omx_config_parser.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/libzxing.so ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/lib/zxing.so ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/bin/alsa_phone ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/ap_gain.bin ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/battd ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/bthelp ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/bin/PhoneSlotService ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/ftmipcd ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/mdm_panicd ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/bin/pppd-ril ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/bin/pvrsrvinit ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/SaveBPVer ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/bin/tcmd ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/etc/01_Vendor_ti_omx.cfg ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/etc/omapcam/eraCalFileDef.bin ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/etc/contributors.css ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/etc/excluded-input-devices.xml ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/etc/wifi/wl1271.bin ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/etc/gps.conf ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/etc/coolpad/12m/key_code_map.txt ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/etc/ppp/peers/pppd-ril.options ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/etc/pvplayer.cfg ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/etc/updatecmds/google_generic_update.txt ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/etc/wifi/firmware.bin ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/baseimage.dof ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/conversions.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/h264vdec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/h264venc_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/jpegenc_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/m4venc_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/mp3dec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/mp4vdec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/mpeg4aacdec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/mpeg4aacenc_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/nbamrdec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/nbamrenc_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/postprocessor_dualout.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/ringio.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/usn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/wbamrdec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/wbamrenc_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/wmadec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/lib/dsp/wmv9dec_sn.dll64P ../../../vendor/coolpad/$DEVICE/proprietary
adb pull /system/usr/keychars/sholes-keypad.kcm.bin ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/usr/keylayout/cpcap-key.kl ../../../vendor/coolpad/$DEVICE/proprietary
#adb pull /system/usr/keylayout/sholes-keypad.kl ../../../vendor/coolpad/$DEVICE/proprietary


(cat << EOF) | sed s/__DEVICE__/$DEVICE/g > ../../../vendor/coolpad/$DEVICE/$DEVICE-vendor-blobs.mk
# Copyright (C) 2010 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This file is generated by device/coolpad/__DEVICE__/extract-files.sh

# Prebuilt libraries that are needed to build open-source libraries
PRODUCT_COPY_FILES := \\
    vendor/coolpad/__DEVICE__/proprietary/gps_via.so:obj/lib/gps_via.so \\
    vendor/coolpad/__DEVICE__/proprietary/libcamera.so:obj/lib/libcamera.so \\
    vendor/coolpad/__DEVICE__/proprietary/libaudio.so:obj/lib/libaudio.so \\
    vendor/coolpad/__DEVICE__/proprietary/librpcril.so:obj/lib/librpc.so \\
    vendor/coolpad/__DEVICE__/proprietary/gralloc.omap3.so:obj/lib/hw/gralloc.omap3.so \\

PRODUCT_COPY_FILES += \\
#    vendor/coolpad/__DEVICE__/proprietary/ProgramMenuSystem.apk:/system/app/ProgramMenuSystem.apk \\
#    vendor/coolpad/__DEVICE__/proprietary/ProgramMenu.apk:/system/app/ProgramMenu.apk \\
#    vendor/coolpad/__DEVICE__/proprietary/PhoneConfig.apk:/system/app/PhoneConfig.apk

# All the blobs necessary for sholes
PRODUCT_COPY_FILES += \\
    vendor/coolpad/__DEVICE__/proprietary/gps_via.so:/system/lib/gps_via.so \\
    vendor/coolpad/__DEVICE__/proprietary/libcamera.so:/system/lib/libcamera.so \\
    vendor/coolpad/__DEVICE__/proprietary/libaudio.so:/system/lib/libaudio.so \\
    vendor/coolpad/__DEVICE__/proprietary/librpcril.so:/system/lib/librpcril.so \\
    vendor/coolpad/__DEVICE__/proprietary/libEGL_POWERVR_SGX530_125.so:/system/lib/egl/libEGL_POWERVR_SGX530_125.so \\
    vendor/coolpad/__DEVICE__/proprietary/libGLESv1_CM_POWERVR_SGX530_125.so:/system/lib/egl/libGLESv1_CM_POWERVR_SGX530_125.so \\
    vendor/coolpad/__DEVICE__/proprietary/libGLESv2_POWERVR_SGX530_125.so:/system/lib/egl/libGLESv2_POWERVR_SGX530_125.so \\
    vendor/coolpad/__DEVICE__/proprietary/gralloc.omap3.so:/system/lib/hw/gralloc.omap3.so \\
#   vendor/coolpad/__DEVICE__/proprietary/libbattd.so:/system/lib/libbattd.so \\
#    vendor/coolpad/__DEVICE__/proprietary/libglslcompiler.so:/system/lib/libglslcompiler.so \\
#    vendor/coolpad/__DEVICE__/proprietary/libHPImgApi.so:/system/lib/libHPImgApi.so \\
    vendor/coolpad/__DEVICE__/proprietary/libIMGegl.so:/system/lib/libIMGegl.so \\
#    vendor/coolpad/__DEVICE__/proprietary/libinterstitial.so:/system/lib/libinterstitial.so \\
    vendor/coolpad/__DEVICE__/proprietary/libLCML.so:/system/lib/libLCML.so \\
#    vendor/coolpad/__DEVICE__/proprietary/liblvmxipc.so:/system/lib/liblvmxipc.so \\
    vendor/coolpad/__DEVICE__/proprietary/libcdma_via_ril.so:/system/lib/libcdma_via_ril.so \\
    vendor/coolpad/__DEVICE__/proprietary/libicamera.so:/system/lib/libicamera.so \\
    vendor/coolpad/__DEVICE__/proprietary/libOMX.TI.AAC.decode.so:/system/lib/libOMX.TI.AAC.decode.so \\
    vendor/coolpad/__DEVICE__/proprietary/libOMX.TI.AMR.encode.so:/system/lib/libOMX.TI.AMR.encode.so \\
    vendor/coolpad/__DEVICE__/proprietary/libOMX.TI.MP3.decode.so:/system/lib/libOMX.TI.MP3.decode.so \\
    vendor/coolpad/__DEVICE__/proprietary/libOMX.TI.WBAMR.decode.so:/system/lib/libOMX.TI.WBAMR.decode.so \\
    vendor/coolpad/__DEVICE__/proprietary/libOMX.TI.WMA.decode.so:/system/lib/libOMX.TI.WMA.decode.so \\
    vendor/coolpad/__DEVICE__/proprietary/libopencore_asflocal.so:/system/lib/libopencore_asflocal.so \\
    vendor/coolpad/__DEVICE__/proprietary/libopencore_asflocalreg.so:/system/lib/libopencore_asflocalreg.so \\
    vendor/coolpad/__DEVICE__/proprietary/libpppd_plugin-ril.so:/system/lib/libpppd_plugin-ril.so \\
    vendor/coolpad/__DEVICE__/proprietary/libpvr2d.so:/system/lib/libpvr2d.so \\
    vendor/coolpad/__DEVICE__/proprietary/libpvrANDROID_WSEGL.so:/system/lib/libpvrANDROID_WSEGL.so \\
#    vendor/coolpad/__DEVICE__/proprietary/libspeech.so:/system/lib/libspeech.so \\
    vendor/coolpad/__DEVICE__/proprietary/libsrv_um.so:/system/lib/libsrv_um.so \\
    vendor/coolpad/__DEVICE__/proprietary/libVendor_ti_omx.so:/system/lib/libVendor_ti_omx.so \\
    vendor/coolpad/__DEVICE__/proprietary/libVendor_ti_omx_config_parser.so:/system/lib/libVendor_ti_omx_config_parser.so \\
#    vendor/coolpad/__DEVICE__/proprietary/libzxing.so:/system/lib/libzxing.so \\
#    vendor/coolpad/__DEVICE__/proprietary/zxing.so:/system/lib/zxing.so \\
    vendor/coolpad/__DEVICE__/proprietary/alsa_phone:/system/bin/alsa_phone \\
#    vendor/coolpad/__DEVICE__/proprietary/ap_gain.bin:/system/bin/ap_gain.bin \\
#    vendor/coolpad/__DEVICE__/proprietary/battd:/system/bin/battd \\
#    vendor/coolpad/__DEVICE__/proprietary/bthelp:/system/bin/bthelp \\
    vendor/coolpad/__DEVICE__/proprietary/PhoneSlotService:/system/bin/PhoneSlotService \\
#    vendor/coolpad/__DEVICE__/proprietary/ftmipcd:/system/bin/ftmipcd \\
#    vendor/coolpad/__DEVICE__/proprietary/mdm_panicd:/system/bin/mdm_panicd \\
    vendor/coolpad/__DEVICE__/proprietary/pppd-ril:/system/bin/pppd-ril \\
    vendor/coolpad/__DEVICE__/proprietary/pvrsrvinit:/system/bin/pvrsrvinit \\
#    vendor/coolpad/__DEVICE__/proprietary/SaveBPVer:/system/bin/SaveBPVer \\
#    vendor/coolpad/__DEVICE__/proprietary/tcmd:/system/bin/tcmd \\
    vendor/coolpad/__DEVICE__/proprietary/01_Vendor_ti_omx.cfg:/system/etc/01_Vendor_ti_omx.cfg \\
#    vendor/coolpad/__DEVICE__/proprietary/cameraCalFileDef.bin:/system/etc/cameraCalFileDef.bin \\
#    vendor/coolpad/__DEVICE__/proprietary/contributors.css:/system/etc/contributors.css \\
#    vendor/coolpad/__DEVICE__/proprietary/excluded-input-devices.xml:/system/etc/excluded-input-devices.xml \\
#    vendor/coolpad/__DEVICE__/proprietary/wl1271.bin:/system/etc/firmware/wl1271.bin \\
#    vendor/coolpad/__DEVICE__/proprietary/gps.conf:/system/etc/gps.conf \\
#    vendor/coolpad/__DEVICE__/proprietary/key_code_map.txt:/system/etc/coolpad/12m/key_code_map.txt \\
#    vendor/coolpad/__DEVICE__/proprietary/pppd-ril.options:/system/etc/ppp/peers/pppd-ril.options \\
    vendor/coolpad/__DEVICE__/proprietary/pvplayer.cfg:/system/etc/pvplayer_mot.cfg \\
#   vendor/coolpad/__DEVICE__/proprietary/google_generic_update.txt:/system/etc/updatecmds/google_generic_update.txt \\
    vendor/coolpad/__DEVICE__/proprietary/firmware.bin:/system/etc/wifi/firmware.bin.bin \\
    vendor/coolpad/__DEVICE__/proprietary/baseimage.dof:/system/lib/dsp/baseimage.dof \\
    vendor/coolpad/__DEVICE__/proprietary/conversions.dll64P:/system/lib/dsp/conversions.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/h264vdec_sn.dll64P:/system/lib/dsp/h264vdec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/h264venc_sn.dll64P:/system/lib/dsp/h264venc_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/jpegenc_sn.dll64P:/system/lib/dsp/jpegenc_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/m4venc_sn.dll64P:/system/lib/dsp/m4venc_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/mp3dec_sn.dll64P:/system/lib/dsp/mp3dec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/mp4vdec_sn.dll64P:/system/lib/dsp/mp4vdec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/mpeg4aacdec_sn.dll64P:/system/lib/dsp/mpeg4aacdec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/mpeg4aacenc_sn.dll64P:/system/lib/dsp/mpeg4aacenc_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/nbamrdec_sn.dll64P:/system/lib/dsp/nbamrdec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/nbamrenc_sn.dll64P:/system/lib/dsp/nbamrenc_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/postprocessor_dualout.dll64P:/system/lib/dsp/postprocessor_dualout.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/ringio.dll64P:/system/lib/dsp/ringio.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/usn.dll64P:/system/lib/dsp/usn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/wbamrdec_sn.dll64P:/system/lib/dsp/wbamrdec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/wbamrenc_sn.dll64P:/system/lib/dsp/wbamrenc_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/wmadec_sn.dll64P:/system/lib/dsp/wmadec_sn.dll64P \\
    vendor/coolpad/__DEVICE__/proprietary/wmv9dec_sn.dll64P:/system/lib/dsp/wmv9dec_sn.dll64P \\
#    vendor/coolpad/__DEVICE__/proprietary/sholes-keypad.kcm.bin:/system/usr/keychars/sholes-keypad.kcm.bin \\
#    vendor/coolpad/__DEVICE__/proprietary/cpcap-key.kl:/system/usr/keylayout/cpcap-key.kl \\
#    vendor/coolpad/__DEVICE__/proprietary/sholes-keypad.kl:/system/usr/keylayout/sholes-keypad.kl


EOF

./setup-makefiles.sh
