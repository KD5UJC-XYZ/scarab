// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import supranational.blst.PT
import xyz.kd5ujc.scarab.codec.Bytes

/** The result of a Miller loop, an element of the BLS12-381 target field: the value of a
  * `bls12_381_mlresult` constant (`DefaultUniBLS12_381_MlResult`,
  * `PlutusCore/Default/Universe.hs:149`, plutus 1.70.0.0 `507cab8c`). It has no Flat or textual
  * form; only `Bls12_381_millerLoop` and `Bls12_381_mulMlResult` make one
  * (`PlutusCore/Crypto/BLS12_381/Pairing.hs:34-44`).
  *
  * The reference holds blst's `PT` and compares two with `blst_fp12_is_equal` (`deriving newtype
  * Eq`, `Pairing.hs:39-40`). jblst can make a `PT` only by a Miller loop or a product, never from
  * bytes, so a value here keeps jblst's `PT` ([[Pairing]] never changes one once it is made) and
  * its 576-byte big-endian encoding [[raw]] (`to_bendian`), which is unique to the field element:
  * equality of the bytes is equality of the elements.
  */
opaque type MlResult = MlResult.Element

object MlResult:
  /** An element and, when the pairing code made it, jblst's value of it. */
  final class Element private[bls] (val raw: Bytes, private[bls] val target: Option[PT])

  /** An element with the raw bytes `raw` and no jblst value: a placeholder for tests of other parts
    * of the evaluator. The pairing builtins refuse it as a defect ([[BlsDefect.DetachedMlResult]]).
    */
  def fromRaw(raw: Bytes): MlResult = Element(raw, None)

  /** The element jblst computed as `target`, which no one may change afterwards. */
  private[bls] def ofTarget(target: PT): MlResult =
    Element(Bytes.wrap(target.to_bendian()), Some(target))

  extension (m: MlResult)
    def raw: Bytes = m.raw
    private[bls] def target: Option[PT] = m.target
