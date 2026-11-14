// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.ledger.StorageError

/** The sidecar schema of the V9 and V10 bodies, frozen: the current one ([[SnapshotSidecar]], V11)
  * with the bootstrap (ledger-lite) follow state where V11 has the follow state, read into the
  * frozen types of [[FrozenSidecar]]. V11 changed nothing else. The schema fingerprint golden of
  * `CodecSuite` keeps it the one V9 and V10 files were written with.
  */
private[storage] object SnapshotSidecarV10:
  import NodeCodecs.given

  given liteLedger: Codec[FrozenSidecar.LiteLedger] = Codec.auto
  given bootstrap: Codec[FrozenSidecar.BootstrapState] = Codec.auto
  given consensusExtras: Codec[FrozenSidecar.ConsensusExtras] = Codec.auto
  given mirrorInShelley: Codec[FrozenSidecar.MirrorExtras.InShelley] = Codec.auto

  val codec: Codec[FrozenSidecar.SnapshotSidecar] = Codec.auto

  /** The schema fingerprint stored in front of a V9 or V10 sidecar. */
  def fingerprint: Array[Byte] =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(codec.shape.getBytes(StandardCharsets.UTF_8))
      .take(SnapshotSidecar.FingerprintSize)

  /** A V9 or V10 sidecar (after its fingerprint). */
  def decode(bytes: Array[Byte]): Either[StorageError, FrozenSidecar.SnapshotSidecar] =
    Codec
      .decode[FrozenSidecar.SnapshotSidecar](bytes)(using codec)
      .leftMap(DecodeFailure.binary("snapshot sidecar", _))
