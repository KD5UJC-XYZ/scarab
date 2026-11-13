// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import xyz.kd5ujc.scarab.codec.Bytes

/** A point of the BLS12-381 group G2, the value of a `bls12_381_G2_element` constant
  * (`DefaultUniBLS12_381_G2_Element`, `PlutusCore/Default/Universe.hs:148`, plutus 1.70.0.0
  * `507cab8c`), held as its 96-byte compressed encoding. The compressed form of a point is unique,
  * so equality of the bytes is equality of the points (`Bls12_381_G2_equal`).
  *
  * Every value is a point of the subgroup: [[Bls12_381]] makes one only by uncompressing with the
  * reference's checks, by hashing to the group, or by the group operations, and decompresses the
  * bytes again for each operation.
  */
opaque type G2Element = Bytes

object G2Element:
  /** The point whose compressed encoding is `compressed`; the caller has validated it. A program's
    * constant is validated by [[Bls12_381.G2.uncompress]] (the reference parses a textual constant
    * through `uncompress`).
    */
  def fromCompressed(compressed: Bytes): G2Element = compressed

  extension (e: G2Element) def compressed: Bytes = e
