ifeq ($(HARDWARE_OMX),true)

LOCAL_PATH:= $(call my-dir)
TI_OMAP_TOP   := $(ANDROID_BUILD_TOP)/hardware/ti/omap3-compat



include $(CLEAR_VARS)

LOCAL_PRELINK_MODULE := false

LOCAL_SRC_FILES:= \
	OMX_Core.c

LOCAL_C_INCLUDES += \
	$(TI_OMX_INCLUDES) \

LOCAL_C_INCLUDES += \
	$(TI_OMAP_TOP)/omx/system/src/openmax_il/omx_core/inc \


LOCAL_SHARED_LIBRARIES := \
	libdl \
	liblog
	
LOCAL_CFLAGS := $(TI_OMX_CFLAGS)

LOCAL_MODULE:= libOMX_Core
LOCAL_MODULE_TAGS := eng

include $(BUILD_SHARED_LIBRARY)

endif
