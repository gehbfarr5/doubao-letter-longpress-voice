LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := doubaolongpress_native
LOCAL_SRC_FILES := native_bridge.cpp
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden -Wall -Wextra -Werror
LOCAL_LDLIBS := -llog
LOCAL_ARM_MODE := arm
include $(BUILD_SHARED_LIBRARY)
