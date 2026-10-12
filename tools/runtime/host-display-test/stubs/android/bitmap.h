/* Host stand-in for <android/bitmap.h>; bitmaps are not used by the pipeline test. */
#pragma once
#include <jni.h>
#include <stdint.h>
typedef struct { uint32_t width, height, stride; int32_t format; uint32_t flags; } AndroidBitmapInfo;
static inline int AndroidBitmap_getInfo(JNIEnv *e, jobject b, AndroidBitmapInfo *i) { (void)e; (void)b; i->width = i->height = i->stride = 0; return -1; }
static inline int AndroidBitmap_lockPixels(JNIEnv *e, jobject b, void **p) { (void)e; (void)b; *p = 0; return -1; }
static inline int AndroidBitmap_unlockPixels(JNIEnv *e, jobject b) { (void)e; (void)b; return 0; }
