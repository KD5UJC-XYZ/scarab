// SPDX-License-Identifier: Apache-2.0
/**
 * The Edwards-curve and scalar arithmetic of curve25519-elisabeth, vendored from its source
 * (docs/licenses.md, "curve25519-elisabeth provenance").
 *
 * <p>This package is imperative Java, kept under two of the design rules' exceptions (FUN-2) and marked here, as
 * FUN-3 asks of a kept site, because no wart reads Java:
 * <ul>
 *   <li>FUN-2 (a), a performance-critical path: the limb arithmetic of {@link FieldElement}, {@link Scalar} and
 *       {@link UnpackedScalar} and the point formulas loop over, and assign, arrays and locals made for the
 *       computation. They are the inner loop of every Ed25519 verification and VRF proof, the largest cost the
 *       profiles show (performance sections 1 and 13). A value's fields are assigned only by its constructor.</li>
 *   <li>FUN-2 (d), secret material erased in place: {@code Scalar.reduceWide}, {@code Scalar.multiplyAndAdd} and
 *       {@code Scalar.toRadix16} over byte arrays, and {@code multiplyRadix16} of {@link EdwardsBasepointTable} and
 *       {@link EdwardsPoint}, read and write arrays their caller owns and allocate none of their own, so that the VRF
 *       prover keeps its secret scalars and their digits in storage it zeroes ({@code xyz.kd5ujc.scarab.crypto.vrf.secret}).
 *       The allocating forms ({@code fromBytesModOrderWide}, {@code multiplyAndAdd(Scalar, Scalar)},
 *       {@code toRadix16()}, {@code multiply(Scalar)}) compute through them.</li>
 * </ul>
 *
 * <p>The decoders return a result and throw on no input: {@link CompressedEdwardsY#decompress()} and
 * {@link CompressedRistretto#decompress()} give an empty {@code Optional} for bytes that encode no point, and
 * {@link Scalar#fromCanonicalBytes(byte[])} for bytes that are not the 32-byte encoding of a scalar below the
 * group order. The {@code throw} statements left check the caller's arguments, which the callers in
 * {@code xyz.kd5ujc.scarab.crypto} never fail: an array of another length than a constructor or a method takes,
 * a radix-16 digit outside [-8, 8], a NAF digit that is even or above 15, an exponent below 1, and the high bit of
 * a 32-byte {@link Scalar}.
 */
package com.weavechain.curve25519;
