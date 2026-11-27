// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{DecodeField, ProtocolParams, Unsigned}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W32, W64}
import xyz.kd5ujc.scarab.ledger.state.{CompactCoin, EpochInterval}

/** The readers of the fields of the parameters the node keeps ([[ProtocolParams]]) at the
  * reference's types, which each era's `fromStored` reads its record with. A field outside its
  * type's range is refused, with the field and the value: the node read each at its type, so this
  * does not happen to parameters it decoded.
  */
private[era] object StoredPParams:
  type Read[A] = Either[PParamsError, A]

  def unsigned[W <: Unsigned.Width: Unsigned.Bound](
      field: DecodeField,
      n: BigInt
  ): Read[Unsigned[W]] =
    Unsigned.from[W](n).toRight(PParamsError.OutOfRange(field, n))

  def compact(field: DecodeField, n: BigInt): Read[CompactCoin] =
    unsigned[W64](field, n).map(w => CompactCoin.fromUnsigned(w.value.toLong))

  def epochs(field: DecodeField, n: BigInt): Read[EpochInterval] =
    unsigned[W32](field, n).map(EpochInterval.fromWord32)

  def unit(field: DecodeField, r: Rational): Read[UnitInterval] =
    UnitInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  def nonNegative(field: DecodeField, r: Rational): Read[NonNegativeInterval] =
    NonNegativeInterval.boundRational(r).toRight(PParamsError.RatioOutOfBounds(field, r))

  def units(field: DecodeField, u: xyz.kd5ujc.scarab.plutus.ExUnits): Read[OrdExUnits] =
    ExUnits.from(u).map(OrdExUnits(_)).toRight(PParamsError.NegativeExUnits(field, u))

  def protVer(p: ProtocolParams): Read[ProtVer] =
    (
      Version
        .mkVersion(p.protocolMajor)
        .toRight(PParamsError.OutOfRange(DecodeField.ProtocolMajor, p.protocolMajor)),
      unsigned[W32](DecodeField.ProtocolVersion, p.protocolMinor)
    ).mapN(ProtVer(_, _))
