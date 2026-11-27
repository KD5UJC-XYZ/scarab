// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Monoid
import cats.data.{Chain, StateT}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  CborRead,
  ConwayParam,
  CostModelDecode,
  DecodeField,
  DecodeSite,
  DecoderVersion,
  LedgerDecodeError,
  Unsigned
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.{W32, W64}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.conway.{DRepVotingThresholds, PoolVotingThresholds}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{Milliseconds32, SlotInterval}
import xyz.kd5ujc.scarab.ledger.state.{Coin, CompactCoin, EpochInterval, HasZero, NonZero, Word64}

// The era types' CBOR (cardano-ledger-binary of cardano-ledger 9df9f98c
// `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/`): the encoding of a value as the
// reference's `EncCBOR` writes it, and its decoding at a decoder version as `DecCBOR` reads it.

/** `Encoding` (`Encoding/Encoder.hs`, cardano-ledger 9df9f98c): the bytes of a sequence of CBOR
  * tokens, each head in its shortest form, joined by its monoid as the reference's `<>`.
  */
opaque type Encoding = Chain[Byte]

object Encoding:
  given Monoid[Encoding] with
    def empty: Encoding = Chain.empty
    def combine(x: Encoding, y: Encoding): Encoding = x ++ y

  private val Word64Limit: BigInt = BigInt(1) << 64

  /** A head of `major` over `n`, which is below 2^64: the shortest of the five widths. */
  private def head(major: Int, n: BigInt): Encoding =
    val m = major << 5
    def bigEndian(width: Int): Chain[Byte] =
      Chain.fromSeq((width - 1 to 0 by -1).map(i => ((n >> (8 * i)) & 0xff).toByte))
    if n < 24 then Chain.one((m | n.toInt).toByte)
    else if n <= 0xff then Chain.one((m | 24).toByte) ++ bigEndian(1)
    else if n <= 0xffff then Chain.one((m | 25).toByte) ++ bigEndian(2)
    else if n <= 0xffffffffL then Chain.one((m | 26).toByte) ++ bigEndian(4)
    else Chain.one((m | 27).toByte) ++ bigEndian(8)

  /** `encodeWord`, `encodeWord16`, `encodeWord32`, `encodeWord64`: an unsigned integer. */
  def encodeWord[W <: Unsigned.Width](u: Unsigned[W]): Encoding = head(0, u.value)

  /** `encodeWord64` of a `Word64`. */
  def encodeWord64(w: Word64): Encoding = head(0, Word64.toBigInt(w))

  /** `encodeInteger` (cborg): an integer item where it fits 64 bits, otherwise the bignum of tag 2
    * (non-negative) or 3 (negative, `-1 - n`) over its magnitude's big-endian bytes.
    */
  def encodeInteger(n: BigInt): Encoding =
    def bignum(tag: Int, m: BigInt): Encoding =
      val raw = m.toByteArray.dropWhile(_ == 0)
      head(6, tag) ++ head(2, raw.length) ++ Chain.fromSeq(raw.toIndexedSeq)
    if n.signum >= 0 then if n < Word64Limit then head(0, n) else bignum(2, n)
    else if -n <= Word64Limit then head(1, -1 - n)
    else bignum(3, -1 - n)

  /** `encodeBytes`: a definite byte string. */
  def encodeBytes(b: Bytes): Encoding = head(2, b.size) ++ Chain.fromSeq(b.toArray.toIndexedSeq)

  /** `encodeListLen`: the head of a definite list of `size` items. */
  def encodeListLen(size: Int): Encoding = head(4, size)

  /** `encodeMapLen`: the head of a definite map of `size` entries. */
  def encodeMapLen(size: Int): Encoding = head(5, size)

  /** `encodeTag`. */
  def encodeTag(tag: Int): Encoding = head(6, tag)

  /** `encodeNull`. */
  val encodeNull: Encoding = Chain.one(0xf6.toByte)

  private val listLenIndef: Encoding = Chain.one(0x9f.toByte)
  private val mapLenIndef: Encoding = Chain.one(0xbf.toByte)
  private val break: Encoding = Chain.one(0xff.toByte)

  /** `lengthThreshold` (`Encoder.hs:442-443`, cardano-ledger 9df9f98c). */
  private val LengthThreshold = 23

  /** `variableListLenEncoding` (`Encoder.hs:458-468`, cardano-ledger 9df9f98c): a definite list of
    * at most 23 items, an indefinite one past that.
    */
  def variableListLenEncoding(size: Int, contents: Encoding): Encoding =
    if size <= LengthThreshold then encodeListLen(size) ++ contents
    else listLenIndef ++ contents ++ break

  /** `variableMapLenEncoding` (`Encoder.hs`, cardano-ledger 9df9f98c): as
    * [[variableListLenEncoding]], for a map.
    */
  def variableMapLenEncoding(size: Int, contents: Encoding): Encoding =
    if size <= LengthThreshold then encodeMapLen(size) ++ contents
    else mapLenIndef ++ contents ++ break

  /** `encodeStrictMaybe` (`Encoder.hs:331-334`, cardano-ledger 9df9f98c): `[]` or `[x]`. */
  def encodeStrictMaybe[A](enc: A => Encoding)(a: Option[A]): Encoding =
    a.fold(encodeListLen(0))(x => encodeListLen(1) ++ enc(x))

  /** `encodeNullStrictMaybe` (`Encoder.hs:340-343`, cardano-ledger 9df9f98c): `null` or `x`. */
  def encodeNullStrictMaybe[A](enc: A => Encoding)(a: Option[A]): Encoding =
    a.fold(encodeNull)(enc)

  extension (e: Encoding)
    /** The bytes of the tokens, in order (`serialize'`). */
    def bytes: Bytes = Bytes.fromArray(e.iterator.toArray)

/** `class EncCBOR a` (`Encoding/EncCBOR.hs`, cardano-ledger 9df9f98c): how a value is written. */
trait EncCBOR[A]:
  def encCBOR(a: A): Encoding

/** What a decoder reads at: the decoder version, which the reference's `Decoder s` carries, and the
  * site a refusal names.
  */
final case class DecodeAt(version: DecoderVersion, site: DecodeSite):
  def at(other: DecodeSite): DecodeAt = copy(site = other)

/** `class DecCBOR a` (`Decoding/DecCBOR.hs`, cardano-ledger 9df9f98c): how a value is read from an
  * item, at the version and site of [[DecodeAt]].
  */
trait DecCBOR[A]:
  def decCBOR(n: Node)(using at: DecodeAt): Either[LedgerDecodeError, A]

/** `data EraCodec era t` (`libs/cardano-ledger-core/src/Cardano/Ledger/Core/PParams.hs:820-829`,
  * cardano-ledger 9df9f98c): the writer and reader an update of a parameter uses instead of its
  * value's `encCBOR` and `decCBOR`.
  */
final case class EraCodec[E, T](eraCodecEncoder: T => Encoding, eraCodecDecoder: DecCBOR[T])

object EncCBOR:
  import Encoding.*

  def apply[A](using e: EncCBOR[A]): EncCBOR[A] = e

  given unsigned[W <: Unsigned.Width]: EncCBOR[Unsigned[W]] = encodeWord(_)

  /** `instance EncCBOR MaryValue` (`eras/mary/impl/src/Cardano/Ledger/Mary/Value.hs:342-350`,
    * cardano-ledger f649f975): the coin alone when the value holds no asset, else `[coin, assets]`,
    * the assets `MultiAsset` 's derived `EncCBOR` (`:154-155`), the `Map PolicyID (Map AssetName
    * Integer)` 's: each map of variable length (`encodeMap`) with its keys ascending, as `Data.Map`
    * holds them (a policy id and an asset name compared bytewise, `Ord` of a hash and of a
    * `ShortByteString`), each key its bytes and each quantity `encodeInteger` of it.
    */
  given multiAssetValue: EncCBOR[MultiAssetValue] = v =>
    def sortedMap[V](m: Map[Bytes, V])(value: V => Encoding): Encoding =
      val entries = m.toVector.sortBy(_._1)
      variableMapLenEncoding(
        entries.size,
        entries.foldMap((k, x) => encodeBytes(k) |+| value(x))
      )
    if v.assets.isEmpty then encodeInteger(v.lovelace)
    else
      encodeListLen(2) |+| encodeInteger(v.lovelace) |+|
        sortedMap(v.assets)(names => sortedMap(names)(encodeInteger))

  given compactCoin: EncCBOR[CompactCoin] = c => encodeWord64(c.word)

  /** `CoinPerByte`'s, its compact coin's (`Coin.hs:220-222`, cardano-ledger 9df9f98c). */
  given coinPerByte: EncCBOR[CoinPerByte] = c => compactCoin.encCBOR(c.unCoinPerByte)

  /** `Coin`'s, `encodeInteger` of its integer (`Coin.hs:106-112`, cardano-ledger 9df9f98c). */
  given coin: EncCBOR[Coin] = c => encodeInteger(c.value)

  /** `EpochInterval`'s, its `Word32` (cardano-slotting). */
  given epochInterval: EncCBOR[EpochInterval] = e => encodeInteger(BigInt(e.toLong))

  /** `BoundedRatio` 's, `encodeRatioWithTag` (`BaseTypes.hs:399-405`, cardano-ledger 9df9f98c): tag
    * 30 over the reduced numerator and denominator.
    */
  private def ratio(r: xyz.kd5ujc.scarab.codec.Rational): Encoding =
    encodeTag(30) |+| encodeListLen(2) |+| encodeInteger(r.numerator) |+|
      encodeInteger(r.denominator)

  given unitInterval: EncCBOR[UnitInterval] = u => ratio(u.rational)
  given nonNegativeInterval: EncCBOR[NonNegativeInterval] = u => ratio(u.rational)
  given positiveInterval: EncCBOR[PositiveInterval] = u => ratio(u.rational)

  /** `NonZero`'s, its value's (`BaseTypes/NonZero.hs:99-100`, cardano-ledger 9df9f98c). */
  given nonZero[A](using e: EncCBOR[A]): EncCBOR[NonZero[A]] = z => e.encCBOR(z.value)

  /** `StrictMaybe`'s, `encodeStrictMaybe` (`EncCBOR.hs:315-316`, cardano-ledger 9df9f98c). */
  given strictMaybe[A](using e: EncCBOR[A]): EncCBOR[Option[A]] = a =>
    encodeStrictMaybe(e.encCBOR)(a)

  /** `ProtVer` via `CBORGroup` (`BaseTypes.hs:212-245`, cardano-ledger 9df9f98c): `[major, minor]`.
    */
  given protVer: EncCBOR[ProtVer] = p =>
    encodeListLen(2) |+| encodeInteger(BigInt(p.pvMajor.value)) |+| encodeWord(p.pvMinor)

  /** `ExUnits` (`Plutus/ExUnits.hs:183-190`, cardano-ledger 9df9f98c): `[memory, steps]`. */
  given exUnits: EncCBOR[ExUnits] = u =>
    encodeListLen(2) |+| encodeInteger(u.units.mem) |+| encodeInteger(u.units.steps)

  /** `OrdExUnits`'s, its units' (`Alonzo/PParams.hs:413-418`, cardano-ledger 9df9f98c). */
  given ordExUnits: EncCBOR[OrdExUnits] = u => exUnits.encCBOR(u.unOrdExUnits)

  /** `Prices` (`ExUnits.hs:232-234`, cardano-ledger 9df9f98c): `[memory price, steps price]`. */
  given prices: EncCBOR[Prices] = p =>
    encodeListLen(2) |+| nonNegativeInterval.encCBOR(p.prMem) |+|
      nonNegativeInterval.encCBOR(p.prSteps)

  /** `CostModels` (`CostModels.hs:470-471`, cardano-ledger 9df9f98c): `encCBOR` of the flattened
    * `Map Word8 [Int64]`, the map and each list of variable length (`encodeMap`, `encodeList`).
    */
  given costModels: EncCBOR[CostModels] = c =>
    val models = c.models.toVector.sortBy(_._1)
    variableMapLenEncoding(
      models.size,
      models.foldMap((tag, ps) =>
        encodeInteger(BigInt(tag)) |+| variableListLenEncoding(ps.size, ps.foldMap(encodeInteger))
      )
    )

  /** `PoolVotingThresholds` (`Conway/PParams.hs:367-375`, cardano-ledger 9df9f98c): a list of five.
    */
  given poolVotingThresholds: EncCBOR[PoolVotingThresholds] = t =>
    encodeListLen(5) |+| Vector(
      t.pvtMotionNoConfidence,
      t.pvtCommitteeNormal,
      t.pvtCommitteeNoConfidence,
      t.pvtHardForkInitiation,
      t.pvtPPSecurityGroup
    ).foldMap(unitInterval.encCBOR)

  /** `DRepVotingThresholds` (`Conway/PParams.hs:520-533`, cardano-ledger 9df9f98c): a list of ten.
    */
  given drepVotingThresholds: EncCBOR[DRepVotingThresholds] = t =>
    encodeListLen(10) |+| Vector(
      t.dvtMotionNoConfidence,
      t.dvtCommitteeNormal,
      t.dvtCommitteeNoConfidence,
      t.dvtUpdateToConstitution,
      t.dvtHardForkInitiation,
      t.dvtPPNetworkGroup,
      t.dvtPPEconomicGroup,
      t.dvtPPTechnicalGroup,
      t.dvtPPGovGroup,
      t.dvtTreasuryWithdrawal
    ).foldMap(unitInterval.encCBOR)

  /** `MaxPledgeLeverage` (`Core/PParams.hs:375-376`, cardano-ledger 9df9f98c): `null` or the
    * `NonNegativeInterval`.
    */
  given maxPledgeLeverage: EncCBOR[MaxPledgeLeverage] = m =>
    encodeNullStrictMaybe(nonNegativeInterval.encCBOR)(m.unMaxPledgeLeverage)

  /** `Milliseconds32`'s, its `Word32` (`BaseTypes.hs:706-708`, cardano-ledger 9df9f98c). */
  given milliseconds32: EncCBOR[Milliseconds32] = m => encodeWord(m.unMilliseconds32)

  /** `SlotInterval`'s, its `Word32` (cardano-slotting). */
  given slotInterval: EncCBOR[SlotInterval] = s => encodeWord(s.unSlotInterval)

object DecCBOR:
  private type Result[A] = Either[LedgerDecodeError, A]

  def apply[A](using d: DecCBOR[A]): DecCBOR[A] = d

  private def from12(using at: DecodeAt): Boolean = at.version.atLeast(12)

  /** What the reader of a record's field meets after the last item of the record's list. */
  private[era] enum RecordEnd:
    /** An indefinite list's break, which the reader of every field refuses. */
    case Break

    /** After a definite list, the enclosing item's next token, which a decoded item does not hold.
      */
    case Beyond

  /** The items of a record its fields have not read yet, the record's length and site, which a
    * refusal of its length names, and what follows its last item.
    */
  private[era] final case class RecordItems(
      rest: List[Node],
      size: Int,
      site: DecodeSite,
      actual: Int,
      end: RecordEnd
  )

  /** A reader of a record's fields, item by item, as the decoder that `decodeRecordNamed` runs
    * reads them from the stream.
    */
  private[era] type RecordRead[A] = StateT[Result, RecordItems, A]

  /** The next field of a record, read by `read`. The reference reads a field the list lacks from
    * the token after the list's last item: an indefinite list's break, which the field's reader
    * refuses ([[PParamsDecodeError.Break]], the field at its index), or, after a definite list, the
    * enclosing item's next token, which a decoded item does not hold, so the record's length is
    * refused at the first field it lacks.
    */
  private[era] def field[A](read: Node => Result[A]): RecordRead[A] =
    StateT(items =>
      items.rest match
        case x :: rest => read(x).map(a => (items.copy(rest = rest), a))
        case Nil =>
          items.end match
            case RecordEnd.Break =>
              Left(
                LedgerDecodeError.PParamsCodec(PParamsDecodeError.Break(items.site, items.actual))
              )
            case RecordEnd.Beyond =>
              Left(LedgerDecodeError.ArrayLength(items.site, items.size, items.actual))
    )

  /** `decodeRecordNamed name (const size)` (`decodeListLikeT`, `Decoder.hs:645-659, 682-700`,
    * cardano-ledger 9df9f98c, and f649f975 `:654-668, 691-709`): the list's length read, definite
    * or indefinite, then its fields by `fields`, then the length checked, `matchSize` of a definite
    * list or the break of an indefinite one ("Excess terms in array"). A field's refusal comes
    * before the length's.
    */
  private[era] def recordNamed[A](n: Node, size: Int, site: DecodeSite)(
      fields: RecordRead[A]
  ): Result[A] =
    CborRead
      .array(n, site)
      .flatMap(xs =>
        fields
          .runA(
            RecordItems(
              xs.toList,
              size,
              site,
              xs.size,
              if n.isIndefinite then RecordEnd.Break else RecordEnd.Beyond
            )
          )
          .flatTap(_ =>
            Either.cond(xs.sizeIs == size, (), LedgerDecodeError.ArrayLength(site, size, xs.size))
          )
      )

  /** `decodeWord16`, `decodeWord32`, `decodeWord64` (cborg): an unsigned integer of the width, read
    * as the node's decoders of an update read it below version 12 and from it.
    */
  given unsigned[W <: Unsigned.Width](using b: Unsigned.Bound[W]): DecCBOR[Unsigned[W]] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[Unsigned[W]] =
      if from12 then
        V12
          .word(n, at.site, b.max)
          .flatMap(v =>
            Unsigned.from[W](v).toRight(LedgerDecodeError.NumberOutOfRange(at.site, v, 0, b.max))
          )
      else Unsigned.read[W](n, at.site)

  /** `CompactForm Coin`, a `Word64` (`Coin.hs:193-194`, cardano-ledger 9df9f98c). */
  given compactCoin: DecCBOR[CompactCoin] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[CompactCoin] =
      unsigned[W64].decCBOR(n).map(u => CompactCoin.fromUnsigned(u.value.toLong))

  given coinPerByte: DecCBOR[CoinPerByte] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[CoinPerByte] =
      compactCoin.decCBOR(n).map(CoinPerByte(_))

  /** `Coin`, a `Word64` (`Coin.hs:111-113`, cardano-ledger 9df9f98c). */
  given coin: DecCBOR[Coin] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[Coin] =
      unsigned[W64].decCBOR(n).map(u => Coin(u.value))

  given epochInterval: DecCBOR[EpochInterval] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[EpochInterval] =
      unsigned[W32].decCBOR(n).map(EpochInterval.fromWord32)

  private def outOfBounds(r: xyz.kd5ujc.scarab.codec.Rational)(using at: DecodeAt) =
    LedgerDecodeError.RationalOutOfBounds(at.site, LedgerDecodeError.OutOfBounds.Reduced(r))

  /** The items of tag 30's list below version 12, each read by `decodeInteger` before the list's
    * length is checked: `decodeRationalWithTag` reads them with `decodeCollectionWithLen`, then
    * matches two (`Binary/Plain.hs:159-168`, cardano-ledger 9df9f98c and f649f975), so an item's
    * refusal comes before the length's. What is not tag 30 is the shared reader's to refuse.
    */
  private def ratioItems(n: Node)(using at: DecodeAt): Result[Unit] = n.value match
    case Value.Tag(t, inner) if t == 30 =>
      CborRead.array(inner, at.site).flatMap(_.traverse_(CborRead.integer(_, at.site)))
    case _ => Right(())

  /** `decCBOR` of `BoundedRatio b Word64` (`BaseTypes.hs:407-428`, cardano-ledger 9df9f98c):
    * `decodeRationalWithTag` below version 12 ([[ratioItems]], then [[CborRead.rational]]),
    * `decodeIntegralRational` from it ([[V12.ratio]]), then `boundRational` into the type's
    * interval.
    */
  private def bounded[A](interval: V12.Interval, below12: CborRead.Bound)(
      bound: xyz.kd5ujc.scarab.codec.Rational => Option[A]
  ): DecCBOR[A] = new DecCBOR[A]:
    def decCBOR(n: Node)(using at: DecodeAt): Result[A] =
      val r =
        if from12 then V12.ratio(n, at.site, interval)
        else ratioItems(n) >> CborRead.rational(n, at.site, below12)
      r.flatMap(x => bound(x).toRight(outOfBounds(x)))

  given unitInterval: DecCBOR[UnitInterval] =
    bounded(V12.Interval.Unit, CborRead.Bound.Unit)(UnitInterval.boundRational)

  given nonNegativeInterval: DecCBOR[NonNegativeInterval] =
    bounded(V12.Interval.NonNegative, CborRead.Bound.NonNegative)(NonNegativeInterval.boundRational)

  /** `PositiveInterval`: below version 12 the tag-30 ratio of `Word64` s ([[ratioItems]], then
    * [[CborRead.ratio]]), then `boundRational`.
    */
  given positiveInterval: DecCBOR[PositiveInterval] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[PositiveInterval] =
      val r =
        if from12 then V12.ratio(n, at.site, V12.Interval.Positive)
        else ratioItems(n) >> CborRead.ratio(n, at.site)
      r.flatMap(x => PositiveInterval.boundRational(x).toRight(outOfBounds(x)))

  private given HasZero[Unsigned[W32]] = HasZero.instance(_.value.signum == 0)

  /** `NonZero Word32` (`BaseTypes/NonZero.hs:102-103`, cardano-ledger 9df9f98c): a `Word32` and
    * `nonZeroM`, refusing zero.
    */
  given nonZeroWord32: DecCBOR[NonZero[Unsigned[W32]]] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[NonZero[Unsigned[W32]]] =
      unsigned[W32]
        .decCBOR(n)
        .flatMap(w => NonZero.from(w).leftMap(_ => LedgerDecodeError.Zero(at.site)))

  /** `decodeStrictMaybe` (`decodeMaybeVarLen`, `Decoder.hs:585-601, 618-619`, cardano-ledger
    * 9df9f98c): a definite or indefinite list of no item or one. A definite list's length is
    * refused before its item is read; an indefinite one's item is read, then its break.
    */
  given strictMaybe[A](using d: DecCBOR[A]): DecCBOR[Option[A]] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[Option[A]] =
      CborRead.array(n, at.site).flatMap {
        case Vector() => Right(None)
        case x +: rest if n.isIndefinite =>
          d.decCBOR(x)
            .flatMap(a =>
              Either
                .cond(rest.isEmpty, Some(a), LedgerDecodeError.AtMostOne(at.site, rest.size + 1))
            )
        case Vector(x) => d.decCBOR(x).map(Some(_))
        case xs        => Left(LedgerDecodeError.AtMostOne(at.site, xs.size))
      }

  /** `decodeNullStrictMaybe` (`Decoder.hs:626-633`, cardano-ledger 9df9f98c): `null`, or the item.
    */
  def nullStrictMaybe[A](d: DecCBOR[A]): DecCBOR[Option[A]] = new DecCBOR[Option[A]]:
    def decCBOR(n: Node)(using at: DecodeAt): Result[Option[A]] = n.value match
      case Value.Null => Right(None)
      case _          => d.decCBOR(n).map(Some(_))

  /** `ProtVer` via `CBORGroup` (`BaseTypes.hs:212-245`, `Binary/Group.hs:33-35`, cardano-ledger
    * 9df9f98c): `decodeRecordNamed` of two, the major `decodeVersion` (a `Word32` in `mkVersion32`
    * 's bounds, `Version.hs:111-124`) and the minor a `Word32`.
    */
  given protVer: DecCBOR[ProtVer] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[ProtVer] =
      recordNamed(n, 2, DecodeField.ProtocolVersion)(
        (
          field(x =>
            unsigned[W32]
              .decCBOR(x)(using at.at(DecodeField.Major))
              .flatMap(major =>
                Version
                  .mkVersion(major.value)
                  .toRight(
                    LedgerDecodeError
                      .NumberOutOfRange(DecodeField.Major, major.value, 0, Version.Max)
                  )
              )
          ),
          field(unsigned[W32].decCBOR(_)(using at.at(DecodeField.Minor)))
        ).mapN(ProtVer(_, _))
      )

  private val Int64Max = BigInt(Long.MaxValue)

  /** `decNat` of `ExUnits` (`Plutus/ExUnits.hs:217-225`, cardano-ledger 9df9f98c): a `Word64` of at
    * most `maxBound :: Int64`.
    */
  private def exUnitsNat(n: Node): Result[BigInt] =
    Unsigned
      .read[W64](n, DecodeField.ExUnits)
      .flatMap(w =>
        Either.cond(
          w.value <= Int64Max,
          w.value,
          LedgerDecodeError.NumberOutOfRange(DecodeField.ExUnits, w.value, 0, Int64Max)
        )
      )

  /** `ExUnits` (`Plutus/ExUnits.hs:208-230`, cardano-ledger 9df9f98c): a record of two `Word64` s
    * of at most `maxBound :: Int64`, below version 12 a `RecD` ([[ConwayParam.exUnits]]), whose
    * length is checked before its fields, and from it `decodeRecordNamed`, whose fields are read
    * first.
    */
  given exUnits: DecCBOR[ExUnits] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[ExUnits] =
      val units =
        if from12 then
          recordNamed(n, 2, DecodeField.ExUnits)(
            (field(exUnitsNat), field(exUnitsNat)).mapN(xyz.kd5ujc.scarab.plutus.ExUnits(_, _))
          )
        else ConwayParam.exUnits(n, DecodeField.ExUnits, ConwayParam.Records.at(at.version))
      units
        .flatMap(u =>
          ExUnits
            .from(u)
            .toRight(
              LedgerDecodeError
                .NumberOutOfRange(DecodeField.ExUnits, u.mem.min(u.steps), 0, BigInt(Long.MaxValue))
            )
        )

  given ordExUnits: DecCBOR[OrdExUnits] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[OrdExUnits] =
      exUnits.decCBOR(n).map(OrdExUnits(_))

  /** `Prices` (`ExUnits.hs:235-243`, cardano-ledger 9df9f98c): a record of two
    * `NonNegativeInterval` s, a `RecD` below version 12, whose length is checked before its fields,
    * and `decodeRecordNamed` from it, whose fields are read first.
    */
  given prices: DecCBOR[Prices] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[Prices] =
      val mem: Node => Result[NonNegativeInterval] =
        nonNegativeInterval.decCBOR(_)(using at.at(DecodeField.PriceMem))
      val steps: Node => Result[NonNegativeInterval] =
        nonNegativeInterval.decCBOR(_)(using at.at(DecodeField.PriceSteps))
      if from12 then
        recordNamed(n, 2, DecodeField.Prices)((field(mem), field(steps)).mapN(Prices(_, _)))
      else
        ConwayParam.Records
          .at(at.version)
          .record(n, 2, DecodeField.Prices)
          .flatMap(xs => (mem(xs(0)), steps(xs(1))).mapN(Prices(_, _)))

  /** `decodeCostModels` (`CostModels.hs:302-316, 426-440`, cardano-ledger 9df9f98c): below version
    * 12 by its rule of the version ([[CostModelDecode]]); from it a map of `Word8` languages, none
    * twice, to `decodeList` of `Int64` (the node's decoder of a Dijkstra update). Each parameter is
    * an `Int64` and each language a `Word8`, so [[CostModels.from]] takes what either read.
    */
  given costModels: DecCBOR[CostModels] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[CostModels] =
      val models =
        if from12 then
          V12
            .map(n, DecodeField.CostModels)(V12.word8(_, DecodeField.CostModelLanguage))((_, v) =>
              V12
                .list(v, DecodeField.CostModel)
                .flatMap(_.traverse(V12.int64(_, DecodeField.Cost).map(BigInt(_))))
            )
            .map(_.toMap)
        else CostModelDecode.decode(n, CostModelDecode.Format.of(at.version))
      models.flatMap(m =>
        CostModels.from(m).leftMap {
          case PParamsError.CostModelOutOfRange(tag, value) if tag < 0 || tag > 0xff =>
            LedgerDecodeError.NumberOutOfRange(DecodeField.CostModelLanguage, value, 0, 0xff)
          case PParamsError.CostModelOutOfRange(_, value) =>
            LedgerDecodeError.NumberOutOfRange(
              DecodeField.Cost,
              value,
              BigInt(Long.MinValue),
              BigInt(Long.MaxValue)
            )
          case _ =>
            LedgerDecodeError.Expected(DecodeField.CostModels, LedgerDecodeError.Shape.Map, n.value)
        }
      )

  /** `decodeRecordNamed` of `size` `UnitInterval` s, at `site`, at every version. */
  private def thresholds(n: Node, size: Int, site: DecodeField)(using
      at: DecodeAt
  ): Result[Vector[UnitInterval]] =
    recordNamed(n, size, site)(
      Vector.fill(size)(field(unitInterval.decCBOR(_)(using at.at(site)))).sequence
    )

  /** `PoolVotingThresholds` (`Conway/PParams.hs:367-375`, cardano-ledger 9df9f98c). */
  given poolVotingThresholds: DecCBOR[PoolVotingThresholds] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[PoolVotingThresholds] =
      thresholds(n, 5, DecodeField.PoolVotingThresholds).map(t =>
        PoolVotingThresholds(t(0), t(1), t(2), t(3), t(4))
      )

  /** `DRepVotingThresholds` (`Conway/PParams.hs:520-533`, cardano-ledger 9df9f98c). */
  given drepVotingThresholds: DecCBOR[DRepVotingThresholds] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[DRepVotingThresholds] =
      thresholds(n, 10, DecodeField.DRepVotingThresholds).map(t =>
        DRepVotingThresholds(t(0), t(1), t(2), t(3), t(4), t(5), t(6), t(7), t(8), t(9))
      )

  /** `MaxPledgeLeverage` (`Core/PParams.hs:378-379`, cardano-ledger 9df9f98c). */
  given maxPledgeLeverage: DecCBOR[MaxPledgeLeverage] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[MaxPledgeLeverage] =
      nullStrictMaybe(nonNegativeInterval).decCBOR(n).map(MaxPledgeLeverage(_))

  given milliseconds32: DecCBOR[Milliseconds32] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[Milliseconds32] =
      unsigned[W32].decCBOR(n).map(Milliseconds32(_))

  given slotInterval: DecCBOR[SlotInterval] with
    def decCBOR(n: Node)(using at: DecodeAt): Result[SlotInterval] =
      unsigned[W32].decCBOR(n).map(SlotInterval(_))
