// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.header.{PraosCertificateState as Certificate, PraosNonceEvolution as Nonces}
import lab.vrf.PraosVrfCertificate as Vrf
import scala.util.control.NonFatal

/** Source-bound v2 diagnostic join. This constructs no admitted state, runner or checkpoint. */
private[lab] object NativeLedgerV2:
  val InputNames: Set[String] = NativeProtocolBootstrap.InputNames ++
    Set("native-projection.json", "effective-shelley-genesis.json")
  private val SharedNames = Set(
    "request.json",
    "capture.json",
    "original-debug-epoch.cbor",
    "original-whole-utxo.cbor",
    "derived-full-epoch-seed.cbor"
  )

  final class Checked private[NativeLedgerV2] (
      val id: Bytes,
      val ledger: NativeLedgerSeed.Checked,
      val acquisition: NativeProtocolBootstrap.Acquisition,
      val protocol: NativeProtocolBootstrap.Prepared,
      val crossingBlockers: Set[NativeLedgerSeed.Blocker]
  ):
    val point = acquisition.anchor
    val protocolSourceJoined = true
    val crossingGeometryCompatible =
      !crossingBlockers.contains(NativeLedgerSeed.Blocker.IncompatibleCrossingGeometry)
    val runtimeImport = false
    val rewardSeedAdmission = false
    val authenticatedSnapshot = false
    val actualAcquisitionVerified = false
    val fullLedgerValidated = false
    val nativeConformance = false
    val runtimeReady = false

  private def checked[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), value => value)
  private def sha(b: Bytes): Bytes = ClusterHeaderObservation.sha256(b)
  private def identity(actual: Bytes, expected: Bytes, label: String): Unit =
    require(
      expected != null && expected.value != null && expected.size == 32 && actual == expected,
      "v2 expected " + label + " identity mismatch"
    )

  def bind(
      ledger: NativeLedgerSeed.Checked,
      acquisition: NativeProtocolBootstrap.Acquisition,
      protocol: NativeProtocolBootstrap.Prepared,
      expectedPoint: Certificate.Point,
      expectedLedgerId: Bytes,
      expectedAcquisitionId: Bytes,
      expectedProtocolId: Bytes
  ): Either[String, Checked] = checked {
    require(
      ledger != null && acquisition != null && protocol != null && expectedPoint != null,
      "v2 checked ledger, acquisition, protocol and point required"
    )
    identity(ledger.id, expectedLedgerId, "ledger")
    identity(acquisition.id, expectedAcquisitionId, "acquisition")
    identity(protocol.id, expectedProtocolId, "protocol")
    val epoch = ledger.epochComponents
    require(
      epoch.protocolAcquisitionId.contains(acquisition.id),
      "v2 ledger was not decoded under this acquisition"
    )
    require(
      ledger.point == expectedPoint && acquisition.anchor == expectedPoint && protocol.anchor == expectedPoint,
      "v2 full point splice"
    )
    require(
      protocol.originals == acquisition.originals && protocol.sourcePins == acquisition.sourcePins,
      "v2 protocol original/pin splice"
    )
    SharedNames.foreach { name =>
      require(
        epoch.originals(name) == acquisition.originals(name) && epoch.pins(name) == acquisition
          .sourcePins(name),
        "v2 shared original/pin splice: " + name
      )
    }
    val globals = ledger.globals
    require(
      protocol.genesisDigest == globals.genesisSHA256 && protocol.originalGenesis == globals.genesisOriginal &&
        globals.networkMagic == acquisition.networkMagic && epoch.parameters.networkMagic == acquisition.networkMagic,
      "v2 genesis/network source mismatch"
    )
    require(
      protocol.leadershipBytes == epoch.components("leadership"),
      "v2 leadership original mismatch"
    )
    val mark = epoch.stake.snapshots.mark
    val certificates = protocol.certificates
    val seed = protocol.certificateSeed
    require(
      certificates.genesisDigest == globals.genesisSHA256 &&
        certificates.registrations == mark.distribution.map((pool, value) => pool -> value.vrf) &&
        certificates.firstSlot == globals.epoch * globals.geometry.epochLength &&
        certificates.lastSlot == (globals.epoch + 1) * globals.geometry.epochLength - 1 &&
        certificates.slotsPerKesPeriod == globals.slotsPerKESPeriod &&
        BigInt(certificates.maxKesEvolutions) == globals.maxKESEvolutions &&
        seed.contextId == certificates.id && seed.tip == expectedPoint,
      "v2 certificate leadership/epoch/anchor mismatch"
    )
    val nonces = protocol.nonces
    require(
      nonces.context.certificates.id == certificates.id &&
        nonces.context.epochLength == globals.geometry.epochLength &&
        nonces.context.window == globals.randomnessStabilisationWindow &&
        nonces.seed.contextId == nonces.context.id && nonces.seed.certificateStateId == seed.id &&
        nonces.seed.lastSlot == expectedPoint.slot && nonces.seed.fields.previousEpoch.nonEmpty,
      "v2 explicit nonce/certificate/timing mismatch"
    )
    val eligibility = protocol.eligibility
    require(
      eligibility.certificates.id == certificates.id && eligibility.seed.id == seed.id &&
        eligibility.epoch == globals.epoch &&
        eligibility.active.numerator == globals.activeSlotCoefficient.numerator &&
        eligibility.active.denominator == globals.activeSlotCoefficient.denominator &&
        eligibility.stakes.keySet == mark.distribution.keySet && eligibility.stakes.forall {
          (pool, value) =>
            val expected = mark.distribution(pool).ratio
            value.numerator == expected.numerator && value.denominator == expected.denominator
        },
      "v2 eligibility/stake/active-coefficient mismatch"
    )
    val nonceMatches = (nonces.seed.fields.epoch, eligibility.nonce) match
      case (Nonces.Nonce.Neutral, Vrf.NeutralNonce)     => true
      case (Nonces.Nonce.Hash(bytes), hash: Vrf.Hash32) => bytes == hash.bytes
      case _                                            => false
    require(nonceMatches, "v2 eligibility epoch nonce mismatch")
    val blockers = ledger.crossingBlockers - NativeLedgerSeed.Blocker.MissingPointBoundProtocolV2
    require(
      blockers.contains(NativeLedgerSeed.Blocker.IncompatibleCrossingGeometry) ==
        (2 * globals.randomnessStabilisationWindow >= globals.geometry.epochLength),
      "v2 crossing geometry declaration mismatch"
    )
    val encoded = get(
      Cbor.encode(
        V.Arr(
          Vector(
            V.Text("native-ledger-v2-diagnostic-join-v1"),
            V.ByteString(ledger.id),
            V.ByteString(acquisition.id),
            V.ByteString(protocol.id),
            V.ByteString(acquisition.sourcePins("receipt.json")),
            V.ByteString(acquisition.sourcePins("original-debug-protocol.cbor")),
            V.ByteString(expectedPoint.hash),
            V.UInt(expectedPoint.slot),
            V.UInt(expectedPoint.blockNo)
          ).map(Node(_, Bytes.empty))
        )
      )
    )
    new Checked(sha(encoded), ledger, acquisition, protocol, blockers)
  }

  /** Geometry predicate only; success is never a runtime or ledger admission capability. */
  def checkCrossingGeometry(value: Checked): Either[String, Unit] = checked {
    require(
      value != null && value.crossingGeometryCompatible,
      "v2 crossing geometry requires 2 * randomnessWindow < epochLength"
    )
  }

  /** Exact ten externally pinned originals: the v2 packet is consumed unchanged by existing
    * decoders.
    */
  def decode(
      originals: Map[String, Bytes],
      expectedPins: Map[String, Bytes],
      anchor: Certificate.Point
  ): Either[String, Checked] = checked {
    require(
      originals != null && expectedPins != null && originals.keySet == InputNames && expectedPins.keySet == InputNames,
      "v2 exact packet/projection/genesis input set"
    )
    require(
      originals.values.forall(b => b != null && b.value != null) &&
        originals.values.foldLeft(0L)(_ + _.size) <= 8L * 1024 * 1024,
      "v2 aggregate original bound"
    )
    val packet = originals.filter((name, _) => NativeProtocolBootstrap.InputNames(name))
    val packetPins = expectedPins.filter((name, _) => NativeProtocolBootstrap.InputNames(name))
    val acquisition = get(NativeProtocolBootstrap.checkAcquisition(packet, packetPins, anchor))
    val epoch = get(
      NativeEpochComponents.decodeV2(
        originals.filter((name, _) => NativeEpochComponents.Names(name)),
        expectedPins.filter((name, _) => NativeEpochComponents.Names(name)),
        anchor,
        acquisition
      )
    )
    val selected =
      epoch.components.filter((name, _) => NativeGovernanceComponents.ComponentNames(name))
    val governance = get(
      NativeGovernanceComponents.decode(
        originals("derived-full-epoch-seed.cbor"),
        expectedPins("derived-full-epoch-seed.cbor"),
        originals("original-debug-epoch.cbor"),
        expectedPins("original-debug-epoch.cbor"),
        selected,
        selected.map((name, raw) => name -> sha(raw))
      )
    )
    val p = epoch.parameters
    val globals = get(GovernanceGlobals.bind(p, p.bindingId, p.epoch, p.pointSlot, p.networkMagic))
    val ledger = get(NativeLedgerSeed.bind(epoch, governance, globals, anchor, epoch.id))
    val protocol =
      get(NativeProtocolBootstrap.bind(acquisition, globals.genesisOriginal, globals.genesisSHA256))
    get(bind(ledger, acquisition, protocol, anchor, ledger.id, acquisition.id, protocol.id))
  }
