// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.{DecodeField, DecodeSite, LedgerDecodeError}
import cats.syntax.all.*
import io.circe.ACursor
import xyz.kd5ujc.scarab.codec.{Node, Value}
import xyz.kd5ujc.scarab.ledger.CborRead

import ByronInteger.decode as integer

/** Byron's `ProtocolVersion`: `Word16`, `Word16`, `Word8`, ordered lexicographically.
  * `Cardano/Chain/Update/ProtocolVersion.hs:27-32,65-68` (ledger f649f97), `byron.cddl:126`.
  * Construction checks the bounds before narrowing to `Int`.
  */
opaque type ByronVersion = (Int, Int, Int)

object ByronVersion:
  enum Field(val max: Int):
    case Major extends Field(0xffff)
    case Minor extends Field(0xffff)
    case Alt extends Field(0xff)

  enum Error:
    case ExpectedTriple
    case ExpectedUnsigned(field: Field)
    case OutOfRange(field: Field, value: BigInt)

    def message: String = this match
      case ExpectedTriple      => "Byron protocol version: expected a three-element array"
      case ExpectedUnsigned(f) => s"Byron protocol version $f: expected an unsigned integer"
      case OutOfRange(f, v)    => s"Byron protocol version $f: $v is outside 0..${f.max}"

  val zero: ByronVersion = (0, 0, 0)
  given Ordering[ByronVersion] = Ordering.Tuple3[Int, Int, Int]

  extension (v: ByronVersion)
    def major: Int = v._1
    def minor: Int = v._2
    def alt: Int = v._3
    def render: String = s"${v.major}.${v.minor}.${v.alt}"

  private def component(field: Field, n: BigInt): Either[Error.OutOfRange, Int] =
    if n >= 0 && n <= field.max then Right(n.toInt)
    else Left(Error.OutOfRange(field, n))

  def from(major: BigInt, minor: BigInt, alt: BigInt): Either[Error.OutOfRange, ByronVersion] =
    (component(Field.Major, major), component(Field.Minor, minor), component(Field.Alt, alt)).tupled

  def decode(n: Node): Either[Error, ByronVersion] =
    def uint(field: Field, n: Node): Either[Error, Int] = n.value match
      case Value.UInt(i) => component(field, i)
      case _             => Left(Error.ExpectedUnsigned(field))
    n.value match
      case Value.Arr(Vector(major, minor, alt)) =>
        (uint(Field.Major, major), uint(Field.Minor, minor), uint(Field.Alt, alt)).tupled
      case _ => Left(Error.ExpectedTriple)

/** The soft-fork rule: the share of stake that must endorse a version (parts per 10^15). */
final case class SoftforkRule(initThd: BigInt, minThd: BigInt, thdDecrement: BigInt)

/** Byron's linear fee, stored in Nano for compatibility with native snapshot fields. */
final case class TxFeePolicy(summandNano: BigInt, multiplierNano: BigInt):
  /** Common/TxSizeLinear.hs:82–88 and Lovelace.hs:216–221,246–248: scale with signed ceiling,
    * convert to Word64, then checked addition. The intermediate conversion deliberately wraps.
    */
  def minimumFee(size: BigInt): Either[TxFeePolicy.Error, BigInt] =
    import TxFeePolicy.*
    val a = roundedSummand(summandNano)
    val scaled = multiplierNano * size
    val ceiling = scaled / Nano + (if scaled % Nano > 0 then 1 else 0)
    val b = ceiling.mod(Word64Modulus)
    val result = (a + b).mod(Word64Modulus)
    if size < 0 then Left(Error.SizeTooSmall(size))
    else if size > ByronLedger.MaxLovelace then Left(Error.SizeTooLarge(size))
    else if a > ByronLedger.MaxLovelace then Left(Error.SummandOutOfRange(a))
    else if result < a || result < b || result > ByronLedger.MaxLovelace then
      Left(Error.Overflow(result))
    else Right(result)

object TxFeePolicy:
  private[byron] val Nano: BigInt = BigInt(1000000000)
  private val Word64Modulus = BigInt(1) << 64

  enum Error:
    case SizeTooSmall(value: BigInt)
    case SizeTooLarge(value: BigInt)
    case SummandOutOfRange(value: BigInt)
    case Overflow(value: BigInt)

    def message: String = this match
      case SizeTooSmall(n)      => s"negative transaction size $n"
      case SizeTooLarge(n)      => s"transaction size $n exceeds lovelace bounds"
      case SummandOutOfRange(n) => s"fee summand $n exceeds lovelace bounds"
      case Overflow(n)          => s"linear fee addition overflowed to $n"

  private[byron] def roundedSummand(value: BigInt): BigInt =
    val quotient = value / Nano
    val remainder = (value % Nano).abs * 2
    val rounded = quotient + (if remainder > Nano || (remainder == Nano && quotient % 2 != 0) then
                                BigInt(value.signum)
                              else BigInt(0))
    rounded.mod(Word64Modulus)

  /** The native decoder rounds Nano to Word64 before mkLovelace; preserve that value in Nano. */
  private[byron] def fromNano(a: BigInt, b: BigInt): Either[LedgerDecodeError, TxFeePolicy] =
    val rounded = roundedSummand(a)
    Either.cond(
      rounded <= ByronLedger.MaxLovelace,
      TxFeePolicy(rounded * Nano, b),
      LedgerDecodeError.NumberOutOfRange(
        DecodeField.FeeSummand,
        rounded,
        0,
        ByronLedger.MaxLovelace
      )
    )

/** The Byron protocol parameters (`blockVersionData` of the genesis file). */
final case class ByronParams(
    scriptVersion: BigInt,
    slotDurationMs: BigInt,
    maxBlockSize: BigInt,
    maxHeaderSize: BigInt,
    maxTxSize: BigInt,
    maxProposalSize: BigInt,
    mpcThd: BigInt,
    heavyDelThd: BigInt,
    updateVoteThd: BigInt,
    updateProposalThd: BigInt,
    updateProposalTtl: BigInt,
    softforkRule: SoftforkRule,
    txFeePolicy: TxFeePolicy,
    unlockStakeEpoch: BigInt
):
  /** `upAdptThd`: how many genesis keys must vote for or endorse an update. */
  def adoptionThreshold(genesisKeys: Int): Int =
    (softforkRule.minThd * genesisKeys / BigInt(10).pow(15)).toInt

/** A protocol parameter update proposal: every field optional. */
final case class ByronParamsUpdate(
    scriptVersion: Option[BigInt],
    slotDurationMs: Option[BigInt],
    maxBlockSize: Option[BigInt],
    maxHeaderSize: Option[BigInt],
    maxTxSize: Option[BigInt],
    maxProposalSize: Option[BigInt],
    mpcThd: Option[BigInt],
    heavyDelThd: Option[BigInt],
    updateVoteThd: Option[BigInt],
    updateProposalThd: Option[BigInt],
    updateProposalTtl: Option[BigInt],
    softforkRule: Option[SoftforkRule],
    txFeePolicy: Option[TxFeePolicy],
    unlockStakeEpoch: Option[BigInt]
):
  def apply(p: ByronParams): ByronParams =
    ByronParams(
      scriptVersion.getOrElse(p.scriptVersion),
      slotDurationMs.getOrElse(p.slotDurationMs),
      maxBlockSize.getOrElse(p.maxBlockSize),
      maxHeaderSize.getOrElse(p.maxHeaderSize),
      maxTxSize.getOrElse(p.maxTxSize),
      maxProposalSize.getOrElse(p.maxProposalSize),
      mpcThd.getOrElse(p.mpcThd),
      heavyDelThd.getOrElse(p.heavyDelThd),
      updateVoteThd.getOrElse(p.updateVoteThd),
      updateProposalThd.getOrElse(p.updateProposalThd),
      updateProposalTtl.getOrElse(p.updateProposalTtl),
      softforkRule.getOrElse(p.softforkRule),
      txFeePolicy.getOrElse(p.txFeePolicy),
      unlockStakeEpoch.getOrElse(p.unlockStakeEpoch)
    )

object ByronParamsUpdate:

  /** A `Maybe` is a list of zero or one element. */
  private def maybe[A](n: Node, what: DecodeSite)(
      f: Node => Either[LedgerDecodeError, A]
  ): Either[LedgerDecodeError, Option[A]] =
    CborRead.array(n, what).flatMap {
      case Vector()  => Right(None)
      case Vector(x) => f(x).map(Some(_))
      case xs        => Left(LedgerDecodeError.AtMostOne(what, xs.size))
    }

  private def uint(what: DecodeSite)(n: Node) = CborRead.uint(n, what)

  private def natural(what: DecodeSite)(n: Node): Either[LedgerDecodeError, BigInt] =
    integer(n, what).flatMap(v =>
      Either.cond(
        v >= 0,
        v,
        LedgerDecodeError.Expected(what, LedgerDecodeError.Shape.Unsigned, n.value)
      )
    )

  private def softfork(n: Node): Either[LedgerDecodeError, SoftforkRule] =
    CborRead.arrayN(n, 3, DecodeField.SoftforkRule).flatMap { xs =>
      (
        CborRead.uint(xs(0), DecodeField.InitThd),
        CborRead.uint(xs(1), DecodeField.MinThd),
        CborRead.uint(xs(2), DecodeField.ThdDecrement)
      ).mapN(SoftforkRule(_, _, _))
    }

  /** `[0, tag24(bytes([summand, multiplier]))]`. */
  private def feePolicy(n: Node): Either[LedgerDecodeError, TxFeePolicy] =
    for
      xs <- CborRead.arrayN(n, 2, DecodeField.TxFeePolicy)
      tag <- CborRead.uint(xs(0), DecodeField.TxFeePolicyTag)
      _ <- Either.cond(tag == 0, (), LedgerDecodeError.UnknownTag(DecodeField.TxFeePolicy, tag))
      inner <- xs(1).value match
        case Value.Tag(t, i) if t == 24 =>
          CborRead
            .bytes(i, DecodeField.TxFeePolicy)
            .flatMap(CborRead.decode(_, ByronCbor.BodyLimits))
        case _ => Left(LedgerDecodeError.ExpectedTag(DecodeField.TxFeePolicy, 24, xs(1).value))
      ys <- CborRead.arrayN(inner, 2, DecodeField.TxSizeLinear)
      a <- integer(ys(0), DecodeField.Summand)
      b <- integer(ys(1), DecodeField.Multiplier)
      policy <- TxFeePolicy.fromNano(a, b)
    yield policy

  def decode(n: Node): Either[LedgerDecodeError, ByronParamsUpdate] =
    (ByronTransport.parameters(n) *> CborRead.arrayN(n, 14, DecodeField.ProtocolParametersUpdate))
      .flatMap { x =>
        (
          maybe(x(0), DecodeField.ScriptVersion)(uint(DecodeField.ScriptVersion)),
          maybe(x(1), DecodeField.SlotDuration)(natural(DecodeField.SlotDuration)),
          maybe(x(2), DecodeField.MaxBlockSize)(natural(DecodeField.MaxBlockSize)),
          maybe(x(3), DecodeField.MaxHeaderSize)(natural(DecodeField.MaxHeaderSize)),
          maybe(x(4), DecodeField.MaxTxSize)(natural(DecodeField.MaxTxSize)),
          maybe(x(5), DecodeField.MaxProposalSize)(natural(DecodeField.MaxProposalSize)),
          maybe(x(6), DecodeField.MpcThd)(uint(DecodeField.MpcThd)),
          maybe(x(7), DecodeField.HeavyDelThd)(uint(DecodeField.HeavyDelThd)),
          maybe(x(8), DecodeField.UpdateVoteThd)(uint(DecodeField.UpdateVoteThd)),
          maybe(x(9), DecodeField.UpdateProposalThd)(uint(DecodeField.UpdateProposalThd)),
          maybe(x(10), DecodeField.UpdateProposalTTL)(uint(DecodeField.UpdateProposalTTL)),
          maybe(x(11), DecodeField.SoftforkRuleParam)(softfork),
          maybe(x(12), DecodeField.TxFeePolicyParam)(feePolicy),
          maybe(x(13), DecodeField.UnlockStakeEpoch)(uint(DecodeField.UnlockStakeEpoch))
        ).mapN(ByronParamsUpdate.apply)
      }

object ByronParams:
  /** `blockVersionData` of the Byron genesis file; numbers are decimal strings (or numbers). */
  def fromGenesis(c: ACursor): Either[LedgerDecodeError, ByronParams] =
    def num(name: DecodeField, from: ACursor = c): Either[LedgerDecodeError, BigInt] =
      from
        .downField(name.label)
        .focus
        .toRight(LedgerDecodeError.GenesisMissingField(name))
        .flatMap { j =>
          j.asString
            .flatMap(s => Either.catchNonFatal(BigInt(s)).toOption)
            .orElse(j.asNumber.flatMap(_.toBigInt))
            .toRight(LedgerDecodeError.GenesisExpectedNumber(name))
        }
    val soft = c.downField(DecodeField.SoftforkRuleParam.label)
    val fee = c.downField(DecodeField.TxFeePolicyParam.label)
    (
      num(DecodeField.ScriptVersion),
      num(DecodeField.SlotDuration),
      num(DecodeField.MaxBlockSize),
      num(DecodeField.MaxHeaderSize),
      num(DecodeField.MaxTxSize),
      num(DecodeField.MaxProposalSize),
      num(DecodeField.MpcThd),
      num(DecodeField.HeavyDelThd),
      num(DecodeField.UpdateVoteThd),
      num(DecodeField.UpdateProposalThd),
      num(DecodeField.UpdateImplicit),
      (
        num(DecodeField.InitThd, soft),
        num(DecodeField.MinThd, soft),
        num(DecodeField.ThdDecrement, soft)
      ).mapN(
        SoftforkRule(_, _, _)
      ),
      (num(DecodeField.Summand, fee), num(DecodeField.Multiplier, fee)).tupled.flatMap { (a, b) =>
        // Common/TxFeePolicy.hs:122–136: genesis JSON uses Word64 div, not the CBOR Nano round.
        Either.cond(
          a >= 0 && a < (BigInt(1) << 64),
          TxFeePolicy((a / TxFeePolicy.Nano) * TxFeePolicy.Nano, b),
          LedgerDecodeError.NumberOutOfRange(
            DecodeField.GenesisFeeSummand,
            a,
            0,
            (BigInt(1) << 64) - 1
          )
        )
      },
      num(DecodeField.UnlockStakeEpoch)
    ).mapN(ByronParams.apply)
