/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Public-input driver for the pinned libsodium: reads rows of tcId, public key, signature and
 * message (hex, tab separated, empty fields kept) on standard input and writes the tcId and the
 * verdict of crypto_sign_ed25519_verify_detached. A key that is not 32 bytes or a signature that
 * is not 64 bytes is never passed to the library: its verdict is NotRunMalformedLength. No
 * signing, key generation or secret key is used.
 */
#define _DEFAULT_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sodium.h>

static int unhex(const char *s, size_t n, unsigned char *out) {
    for (size_t i = 0; i < n / 2; i++) {
        unsigned int b;
        if (sscanf(s + 2 * i, "%2x", &b) != 1) return -1;
        out[i] = (unsigned char) b;
    }
    return 0;
}

int main(void) {
    static char line[1 << 20];
    fprintf(stderr, "libsodium %s\n", sodium_version_string());
    while (fgets(line, sizeof line, stdin)) {
        size_t len = strcspn(line, "\r\n");
        line[len] = 0;
        char *rest = line;
        char *id = strsep(&rest, "\t");
        char *pk = strsep(&rest, "\t");
        char *sig = strsep(&rest, "\t");
        char *msg = strsep(&rest, "\t");
        if (!id || !pk || !sig) { fprintf(stderr, "bad row\n"); return 2; }
        if (!msg) msg = "";
        size_t pkn = strlen(pk), sign = strlen(sig), msgn = strlen(msg);
        if (pkn != 64 || sign != 128) { printf("%s\tNotRunMalformedLength\n", id); continue; }
        unsigned char k[32], s[64];
        unsigned char *m = malloc(msgn / 2 + 1);
        if (!m || unhex(pk, pkn, k) || unhex(sig, sign, s) || unhex(msg, msgn, m)) {
            fprintf(stderr, "bad hex %s\n", id);
            return 2;
        }
        int r = crypto_sign_ed25519_verify_detached(s, m, msgn / 2, k);
        printf("%s\t%s\n", id, r == 0 ? "SignatureVerified" : "SignatureRejected");
        free(m);
    }
    return 0;
}
