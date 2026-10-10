// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayRewardStart as R, FeeSize, MinimumOutput}
import scala.util.control.NonFatal

/** Exact PV9 array projection for the finite empty-governance profile. Complete originals are
  * retained; supported encoding and a matching source pin do not imply matching projections. Cost
  * models use the narrow initial V1/V2/V3 lengths, not the native PV9 lenient decoder's complete
  * domain. This does not construct or validate a Plutus evaluation context. No native state or
  * runtime admission.
  */
private[lab] object GovernanceParameterPayload:
  final class Checked private[GovernanceParameterPayload] (
      val payload: G.Payload,
      val decoded: NativeSeedParameters.Parameters,
      val fieldOriginals: Vector[Bytes],
      val costModels: Map[Int, Vector[BigInt]]
  ):
    val original = payload.original
    val sha256 = decoded.sha256
    val rewards = decoded.rewards
    val feePerByte = decoded.feePerByte
    val feeFixed = decoded.feeFixed
    val maxTxSize = decoded.maxTxSize
    val coinsPerUTxOByte = decoded.coinsPerUTxOByte
    val nativeSeedAdmitted = false
    val fullParameterValidity = false

  final class Roles private[GovernanceParameterPayload] (
      val previous: Checked,
      val current: Checked,
      val id: Bytes
  )
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[E, A](v: Either[E, A]): A =
    v.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def width(b: Bytes) = b != null && b.value != null && b.size == 32

  // Pinned core-1.21.0.0 Plutus/CostModels.hs costModelInitParamCount (233-238),
  // flattenCostModels/EncCBOR (446-471); Language.hs Enum ordering (271-277).
  // PV9 is natively lenient (311-316): exact lengths here are a deliberate scope restriction.
  private val CostModelLengths = Map(0 -> 166, 1 -> 175, 2 -> 251)
  private val SignedMax = (BigInt(1) << 63) - 1
  private def costModels(value: V): Map[Int, Vector[BigInt]] = value match
    case V.Map(entries) =>
      require(entries.size <= CostModelLengths.size, "cost-model language count")
      val rows = entries.map { (key, model) =>
        val language = key.value match
          case V.UInt(n) if n >= 0 && n <= 2 => n.toInt
          case _ => throw new IllegalArgumentException("unsupported cost-model language")
        val values = model.value match
          case V.Arr(xs) if xs.size == CostModelLengths(language) =>
            xs.map { x =>
              x.value match
                case V.UInt(n) if n >= 0 && n <= SignedMax     => n
                case V.NInt(n) if n < 0 && n >= -SignedMax - 1 => n
                case _ => throw new IllegalArgumentException("cost-model signed Int64 parameter")
            }
          case _ => throw new IllegalArgumentException("cost-model initial parameter count")
        language -> values
      }
      require(rows.map(_._1).distinct.size == rows.size, "duplicate cost-model language")
      rows.toMap
    case _ => throw new IllegalArgumentException("cost-model map required")

  def decode(original: Bytes, expectedSha256: Bytes): Either[String, Checked] = checked {
    // Preserve native long cost-array envelopes; canonical structure remains required elsewhere.
    val payload = get(G.parameterPayloadWithNativeCostArrays(original))
    require(
      width(expectedSha256) && ClusterHeaderObservation.sha256(original) == expectedSha256,
      "parameter original SHA256 mismatch"
    )
    val decoded = get(NativeSeedParameters.decodeParameters(original))
    val fields = get(Cbor.decode(original)).value match
      case V.Arr(xs) if xs.size == 31 => xs
      case _ => throw new IllegalArgumentException("complete Conway parameter record required")
    val models = costModels(fields(15).value)
    new Checked(payload, decoded, fields.map(_.original), models)
  }

  /** The PV9 version was decoded, not taken from a caller assertion. Comparison uses all fields
    * consumed by the two existing ledger predicates and the reward-start/pool projection.
    */
  def checkProjections(
      value: Checked,
      expectedFees: FeeSize.Parameters,
      expectedMinimum: MinimumOutput.Parameters,
      expectedRewards: R.Parameters
  ): Either[String, Unit] = checked {
    require(
      value != null && expectedFees != null && expectedMinimum != null && expectedRewards != null,
      "parameter projections required"
    )
    require(
      value.feePerByte == expectedFees.feePerByte && value.feeFixed == expectedFees.feeFixed &&
        value.maxTxSize == expectedFees.maxTxSize && value.coinsPerUTxOByte == expectedMinimum.coinsPerUTxOByte,
      "decoded ledger parameter projection mismatch"
    )
    get(checkRewards(value, expectedRewards))
  }

  def checkRewards(value: Checked, expected: R.Parameters): Either[String, Unit] = checked {
    require(
      value != null && expected != null && expected.pool.isDefined,
      "complete reward parameter projection required"
    )
    val actual = value.rewards
    require(
      actual.rho == expected.rho && actual.tau == expected.tau &&
        actual.pool.get.a0 == expected.pool.get.a0 && actual.pool.get.nOpt == expected.pool.get.nOpt,
      "decoded reward parameter projection mismatch"
    )
  }

  /** Explicit temporal roles; whole original hashes remain distinct even for equal projections. */
  def bindRoles(
      previous: Checked,
      current: Checked,
      expectedPreviousSha256: Bytes,
      expectedCurrentSha256: Bytes,
      expectedPreviousRewards: R.Parameters,
      expectedCurrentRewards: R.Parameters,
      currentFees: FeeSize.Parameters,
      currentMinimum: MinimumOutput.Parameters
  ): Either[String, Roles] = checked {
    require(
      previous != null && current != null && width(expectedPreviousSha256) && width(
        expectedCurrentSha256
      ) &&
        previous.sha256 == expectedPreviousSha256 && current.sha256 == expectedCurrentSha256,
      "parameter role source binding mismatch"
    )
    get(checkRewards(previous, expectedPreviousRewards))
    get(checkProjections(current, currentFees, currentMinimum, expectedCurrentRewards))
    val id = ClusterHeaderObservation.sha256(
      Bytes.fromArray(
        ("governance-parameter-roles-v1\n" + previous.sha256.hex + "\n" + current.sha256.hex)
          .getBytes("UTF-8")
      )
    )
    new Roles(previous, current, id)
  }

  /** Empty governance rolls complete current originals into both temporal roles. */
  private[lab] def rollover(before: Roles, applied: G.Applied): Either[String, Roles] = checked {
    require(before != null && applied != null, "parameter rollover source required")
    require(
      applied.before.parameters.previous.original == before.previous.original &&
        applied.before.parameters.current.original == before.current.original &&
        applied.parameters.previous.original == before.current.original &&
        applied.parameters.current.original == before.current.original,
      "parameter rollover originals differ"
    )
    val id = ClusterHeaderObservation.sha256(
      Bytes.fromArray(
        ("governance-parameter-roles-v1\n" + before.current.sha256.hex + "\n" + before.current.sha256.hex)
          .getBytes("UTF-8")
      )
    )
    new Roles(before.current, before.current, id)
  }
