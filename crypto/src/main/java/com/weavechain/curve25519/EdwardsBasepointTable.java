// SPDX-License-Identifier: Apache-2.0 AND MIT
/*
 * This file is part of curve25519-elisabeth.
 * Copyright (c) 2019 Jack Grigg
 * See LICENSE for licensing information.
 */

package com.weavechain.curve25519;

/**
 * A pre-computed table of multiples of a basepoint, for accelerating fixed-base
 * scalar multiplication.
 */
public class EdwardsBasepointTable {
    final AffineNielsPoint.LookupTable[] tables;

    /**
     * Create a table of pre-computed multiples of basepoint.
     */
    public EdwardsBasepointTable(final EdwardsPoint basepoint) {
        this.tables = new AffineNielsPoint.LookupTable[32];
        EdwardsPoint Bi = basepoint;
        for (int i = 0; i < 32; i++) {
            this.tables[i] = AffineNielsPoint.buildLookupTable(Bi);
            // Only every second summand is precomputed (16^2 = 256)
            Bi = Bi.multiplyByPow2(8);
        }
    }

    /**
     * Constant-time fixed-base scalar multiplication.
     *
     * @param s the Scalar to multiply by.
     * @return $[s]B$
     */
    public EdwardsPoint multiply(final Scalar s) {
        return multiplyRadix16(s.toRadix16());
    }

    /**
     * Constant-time fixed-base scalar multiplication by a scalar given as its 64 radix-16 digits
     * ({@link Scalar#toRadix16(byte[], byte[])}), which the caller owns and may erase.
     *
     * @param e the digits, each in $[-8, 8]$.
     * @return $[s]B$
     */
    public EdwardsPoint multiplyRadix16(final byte[] e) {
        if (e.length != 64) {
            throw new IllegalArgumentException("Digits must be 64 bytes");
        }
        int i;

        EdwardsPoint h = EdwardsPoint.IDENTITY;
        for (i = 1; i < 64; i += 2) {
            h = h.add(this.tables[i / 2].select(e[i])).toExtended();
        }

        h = h.multiplyByPow2(4);

        for (i = 0; i < 64; i += 2) {
            h = h.add(this.tables[i / 2].select(e[i])).toExtended();
        }

        return h;
    }
}
