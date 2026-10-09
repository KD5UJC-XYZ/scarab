// SPDX-License-Identifier: Apache-2.0
package lab

import java.time.Instant
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayRewardStart as R, ConwayStake as S}
import scala.util.control.NonFatal

/** Typed supplied fixed-epoch Globals projection. Native Globals contains EpochInfo functions; this
  * is neither a native Globals wire format nor authenticated hard-fork history.
  */
private[lab] object GovernanceGlobals:
  val Profile = "governance-supplied-fixed-epoch-globals-v1"
  enum Network:
    case Testnet
  final case class FixedEpochGeometry(epochLength: BigInt, slotLength: S.Ratio)
  final class Checked private[GovernanceGlobals] (
      val geometry: FixedEpochGeometry,
      val slotsPerKESPeriod: BigInt,
      val stabilityWindow: BigInt,
      val randomnessStabilisationWindow: BigInt,
      val securityParameter: BigInt,
      val maxKESEvolutions: BigInt,
      val quorum: BigInt,
      val maxLovelaceSupply: BigInt,
      val activeSlotCoefficient: S.Ratio,
      val network: Network,
      val systemStart: Instant,
      val epoch: BigInt,
      val pointSlot: BigInt,
      val networkMagic: BigInt,
      val sourceBindingId: Bytes,
      val previousParameterSHA256: Bytes,
      val currentParameterSHA256: Bytes,
      val genesisOriginal: Bytes,
      val genesisSHA256: Bytes,
      val rewardGlobals: R.Globals,
      val id: Bytes
  ):
    val suppliedFixedEpochProfile = true
    val nativeWireFormat = false
    val nativeGlobalsDecoded = false
    val epochInfoAuthenticated = false
    val cachedActiveSlotLogValidated = false
    val nativeConformance = false
    val runtimeAdmission = false
    val legacyGovernancePayloadAdmitted = false
  private val Max = (BigInt(1) << 64) - 1
  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(e => throw new IllegalArgumentException(e), identity)
  private def width(b: Bytes) = b != null && b.value != null && b.size == 32
  private def uint(n: BigInt) = n != null && n >= 0 && n <= Max
  private def encode(xs: Vector[V]): Bytes = get(Cbor.encode(V.Arr(xs.map(Node(_, Bytes.empty)))))

  /** Bind already checked diagnostic sources to an independent expected point and source identity.
    * Remaining scalar fields are read from the exact pinned genesis, never supplied as overrides.
    */
  def bind(
      seed: NativeSeedParameters.Prepared,
      expectedBindingId: Bytes,
      expectedEpoch: BigInt,
      expectedPointSlot: BigInt,
      expectedNetworkMagic: BigInt
  ): Either[String, Checked] = checked {
    require(
      seed != null && width(expectedBindingId) && seed.bindingId == expectedBindingId,
      "globals source binding mismatch"
    )
    require(
      uint(expectedEpoch) && uint(expectedPointSlot) && uint(expectedNetworkMagic) &&
        expectedNetworkMagic <= 0xffffffffL && seed.epoch == expectedEpoch && seed.pointSlot == expectedPointSlot &&
        seed.networkMagic == expectedNetworkMagic,
      "globals point/epoch/network binding mismatch"
    )
    require(
      width(seed.genesisSHA256) && ClusterHeaderObservation.sha256(
        seed.genesisOriginal
      ) == seed.genesisSHA256,
      "globals genesis content identity"
    )
    val json = ReferenceJson.parse(seed.genesisOriginal)
    def number(name: String): BigInt =
      val n = ReferenceJson.uint(ReferenceJson.field(json, name))
      require(uint(n), "globals uint64 field"); n
    val slots = number("slotsPerKESPeriod")
    val evolutions = number("maxKESEvolutions")
    val quorum = number("updateQuorum")
    val start = Instant.parse(ReferenceJson.string(ReferenceJson.field(json, "systemStart")))
    require(
      ReferenceJson.string(ReferenceJson.field(json, "networkId")) == "Testnet",
      "globals network profile"
    )
    require(number("networkMagic") == expectedNetworkMagic, "globals network source")
    val globals = seed.globals
    val k = globals.securityParameter.getOrElse(
      throw new IllegalArgumentException("globals security parameter required")
    )
    require(
      slots > 0 && evolutions > 0 && globals.epochLength == number("epochLength") &&
        k == number("securityParam") && globals.maxSupply == number("maxLovelaceSupply") &&
        expectedPointSlot / globals.epochLength == expectedEpoch,
      "globals scalar/geometry mismatch"
    )
    val fields = Vector[V](
      V.Text(Profile),
      V.ByteString(seed.bindingId),
      V.ByteString(seed.genesisSHA256),
      V.Text("fixed-epoch-from-slot-zero"),
      V.UInt(globals.epochLength),
      V.UInt(seed.slotLength.numerator),
      V.UInt(seed.slotLength.denominator),
      V.UInt(slots),
      V.UInt(seed.stabilityWindow),
      V.UInt(seed.randomnessWindow),
      V.UInt(k),
      V.UInt(evolutions),
      V.UInt(quorum),
      V.UInt(globals.maxSupply),
      V.UInt(globals.activeSlotCoefficient.numerator),
      V.UInt(globals.activeSlotCoefficient.denominator),
      V.Text("Testnet"),
      V.Text(start.toString),
      V.UInt(expectedEpoch),
      V.UInt(expectedPointSlot),
      V.UInt(expectedNetworkMagic),
      V.ByteString(globals.id)
    )
    val id = ClusterHeaderObservation.sha256(encode(fields))
    new Checked(
      FixedEpochGeometry(globals.epochLength, seed.slotLength),
      slots,
      seed.stabilityWindow,
      seed.randomnessWindow,
      k,
      evolutions,
      quorum,
      globals.maxSupply,
      globals.activeSlotCoefficient,
      Network.Testnet,
      start,
      expectedEpoch,
      expectedPointSlot,
      expectedNetworkMagic,
      seed.bindingId,
      seed.previous.sha256,
      seed.current.sha256,
      seed.genesisOriginal,
      seed.genesisSHA256,
      globals,
      id
    )
  }

  def checkProjection(
      value: Checked,
      expected: R.Globals,
      expectedSlotLength: S.Ratio,
      expectedStabilityWindow: BigInt,
      expectedRandomnessWindow: BigInt
  ): Either[String, Unit] = checked {
    require(
      value != null && expected != null && expected.securityParameter.contains(
        value.securityParameter
      ) &&
        expected.epochLength == value.geometry.epochLength && expected.maxSupply == value.maxLovelaceSupply &&
        expected.activeSlotCoefficient == value.activeSlotCoefficient && expected.id == value.rewardGlobals.id &&
        expectedSlotLength == value.geometry.slotLength && expectedStabilityWindow == value.stabilityWindow &&
        expectedRandomnessWindow == value.randomnessStabilisationWindow,
      "globals reward/timing projection mismatch"
    )
  }

  /** Optional existing header-context bridge: checks only fields and exact source identities that
    * the existing context exposes. No native EpochInfo callbacks are reconstructed.
    */
  def checkHeader(value: Checked, context: SequenceInput.Context): Either[String, Unit] = checked {
    require(
      value != null && context != null && context.certificates.genesisDigest == value.genesisSHA256 &&
        context.ledger.environment.genesisDigest == value.genesisSHA256 &&
        BigInt(context.ledger.environment.networkMagic) == value.networkMagic &&
        context.epoch == value.epoch && context.ledger.slot == value.pointSlot &&
        context.certificateSeed.tip.slot == value.pointSlot &&
        context.nonces.context.epochLength == value.geometry.epochLength &&
        context.nonces.context.window == value.randomnessStabilisationWindow &&
        context.certificates.slotsPerKesPeriod == value.slotsPerKESPeriod &&
        BigInt(context.certificates.maxKesEvolutions) == value.maxKESEvolutions &&
        context.eligibility.active.numerator == value.activeSlotCoefficient.numerator &&
        context.eligibility.active.denominator == value.activeSlotCoefficient.denominator,
      "globals header source/geometry/point projection mismatch"
    )
  }
