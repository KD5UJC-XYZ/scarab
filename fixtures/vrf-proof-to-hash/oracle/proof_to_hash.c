/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Public-input driver for the pinned libsodium: reads rows of id and an 80-byte proof (hex, tab
 * separated) on standard input and writes the id and the result of
 * crypto_vrf_ietfdraft03_proof_to_hash: Output:<the 64-byte output in hex>, or Refused when it
 * returns nonzero. No key, prover or secret is used.
 */
#define _DEFAULT_SOURCE
#include <stdio.h>
#include <string.h>
#include <sodium.h>

int main(void) {
    static char line[4096];
    fprintf(stderr, "libsodium %s\n", sodium_version_string());
    while (fgets(line, sizeof line, stdin)) {
        line[strcspn(line, "\r\n")] = 0;
        char *rest = line;
        char *id = strsep(&rest, "\t");
        char *hex = strsep(&rest, "\t");
        if (!id || !hex || strlen(hex) != 2 * crypto_vrf_ietfdraft03_BYTES) {
            fprintf(stderr, "bad row\n");
            return 2;
        }
        unsigned char pi[crypto_vrf_ietfdraft03_BYTES], beta[crypto_vrf_ietfdraft03_OUTPUTBYTES];
        for (size_t i = 0; i < sizeof pi; i++) {
            unsigned int b;
            if (sscanf(hex + 2 * i, "%2x", &b) != 1) { fprintf(stderr, "bad hex %s\n", id); return 2; }
            pi[i] = (unsigned char) b;
        }
        if (crypto_vrf_ietfdraft03_proof_to_hash(beta, pi) != 0) {
            printf("%s\tRefused\n", id);
        } else {
            printf("%s\tOutput:", id);
            for (size_t i = 0; i < sizeof beta; i++) printf("%02x", beta[i]);
            printf("\n");
        }
    }
    return 0;
}
