# Top-level ndk-build script: just pull in the vendored hev-socks5-tunnel (MIT).
LOCAL_PATH := $(call my-dir)
include $(LOCAL_PATH)/hev-socks5-tunnel/Android.mk
