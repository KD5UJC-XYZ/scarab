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
 * <p>The decoders throw on a malformed encoding ({@link InvalidEncodingException} from {@code decompress},
 * {@link IllegalArgumentException} from {@code Scalar.fromCanonicalBytes}), as their upstream does: ERR-4 lists
 * these sources among its exceptions, and the interfaces of {@code xyz.kd5ujc.scarab.crypto} that pass them
 * outside input catch where they call. No wart or guard of FUN-1, FUN-3 or ERR-4 reads these sources: they are
 * FUN-2 (f), review only. A typed result from the decoders is an open item of #1418.
 */
package com.weavechain.curve25519;
