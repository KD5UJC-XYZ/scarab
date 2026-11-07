// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.consensus.state.MirrorExtras
import xyz.kd5ujc.scarab.consensus.{BootstrapState, ConsensusExtras}
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.protocol.Era

/** The sidecar schema of the V8 body, frozen: the current one ([[SnapshotSidecar]], V9) with the
  * eras of the files before V9 ([[FrozenEra]], Byron to Conway) and their protocol parameters,
  * without the fields Dijkstra adds ([[FrozenProtocolParams]]). V9 changed nothing else: its
  * sidecar is the V8 one whose eras include Dijkstra and whose parameters have Dijkstra's fields,
  * so every V8 sidecar reads into the current types with no loss, those fields at their
  * pre-Dijkstra defaults. The schema fingerprint golden of `CodecSuite` keeps it the one V8 files
  * were written with.
  */
private[storage] object SnapshotSidecarV8:
  // The givens below that contain an era or the parameters are named as those of `NodeCodecs`, which they shadow:
  // the derivations here find them, and the other givens of `NodeCodecs`, unchanged.
  import NodeCodecs.given

  given era: Codec[Era] = FrozenEra.codec
  given protocolParams: Codec[ProtocolParams] = FrozenProtocolParams.codec
  given bootstrap: Codec[BootstrapState] = Codec.auto
  given consensusExtras: Codec[ConsensusExtras] = Codec.auto
  given mirrorInShelley: Codec[MirrorExtras.InShelley] = Codec.auto

  val codec: Codec[SnapshotSidecar] = Codec.auto

  /** The schema fingerprint stored in front of a V8 sidecar. */
  def fingerprint: Array[Byte] =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(codec.shape.getBytes(StandardCharsets.UTF_8))
      .take(SnapshotSidecar.FingerprintSize)

  /** A V8 sidecar (after its fingerprint). */
  def decode(bytes: Array[Byte]): Either[xyz.kd5ujc.scarab.ledger.StorageError, SnapshotSidecar] =
    Codec
      .decode[SnapshotSidecar](bytes)(using codec)
      .leftMap(DecodeFailure.binary("snapshot sidecar", _))
