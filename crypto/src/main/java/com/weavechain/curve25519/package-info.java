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
 *       profiles show (performance sections 1 and 13). Outside the hooks below, a value's fields are assigned only
 *       by its constructor.</li>
 *   <li>FUN-2 (c), a callback whose contract keeps its state by mutation and signals failure by exception: the
 *       {@code java.io.Serializable} hooks ({@code writeObject}, {@code readObject}, {@code readObjectNoData}) of
 *       {@link EdwardsPoint}, {@link RistrettoElement}, {@link CompressedEdwardsY}, {@link CompressedRistretto} and
 *       {@link Scalar}, the only code that assigns their {@code transient} fields after construction.</li>
 * </ul>
 *
 * <p>No exception covers the decoders that throw on a malformed encoding ({@link InvalidEncodingException} from
 * {@code decompress}, {@link IllegalArgumentException} from {@code Scalar.fromCanonicalBytes}), which ERR-4
 * refuses; the callers in {@code xyz.kd5ujc.scarab.crypto} catch them where they call. That, and that no guard
 * reads these sources, are gaps of #1418.
 */
package com.weavechain.curve25519;
