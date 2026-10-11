// JNI bridge to LibRaw: open a RAW from a memory buffer, develop it (white balance from the camera, sRGB, 8 bit) into an Android bitmap.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include "libraw/libraw.h"

#define TAG "FeldRaw"
#define JEXPORT extern "C" JNIEXPORT __attribute__((visibility("default")))

struct Dev {
    LibRaw raw;
    libraw_processed_image_t* img = nullptr;
    ~Dev() { if (img) LibRaw::dcraw_clear_mem(img); raw.recycle(); }
};

static void setErr(JNIEnv* env, jobjectArray out, const char* what, int code) {
    std::string s = std::string(what) + ": " + libraw_strerror(code);
    env->SetObjectArrayElement(out, 0, env->NewStringUTF(s.c_str()));
}

// Returns a handle (0 on failure; out[0] then carries the reason). halfSize=1 develops at half the resolution (4x less memory, much faster).
JEXPORT jlong JNICALL Java_app_feldkit_photo_RawDeveloper_nativeDevelop(JNIEnv* env, jclass, jobject buffer, jlong length, jint halfSize, jobjectArray err) {
    void* data = env->GetDirectBufferAddress(buffer);
    if (!data || length <= 0) { env->SetObjectArrayElement(err, 0, env->NewStringUTF("no data")); return 0; }
    Dev* d = new Dev();
    int rc = d->raw.open_buffer(data, (size_t)length);
    if (rc != LIBRAW_SUCCESS) { setErr(env, err, "Cannot read this RAW", rc); delete d; return 0; }
    rc = d->raw.unpack();
    if (rc != LIBRAW_SUCCESS) { setErr(env, err, "Cannot decode this RAW", rc); delete d; return 0; }
    auto& p = d->raw.imgdata.params;
    p.use_camera_wb = 1; p.output_bps = 8; p.output_color = 1; p.no_auto_bright = 0; p.half_size = halfSize ? 1 : 0;
    p.user_qual = 3;     // AHD
    p.use_auto_wb = 0; p.highlight = 0;
    rc = d->raw.dcraw_process();
    if (rc != LIBRAW_SUCCESS) { setErr(env, err, "Cannot develop this RAW", rc); delete d; return 0; }
    int e = 0;
    d->img = d->raw.dcraw_make_mem_image(&e);
    if (!d->img || d->img->type != LIBRAW_IMAGE_BITMAP || d->img->colors != 3) { setErr(env, err, "Cannot make the picture", e); delete d; return 0; }
    return (jlong)(intptr_t)d;
}

// width, height, iso, shutter (s), aperture, focal length (mm), then camera make / model / lens as strings in info.
JEXPORT jintArray JNICALL Java_app_feldkit_photo_RawDeveloper_nativeSize(JNIEnv* env, jclass, jlong h) {
    Dev* d = (Dev*)(intptr_t)h; jint v[2] = { d->img->width, d->img->height };
    jintArray a = env->NewIntArray(2); env->SetIntArrayRegion(a, 0, 2, v); return a;
}

JEXPORT jobjectArray JNICALL Java_app_feldkit_photo_RawDeveloper_nativeInfo(JNIEnv* env, jclass, jlong h) {
    Dev* d = (Dev*)(intptr_t)h; auto& o = d->raw.imgdata.other; auto& id = d->raw.imgdata.idata; auto& lens = d->raw.imgdata.lens; auto& sz = d->raw.imgdata.sizes;
    char buf[64]; jclass sc = env->FindClass("java/lang/String");
    jobjectArray r = env->NewObjectArray(9, sc, nullptr);
    auto put = [&](int i, const std::string& s) { env->SetObjectArrayElement(r, i, env->NewStringUTF(s.c_str())); };
    put(0, id.make); put(1, id.model); put(2, lens.Lens);
    snprintf(buf, sizeof buf, "%.0f", o.iso_speed); put(3, buf);
    snprintf(buf, sizeof buf, "%g", o.shutter); put(4, buf);
    snprintf(buf, sizeof buf, "%g", o.aperture); put(5, buf);
    snprintf(buf, sizeof buf, "%g", o.focal_len); put(6, buf);
    snprintf(buf, sizeof buf, "%dx%d", sz.raw_width, sz.raw_height); put(7, buf);
    put(8, id.software);
    return r;
}

JEXPORT jboolean JNICALL Java_app_feldkit_photo_RawDeveloper_nativeFill(JNIEnv* env, jclass, jlong h, jobject bitmap) {
    Dev* d = (Dev*)(intptr_t)h; AndroidBitmapInfo bi; void* px = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) < 0 || bi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if ((int)bi.width != d->img->width || (int)bi.height != d->img->height) return JNI_FALSE;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) < 0) return JNI_FALSE;
    const unsigned char* src = d->img->data;
    for (int y = 0; y < d->img->height; y++) {
        unsigned char* dst = (unsigned char*)px + (size_t)y * bi.stride;
        for (int x = 0; x < d->img->width; x++) { dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]; dst[3] = 255; dst += 4; src += 3; }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}

JEXPORT void JNICALL Java_app_feldkit_photo_RawDeveloper_nativeFree(JNIEnv*, jclass, jlong h) { delete (Dev*)(intptr_t)h; }

// The camera's own thumbnail (JPEG, or an uncompressed bitmap as some Nikon / Kodak / older cameras store it).
JEXPORT jlong JNICALL Java_app_feldkit_photo_RawDeveloper_nativeThumb(JNIEnv* env, jclass, jobject buffer, jlong length, jobjectArray err) {
    void* data = env->GetDirectBufferAddress(buffer);
    if (!data || length <= 0) { env->SetObjectArrayElement(err, 0, env->NewStringUTF("no data")); return 0; }
    Dev* d = new Dev();
    int rc = d->raw.open_buffer(data, (size_t)length);
    if (rc != LIBRAW_SUCCESS) { setErr(env, err, "Cannot read this RAW", rc); delete d; return 0; }
    rc = d->raw.unpack_thumb();
    if (rc != LIBRAW_SUCCESS) { setErr(env, err, "No thumbnail", rc); delete d; return 0; }
    int e = 0;
    d->img = d->raw.dcraw_make_mem_thumb(&e);
    if (!d->img) { setErr(env, err, "No thumbnail", e); delete d; return 0; }
    return (jlong)(intptr_t)d;
}

JEXPORT jboolean JNICALL Java_app_feldkit_photo_RawDeveloper_nativeIsJpeg(JNIEnv*, jclass, jlong h) { return ((Dev*)(intptr_t)h)->img->type == LIBRAW_IMAGE_JPEG; }

JEXPORT jbyteArray JNICALL Java_app_feldkit_photo_RawDeveloper_nativeJpeg(JNIEnv* env, jclass, jlong h) {
    Dev* d = (Dev*)(intptr_t)h; jbyteArray a = env->NewByteArray(d->img->data_size);
    env->SetByteArrayRegion(a, 0, d->img->data_size, (const jbyte*)d->img->data); return a;
}
