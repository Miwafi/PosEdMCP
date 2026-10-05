/*
 * The native half of injection.
 *
 * The module is loaded inside a target application's process, so this code runs
 * with that process's privileges and, more to the point, inside its address
 * space. What it adds over the Java layer is the part Java cannot reach: the
 * application's own .so files, libc, and anything else a symbol name resolves
 * to.
 *
 * The surface is deliberately small - open a library, find a symbol, call a
 * function pointer, read or write memory. Arguments and results are machine
 * words, so a function taking or returning a float, a double, or a struct by
 * value cannot be called through here. That is a real limit of a word-only
 * bridge rather than something to work around later.
 */
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#define TAG "PosEdNative"

typedef int64_t (*fn0_t)(void);
typedef int64_t (*fn1_t)(int64_t);
typedef int64_t (*fn2_t)(int64_t, int64_t);
typedef int64_t (*fn3_t)(int64_t, int64_t, int64_t);
typedef int64_t (*fn4_t)(int64_t, int64_t, int64_t, int64_t);
typedef int64_t (*fn5_t)(int64_t, int64_t, int64_t, int64_t, int64_t);
typedef int64_t (*fn6_t)(int64_t, int64_t, int64_t, int64_t, int64_t, int64_t);

JNIEXPORT jstring JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_probe(JNIEnv *env, jclass clazz) {
    char buf[192];
    snprintf(buf, sizeof buf, "native runtime ready: %d-bit pointers, pid %d",
             (int) (sizeof(void *) * 8), getpid());
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_lastError(JNIEnv *env, jclass clazz) {
    const char *error = dlerror();
    return (*env)->NewStringUTF(env, error == NULL ? "" : error);
}

/*
 * A dlopen handle on this platform is not always an address: for a library in a
 * non-default namespace the linker hands back a synthetic odd value that it
 * resolves through an internal map. Sending that out through Java and Lua and
 * back got it mangled - a dlsym with the returned value faulted inside the
 * linker's own namespace lookup - so handles never leave here. A caller gets a
 * small id instead, and this table is what it means.
 */
#define MAX_HANDLES 64
static void *g_handles[MAX_HANDLES];
static int g_handle_count = 0;

JNIEXPORT jint JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_openLibrary(JNIEnv *env, jclass clazz, jstring path) {
    if (path == NULL) {
        return 0;
    }
    const char *cPath = (*env)->GetStringUTFChars(env, path, NULL);
    if (cPath == NULL) {
        return 0;
    }
    dlerror();
    void *handle = dlopen(cPath, RTLD_NOW);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "dlopen(%s) failed: %s", cPath, dlerror());
    } else if (g_handle_count >= MAX_HANDLES) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "no room for another library handle");
        handle = NULL;
    } else {
        g_handles[g_handle_count++] = handle;
        __android_log_print(ANDROID_LOG_INFO, TAG, "dlopen(%s) -> id %d", cPath, g_handle_count);
    }
    (*env)->ReleaseStringUTFChars(env, path, cPath);
    return handle == NULL ? 0 : g_handle_count;
}

JNIEXPORT jlong JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_findSymbol(JNIEnv *env, jclass clazz, jint id,
        jstring name) {
    if (name == NULL || id <= 0 || id > g_handle_count) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "no library for handle id %d", id);
        return 0;
    }
    const char *cName = (*env)->GetStringUTFChars(env, name, NULL);
    if (cName == NULL) {
        return 0;
    }
    dlerror();
    void *symbol = dlsym(g_handles[id - 1], cName);
    __android_log_print(ANDROID_LOG_INFO, TAG, "dlsym(id %d, %s) -> %p", id, cName, symbol);
    (*env)->ReleaseStringUTFChars(env, name, cName);
    return (jlong) (intptr_t) symbol;
}

/*
 * Calls a function pointer. The arity is passed explicitly rather than inferred
 * because the cast has to match the real signature: reading six argument
 * registers for a function that takes none happens to work on this ABI, but it
 * is undefined behaviour and not something to rely on.
 */
JNIEXPORT jlong JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_call(JNIEnv *env, jclass clazz, jlong address, jint arity,
        jlong a0, jlong a1, jlong a2, jlong a3, jlong a4, jlong a5) {
    if (address == 0) {
        return 0;
    }
    void *fn = (void *) (intptr_t) address;
    switch (arity) {
        case 0:
            return (jlong) ((fn0_t) fn)();
        case 1:
            return (jlong) ((fn1_t) fn)(a0);
        case 2:
            return (jlong) ((fn2_t) fn)(a0, a1);
        case 3:
            return (jlong) ((fn3_t) fn)(a0, a1, a2);
        case 4:
            return (jlong) ((fn4_t) fn)(a0, a1, a2, a3);
        case 5:
            return (jlong) ((fn5_t) fn)(a0, a1, a2, a3, a4);
        case 6:
            return (jlong) ((fn6_t) fn)(a0, a1, a2, a3, a4, a5);
        default:
            return 0;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_readMemory(JNIEnv *env, jclass clazz, jlong address,
        jint length) {
    if (address == 0 || length <= 0) {
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, length);
    if (out == NULL) {
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte *) (intptr_t) address);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_dev_posedmcp_xposed_NativeRuntime_writeMemory(JNIEnv *env, jclass clazz, jlong address,
        jbyteArray bytes) {
    if (address == 0 || bytes == NULL) {
        return JNI_FALSE;
    }
    jsize length = (*env)->GetArrayLength(env, bytes);
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *) (intptr_t) address);
    return JNI_TRUE;
}
