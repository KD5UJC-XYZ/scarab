// SPDX-License-Identifier: Apache-2.0 AND MIT
package lab.vrf;

// Public-input-only adaptation of CCL VrfUtil at 45906b783973b4c44ac6883253af26bdd07bc1b4.
// MIT: see fixtures/vrf/licenses/CCL-LICENSE. Variable-time; public inputs only.
// Native profile: sodium dbb48cce5429cb6585c9034f002568964f1ce567.

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class Draft03Math {
    /** The field prime 2^255 - 19. */
    public static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

    /** The group order 2^252 + 27742317777372353535851937790883648493. */
    public static final BigInteger L =
            BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));

    /** The Montgomery curve coefficient A. */
    static final BigInteger A = BigInteger.valueOf(486662);

    /** Little-endian bytes as a non-negative integer. */
    public static BigInteger integer(byte[] b) {
        byte[] reversed = b.clone();
        for (int i = 0; i < b.length; i++) {
            reversed[b.length - 1 - i] = b[i];
        }
        return new BigInteger(1, reversed);
    }

    /** A non-negative integer below 2^256 as 32 little-endian bytes. */
    public static byte[] scalar(BigInteger x) {
        if (x.signum() < 0 || x.bitLength() > 256) {
            throw new IllegalArgumentException("scalar range");
        }
        byte[] big = x.toByteArray();
        byte[] result = new byte[32];
        for (int i = 0; i < Math.min(32, big.length); i++) {
            result[i] = big[big.length - 1 - i];
        }
        return result;
    }

    /** SHA-512 of the concatenated pieces. */
    public static byte[] hash(byte[]... pieces) {
        try {
            var sha = MessageDigest.getInstance("SHA-512");
            for (byte[] piece : pieces) {
                sha.update(piece);
            }
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /** The Elligator 2 map of 32 uniform bytes to a point in the prime-order subgroup. */
    public static Ed25519Point uniform(byte[] input) {
        if (input.length != 32) {
            throw new IllegalArgumentException("uniform length");
        }
        byte[] rb = input.clone();
        int sign = (rb[31] & 255) >>> 7;
        rb[31] &= 127;
        BigInteger r = integer(rb).mod(P);
        BigInteger den = BigInteger.ONE.add(BigInteger.TWO.multiply(r.multiply(r))).mod(P);
        // Field inversion maps zero to zero, as native fe25519_invert does.
        BigInteger u = A.negate().multiply(den.modPow(P.subtract(BigInteger.TWO), P)).mod(P);
        BigInteger w = u.multiply(u.multiply(u).add(A.multiply(u)).add(BigInteger.ONE)).mod(P);
        BigInteger legendre = w.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P);
        if (legendre.equals(P.subtract(BigInteger.ONE))) {
            u = u.negate().subtract(A).mod(P);
        }
        BigInteger w2 = u.multiply(u.multiply(u).add(A.multiply(u)).add(BigInteger.ONE)).mod(P);
        BigInteger up = u.add(BigInteger.ONE).mod(P);
        // mont_to_ed returns identity if (u+1)*montgomery_y == 0.
        BigInteger y =
                up.signum() == 0 || w2.signum() == 0
                        ? BigInteger.ONE
                        : u.subtract(BigInteger.ONE).multiply(up.modInverse(P)).mod(P);
        byte[] encoded = scalar(y);
        encoded[31] |= (byte) (sign << 7);
        Ed25519Point h = Ed25519Point.decode(encoded);
        if (h == null) {
            throw new IllegalStateException("Elligator map produced undecodable point");
        }
        return h.multiplyByCofactor();
    }

    /** Hash to curve: the point H for public key {@code y} and message {@code alpha}. */
    public static Ed25519Point h2c(byte[] y, byte[] alpha) {
        byte[] r = Arrays.copyOf(hash(new byte[] {4, 1}, y, alpha), 32);
        r[31] &= 127;
        return uniform(r);
    }

    /** The 16-byte challenge over H, the original Gamma encoding, U and V. */
    public static byte[] challenge(
            Ed25519Point h, byte[] originalGamma, Ed25519Point u, Ed25519Point v) {
        return Arrays.copyOf(
                hash(new byte[] {4, 2}, h.encode(), originalGamma, u.encode(), v.encode()), 16);
    }

    /** The 64-byte VRF output for Gamma. */
    public static byte[] output(Ed25519Point gamma) {
        return hash(new byte[] {4, 3}, gamma.multiplyByCofactor().encode());
    }

    /** {@code -c * p + s * q}, the verification equation shared by U and V. */
    public static Ed25519Point equation(Ed25519Point p, Ed25519Point q, byte[] c, byte[] s) {
        return p.scalarMultiply(scalar(integer(c).negate().mod(L))).add(q.scalarMultiply(s));
    }
}
