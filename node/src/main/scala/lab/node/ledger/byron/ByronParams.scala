// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger.byron

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.node.ledger.CborRead

/** A Byron block/protocol version `major.minor.alt`, ordered lexicographically. */
final case class ByronVersion(major: BigInt, minor: BigInt, alt: BigInt) derives CanEqual:
  def render: String = s"$major.$minor.$alt"

object ByronVersion:
  val zero: ByronVersion = ByronVersion(0, 0, 0)
  given Ordering[ByronVersion] = Ordering.by(v => (v.major, v.minor, v.alt))

  def decode(n: Node): Either[String, ByronVersion] =
    CborRead.arrayN(n, 3, "Byron protocol version").flatMap { xs =>
      (
        CborRead.uint(xs(0), "major"),
        CborRead.uint(xs(1), "minor"),
        CborRead.uint(xs(2), "alt")
      ).mapN(ByronVersion(_, _, _))
    }

/** The soft-fork rule: the share of stake that must endorse a version (parts per 10^15). */
final case class SoftforkRule(initThd: BigInt, minThd: BigInt, thdDecrement: BigInt)

/** The Byron fee policy `a + b * size`, both in 10^-9 lovelace (`Nano`). */
final case class TxFeePolicy(summandNano: BigInt, multiplierNano: BigInt):
  private val Nano = BigInt(1000000000)

  /** `calculateTxSizeLinear`: the summand in whole lovelace (rounded) plus the size-scaled
    * multiplier rounded up.
    */
  def minimumFee(size: Long): BigInt =
    val a = (summandNano * 2 + Nano) / (Nano * 2)
    val scaled = multiplierNano * size
    a + (scaled + Nano - 1) / Nano

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
  private val limits = Cbor.Limits(4096, 16, 256, 4096)

  /** A `Maybe` is a list of zero or one element. */
  private def maybe[A](n: Node, what: String)(
      f: Node => Either[String, A]
  ): Either[String, Option[A]] =
    CborRead.array(n, what).flatMap {
      case Vector()  => Right(None)
      case Vector(x) => f(x).map(Some(_))
      case _         => Left(s"$what: expected a list of at most one element")
    }

  private def uint(what: String)(n: Node) = CborRead.uint(n, what)

  private def softfork(n: Node): Either[String, SoftforkRule] =
    CborRead.arrayN(n, 3, "softfork rule").flatMap { xs =>
      (
        CborRead.uint(xs(0), "initThd"),
        CborRead.uint(xs(1), "minThd"),
        CborRead.uint(xs(2), "thdDecrement")
      ).mapN(SoftforkRule(_, _, _))
    }

  /** `[0, tag24(bytes([summand, multiplier]))]`. */
  private def feePolicy(n: Node): Either[String, TxFeePolicy] =
    for
      xs <- CborRead.arrayN(n, 2, "tx fee policy")
      tag <- CborRead.uint(xs(0), "tx fee policy tag")
      _ <- Either.cond(tag == 0, (), s"tx fee policy: unknown tag $tag")
      inner <- xs(1).value match
        case Value.Tag(t, i) if t == 24 =>
          CborRead.bytes(i, "tx fee policy").flatMap(Cbor.decode(_, limits))
        case _ => Left("tx fee policy: expected tag 24")
      ys <- CborRead.arrayN(inner, 2, "tx size linear")
      a <- CborRead.uint(ys(0), "summand")
      b <- CborRead.uint(ys(1), "multiplier")
    yield TxFeePolicy(a, b)

  def decode(n: Node): Either[String, ByronParamsUpdate] =
    CborRead.arrayN(n, 14, "protocol parameters update").flatMap { x =>
      (
        maybe(x(0), "scriptVersion")(uint("scriptVersion")),
        maybe(x(1), "slotDuration")(uint("slotDuration")),
        maybe(x(2), "maxBlockSize")(uint("maxBlockSize")),
        maybe(x(3), "maxHeaderSize")(uint("maxHeaderSize")),
        maybe(x(4), "maxTxSize")(uint("maxTxSize")),
        maybe(x(5), "maxProposalSize")(uint("maxProposalSize")),
        maybe(x(6), "mpcThd")(uint("mpcThd")),
        maybe(x(7), "heavyDelThd")(uint("heavyDelThd")),
        maybe(x(8), "updateVoteThd")(uint("updateVoteThd")),
        maybe(x(9), "updateProposalThd")(uint("updateProposalThd")),
        maybe(x(10), "updateProposalTTL")(uint("updateProposalTTL")),
        maybe(x(11), "softforkRule")(softfork),
        maybe(x(12), "txFeePolicy")(feePolicy),
        maybe(x(13), "unlockStakeEpoch")(uint("unlockStakeEpoch"))
      ).mapN(ByronParamsUpdate.apply)
    }

object ByronParams:
  /** `blockVersionData` of the Byron genesis file; numbers are decimal strings (or numbers). */
  def fromGenesis(c: ACursor): Either[String, ByronParams] =
    def num(name: String, from: ACursor = c): Either[String, BigInt] =
      from.downField(name).focus.toRight(s"blockVersionData: missing $name").flatMap { j =>
        j.asString
          .flatMap(s => Either.catchNonFatal(BigInt(s)).toOption)
          .orElse(j.asNumber.flatMap(_.toBigInt))
          .toRight(s"blockVersionData: $name is not a number")
      }
    val soft = c.downField("softforkRule")
    val fee = c.downField("txFeePolicy")
    (
      num("scriptVersion"),
      num("slotDuration"),
      num("maxBlockSize"),
      num("maxHeaderSize"),
      num("maxTxSize"),
      num("maxProposalSize"),
      num("mpcThd"),
      num("heavyDelThd"),
      num("updateVoteThd"),
      num("updateProposalThd"),
      num("updateImplicit"),
      (num("initThd", soft), num("minThd", soft), num("thdDecrement", soft)).mapN(
        SoftforkRule(_, _, _)
      ),
      (num("summand", fee), num("multiplier", fee)).mapN(TxFeePolicy(_, _)),
      num("unlockStakeEpoch")
    ).mapN(ByronParams.apply)
