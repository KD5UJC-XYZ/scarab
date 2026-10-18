/* SPDX-License-Identifier: Apache-2.0
 * Adapted from scalus3/scalus c132a2c8615db9cd27b464b7863863cd576d23be,
 * scalus-crypto-jni/native/{blst.c,jni_util.h}. Original Apache-2.0 licence.
 * Changes: retain only byte-array hashToGroup; return standard compressed points for blst-java.
 * No curve arithmetic is implemented here: pinned libblst performs hashing and compression.
 */
#include <jni.h>
#include <stdlib.h>
#include <blst.h>

static unsigned char *read_bytes(JNIEnv *env, jbyteArray a, jsize *len) {
    if (a == NULL) {
        jclass cls = (*env)->FindClass(env, "java/lang/NullPointerException");
        if (cls != NULL) (*env)->ThrowNew(env, cls, "scarab blst: null byte array");
        return NULL;
    }
    *len = (*env)->GetArrayLength(env, a);
    unsigned char *buf = malloc(*len > 0 ? (size_t) *len : 1);
    if (buf == NULL) {
        jclass cls = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (cls != NULL) (*env)->ThrowNew(env, cls, "scarab blst: native allocation failed");
        return NULL;
    }
    if (*len > 0) (*env)->GetByteArrayRegion(env, a, 0, *len, (jbyte *) buf);
    if ((*env)->ExceptionCheck(env)) { free(buf); return NULL; }
    return buf;
}

#define HASH_TO_GROUP(NAME, GROUP, POINT, LENGTH)                                        \
JNIEXPORT jbyteArray JNICALL Java_xyz_kd5ujc_scarab_blst_RawDst_##NAME(                   \
    JNIEnv *env, jclass c, jbyteArray msg, jbyteArray dst) {                             \
    (void)c;                                                                           \
    jsize ml, dl; blst_##POINT point; unsigned char compressed[LENGTH];                \
    unsigned char *m = read_bytes(env, msg, &ml);                                        \
    if (m == NULL) return NULL;                                                         \
    unsigned char *d = read_bytes(env, dst, &dl);                                        \
    if (d == NULL) { free(m); return NULL; }                                            \
    blst_hash_to_##GROUP(&point, m, (size_t)ml, d, (size_t)dl, NULL, 0);                 \
    free(m); free(d);                                                                  \
    blst_##POINT##_compress(compressed, &point);                                        \
    jbyteArray out = (*env)->NewByteArray(env, LENGTH);                                  \
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, LENGTH,                     \
                                               (const jbyte *)compressed);             \
    return out;                                                                        \
}

HASH_TO_GROUP(hashG1, g1, p1, 48)
HASH_TO_GROUP(hashG2, g2, p2, 96)
