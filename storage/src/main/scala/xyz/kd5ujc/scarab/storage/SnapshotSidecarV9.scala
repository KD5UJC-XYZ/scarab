// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.ledger.{Script, TxOut, Utxo}

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.ledger.StorageError

/** The sidecar schema of the V9 body, frozen: the current one ([[SnapshotSidecar]], V10) with the
  * bootstrap (ledger-lite) state where V10 has the follow state, read into the frozen types of
  * [[FrozenSidecar]]. V10's sidecar was changed in place, before any deployment, by the owner's
  * decision of 2026-11-14 (#988); nothing else differs. The schema fingerprint golden of
  * `CodecSuite` keeps it the one V9 files were written with.
  */
private[storage] object SnapshotSidecarV9:
  import NodeCodecs.given

  given script: Codec[Script] = NodeCodecs.scriptOf(FrozenScriptKind.codec)
  given txOut: Codec[TxOut] = Codec.auto
  given utxoChange: Codec[Utxo.Change] = Codec.auto
  given utxo: Codec[Utxo] = Codec.auto

  given liteLedger: Codec[FrozenSidecar.LiteLedger] = Codec.auto
  given bootstrap: Codec[FrozenSidecar.BootstrapState] = Codec.auto
  given consensusExtras: Codec[FrozenSidecar.ConsensusExtras] = Codec.auto
  given mirrorInShelley: Codec[FrozenSidecar.MirrorExtras.InShelley] = Codec.auto

  val codec: Codec[FrozenSidecar.SnapshotSidecar] = Codec.auto

  /** The schema fingerprint stored in front of a V9 sidecar. */
  def fingerprint: Array[Byte] =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(codec.shape.getBytes(StandardCharsets.UTF_8))
      .take(SnapshotSidecar.FingerprintSize)

  /** A V9 sidecar (after its fingerprint). */
  def decode(bytes: Array[Byte]): Either[StorageError, FrozenSidecar.SnapshotSidecar] =
    Codec
      .decode[FrozenSidecar.SnapshotSidecar](bytes)(using codec)
      .leftMap(DecodeFailure.binary("snapshot sidecar", _))
