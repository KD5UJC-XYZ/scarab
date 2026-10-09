// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.util.control.NonFatal
import ConwayEpochBoundary as B
import ConwayStake as S

/** Exact PV9 reward-start allocation only. Scoped supplied input codec, not native PParams CBOR. */
object ConwayRewardStart:
  val Profile = "conway-pv9-reward-start-allocation-v1"
  val ParameterFormat = "conway-pv9-reward-start-parameters-v1"
  val GlobalFormat = "conway-pv9-reward-start-globals-v1"
  val PulserGlobalFormat = "conway-pv9-reward-pulser-globals-v1"
  val PoolParameterFormat = "conway-pv9-reward-pool-parameters-v1"
  private val Max = (BigInt(1) << 64) - 1
  final class Parameters private[ConwayRewardStart] (
      val original: Bytes,
      val rho: S.Ratio,
      val tau: S.Ratio,
      val id: Bytes,
      val pool: Option[PoolParameters] = None
  ):
    val decentralization = S.Ratio(0, 1) // Conway's ppDG getter is constant minBound.
  final class PoolParameters private[ConwayRewardStart] (
      val a0: S.Ratio,
      val nOpt: Int
  )
  final class Globals private[ConwayRewardStart] (
      val original: Bytes,
      val epochLength: BigInt,
      val activeSlotCoefficient: S.Ratio,
      val maxSupply: BigInt,
      val id: Bytes,
      val securityParameter: Option[BigInt] = None
  )
  final class Fraction private[ConwayRewardStart] (val numerator: BigInt, val denominator: BigInt)
  final class Allocation private[ConwayRewardStart] (
      val frozenId: Bytes,
      val expectedBlocks: BigInt,
      val blocksMade: BigInt,
      val performance: Fraction,
      val cappedPerformance: Fraction,
      val deltaR1: BigInt,
      val grossPot: BigInt,
      val treasuryDelta: BigInt,
      val rewardPot: BigInt,
      val completionInputs: ConwayRewardCompletion.Inputs,
      val id: Bytes
  ):
    val nativeSeedAdmitted = false
    val entitlementCalculated = false
    val pulserExecuted = false
    val published = false
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def hash(text: String) =
    Blake2b.hash256.hash(Bytes.fromArray((Profile + "\n" + text).getBytes("UTF-8")))
  private def uint(n: Node): BigInt = n.value match
    case V.UInt(v) if v >= 0 && v <= Max => v
    case _ => throw new IllegalArgumentException("bounded unsigned field required")
  private def fields(raw: Bytes, format: String, count: Int): Vector[Node] =
    require(
      raw != null && raw.value != null && raw.size > 0 && raw.size <= 1024,
      "bounded reward-start input required"
    )
    val root = get(Cbor.decode(raw, Cbor.Limits(1024, 4, 32, 128)))
    require(get(Cbor.encode(root.value)) == raw, "canonical reward-start input required")
    root.value match
      case V.Arr(xs) if xs.size == count && xs.head.value == V.Text(format) => xs.tail
      case _ => throw new IllegalArgumentException("reward-start input schema/profile")
  private def ratio(n: Node, d: Node, positive: Boolean = false): S.Ratio =
    val nn = uint(n); val dd = uint(d)
    require(
      dd > 0 && nn <= dd && nn.gcd(dd) == 1 && (!positive || nn > 0),
      "canonical unit rational required"
    )
    S.Ratio(nn, dd)
  def decodeParameters(raw: Bytes): Either[String, Parameters] = checked {
    val xs = fields(raw, ParameterFormat, 7)
    require(uint(xs(0)) == 9 && uint(xs(1)) == 0, "previous PV9.0 parameters required")
    new Parameters(raw, ratio(xs(2), xs(3)), ratio(xs(4), xs(5)), hash("parameters:" + raw.hex))
  }

  /** Full scoped reward projection; this is not a native previous-PParams decoder. */
  def decodePoolParameters(raw: Bytes): Either[String, Parameters] = checked {
    val xs = fields(raw, PoolParameterFormat, 10)
    require(uint(xs(0)) == 9 && uint(xs(1)) == 0, "previous PV9.0 parameters required")
    val a = uint(xs(6)); val d = uint(xs(7)); val k = uint(xs(8))
    require(d > 0 && a.gcd(d) == 1, "canonical nonnegative a0 rational required")
    require(k > 0 && k <= 65535, "positive Word16 nOpt required")
    new Parameters(
      raw,
      ratio(xs(2), xs(3)),
      ratio(xs(4), xs(5)),
      hash("parameters:" + raw.hex),
      Some(new PoolParameters(S.Ratio(a, d), k.toInt))
    )
  }
  def decodeGlobals(raw: Bytes): Either[String, Globals] = checked {
    val xs = fields(raw, GlobalFormat, 5)
    val epochLength = uint(xs(0)); val asc = ratio(xs(1), xs(2), true); val maxSupply = uint(xs(3))
    require(epochLength > 0 && maxSupply > 0, "positive epoch length/supply required")
    new Globals(raw, epochLength, asc, maxSupply, hash("globals:" + raw.hex))
  }

  /** Scoped globals including positive uint64 security parameter, bound before freezing. */
  def decodePulserGlobals(raw: Bytes): Either[String, Globals] = checked {
    val xs = fields(raw, PulserGlobalFormat, 6)
    val epochLength = uint(xs(0)); val asc = ratio(xs(1), xs(2), true)
    val maxSupply = uint(xs(3)); val k = uint(xs(4))
    require(
      epochLength > 0 && maxSupply > 0 && k > 0,
      "positive epoch length/supply/security parameter required"
    )
    new Globals(raw, epochLength, asc, maxSupply, hash("globals:" + raw.hex), Some(k))
  }
  private def fraction(n: BigInt, d: BigInt): Fraction =
    val gcd = n.gcd(d); new Fraction(n / gcd, d / gcd)

  /** No loose parameter/global overrides: both checked byte projections must be in Frozen. */
  def calculate(frozen: B.Frozen, expectedFrozenId: Bytes): Either[String, Allocation] = checked {
    require(
      frozen != null && expectedFrozenId != null && frozen.id == expectedFrozenId,
      "frozen allocation identity mismatch"
    )
    val parameters = frozen.rewardParameters.getOrElse(
      throw new IllegalArgumentException("checked frozen reward parameters required")
    )
    val globals = frozen.rewardGlobals.getOrElse(
      throw new IllegalArgumentException("checked frozen globals required")
    )
    require(
      parameters.original == frozen.previousParameters && globals.maxSupply == frozen.maxSupply &&
        globals.epochLength == frozen.epochLength,
      "frozen allocation binding mismatch"
    )
    val asc = globals.activeSlotCoefficient
    val expected = (globals.epochLength * asc.numerator) / asc.denominator // Conway d=0.
    require(
      expected > 0 && expected <= Max,
      "expected blocks denominator must be positive and bounded"
    )
    val produced = frozen.previousBlocks.values.sum
    require(produced >= 0 && produced <= Max, "produced block bound")
    val eta = fraction(produced, expected)
    val capped = fraction(produced.min(expected), expected)
    val reserve = (capped.numerator * parameters.rho.numerator * frozen.reserves) /
      (capped.denominator * parameters.rho.denominator)
    val gross = frozen.snapshotFees + reserve
    require(gross >= 0 && gross <= Max, "gross reward pot overflow")
    val treasury = (gross * parameters.tau.numerator) / parameters.tau.denominator
    val available = gross - treasury
    val completion = get(
      ConwayRewardCompletion.checkedInputs(
        frozen,
        frozen.id,
        frozen.snapshotFees,
        reserve,
        available,
        treasury
      )
    )
    new Allocation(
      frozen.id,
      expected,
      produced,
      eta,
      capped,
      reserve,
      gross,
      treasury,
      available,
      completion,
      hash(
        s"allocation:${frozen.id.hex}:$expected:$produced:${eta.numerator}/${eta.denominator}:$reserve:$gross:$treasury:$available"
      )
    )
  }
