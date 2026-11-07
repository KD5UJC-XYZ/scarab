// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.{BinaryError, Bytes}
import xyz.kd5ujc.scarab.consensus.state.MirrorExtras
import xyz.kd5ujc.scarab.consensus.{BootstrapState, ByronState, ConsensusExtras}
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{FileKind, FormatLoss, FormatVersion, StorageError}
import xyz.kd5ujc.scarab.ledger.byron.{
  ByronParams,
  ByronVersion,
  CandidateUpdate,
  ProtocolProposal,
  UpdateState as LiveUpdateState
}

/** The sidecar schema of the V5 and V7 bodies, frozen: the V8 one ([[SnapshotSidecarV8]]) less the
  * installer metadata of the Byron update state's software proposals and application versions,
  * which those versions discarded (issue #726).
  *
  * It reads into the current types. A Byron update state that holds no software proposal and no
  * application version lost nothing and is read as it is; one that holds any is refused with
  * [[StorageError.ResyncRequired]], because its metadata cannot be recovered and the node does not
  * invent it (ADR 0006). The schema fingerprint golden of `CodecSuite` keeps it the one those files
  * were written with: the frozen types below carry the names of the V7 types, the eras are those of
  * the files before V9 ([[FrozenEra]]), so are the protocol parameters ([[FrozenProtocolParams]]),
  * and the other types are shared with the current schema, unchanged at V8 and V9.
  */
private[storage] object SnapshotSidecarV7:

  /** The Byron update-state types as V5 and V7 hold them. */
  object Frozen:
    final case class AppVersion(number: BigInt, slot: BigInt)

    final case class UpdateState(
        adoptedVersion: ByronVersion,
        adoptedParams: ByronParams,
        candidates: Vector[CandidateUpdate],
        appVersions: Map[String, AppVersion],
        registeredProtocol: Map[Bytes, ProtocolProposal],
        registeredSoftware: Map[Bytes, (String, BigInt)],
        confirmed: Map[Bytes, BigInt],
        votes: Map[Bytes, Set[Bytes]],
        endorsements: Set[(ByronVersion, Bytes)],
        registrationSlot: Map[Bytes, BigInt]
    )

  // The givens below that contain the update state or an era are named as those of `NodeCodecs`,
  // which they shadow: the derivations here find them, and the other givens of `NodeCodecs`,
  // unchanged. The eras and the parameters are the frozen ones of the files before V9
  // ([[FrozenEra]], [[FrozenProtocolParams]]).
  import NodeCodecs.given

  private val frozen: Codec[Frozen.UpdateState] = Codec.auto

  /** The state of a V7 file, or the loss that refuses it. */
  private def current(s: Frozen.UpdateState): Either[BinaryError, LiveUpdateState] =
    Either.cond(
      s.registeredSoftware.isEmpty && s.appVersions.isEmpty,
      LiveUpdateState(
        s.adoptedVersion,
        s.adoptedParams,
        s.candidates,
        Map.empty,
        s.registeredProtocol,
        Map.empty,
        s.confirmed,
        s.votes,
        s.endorsements,
        s.registrationSlot
      ),
      BinaryError.Reconstruction(
        FormatLoss.ByronSoftwareMetadata(s.registeredSoftware.size, s.appVersions.size)
      )
    )

  /** The state as the V7 writer wrote it, its metadata dropped: tests write previous-version files
    * with it; the node writes only the current version.
    */
  private def previous(s: LiveUpdateState): Frozen.UpdateState =
    Frozen.UpdateState(
      s.adoptedVersion,
      s.adoptedParams,
      s.candidates,
      s.appVersions.view.mapValues(a => Frozen.AppVersion(a.number, a.slot)).toMap,
      s.registeredProtocol,
      s.registeredSoftware.view.mapValues(p => (p.name, p.number)).toMap,
      s.confirmed,
      s.votes,
      s.endorsements,
      s.registrationSlot
    )

  given updateState: Codec[LiveUpdateState] with
    def write(out: Out, a: LiveUpdateState): Unit = frozen.write(out, previous(a))
    def read(in: In): Either[BinaryError, LiveUpdateState] = frozen.read(in).flatMap(current)
    def shape: String = frozen.shape

  given era: Codec[Era] = FrozenEra.codec
  given protocolParams: Codec[ProtocolParams] = FrozenProtocolParams.codec
  given bootstrap: Codec[BootstrapState] = Codec.auto
  given byron: Codec[ByronState] = Codec.auto
  given consensusExtras: Codec[ConsensusExtras] = Codec.auto
  given mirrorInShelley: Codec[MirrorExtras.InShelley] = Codec.auto

  val codec: Codec[SnapshotSidecar] = Codec.auto

  /** The schema fingerprint stored in front of a V5 or V7 sidecar. */
  def fingerprint: Array[Byte] =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(codec.shape.getBytes(StandardCharsets.UTF_8))
      .take(SnapshotSidecar.FingerprintSize)

  /** A V5 or V7 sidecar (after its fingerprint), the refusal of a lost Byron metadata as
    * [[StorageError.ResyncRequired]] of a file of version `found`.
    */
  def decode(bytes: Array[Byte], found: FormatVersion): Either[StorageError, SnapshotSidecar] =
    Codec.decode[SnapshotSidecar](bytes)(using codec).leftMap {
      case BinaryError.Reconstruction(loss: FormatLoss) =>
        StorageError.ResyncRequired("snapshot", FileKind.SnapshotFile, found, loss)
      case other => DecodeFailure.binary("snapshot sidecar", other)
    }
