// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import xyz.kd5ujc.scarab.codec.Bytes

/** The result of a Miller loop, an element of the BLS12-381 target field: the value of a
  * `bls12_381_mlresult` constant (`DefaultUniBLS12_381_MlResult`,
  * `PlutusCore/Default/Universe.hs:149`, plutus 1.70.0.0 `507cab8c`), held as the raw bytes of the
  * field element. It has no Flat or textual form; only `Bls12_381_millerLoop` and
  * `Bls12_381_mulMlResult` make one. Equality is equality of the bytes.
  *
  * The builtins (work package 6 of issue 43) own the representation and may refine its internals.
  */
opaque type MlResult = Bytes

object MlResult:
  /** The element whose raw bytes are `raw`, as the pairing code produced them. */
  def fromRaw(raw: Bytes): MlResult = raw

  extension (m: MlResult) def raw: Bytes = m
