// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import cats.syntax.either.*
import supranational.blst.{P1_Affine, P2_Affine}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element}

/** The points of a `bls12_381_G1_element` or `bls12_381_G2_element` literal, which the parser
  * decodes with `uncompress` (`PlutusCore/Parser/Builtin.hs:138-150`, plutus 1.70.0.0 `507cab8c`):
  * cardano-base's `blsUncompress` (not vendored) takes a compressed point of the group's length,
  * decodes it with blst and refuses a point outside the subgroup. Here blst is jblst's, through the
  * same steps as `MinSig.decodeG1` in `crypto`: a native failure is a value, never thrown.
  */
object BlsLiteral:
  private def compressed(bytes: Bytes, size: Int): Either[PointProblem, Array[Byte]] =
    if bytes.size != size then Left(PointProblem.WrongLength(size, bytes.size))
    else if !bytes.headOption.exists(b => (b & 0x80) != 0) then Left(PointProblem.NotCompressed)
    else Right(bytes.toArray)

  /** The point of G1 whose 48-byte compressed encoding is `bytes`. */
  def g1(bytes: Bytes): Either[PointProblem, G1Element] =
    for
      raw <- compressed(bytes, 48)
      point <- Either.catchNonFatal(new P1_Affine(raw)).leftMap(_ => PointProblem.Malformed)
      _ <- Either.cond(point.in_group(), (), PointProblem.NotInGroup)
    yield G1Element.fromCompressed(Bytes.wrap(point.compress()))

  /** The point of G2 whose 96-byte compressed encoding is `bytes`. */
  def g2(bytes: Bytes): Either[PointProblem, G2Element] =
    for
      raw <- compressed(bytes, 96)
      point <- Either.catchNonFatal(new P2_Affine(raw)).leftMap(_ => PointProblem.Malformed)
      _ <- Either.cond(point.in_group(), (), PointProblem.NotInGroup)
    yield G2Element.fromCompressed(Bytes.wrap(point.compress()))
