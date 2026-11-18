// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import supranational.blst.P1
import xyz.kd5ujc.scarab.codec.Bytes

/** A point of the BLS12-381 group G1, the value of a `bls12_381_G1_element` constant
  * (`DefaultUniBLS12_381_G1_Element`, `PlutusCore/Default/Universe.hs:147`, plutus 1.70.0.0
  * `507cab8c`). The reference holds blst's point (`blst_p1`, projective: `Element`,
  * `PlutusCore/Crypto/BLS12_381/G1.hs:69-70` at plutus 1.71.0.0; `Point`, compared by
  * `blst_p_is_equal`, cardano-crypto-class `BLS12_381/Internal.hs:297,897-901` at cardano-base
  * `12168e4b`), which its group operations take and give as it is; so is an element here: jblst's
  * point, and its 48-byte compressed encoding, made once from the point when it is first asked for
  * (by `compress`, or to compare, hash or show the element as a constant). A point is made once
  * from an encoding too, when an operation first needs it. The compressed form of a point is
  * unique, so equality of the encodings is equality of the points.
  *
  * Every value is a point of the subgroup: [[Bls12_381]] makes one only by uncompressing with the
  * reference's checks, by hashing to the group, or by the group operations. jblst's point is a
  * mutable handle; an element's is never changed: an operation changes a copy (`dup`) only.
  */
final class G1Element private (seed: G1Element.Seed):
  /** The compressed encoding (`blsCompress`). */
  lazy val compressed: Bytes = seed match
    case G1Element.Seed.Encoded(bytes) => bytes
    case G1Element.Seed.Point(point)   => Bytes.wrap(point.compress())
    case G1Element.Seed.Both(bytes, _) => bytes

  /** jblst's point, which no one may change. Decompressing an encoding that the reference's checks
    * passed cannot fail; a placeholder's ([[G1Element.fromCompressed]]) can, and is read only where
    * a failure is a [[BlsDefect]].
    */
  private[bls] lazy val point: P1 = seed match
    case G1Element.Seed.Encoded(bytes) => new P1(bytes.toArray)
    case G1Element.Seed.Point(point)   => point
    case G1Element.Seed.Both(_, point) => point

  override def equals(that: Any): Boolean = that match
    case other: G1Element => compressed == other.compressed
    case _                => false
  override def hashCode(): Int = compressed.hashCode
  override def toString: String = s"G1Element(${compressed.hex})"

object G1Element:
  /** What an element is made from: its encoding, its point, or both. */
  private enum Seed:
    case Encoded(bytes: Bytes)
    case Point(point: P1)
    case Both(bytes: Bytes, point: P1)

  /** The point whose compressed encoding is `compressed`; the caller has validated it. A program's
    * constant is validated by [[Bls12_381.G1.uncompress]] (the reference parses a textual constant
    * through `uncompress`).
    */
  def fromCompressed(compressed: Bytes): G1Element = new G1Element(Seed.Encoded(compressed))

  /** The element of a point of the subgroup that no one changes afterwards. */
  private[bls] def ofPoint(point: P1): G1Element = new G1Element(Seed.Point(point))

  /** The element of a point of the subgroup and its compressed encoding. */
  private[bls] def of(compressed: Bytes, point: P1): G1Element =
    new G1Element(Seed.Both(compressed, point))
