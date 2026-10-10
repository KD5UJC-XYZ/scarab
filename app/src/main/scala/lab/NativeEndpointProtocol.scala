// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.header.{PraosCertificateState as Certificate, PraosNonceEvolution as Nonces}
import scala.util.control.NonFatal

/** Restricted research same-point field comparison. Source pins are attribution, not
  * authentication.
  */
private[lab] object NativeEndpointProtocol:
  final class Checked private[NativeEndpointProtocol] (
      val terminal: Certificate.Point,
      val acquisitionId: Bytes,
      val originalProtocol: Bytes,
      val protocolSHA256: Bytes
  ):
    val representedProtocolFieldsEqual = true
    val comparisonScope = "Praos v0 eight-field payload: last slot, counters, six nonce fields only"
    val excludes =
      "ledger state, cryptographic history, acquisition authenticity, other protocol formats"
    val authenticatedSnapshot = false
    val actualAcquisitionVerified = false
    val fullConsensusValidated = false
    val runtimeImport = false
    val rewardSeedAdmission = false

  private def protect[A](body: => A): Either[String, A] =
    try Right(body)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](value: Either[String, A]): A =
    value.fold(e => throw new IllegalArgumentException(e), value => value)

  /** No Checked capability is returned from supplied value-level comparisons. */
  def compareValues(
      native: NativePraosProtocol.Decoded,
      replayLastSlot: BigInt,
      replayFields: Nonces.Fields,
      replayCounters: Map[Bytes, BigInt]
  ): Either[String, Unit] = protect {
    require(
      native != null && replayFields != null && replayCounters != null,
      "protocol comparison values required"
    )
    require(native.snapshot.lastSlot == replayLastSlot, "protocol.lastSlot mismatch")
    val expected = native.snapshot.fields
    require(expected.evolving == replayFields.evolving, "protocol.evolving mismatch")
    require(expected.candidate == replayFields.candidate, "protocol.candidate mismatch")
    require(expected.epoch == replayFields.epoch, "protocol.epoch mismatch")
    require(expected.previousEpoch == replayFields.previousEpoch, "protocol.previousEpoch mismatch")
    require(expected.lab == replayFields.lab, "protocol.lab mismatch")
    require(
      expected.lastEpochBlock == replayFields.lastEpochBlock,
      "protocol.lastEpochBlock mismatch"
    )
    require(native.counters.keySet == replayCounters.keySet, "protocol.counters domain mismatch")
    native.counters.keys.toVector.sortBy(_.hex).foreach { issuer =>
      require(
        native.counters(issuer) == replayCounters(issuer),
        "protocol.counters[" + issuer.hex + "] mismatch"
      )
    }
  }

  def compare(
      acquisition: NativeProtocolBootstrap.Acquisition,
      expectedTerminal: Certificate.Point,
      expectedAcquisitionId: Bytes,
      replay: CoherentSequence.State
  ): Either[String, Checked] = protect {
    require(
      acquisition != null && replay != null && expectedTerminal != null &&
        expectedAcquisitionId != null && expectedAcquisitionId.size == 32,
      "checked endpoint acquisition replay and expected identity required"
    )
    require(acquisition.id == expectedAcquisitionId, "endpoint acquisition identity mismatch")
    require(acquisition.anchor == expectedTerminal, "endpoint acquisition full point mismatch")
    require(
      replay.certificates.state.tip == expectedTerminal,
      "endpoint replay full point mismatch"
    )
    require(replay.nonces.lastSlot == expectedTerminal.slot, "endpoint replay nonce slot mismatch")
    require(replay.ledger.slot == expectedTerminal.slot, "endpoint replay ledger slot mismatch")
    require(
      replay.nonces.certificateStateId == replay.certificates.state.id,
      "endpoint replay nonce/certificate binding mismatch"
    )
    val original = acquisition.originals("original-debug-protocol.cbor")
    val pin = acquisition.sourcePins("original-debug-protocol.cbor")
    require(
      ClusterHeaderObservation.sha256(original) == pin,
      "endpoint protocol source pin mismatch"
    )
    val native = get(NativePraosProtocol.decode(original))
    require(native.snapshot.lastSlot == expectedTerminal.slot, "endpoint native lastSlot mismatch")
    get(
      compareValues(
        native,
        replay.nonces.lastSlot,
        replay.nonces.fields,
        replay.certificates.state.counters
      )
    )
    new Checked(expectedTerminal, acquisition.id, original, pin)
  }
