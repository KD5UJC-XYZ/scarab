// SPDX-License-Identifier: Apache-2.0 AND MIT
/*
 * The field arithmetic, point formulas, decoding and double-scalar multiplication below are those of
 * curve25519-elisabeth at 81bd79d (com.weavechain.curve25519: FieldElement, CompletedPoint, ProjectivePoint,
 * EdwardsPoint, ProjectiveNielsPoint, AffineNielsPoint, CompressedEdwardsY, Scalar.nonAdjacentForm),
 * Copyright (c) 2019 Jack Grigg, MIT (crypto/licenses/LICENSE-curve25519-elisabeth), written again over arrays
 * the caller supplies (docs/licenses.md, "curve25519-elisabeth provenance").
 */
package xyz.kd5ujc.scarab.crypto.witness;

import com.weavechain.curve25519.Scalar;
import java.util.function.Supplier;

/**
 * The arithmetic of an Ed25519 verification, for public inputs only, in working storage that one verification
 * allocates and drops: the verifiers' path through the vendored curve25519-elisabeth classes allocated a new limb
 * array and object for every field operation, about 480 KB a verification (docs/performance.md, section 66).
 *
 * <p>A kept imperative site under FUN-2 (a), marked here because no wart reads Java (FUN-3's marking has no Java
 * form; that is an open item of #1418): the field operations are static methods that write their result into an
 * array the caller passes, and the point operations write into the points of an instance that {@link #strict} and
 * {@link #byron} make for one call and that never leaves it. Nothing mutable is shared between calls or threads:
 * the static arrays (the constants and the basepoint's table) are private, written once while the class is
 * initialised and only read afterwards, and the suites see copies of them. Each entry point is a pure function of
 * its arguments, which it only reads.
 *
 * <p>Variable time: the code branches on, and indexes tables by, the scalars and the encodings. Only signature
 * verification may call it, never the prover or anything that holds a secret (CRY-1): the class is
 * package-private in {@code xyz.kd5ujc.scarab.crypto.witness}, the package of the verifiers, so the compiler
 * refuses any other caller. The constant-time operations of the vendored classes, which VRF proving uses, are
 * untouched.
 *
 * <p>Every field and point operation computes exactly what the vendored one computes, on the same limbs, in the
 * same order, so its results are the same limbs, not only the same values ({@code VartimeEd25519Suite}). Where a
 * test is decided otherwise (the identity and small-order tests, the bound on S, the encoding of the result), the
 * method says why the answer is the same. The verifiers make their checks in the order the vendored path made
 * them, and hash only after all of them pass.
 *
 * <p>Arguments are results or operands, never scratch. A result may be any of the operands: the element-wise field
 * operations read limb i before they write it, and every other operation reads all of its operands before it
 * writes its result, or works in arrays of its own until then. The scratch an instance method needs is in private
 * arrays of the instance that no other method uses, so no caller can hand it an array that is in use. A point
 * owns its arrays, so a point result shares arrays with a point operand only when it is the same object; the
 * methods that take two points of one type allow that. A field element is ten limbs in radix 2^25.5
 * (FieldElement's {@code t}), with the bounds FieldElement documents.
 */
final class VartimeEd25519 {

    // ---------------------------------------------------------------------------------------------------------
    // Field elements modulo p = 2^255 - 19: int[10], limb i weighted 2^ceil(25.5 i) (FieldElement.t).

    static int[] fe() {
        return new int[10];
    }

    static int[] fe(int... limbs) {
        return limbs.clone();
    }

    static void copy(int[] h, int[] f) {
        System.arraycopy(f, 0, h, 0, 10);
    }

    static void zero(int[] h) {
        for (int i = 0; i < 10; i++) {
            h[i] = 0;
        }
    }

    static void one(int[] h) {
        zero(h);
        h[0] = 1;
    }

    private static final int[] ONE = fe(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Edwards d = -121665/121666 (Constants.EDWARDS_D). */
    private static final int[] D = fe(-10913610, 13857413, -15372611, 6949391, 114729, -8787816, -6275908, -3247719,
            -18696448, -12055116);

    /** 2d (Constants.EDWARDS_2D). */
    private static final int[] D2 = fe(-21827239, -5839606, -30745221, 13898782, 229458, 15978800, -12551817, -6495438,
            29715968, 9444199);

    /** A square root of -1 (Constants.SQRT_M1). */
    private static final int[] SQRT_M1 = fe(-32595792, -7943725, 9377950, 3500415, 12389472, -272473, -25146209, -2005654,
            326686, 11406482);

    /** Copies of the constants, for the suites. */
    static int[] copyOfOne() {
        return ONE.clone();
    }

    static int[] copyOfD() {
        return D.clone();
    }

    static int[] copyOfD2() {
        return D2.clone();
    }

    static int[] copyOfSqrtM1() {
        return SQRT_M1.clone();
    }

    private static int load3(byte[] in, int offset) {
        int result = in[offset] & 0xff;
        result |= (in[offset + 1] & 0xff) << 8;
        result |= (in[offset + 2] & 0xff) << 16;
        return result;
    }

    private static long load4(byte[] in, int offset) {
        int result = in[offset] & 0xff;
        result |= (in[offset + 1] & 0xff) << 8;
        result |= (in[offset + 2] & 0xff) << 16;
        result |= in[offset + 3] << 24;
        return ((long) result) & 0xffffffffL;
    }

    /** h = the low 255 bits of the 32 bytes of {@code in} from {@code offset} (FieldElement.fromByteArray). */
    static void fromBytes(int[] h, byte[] in, int offset) {
        long h0 = load4(in, offset);
        long h1 = load3(in, offset + 4) << 6;
        long h2 = load3(in, offset + 7) << 5;
        long h3 = load3(in, offset + 10) << 3;
        long h4 = load3(in, offset + 13) << 2;
        long h5 = load4(in, offset + 16);
        long h6 = load3(in, offset + 20) << 7;
        long h7 = load3(in, offset + 23) << 5;
        long h8 = load3(in, offset + 26) << 4;
        long h9 = (load3(in, offset + 29) & 0x7FFFFF) << 2;
        long carry;

        // @formatter:off
        carry = (h9 + (long) (1<<24)) >> 25; h0 += carry * 19; h9 -= carry << 25;
        carry = (h1 + (long) (1<<24)) >> 25; h2 += carry; h1 -= carry << 25;
        carry = (h3 + (long) (1<<24)) >> 25; h4 += carry; h3 -= carry << 25;
        carry = (h5 + (long) (1<<24)) >> 25; h6 += carry; h5 -= carry << 25;
        carry = (h7 + (long) (1<<24)) >> 25; h8 += carry; h7 -= carry << 25;

        carry = (h0 + (long) (1<<25)) >> 26; h1 += carry; h0 -= carry << 26;
        carry = (h2 + (long) (1<<25)) >> 26; h3 += carry; h2 -= carry << 26;
        carry = (h4 + (long) (1<<25)) >> 26; h5 += carry; h4 -= carry << 26;
        carry = (h6 + (long) (1<<25)) >> 26; h7 += carry; h6 -= carry << 26;
        carry = (h8 + (long) (1<<25)) >> 26; h9 += carry; h8 -= carry << 26;
        // @formatter:on

        h[0] = (int) h0;
        h[1] = (int) h1;
        h[2] = (int) h2;
        h[3] = (int) h3;
        h[4] = (int) h4;
        h[5] = (int) h5;
        h[6] = (int) h6;
        h[7] = (int) h7;
        h[8] = (int) h8;
        h[9] = (int) h9;
    }

    /**
     * h = the canonical limbs of f: its value modulo p, below p, each limb in [0, 2^26) or [0, 2^25). The first
     * step of FieldElement.toByteArray, with its bound: any limbs below 2^27 in absolute value.
     */
    static void reduce(int[] h, int[] f) {
        int h0 = f[0];
        int h1 = f[1];
        int h2 = f[2];
        int h3 = f[3];
        int h4 = f[4];
        int h5 = f[5];
        int h6 = f[6];
        int h7 = f[7];
        int h8 = f[8];
        int h9 = f[9];
        int carry;

        // 19 * h9 needs more than 32 bits once |h9| is near 2^27; the result, at most 76 in absolute value,
        // fits an int (FieldElement.toByteArray).
        int q = (int) ((19L * h9 + (1L << 24)) >> 25);
        q = (h0 + q) >> 26;
        q = (h1 + q) >> 25;
        q = (h2 + q) >> 26;
        q = (h3 + q) >> 25;
        q = (h4 + q) >> 26;
        q = (h5 + q) >> 25;
        q = (h6 + q) >> 26;
        q = (h7 + q) >> 25;
        q = (h8 + q) >> 26;
        q = (h9 + q) >> 25;

        // r = h - q * p = h - 2^255 * q + 19 * q: add 19 * q, then drop bit 255
        h0 += 19 * q;

        // @formatter:off
        carry = h0 >> 26; h1 += carry; h0 -= carry << 26;
        carry = h1 >> 25; h2 += carry; h1 -= carry << 25;
        carry = h2 >> 26; h3 += carry; h2 -= carry << 26;
        carry = h3 >> 25; h4 += carry; h3 -= carry << 25;
        carry = h4 >> 26; h5 += carry; h4 -= carry << 26;
        carry = h5 >> 25; h6 += carry; h5 -= carry << 25;
        carry = h6 >> 26; h7 += carry; h6 -= carry << 26;
        carry = h7 >> 25; h8 += carry; h7 -= carry << 25;
        carry = h8 >> 26; h9 += carry; h8 -= carry << 26;
        carry = h9 >> 25;              h9 -= carry << 25;
        // @formatter:on

        h[0] = h0;
        h[1] = h1;
        h[2] = h2;
        h[3] = h3;
        h[4] = h4;
        h[5] = h5;
        h[6] = h6;
        h[7] = h7;
        h[8] = h8;
        h[9] = h9;
    }

    /** The 32-byte encoding of canonical limbs {@code h} (as {@link #reduce} leaves them) into s from offset. */
    static void pack(byte[] s, int offset, int[] h) {
        int h0 = h[0];
        int h1 = h[1];
        int h2 = h[2];
        int h3 = h[3];
        int h4 = h[4];
        int h5 = h[5];
        int h6 = h[6];
        int h7 = h[7];
        int h8 = h[8];
        int h9 = h[9];
        s[offset] = (byte) h0;
        s[offset + 1] = (byte) (h0 >> 8);
        s[offset + 2] = (byte) (h0 >> 16);
        s[offset + 3] = (byte) ((h0 >> 24) | (h1 << 2));
        s[offset + 4] = (byte) (h1 >> 6);
        s[offset + 5] = (byte) (h1 >> 14);
        s[offset + 6] = (byte) ((h1 >> 22) | (h2 << 3));
        s[offset + 7] = (byte) (h2 >> 5);
        s[offset + 8] = (byte) (h2 >> 13);
        s[offset + 9] = (byte) ((h2 >> 21) | (h3 << 5));
        s[offset + 10] = (byte) (h3 >> 3);
        s[offset + 11] = (byte) (h3 >> 11);
        s[offset + 12] = (byte) ((h3 >> 19) | (h4 << 6));
        s[offset + 13] = (byte) (h4 >> 2);
        s[offset + 14] = (byte) (h4 >> 10);
        s[offset + 15] = (byte) (h4 >> 18);
        s[offset + 16] = (byte) h5;
        s[offset + 17] = (byte) (h5 >> 8);
        s[offset + 18] = (byte) (h5 >> 16);
        s[offset + 19] = (byte) ((h5 >> 24) | (h6 << 1));
        s[offset + 20] = (byte) (h6 >> 7);
        s[offset + 21] = (byte) (h6 >> 15);
        s[offset + 22] = (byte) ((h6 >> 23) | (h7 << 3));
        s[offset + 23] = (byte) (h7 >> 5);
        s[offset + 24] = (byte) (h7 >> 13);
        s[offset + 25] = (byte) ((h7 >> 21) | (h8 << 4));
        s[offset + 26] = (byte) (h8 >> 4);
        s[offset + 27] = (byte) (h8 >> 12);
        s[offset + 28] = (byte) ((h8 >> 20) | (h9 << 6));
        s[offset + 29] = (byte) (h9 >> 2);
        s[offset + 30] = (byte) (h9 >> 10);
        s[offset + 31] = (byte) (h9 >> 18);
    }

    /** h = f + g, without carries (FieldElement.add). */
    static void add(int[] h, int[] f, int[] g) {
        for (int i = 0; i < 10; i++) {
            h[i] = f[i] + g[i];
        }
    }

    /** h = f - g, without carries (FieldElement.subtract). */
    static void sub(int[] h, int[] f, int[] g) {
        for (int i = 0; i < 10; i++) {
            h[i] = f[i] - g[i];
        }
    }

    /** h = -f (FieldElement.negate). */
    static void neg(int[] h, int[] f) {
        for (int i = 0; i < 10; i++) {
            h[i] = -f[i];
        }
    }

    /**
     * h = f * g (FieldElement.multiply, with its bounds: limbs below 1.65 * 2^26, 1.65 * 2^25, ... in, below
     * 1.01 * 2^25, 1.01 * 2^24, ... out).
     */
    static void mul(int[] h, int[] f, int[] g) {
        int f0 = f[0];
        int f1 = f[1];
        int f2 = f[2];
        int f3 = f[3];
        int f4 = f[4];
        int f5 = f[5];
        int f6 = f[6];
        int f7 = f[7];
        int f8 = f[8];
        int f9 = f[9];
        int g0 = g[0];
        int g1 = g[1];
        int g2 = g[2];
        int g3 = g[3];
        int g4 = g[4];
        int g5 = g[5];
        int g6 = g[6];
        int g7 = g[7];
        int g8 = g[8];
        int g9 = g[9];
        int g1_19 = 19 * g1; /* 1.959375*2^29 */
        int g2_19 = 19 * g2; /* 1.959375*2^30; still ok */
        int g3_19 = 19 * g3;
        int g4_19 = 19 * g4;
        int g5_19 = 19 * g5;
        int g6_19 = 19 * g6;
        int g7_19 = 19 * g7;
        int g8_19 = 19 * g8;
        int g9_19 = 19 * g9;
        int f1_2 = 2 * f1;
        int f3_2 = 2 * f3;
        int f5_2 = 2 * f5;
        int f7_2 = 2 * f7;
        int f9_2 = 2 * f9;
        // @formatter:off
        long f0g0    = f0   * (long) g0;
        long f0g1    = f0   * (long) g1;
        long f0g2    = f0   * (long) g2;
        long f0g3    = f0   * (long) g3;
        long f0g4    = f0   * (long) g4;
        long f0g5    = f0   * (long) g5;
        long f0g6    = f0   * (long) g6;
        long f0g7    = f0   * (long) g7;
        long f0g8    = f0   * (long) g8;
        long f0g9    = f0   * (long) g9;
        long f1g0    = f1   * (long) g0;
        long f1g1_2  = f1_2 * (long) g1;
        long f1g2    = f1   * (long) g2;
        long f1g3_2  = f1_2 * (long) g3;
        long f1g4    = f1   * (long) g4;
        long f1g5_2  = f1_2 * (long) g5;
        long f1g6    = f1   * (long) g6;
        long f1g7_2  = f1_2 * (long) g7;
        long f1g8    = f1   * (long) g8;
        long f1g9_38 = f1_2 * (long) g9_19;
        long f2g0    = f2   * (long) g0;
        long f2g1    = f2   * (long) g1;
        long f2g2    = f2   * (long) g2;
        long f2g3    = f2   * (long) g3;
        long f2g4    = f2   * (long) g4;
        long f2g5    = f2   * (long) g5;
        long f2g6    = f2   * (long) g6;
        long f2g7    = f2   * (long) g7;
        long f2g8_19 = f2   * (long) g8_19;
        long f2g9_19 = f2   * (long) g9_19;
        long f3g0    = f3   * (long) g0;
        long f3g1_2  = f3_2 * (long) g1;
        long f3g2    = f3   * (long) g2;
        long f3g3_2  = f3_2 * (long) g3;
        long f3g4    = f3   * (long) g4;
        long f3g5_2  = f3_2 * (long) g5;
        long f3g6    = f3   * (long) g6;
        long f3g7_38 = f3_2 * (long) g7_19;
        long f3g8_19 = f3   * (long) g8_19;
        long f3g9_38 = f3_2 * (long) g9_19;
        long f4g0    = f4   * (long) g0;
        long f4g1    = f4   * (long) g1;
        long f4g2    = f4   * (long) g2;
        long f4g3    = f4   * (long) g3;
        long f4g4    = f4   * (long) g4;
        long f4g5    = f4   * (long) g5;
        long f4g6_19 = f4   * (long) g6_19;
        long f4g7_19 = f4   * (long) g7_19;
        long f4g8_19 = f4   * (long) g8_19;
        long f4g9_19 = f4   * (long) g9_19;
        long f5g0    = f5   * (long) g0;
        long f5g1_2  = f5_2 * (long) g1;
        long f5g2    = f5   * (long) g2;
        long f5g3_2  = f5_2 * (long) g3;
        long f5g4    = f5   * (long) g4;
        long f5g5_38 = f5_2 * (long) g5_19;
        long f5g6_19 = f5   * (long) g6_19;
        long f5g7_38 = f5_2 * (long) g7_19;
        long f5g8_19 = f5   * (long) g8_19;
        long f5g9_38 = f5_2 * (long) g9_19;
        long f6g0    = f6   * (long) g0;
        long f6g1    = f6   * (long) g1;
        long f6g2    = f6   * (long) g2;
        long f6g3    = f6   * (long) g3;
        long f6g4_19 = f6   * (long) g4_19;
        long f6g5_19 = f6   * (long) g5_19;
        long f6g6_19 = f6   * (long) g6_19;
        long f6g7_19 = f6   * (long) g7_19;
        long f6g8_19 = f6   * (long) g8_19;
        long f6g9_19 = f6   * (long) g9_19;
        long f7g0    = f7   * (long) g0;
        long f7g1_2  = f7_2 * (long) g1;
        long f7g2    = f7   * (long) g2;
        long f7g3_38 = f7_2 * (long) g3_19;
        long f7g4_19 = f7   * (long) g4_19;
        long f7g5_38 = f7_2 * (long) g5_19;
        long f7g6_19 = f7   * (long) g6_19;
        long f7g7_38 = f7_2 * (long) g7_19;
        long f7g8_19 = f7   * (long) g8_19;
        long f7g9_38 = f7_2 * (long) g9_19;
        long f8g0    = f8   * (long) g0;
        long f8g1    = f8   * (long) g1;
        long f8g2_19 = f8   * (long) g2_19;
        long f8g3_19 = f8   * (long) g3_19;
        long f8g4_19 = f8   * (long) g4_19;
        long f8g5_19 = f8   * (long) g5_19;
        long f8g6_19 = f8   * (long) g6_19;
        long f8g7_19 = f8   * (long) g7_19;
        long f8g8_19 = f8   * (long) g8_19;
        long f8g9_19 = f8   * (long) g9_19;
        long f9g0    = f9   * (long) g0;
        long f9g1_38 = f9_2 * (long) g1_19;
        long f9g2_19 = f9   * (long) g2_19;
        long f9g3_38 = f9_2 * (long) g3_19;
        long f9g4_19 = f9   * (long) g4_19;
        long f9g5_38 = f9_2 * (long) g5_19;
        long f9g6_19 = f9   * (long) g6_19;
        long f9g7_38 = f9_2 * (long) g7_19;
        long f9g8_19 = f9   * (long) g8_19;
        long f9g9_38 = f9_2 * (long) g9_19;

        // 2^255 is 19 modulo p, so a product of limbs whose weights reach 2^255 comes back to the low limbs
        // times 19 (and 38 where both weights round up), as FieldElement.multiply explains
        long h0 = f0g0 + f1g9_38 + f2g8_19 + f3g7_38 + f4g6_19 + f5g5_38 + f6g4_19 + f7g3_38 + f8g2_19 + f9g1_38;
        long h1 = f0g1 + f1g0    + f2g9_19 + f3g8_19 + f4g7_19 + f5g6_19 + f6g5_19 + f7g4_19 + f8g3_19 + f9g2_19;
        long h2 = f0g2 + f1g1_2  + f2g0    + f3g9_38 + f4g8_19 + f5g7_38 + f6g6_19 + f7g5_38 + f8g4_19 + f9g3_38;
        long h3 = f0g3 + f1g2    + f2g1    + f3g0    + f4g9_19 + f5g8_19 + f6g7_19 + f7g6_19 + f8g5_19 + f9g4_19;
        long h4 = f0g4 + f1g3_2  + f2g2    + f3g1_2  + f4g0    + f5g9_38 + f6g8_19 + f7g7_38 + f8g6_19 + f9g5_38;
        long h5 = f0g5 + f1g4    + f2g3    + f3g2    + f4g1    + f5g0    + f6g9_19 + f7g8_19 + f8g7_19 + f9g6_19;
        long h6 = f0g6 + f1g5_2  + f2g4    + f3g3_2  + f4g2    + f5g1_2  + f6g0    + f7g9_38 + f8g8_19 + f9g7_38;
        long h7 = f0g7 + f1g6    + f2g5    + f3g4    + f4g3    + f5g2    + f6g1    + f7g0    + f8g9_19 + f9g8_19;
        long h8 = f0g8 + f1g7_2  + f2g6    + f3g5_2  + f4g4    + f5g3_2  + f6g2    + f7g1_2  + f8g0    + f9g9_38;
        long h9 = f0g9 + f1g8    + f2g7    + f3g6    + f4g5    + f5g4    + f6g3    + f7g2    + f8g1    + f9g0;
        // @formatter:on

        carryProduct(h, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9);
    }

    /**
     * The carry chain of FieldElement.multiply, square and squareAndDouble, from the 64-bit sums of the limb
     * products to limbs below 1.01 * 2^25, 1.01 * 2^24, ...
     */
    private static void carryProduct(int[] h, long h0, long h1, long h2, long h3, long h4, long h5, long h6,
            long h7, long h8, long h9) {
        long carry0;
        long carry1;
        long carry2;
        long carry3;
        long carry4;
        long carry5;
        long carry6;
        long carry7;
        long carry8;
        long carry9;

        // @formatter:off
        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;

        carry1 = (h1 + (long) (1<<24)) >> 25; h2 += carry1; h1 -= carry1 << 25;
        carry5 = (h5 + (long) (1<<24)) >> 25; h6 += carry5; h5 -= carry5 << 25;

        carry2 = (h2 + (long) (1<<25)) >> 26; h3 += carry2; h2 -= carry2 << 26;
        carry6 = (h6 + (long) (1<<25)) >> 26; h7 += carry6; h6 -= carry6 << 26;

        carry3 = (h3 + (long) (1<<24)) >> 25; h4 += carry3; h3 -= carry3 << 25;
        carry7 = (h7 + (long) (1<<24)) >> 25; h8 += carry7; h7 -= carry7 << 25;

        carry4 = (h4 + (long) (1<<25)) >> 26; h5 += carry4; h4 -= carry4 << 26;
        carry8 = (h8 + (long) (1<<25)) >> 26; h9 += carry8; h8 -= carry8 << 26;

        carry9 = (h9 + (long) (1<<24)) >> 25; h0 += carry9 * 19; h9 -= carry9 << 25;

        carry0 = (h0 + (long) (1<<25)) >> 26; h1 += carry0; h0 -= carry0 << 26;
        // @formatter:on

        h[0] = (int) h0;
        h[1] = (int) h1;
        h[2] = (int) h2;
        h[3] = (int) h3;
        h[4] = (int) h4;
        h[5] = (int) h5;
        h[6] = (int) h6;
        h[7] = (int) h7;
        h[8] = (int) h8;
        h[9] = (int) h9;
    }

    /** h = f * f (FieldElement.square, the bounds of {@link #mul}). */
    static void sq(int[] h, int[] f) {
        square(h, f, false);
    }

    /** h = 2 * f * f (FieldElement.squareAndDouble, the bounds of {@link #mul}). */
    static void sq2(int[] h, int[] f) {
        square(h, f, true);
    }

    private static void square(int[] h, int[] f, boolean twice) {
        int f0 = f[0];
        int f1 = f[1];
        int f2 = f[2];
        int f3 = f[3];
        int f4 = f[4];
        int f5 = f[5];
        int f6 = f[6];
        int f7 = f[7];
        int f8 = f[8];
        int f9 = f[9];
        int f0_2 = 2 * f0;
        int f1_2 = 2 * f1;
        int f2_2 = 2 * f2;
        int f3_2 = 2 * f3;
        int f4_2 = 2 * f4;
        int f5_2 = 2 * f5;
        int f6_2 = 2 * f6;
        int f7_2 = 2 * f7;
        int f5_38 = 38 * f5; /* 1.959375*2^30 */
        int f6_19 = 19 * f6; /* 1.959375*2^30 */
        int f7_38 = 38 * f7; /* 1.959375*2^30 */
        int f8_19 = 19 * f8; /* 1.959375*2^30 */
        int f9_38 = 38 * f9; /* 1.959375*2^30 */
        // @formatter:off
        long f0f0    = f0   * (long) f0;
        long f0f1_2  = f0_2 * (long) f1;
        long f0f2_2  = f0_2 * (long) f2;
        long f0f3_2  = f0_2 * (long) f3;
        long f0f4_2  = f0_2 * (long) f4;
        long f0f5_2  = f0_2 * (long) f5;
        long f0f6_2  = f0_2 * (long) f6;
        long f0f7_2  = f0_2 * (long) f7;
        long f0f8_2  = f0_2 * (long) f8;
        long f0f9_2  = f0_2 * (long) f9;
        long f1f1_2  = f1_2 * (long) f1;
        long f1f2_2  = f1_2 * (long) f2;
        long f1f3_4  = f1_2 * (long) f3_2;
        long f1f4_2  = f1_2 * (long) f4;
        long f1f5_4  = f1_2 * (long) f5_2;
        long f1f6_2  = f1_2 * (long) f6;
        long f1f7_4  = f1_2 * (long) f7_2;
        long f1f8_2  = f1_2 * (long) f8;
        long f1f9_76 = f1_2 * (long) f9_38;
        long f2f2    = f2   * (long) f2;
        long f2f3_2  = f2_2 * (long) f3;
        long f2f4_2  = f2_2 * (long) f4;
        long f2f5_2  = f2_2 * (long) f5;
        long f2f6_2  = f2_2 * (long) f6;
        long f2f7_2  = f2_2 * (long) f7;
        long f2f8_38 = f2_2 * (long) f8_19;
        long f2f9_38 = f2   * (long) f9_38;
        long f3f3_2  = f3_2 * (long) f3;
        long f3f4_2  = f3_2 * (long) f4;
        long f3f5_4  = f3_2 * (long) f5_2;
        long f3f6_2  = f3_2 * (long) f6;
        long f3f7_76 = f3_2 * (long) f7_38;
        long f3f8_38 = f3_2 * (long) f8_19;
        long f3f9_76 = f3_2 * (long) f9_38;
        long f4f4    = f4   * (long) f4;
        long f4f5_2  = f4_2 * (long) f5;
        long f4f6_38 = f4_2 * (long) f6_19;
        long f4f7_38 = f4   * (long) f7_38;
        long f4f8_38 = f4_2 * (long) f8_19;
        long f4f9_38 = f4   * (long) f9_38;
        long f5f5_38 = f5   * (long) f5_38;
        long f5f6_38 = f5_2 * (long) f6_19;
        long f5f7_76 = f5_2 * (long) f7_38;
        long f5f8_38 = f5_2 * (long) f8_19;
        long f5f9_76 = f5_2 * (long) f9_38;
        long f6f6_19 = f6   * (long) f6_19;
        long f6f7_38 = f6   * (long) f7_38;
        long f6f8_38 = f6_2 * (long) f8_19;
        long f6f9_38 = f6   * (long) f9_38;
        long f7f7_38 = f7   * (long) f7_38;
        long f7f8_38 = f7_2 * (long) f8_19;
        long f7f9_76 = f7_2 * (long) f9_38;
        long f8f8_19 = f8   * (long) f8_19;
        long f8f9_38 = f8   * (long) f9_38;
        long f9f9_38 = f9   * (long) f9_38;

        long h0 = f0f0   + f1f9_76 + f2f8_38 + f3f7_76 + f4f6_38 + f5f5_38;
        long h1 = f0f1_2 + f2f9_38 + f3f8_38 + f4f7_38 + f5f6_38;
        long h2 = f0f2_2 + f1f1_2  + f3f9_76 + f4f8_38 + f5f7_76 + f6f6_19;
        long h3 = f0f3_2 + f1f2_2  + f4f9_38 + f5f8_38 + f6f7_38;
        long h4 = f0f4_2 + f1f3_4  + f2f2    + f5f9_76 + f6f8_38 + f7f7_38;
        long h5 = f0f5_2 + f1f4_2  + f2f3_2  + f6f9_38 + f7f8_38;
        long h6 = f0f6_2 + f1f5_4  + f2f4_2  + f3f3_2  + f7f9_76 + f8f8_19;
        long h7 = f0f7_2 + f1f6_2  + f2f5_2  + f3f4_2  + f8f9_38;
        long h8 = f0f8_2 + f1f7_4  + f2f6_2  + f3f5_4  + f4f4    + f9f9_38;
        long h9 = f0f9_2 + f1f8_2  + f2f7_2  + f3f6_2  + f4f5_2;
        // @formatter:on

        if (twice) {
            h0 += h0;
            h1 += h1;
            h2 += h2;
            h3 += h3;
            h4 += h4;
            h5 += h5;
            h6 += h6;
            h7 += h7;
            h8 += h8;
            h9 += h9;
        }

        carryProduct(h, h0, h1, h2, h3, h4, h5, h6, h7, h8, h9);
    }

    /** h = f squared k times, k at least 1. */
    private static void sqTimes(int[] h, int[] f, int k) {
        sq(h, f);
        for (int i = 1; i < k; i++) {
            sq(h, h);
        }
    }

    /**
     * h = z^(p - 2), the inverse of z (zero for zero), by FieldElement.invert's chain of squarings and
     * products; h may be z, which it reads before it writes h.
     */
    void invert(int[] h, int[] z) {
        int[] t0 = chain0;
        int[] t1 = chain1;
        int[] t2 = chain2;
        int[] t3 = chain3;
        sq(t0, z); // 2
        sqTimes(t1, t0, 2); // 8
        mul(t1, z, t1); // 9
        mul(t0, t0, t1); // 11
        sq(t2, t0); // 22
        mul(t1, t1, t2); // 31 = 2^5 - 2^0
        sqTimes(t2, t1, 5); // 2^10 - 2^5
        mul(t1, t2, t1); // 2^10 - 2^0
        sqTimes(t2, t1, 10); // 2^20 - 2^10
        mul(t2, t2, t1); // 2^20 - 2^0
        sqTimes(t3, t2, 20); // 2^40 - 2^20
        mul(t2, t3, t2); // 2^40 - 2^0
        sqTimes(t2, t2, 10); // 2^50 - 2^10
        mul(t1, t2, t1); // 2^50 - 2^0
        sqTimes(t2, t1, 50); // 2^100 - 2^50
        mul(t2, t2, t1); // 2^100 - 2^0
        sqTimes(t3, t2, 100); // 2^200 - 2^100
        mul(t2, t3, t2); // 2^200 - 2^0
        sqTimes(t2, t2, 50); // 2^250 - 2^50
        mul(t1, t2, t1); // 2^250 - 2^0
        sqTimes(t1, t1, 5); // 2^255 - 2^5
        mul(h, t1, t0); // 2^255 - 21
    }

    /**
     * h = z^((p - 5)/8) = z^(2^252 - 3), by FieldElement.powP58's chain; h may be z, which it reads last, in the
     * product that writes h.
     */
    void powP58(int[] h, int[] z) {
        int[] t0 = chain0;
        int[] t1 = chain1;
        int[] t2 = chain2;
        sq(t0, z); // 2
        sqTimes(t1, t0, 2); // 8
        mul(t1, z, t1); // 9
        mul(t0, t0, t1); // 11
        sq(t0, t0); // 22
        mul(t0, t1, t0); // 31 = 2^5 - 2^0
        sqTimes(t1, t0, 5); // 2^10 - 2^5
        mul(t0, t1, t0); // 2^10 - 2^0
        sqTimes(t1, t0, 10); // 2^20 - 2^10
        mul(t1, t1, t0); // 2^20 - 2^0
        sqTimes(t2, t1, 20); // 2^40 - 2^20
        mul(t1, t2, t1); // 2^40 - 2^0
        sqTimes(t1, t1, 10); // 2^50 - 2^10
        mul(t0, t1, t0); // 2^50 - 2^0
        sqTimes(t1, t0, 50); // 2^100 - 2^50
        mul(t1, t1, t0); // 2^100 - 2^0
        sqTimes(t2, t1, 100); // 2^200 - 2^100
        mul(t1, t2, t1); // 2^200 - 2^0
        sqTimes(t1, t1, 50); // 2^250 - 2^50
        mul(t0, t1, t0); // 2^250 - 2^0
        sqTimes(t0, t0, 2); // 2^252 - 2^2
        mul(h, z, t0); // 2^252 - 3
    }

    // ---------------------------------------------------------------------------------------------------------
    // Points, in the coordinates of the vendored classes.

    /** (X : Y : Z : T) with x = X/Z, y = Y/Z, x y = T/Z (EdwardsPoint). */
    static final class Extended {
        final int[] X = fe();
        final int[] Y = fe();
        final int[] Z = fe();
        final int[] T = fe();
    }

    /** ((X : Z), (Y : T)) on P^1 x P^1 (CompletedPoint). */
    static final class Completed {
        final int[] X = fe();
        final int[] Y = fe();
        final int[] Z = fe();
        final int[] T = fe();
    }

    /** (X : Y : Z) on P^2 (ProjectivePoint). */
    static final class Projective {
        final int[] X = fe();
        final int[] Y = fe();
        final int[] Z = fe();
    }

    /** (Y + X, Y - X, Z, 2d T) (ProjectiveNielsPoint). */
    static final class Cached {
        final int[] YPlusX = fe();
        final int[] YMinusX = fe();
        final int[] Z = fe();
        final int[] T2D = fe();
    }

    /** (y + x, y - x, 2d x y) (AffineNielsPoint). */
    static final class Affine {
        final int[] yPlusx = fe();
        final int[] yMinusx = fe();
        final int[] xy2D = fe();
    }

    /** r = 2p (ProjectivePoint.dbl). */
    void dbl(Completed r, Projective p) {
        int[] t0 = pointScratch;
        sq(r.X, p.X); // XX
        sq(r.Z, p.Y); // YY
        sq2(r.T, p.Z); // ZZ2
        add(r.Y, p.X, p.Y); // XPlusY
        sq(t0, r.Y); // XPlusYSq
        add(r.Y, r.Z, r.X); // YYPlusXX
        sub(r.Z, r.Z, r.X); // YYMinusXX
        sub(r.X, t0, r.Y); // XPlusYSq - YYPlusXX
        sub(r.T, r.T, r.Z); // ZZ2 - YYMinusXX
    }

    /** CompletedPoint.toProjective: (X T, Y Z, Z T). */
    static void toProjective(Projective r, Completed p) {
        mul(r.X, p.X, p.T);
        mul(r.Y, p.Y, p.Z);
        mul(r.Z, p.Z, p.T);
    }

    /** CompletedPoint.toExtended: (X T, Y Z, Z T, X Y). */
    static void toExtended(Extended r, Completed p) {
        mul(r.X, p.X, p.T);
        mul(r.Y, p.Y, p.Z);
        mul(r.Z, p.Z, p.T);
        mul(r.T, p.X, p.Y);
    }

    /** ProjectivePoint.toExtended: (X Z, Y Z, Z^2, X Y). */
    static void toExtended(Extended r, Projective p) {
        mul(r.X, p.X, p.Z);
        mul(r.Y, p.Y, p.Z);
        sq(r.Z, p.Z);
        mul(r.T, p.X, p.Y);
    }

    /** EdwardsPoint.toProjective: (X, Y, Z). */
    static void toProjective(Projective r, Extended p) {
        copy(r.X, p.X);
        copy(r.Y, p.Y);
        copy(r.Z, p.Z);
    }

    /** EdwardsPoint.toProjectiveNiels: (Y + X, Y - X, Z, 2d T). */
    static void toCached(Cached r, Extended p) {
        add(r.YPlusX, p.Y, p.X);
        sub(r.YMinusX, p.Y, p.X);
        copy(r.Z, p.Z);
        mul(r.T2D, p.T, D2);
    }

    /** EdwardsPoint.negate: (-X, Y, Z, -T); r may be p. */
    static void negate(Extended r, Extended p) {
        neg(r.X, p.X);
        copy(r.Y, p.Y);
        copy(r.Z, p.Z);
        neg(r.T, p.T);
    }

    /** r = p + q (EdwardsPoint.add(ProjectiveNielsPoint)). */
    void add(Completed r, Extended p, Cached q) {
        int[] t0 = pointScratch;
        add(r.X, p.Y, p.X); // YPlusX
        sub(r.Y, p.Y, p.X); // YMinusX
        mul(r.Z, r.X, q.YPlusX); // PP
        mul(r.Y, r.Y, q.YMinusX); // MM
        mul(r.T, p.T, q.T2D); // TT2D
        mul(r.X, p.Z, q.Z); // ZZ
        add(t0, r.X, r.X); // ZZ2
        sub(r.X, r.Z, r.Y); // PP - MM
        add(r.Y, r.Z, r.Y); // PP + MM
        add(r.Z, t0, r.T); // ZZ2 + TT2D
        sub(r.T, t0, r.T); // ZZ2 - TT2D
    }

    /** r = p - q (EdwardsPoint.subtract(ProjectiveNielsPoint)). */
    void sub(Completed r, Extended p, Cached q) {
        int[] t0 = pointScratch;
        add(r.X, p.Y, p.X); // YPlusX
        sub(r.Y, p.Y, p.X); // YMinusX
        mul(r.Z, r.X, q.YMinusX); // PM
        mul(r.Y, r.Y, q.YPlusX); // MP
        mul(r.T, p.T, q.T2D); // TT2D
        mul(r.X, p.Z, q.Z); // ZZ
        add(t0, r.X, r.X); // ZZ2
        sub(r.X, r.Z, r.Y); // PM - MP
        add(r.Y, r.Z, r.Y); // PM + MP
        sub(r.Z, t0, r.T); // ZZ2 - TT2D
        add(r.T, t0, r.T); // ZZ2 + TT2D
    }

    /** r = p + q (EdwardsPoint.add(AffineNielsPoint)). */
    void add(Completed r, Extended p, Affine q) {
        int[] t0 = pointScratch;
        add(r.X, p.Y, p.X); // YPlusX
        sub(r.Y, p.Y, p.X); // YMinusX
        mul(r.Z, r.X, q.yPlusx); // PP
        mul(r.Y, r.Y, q.yMinusx); // MM
        mul(r.T, p.T, q.xy2D); // Txy2D
        add(t0, p.Z, p.Z); // Z2
        sub(r.X, r.Z, r.Y); // PP - MM
        add(r.Y, r.Z, r.Y); // PP + MM
        add(r.Z, t0, r.T); // Z2 + Txy2D
        sub(r.T, t0, r.T); // Z2 - Txy2D
    }

    /** r = p - q (EdwardsPoint.subtract(AffineNielsPoint)). */
    void sub(Completed r, Extended p, Affine q) {
        int[] t0 = pointScratch;
        add(r.X, p.Y, p.X); // YPlusX
        sub(r.Y, p.Y, p.X); // YMinusX
        mul(r.Z, r.X, q.yMinusx); // PM
        mul(r.Y, r.Y, q.yPlusx); // MP
        mul(r.T, p.T, q.xy2D); // Txy2D
        add(t0, p.Z, p.Z); // Z2
        sub(r.X, r.Z, r.Y); // PM - MP
        add(r.Y, r.Z, r.Y); // PM + MP
        sub(r.Z, t0, r.T); // Z2 - Txy2D
        add(r.T, t0, r.T); // Z2 + Txy2D
    }

    /**
     * The width-5 non-adjacent form of the 256-bit little-endian integer at {@code s[offset]}: naf[i] odd in
     * [-15, 15] or zero, the sum of naf[i] 2^i the integer, for an integer below 2^255 (Scalar.nonAdjacentForm).
     */
    static void nonAdjacentForm(byte[] naf, byte[] s, int offset) {
        for (int i = 0; i < 256; ++i) {
            naf[i] = (byte) (1 & (s[offset + (i >> 3)] >> (i & 7)));
        }
        for (int i = 0; i < 256; ++i) {
            if (naf[i] != 0) {
                for (int b = 1; b <= 6 && i + b < 256; ++b) {
                    // Accumulate bits if possible
                    if (naf[i + b] != 0) {
                        if (naf[i] + (naf[i + b] << b) <= 15) {
                            naf[i] += naf[i + b] << b;
                            naf[i + b] = 0;
                        } else if (naf[i] - (naf[i + b] << b) >= -15) {
                            naf[i] -= naf[i + b] << b;
                            for (int k = i + b; k < 256; ++k) {
                                if (naf[k] == 0) {
                                    naf[k] = 1;
                                    break;
                                }
                                naf[k] = 0;
                            }
                        } else {
                            break;
                        }
                    }
                }
            }
        }
    }

    /** The Ed25519 basepoint (Constants.ED25519_BASEPOINT's limbs). */
    private static Extended basepoint() {
        Extended b = new Extended();
        copy(b.X, fe(-14297830, -7645148, 16144683, -16471763, 27570974, -2696100, -26142465, 8378389, 20764389,
                8758491));
        copy(b.Y, fe(-26843541, -6710886, 13421773, -13421773, 26843546, 6710886, -13421773, 13421773, -26843546,
                -6710886));
        one(b.Z);
        copy(b.T, fe(28827062, -6116119, -27349572, 244363, 8635006, 11264893, 19351346, 13413597, 16611511,
                -6414980));
        return b;
    }

    private static final Extended BASEPOINT = basepoint();

    /**
     * [B, 3B, 5B, ..., 15B] in affine Niels coordinates (Constants.AFFINE_ODD_MULTIPLES_OF_BASEPOINT, built by
     * AffineNielsPoint.buildNafLookupTable): written here once, read by every verification.
     */
    private static final Affine[] BASEPOINT_ODD_MULTIPLES = basepointOddMultiples();

    private static Affine[] basepointOddMultiples() {
        VartimeEd25519 w = new VartimeEd25519();
        Affine[] table = new Affine[8];
        Extended b2 = new Extended();
        Extended sum = new Extended();
        table[0] = new Affine();
        w.toAffine(table[0], BASEPOINT);
        w.dbl(b2, BASEPOINT);
        for (int i = 0; i < 7; i++) {
            w.add(w.completed, b2, table[i]);
            toExtended(sum, w.completed);
            table[i + 1] = new Affine();
            w.toAffine(table[i + 1], sum);
        }
        return table;
    }

    /** A copy of the basepoint, for the suites. */
    static Extended copyOfBasepoint() {
        Extended b = new Extended();
        copy(b.X, BASEPOINT.X);
        copy(b.Y, BASEPOINT.Y);
        copy(b.Z, BASEPOINT.Z);
        copy(b.T, BASEPOINT.T);
        return b;
    }

    /** A copy of (2i + 1)B from the basepoint's table, i from 0 to 7, for the suites. */
    static Affine copyOfBasepointOddMultiple(int i) {
        Affine a = new Affine();
        copy(a.yPlusx, BASEPOINT_ODD_MULTIPLES[i].yPlusx);
        copy(a.yMinusx, BASEPOINT_ODD_MULTIPLES[i].yMinusx);
        copy(a.xy2D, BASEPOINT_ODD_MULTIPLES[i].xy2D);
        return a;
    }

    // ---------------------------------------------------------------------------------------------------------
    // One verification's working storage. The points below are what the verification computes; the private
    // arrays after them are scratch, each set used by one method only.

    final Extended point = new Extended();
    final Extended rPoint = new Extended();
    final Extended extended = new Extended();
    final Completed completed = new Completed();
    final Projective projective = new Projective();
    final Cached[] table = { new Cached(), new Cached(), new Cached(), new Cached(), new Cached(),
            new Cached(), new Cached(), new Cached() };
    final byte[] nafA = new byte[256];
    final byte[] nafB = new byte[256];
    final byte[] encoding = new byte[32];

    /** reduce's output for f and g: {@link #toBytes}, {@link #equal}, {@link #isZero}, {@link #isNegative}. */
    private final int[] canonicalF = fe();
    private final int[] canonicalG = fe();
    /** {@link #invert} and {@link #powP58}. */
    private final int[] chain0 = fe();
    private final int[] chain1 = fe();
    private final int[] chain2 = fe();
    private final int[] chain3 = fe();
    /** {@link #sqrtRatioM1}. */
    private final int[] root0 = fe();
    private final int[] root1 = fe();
    private final int[] root2 = fe();
    private final int[] root3 = fe();
    private final int[] root4 = fe();
    /** {@link #decode}. */
    private final int[] decode0 = fe();
    private final int[] decode1 = fe();
    private final int[] decode2 = fe();
    /** {@link #toAffine} and {@link #encodeProjective}. */
    private final int[] coordinate0 = fe();
    private final int[] coordinate1 = fe();
    private final int[] coordinate2 = fe();
    /** The point operations. */
    private final int[] pointScratch = fe();
    /** {@link #dbl(Extended, Extended)}. */
    private final Projective doublingProjective = new Projective();
    private final Completed doublingCompleted = new Completed();

    VartimeEd25519() {
    }

    /** The canonical encoding of f into s from offset (FieldElement.toByteArray). */
    void toBytes(byte[] s, int offset, int[] f) {
        reduce(canonicalF, f);
        pack(s, offset, canonicalF);
    }

    /** Whether f and g are equal modulo p (FieldElement.ctEquals, which compares the canonical encodings). */
    boolean equal(int[] f, int[] g) {
        reduce(canonicalF, f);
        reduce(canonicalG, g);
        for (int i = 0; i < 10; i++) {
            if (canonicalF[i] != canonicalG[i]) {
                return false;
            }
        }
        return true;
    }

    /** Whether f is zero modulo p (FieldElement.isZero). */
    boolean isZero(int[] f) {
        reduce(canonicalF, f);
        int any = 0;
        for (int i = 0; i < 10; i++) {
            any |= canonicalF[i];
        }
        return any == 0;
    }

    /** 1 if the canonical value of f is odd, else 0 (FieldElement.isNegative: bit 0 of the encoding). */
    int isNegative(int[] f) {
        reduce(canonicalF, f);
        return canonicalF[0] & 1;
    }

    /** EdwardsPoint.dbl: r = 2p, through the projective and completed forms; r may be p. */
    void dbl(Extended r, Extended p) {
        toProjective(doublingProjective, p);
        dbl(doublingCompleted, doublingProjective);
        toExtended(r, doublingCompleted);
    }

    /** EdwardsPoint.toAffineNiels: (y + x, y - x, 2d x y) with x = X/Z and y = Y/Z. */
    void toAffine(Affine r, Extended p) {
        int[] recip = coordinate0;
        int[] x = coordinate1;
        int[] y = coordinate2;
        invert(recip, p.Z);
        mul(x, p.X, recip);
        mul(y, p.Y, recip);
        mul(r.xy2D, x, y);
        mul(r.xy2D, r.xy2D, D2);
        add(r.yPlusx, y, x);
        sub(r.yMinusx, y, x);
    }

    /**
     * The odd multiples [A, 3A, ..., 15A] of a point in projective Niels coordinates into {@link #table}
     * (ProjectiveNielsPoint.buildNafLookupTable). Leaves 2A in {@link #point}, so a may be {@link #point}.
     */
    void oddMultiples(Extended a) {
        toCached(table[0], a);
        dbl(point, a);
        for (int i = 0; i < 7; i++) {
            add(completed, point, table[i]);
            toExtended(extended, completed);
            toCached(table[i + 1], extended);
        }
    }

    /**
     * {@link #projective} = [a]A + [b]B in variable time, B the basepoint, from the non-adjacent forms of a and b
     * in {@link #nafA} and {@link #nafB} and the odd multiples of A in {@link #table}
     * (EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint, which then converts to extended coordinates).
     */
    void doubleScalarMultiplyBasepoint() {
        int i;
        for (i = 255; i >= 0; --i) {
            if (nafA[i] != 0 || nafB[i] != 0) {
                break;
            }
        }

        Projective r = projective;
        zero(r.X);
        one(r.Y);
        one(r.Z);
        for (; i >= 0; --i) {
            dbl(completed, r);
            int a = nafA[i];
            if (a > 0) {
                toExtended(extended, completed);
                add(completed, extended, table[a / 2]);
            } else if (a < 0) {
                toExtended(extended, completed);
                sub(completed, extended, table[-a / 2]);
            }
            int b = nafB[i];
            if (b > 0) {
                toExtended(extended, completed);
                add(completed, extended, BASEPOINT_ODD_MULTIPLES[b / 2]);
            } else if (b < 0) {
                toExtended(extended, completed);
                sub(completed, extended, BASEPOINT_ODD_MULTIPLES[-b / 2]);
            }
            toProjective(r, completed);
        }
    }

    /**
     * Decode the 32 bytes at {@code s[offset]} into {@code r} as CompressedEdwardsY.decompress decodes them: y
     * the low 255 bits modulo p, x the root of (y^2 - 1)/(d y^2 + 1) whose sign is bit 255 (x = 0 whatever that
     * bit). False, with r unspecified, when y is no point's.
     */
    boolean decode(Extended r, byte[] s, int offset) {
        int[] yy = decode0;
        int[] u = decode1;
        int[] v = decode2;
        fromBytes(r.Y, s, offset);
        sq(yy, r.Y);
        sub(u, yy, ONE); // u = YY - 1
        mul(v, yy, D);
        add(v, v, ONE); // v = d YY + 1
        if (!sqrtRatioM1(r.X, u, v)) {
            return false;
        }
        int sign = (s[offset + 31] >> 7) & 1;
        if (isNegative(r.X) != sign) {
            neg(r.X, r.X);
        }
        one(r.Z);
        mul(r.T, r.X, r.Y);
        return true;
    }

    /**
     * r = the non-negative root of u/v or, when u/v is not a square, of i u/v, and whether u/v was a square
     * (FieldElement.sqrtRatioM1, whose limbs it gives). The root is computed in an array of its own and copied to
     * r at the end, so r may be u or v.
     */
    boolean sqrtRatioM1(int[] r, int[] u, int[] v) {
        int[] v3 = root0;
        int[] power = root1;
        int[] check = root2;
        int[] uNeg = root3;
        int[] root = root4;
        sq(v3, v);
        mul(v3, v3, v); // v3
        sq(power, v3);
        mul(power, power, v); // v7
        mul(power, u, power);
        powP58(power, power); // (u v7)^((p - 5)/8)
        mul(root, u, v3);
        mul(root, root, power); // u v3 (u v7)^((p - 5)/8)
        sq(check, root);
        mul(check, v, check); // v root^2
        neg(uNeg, u);
        boolean correctSignSqrt = equal(check, u);
        boolean flippedSignSqrt = equal(check, uNeg);
        mul(uNeg, uNeg, SQRT_M1);
        boolean flippedSignSqrtM1 = equal(check, uNeg);
        if (flippedSignSqrt || flippedSignSqrtM1) {
            mul(root, root, SQRT_M1);
        }
        if (isNegative(root) == 1) {
            neg(root, root);
        }
        copy(r, root);
        return correctSignSqrt || flippedSignSqrt;
    }

    /**
     * Whether a projective point on the curve is the identity: X = 0 and Y = Z. The same test as comparing its
     * encoding with the identity's (EdwardsPoint.isIdentity), without the inversion: the encodings are equal when
     * y = 1 and x is even, and the only point of the curve with y = 1 is (0, 1).
     */
    boolean isIdentity(Projective p) {
        return isZero(p.X) && equal(p.Y, p.Z);
    }

    /**
     * Whether [8]p is the identity, p's projective form being in {@link #projective}, which it doubles in place
     * (EdwardsPoint.isSmallOrder).
     */
    boolean isSmallOrder() {
        for (int i = 0; i < 3; i++) {
            dbl(completed, projective);
            toProjective(projective, completed);
        }
        return isIdentity(projective);
    }

    /**
     * The canonical encoding of the projective point in {@link #projective} into {@link #encoding}
     * (EdwardsPoint.compress). The vendored path compressed the point's extended form (X Z : Y Z : Z^2 : X Y), the
     * same point, so the same canonical bytes.
     */
    void encodeProjective() {
        Projective p = projective;
        int[] recip = coordinate0;
        int[] x = coordinate1;
        int[] y = coordinate2;
        invert(recip, p.Z);
        mul(x, p.X, recip);
        mul(y, p.Y, recip);
        toBytes(encoding, 0, y);
        encoding[31] |= (byte) (isNegative(x) << 7);
    }

    /** Whether {@link #encoding} is the 32 bytes at {@code s[offset]}. */
    boolean encodingIs(byte[] s, int offset) {
        for (int i = 0; i < 32; i++) {
            if (encoding[i] != s[offset + i]) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------
    // The verifiers.

    /**
     * Whether the 255-bit little-endian y of the encoding at {@code s[offset]} is below p, the encoding being
     * canonical (bit 255, the sign of x, is ignored). y is at least p only for the 19 values 2^255 - 19 to
     * 2^255 - 1: byte 31 is 0x7f with bit 255 aside, bytes 1 to 30 are 0xff, and byte 0 is at least 0xed.
     */
    static boolean canonicalY(byte[] s, int offset) {
        if ((s[offset + 31] & 0x7f) != 0x7f || (s[offset] & 0xff) < 0xed) {
            return true;
        }
        for (int i = 1; i < 31; i++) {
            if (s[offset + i] != (byte) 0xff) {
                return true;
            }
        }
        return false;
    }

    /** L = 2^252 + 27742317777372353535851937790883648493, little-endian (Constants.BASEPOINT_ORDER). */
    private static final byte[] L = { (byte) 0xed, (byte) 0xd3, (byte) 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
            (byte) 0xd6, (byte) 0x9c, (byte) 0xf7, (byte) 0xa2, (byte) 0xde, (byte) 0xf9, (byte) 0xde, 0x14, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10 };

    /**
     * Whether the 256-bit little-endian integer at {@code s[offset]} is below L: what Scalar.fromCanonicalBytes
     * accepts (bit 255 clear, and equal to its reduction modulo L), and libsodium's {@code sc25519_is_canonical}.
     */
    static boolean canonicalScalar(byte[] s, int offset) {
        for (int i = 31; i >= 0; i--) {
            int a = s[offset + i] & 0xff;
            int l = L[i] & 0xff;
            if (a != l) {
                return a < l;
            }
        }
        return false;
    }

    /**
     * StrictEd25519's verification of the key at {@code key[keyOffset]} (32 bytes) and the signature R || S at
     * {@code signature[signatureOffset]} (64 bytes), in the vendored path's order:
     * <ol>
     * <li>A: its y canonical, A decoded, A not of small order;</li>
     * <li>R: the same three;</li>
     * <li>S below L;</li>
     * <li>only then {@code challenge}, which gives h = SHA-512(R || A || M) modulo L (32 bytes, little-endian);</li>
     * <li>the canonical encoding of [h](-A) + [S]B must be the bytes of R.</li>
     * </ol>
     * A signature refused by one of the first three steps is refused without hashing; whatever
     * {@code challenge} throws, this throws.
     */
    static boolean strict(byte[] key, int keyOffset, byte[] signature, int signatureOffset,
            Supplier<byte[]> challenge) {
        if (!canonicalY(key, keyOffset)) {
            return false;
        }
        VartimeEd25519 w = new VartimeEd25519();
        if (!w.decodeStrict(w.point, key, keyOffset) || !canonicalY(signature, signatureOffset)
                || !w.decodeStrict(w.rPoint, signature, signatureOffset)
                || !canonicalScalar(signature, signatureOffset + 32)) {
            return false;
        }
        return w.equation(signature, signatureOffset, signature, signatureOffset + 32, challenge.get());
    }

    /**
     * Whether StrictEd25519 accepts the 32 bytes at {@code key[keyOffset]} as a key: y canonical, on the curve,
     * not of small order (the first step of {@link #strict}).
     */
    static boolean strictKey(byte[] key, int keyOffset) {
        return canonicalY(key, keyOffset) && new VartimeEd25519().decodeStrict(new Extended(), key, keyOffset);
    }

    /** Decode a canonical encoding into r: whether it decodes to a point not of small order. */
    boolean decodeStrict(Extended r, byte[] s, int offset) {
        if (!decode(r, s, offset)) {
            return false;
        }
        toProjective(projective, r);
        return !isSmallOrder();
    }

    /**
     * ByronEd25519's verification of the key at {@code key[keyOffset]} (32 bytes) and the signature R || S at
     * {@code signature[signatureOffset]} (64 bytes), in the vendored path's order:
     * <ol>
     * <li>S below 2^253 (none of the top three bits of its last byte set);</li>
     * <li>A decoded, y modulo p, any order;</li>
     * <li>s = S modulo L, then {@code challenge}, which gives h = SHA-512(R || A || M) modulo L (32 bytes,
     * little-endian);</li>
     * <li>the canonical encoding of [h](-A) + [s]B must be the bytes of R, which is never decoded.</li>
     * </ol>
     * A signature refused by one of the first two steps is refused without hashing; whatever {@code challenge}
     * throws, this throws.
     */
    static boolean byron(byte[] key, int keyOffset, byte[] signature, int signatureOffset,
            Supplier<byte[]> challenge) {
        if ((signature[signatureOffset + 63] & 0xe0) != 0) {
            return false;
        }
        VartimeEd25519 w = new VartimeEd25519();
        if (!w.decode(w.point, key, keyOffset)) {
            return false;
        }
        // S zero-padded to 64 bytes and reduced, as the vendored path reduced it
        byte[] wide = new byte[64];
        System.arraycopy(signature, signatureOffset + 32, wide, 0, 32);
        byte[] s = Scalar.fromBytesModOrderWide(wide).toByteArray();
        return w.equation(signature, signatureOffset, s, 0, challenge.get());
    }

    /**
     * Whether the canonical encoding of Q = [h](-A) + [s]B is the bytes of R at {@code signature[signatureOffset]},
     * A being decoded in {@link #point}; leaves Q in {@link #projective}. The point is negated, not the scalar:
     * -h modulo L would change the result for a key with a torsion component, which StrictEd25519's profile
     * accepts (mixed-order speccheck case 3).
     */
    boolean equation(byte[] signature, int signatureOffset, byte[] s, int sOffset, byte[] h) {
        negate(point, point);
        oddMultiples(point);
        nonAdjacentForm(nafA, h, 0);
        nonAdjacentForm(nafB, s, sOffset);
        doubleScalarMultiplyBasepoint();
        encodeProjective();
        return encodingIs(signature, signatureOffset);
    }
}
