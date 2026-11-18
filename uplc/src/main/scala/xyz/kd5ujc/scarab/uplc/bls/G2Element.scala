// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import supranational.blst.P2
import xyz.kd5ujc.scarab.codec.Bytes

/** A point of the BLS12-381 group G2, the value of a `bls12_381_G2_element` constant
  * (`DefaultUniBLS12_381_G2_Element`, `PlutusCore/Default/Universe.hs:148`, plutus 1.70.0.0
  * `507cab8c`). The reference holds blst's point (`blst_p2`, projective: `Element`,
  * `PlutusCore/Crypto/BLS12_381/G2.hs:55-56` at plutus 1.71.0.0; `Point`, compared by
  * `blst_p_is_equal`, cardano-crypto-class `BLS12_381/Internal.hs:297,897-901` at cardano-base
  * `12168e4b`), which its group operations take and give as it is; so is an element here: jblst's
  * point, and its 96-byte compressed encoding, made once from the point when it is first asked for
  * (by `compress`, or to compare, hash or show the element as a constant). A point is made once
  * from an encoding too, when an operation first needs it. The compressed form of a point is
  * unique, so equality of the encodings is equality of the points.
  *
  * Every value is a point of the subgroup: [[Bls12_381]] makes one only by uncompressing with the
  * reference's checks, by hashing to the group, or by the group operations. jblst's point is a
  * mutable handle; an element's is never changed: an operation changes a copy (`dup`) only.
  */
final class G2Element private (seed: G2Element.Seed):
  /** The compressed encoding (`blsCompress`). */
  lazy val compressed: Bytes = seed match
    case G2Element.Seed.Encoded(bytes) => bytes
    case G2Element.Seed.Point(point)   => Bytes.wrap(point.compress())
    case G2Element.Seed.Both(bytes, _) => bytes

  /** jblst's point, which no one may change. Decompressing an encoding that the reference's checks
    * passed cannot fail; a placeholder's ([[G2Element.fromCompressed]]) can, and is read only where
    * a failure is a [[BlsDefect]].
    */
  private[bls] lazy val point: P2 = seed match
    case G2Element.Seed.Encoded(bytes) => new P2(bytes.toArray)
    case G2Element.Seed.Point(point)   => point
    case G2Element.Seed.Both(_, point) => point

  override def equals(that: Any): Boolean = that match
    case other: G2Element => compressed == other.compressed
    case _                => false
  override def hashCode(): Int = compressed.hashCode
  override def toString: String = s"G2Element(${compressed.hex})"

object G2Element:
  /** What an element is made from: its encoding, its point, or both. */
  private enum Seed:
    case Encoded(bytes: Bytes)
    case Point(point: P2)
    case Both(bytes: Bytes, point: P2)

  /** The point whose compressed encoding is `compressed`; the caller has validated it. A program's
    * constant is validated by [[Bls12_381.G2.uncompress]] (the reference parses a textual constant
    * through `uncompress`).
    */
  def fromCompressed(compressed: Bytes): G2Element = new G2Element(Seed.Encoded(compressed))

  /** The element of a point of the subgroup that no one changes afterwards. */
  private[bls] def ofPoint(point: P2): G2Element = new G2Element(Seed.Point(point))

  /** The element of a point of the subgroup and its compressed encoding. */
  private[bls] def of(compressed: Bytes, point: P2): G2Element =
    new G2Element(Seed.Both(compressed, point))
