// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError
import cats.syntax.all.*
import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.{
  ByronMirror,
  ConsensusMirrorEnv,
  EraLedgerState,
  ExtLedgerState,
  MirrorExtras,
  MirrorProvenance,
  MirrorState,
  MirrorStep,
  ShelleyLedgerState,
  StateMirror,
  StateMirrorError,
  Telescope
}
import xyz.kd5ujc.scarab.ledger.{FileKind, ProtocolParams, StorageError, Utxo}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{
  EpochState,
  LedgerState,
  NewEpochState,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellState}

/** The snapshot of format version 5, the Haskell ledger state with a sidecar, and the body of the
  * current version ([[SnapshotV7]]), which puts a checkpoint binding in front of it unchanged:
  *
  * {{{
  * "SCRBSNP5" | sidecar length u32 | sidecar | Haskell state bytes
  * sidecar = schema fingerprint[8] | Codec of SnapshotSidecar
  * }}}
  *
  * The Haskell state bytes are the `state` file of the reference (`[1, ExtLedgerState]`, written by
  * [[HaskellLedgerFile]]) of the chain's [[MirrorState]], less the UTxO, whose entries stay in the
  * RocksDB store (the file has `map(0)` there, as the reference's `state` file does). The chain
  * still in Byron has no Haskell section: the sidecar holds the Byron state and what Shelley will
  * start from. The checksum that closes the file is the store's ([[FileLedgerSnapshots]]).
  *
  * The state is written as it is held and read back as it is: no conversion of any kind. What the
  * Haskell section has no place for is the sidecar's: the UTxO set's handle, the node's bookkeeping
  * (the extras), and the parameters in force and the previous ones, which the sidecar holds as the
  * node's full set (a Shelley-based era's `PParams` in the Haskell section has only its own
  * fields).
  *
  * Version 4 differed in the sidecar only: its extras held the old rules' bookkeeping (the reward
  * draft, the attributed amounts, the pool details of the snapshots, the parameter fields of other
  * eras) and, in Byron, the flat ledger state, none of which the era-indexed rules keep. Files of
  * versions 1 to 4 are recognized as retired by [[SnapshotMigration]] before stores open.
  *
  * A V5 file is read only to be migrated ([[SnapshotV7.fromV5]]): the body after its magic becomes
  * the body of the V7 file byte for byte, so the sidecar and Haskell readers here are those of both
  * versions, and the V5 sidecar fingerprint golden (`CodecSuite`) keeps guarding them.
  */
object SnapshotV5:
  val Magic: Array[Byte] = "SCRBSNP5".getBytes("US-ASCII")

  /** Magic and the length of the sidecar. */
  private val HeaderSize = Magic.length + 4

  /** The sidecar and the Haskell section of a snapshot (the body the V5 and V7 files share), or the
    * refusal of the state.
    */
  private[storage] def parts(
      snapshot: StateSnapshot[MirrorState]
  ): Either[SnapshotRefusal, (SnapshotSidecar, Bytes)] =
    val m = snapshot.state
    def sidecar(chain: SnapshotSidecar.Chain) =
      SnapshotSidecar(
        snapshot.anchor,
        m.provenance.observed,
        m.provenance.gaps,
        chain
      )
    (m.extras, m.ext.ledgerState.state) match
      case (
            MirrorExtras.InByron(pending, boot, protocol),
            EraLedgerState.ByronCurrent(b)
          ) =>
        Right(
          sidecar(SnapshotSidecar.Chain.InByron(pending, ConsensusExtras(boot, b, protocol))) ->
            Bytes.empty
        )
      case (shelley: MirrorExtras.InShelley, i: EraLedgerState.InShelleyEra[e]) =>
        given xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra[e] = i.era
        val gov = i.state.nes.es.ls.utxo.gov
        for
          file <- HaskellLedgerFile
            .of(m.ext)
            .leftMap(e => SnapshotRefusal.Mirror(StateMirrorError.Telescope(e)))
          haskell <- HaskellLedgerFile.encode(file).leftMap(SnapshotRefusal.NotWritable(_))
        yield sidecar(
          SnapshotSidecar.Chain.InShelley(
            i.state.nes.es.ls.utxo.utxo.utxo,
            EraLedger.curPParams(gov),
            Some(EraLedger.prevPParams(gov)),
            shelley
          )
        ) -> haskell
      case (_, state) =>
        Left(SnapshotRefusal.Mirror(StateMirrorError.ExtrasMismatch(state.era)))

  /** The encoded sidecar with its fingerprint: what follows the sidecar length in the body. */
  private[storage] def sidecarBytes(sidecar: SnapshotSidecar): Array[Byte] =
    SnapshotSidecar.fingerprint ++ Codec.encode(sidecar)(using SnapshotSidecar.codec)

  /** Write the body (`sidecar length u32 | sidecar | Haskell bytes`) into `out` at `at`. */
  private[storage] def writeBody(
      out: Array[Byte],
      at: Int,
      sidecar: Array[Byte],
      haskell: Bytes
  ): Unit =
    ByteBuffer.wrap(out, at, 4).putInt(sidecar.length)
    System.arraycopy(sidecar, 0, out, at + 4, sidecar.length)
    haskell.copyTo(out, at + 4 + sidecar.length)

  /** A V5 file's content, in one array of its exact size (the previous-version tests and fixture
    * recipes write it; the node writes [[SnapshotV7]]).
    */
  private[storage] def framed(sidecar: SnapshotSidecar, haskell: Bytes): Array[Byte] =
    val side = sidecarBytes(sidecar)
    val out = new Array[Byte](HeaderSize + side.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    writeBody(out, Magic.length, side, haskell)
    out

  // ---- read

  private def corrupt[E <: DomainError](reason: E): StorageError =
    StorageError.Corrupt("snapshot", reason)

  /** The state of a body that starts at `at`: the anchor and the mirror. */
  private[storage] def decodeBody(
      env: ConsensusMirrorEnv,
      content: Array[Byte],
      at: Int
  ): Either[StorageError, (Option[BlockRef], MirrorState)] =
    body(content, at).flatMap { (sidecar, haskell) =>
      val state: Either[StorageError, MirrorState] = sidecar.chain match
        case SnapshotSidecar.Chain.InByron(pending, extras) =>
          Either
            .cond(haskell.size == 0, (), corrupt(SnapshotReadError.ByronWithLedgerState))
            .flatMap { _ =>
              val seen = env.copy(observed = env.observed ++ sidecar.observed)
              ByronMirror
                .of(
                  seen,
                  sidecar.anchor,
                  pending,
                  extras,
                  MirrorProvenance.recorded(sidecar.gaps, sidecar.observed)
                )
                .leftMap(e => corrupt(e))
            }
        case shelley: SnapshotSidecar.Chain.InShelley =>
          shelleyState(
            sidecar.anchor,
            haskell,
            shelley.utxo,
            shelley.params,
            shelley.prevParams,
            shelley.extras,
            MirrorProvenance.recorded(sidecar.gaps, sidecar.observed)
          )
      state.map(sidecar.anchor -> _)
    }

  /** The sidecar of a body that starts at `at` decodes and its Haskell section (empty in Byron) is
    * one CBOR item under the limits the state is read with: the index of its items is made and
    * dropped.
    */
  private[storage] def bodyWellFormed(content: Array[Byte], at: Int): Either[StorageError, Unit] =
    body(content, at).flatMap { (_, haskell) =>
      if haskell.size == 0 then Right(())
      else Cbor.decode(haskell, HaskellState.limits).bimap(corrupt, _ => ())
    }

  /** The mirror of a Shelley-based snapshot: the Haskell section decoded, the UTxO handle and the
    * parameters put back from the sidecar.
    */
  private def shelleyState(
      anchor: Option[BlockRef],
      haskell: Bytes,
      utxo: Utxo,
      params: ProtocolParams,
      prevParams: Option[ProtocolParams],
      extras: MirrorExtras.InShelley,
      provenance: MirrorProvenance
  ): Either[StorageError, MirrorState] =
    for
      file <- HaskellLedgerFile.decode(haskell).leftMap(e => corrupt(e))
      decoded <- file.ext.toRight(corrupt(SnapshotReadError.ByronLedgerInShelley))
      ext <- withUtxo(decoded, utxo)
      _ <- Either.cond(
        StateMirror.anchorOf(ext.headerState.tip) == anchor,
        (),
        corrupt(SnapshotReadError.AnchorMismatch)
      )
    yield MirrorStep.withParams(MirrorState(ext, extras, provenance), params, prevParams)

  /** The sidecar of the content of a V5 file. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._1)

  /** The sidecar and the Haskell bytes of a V5 file, after the magic, the length and the
    * fingerprint have been checked.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (SnapshotSidecar, Bytes)] =
    if content.length < HeaderSize || !content.startsWith(Magic) then
      SnapshotMigration
        .versionOf("snapshot format", content)
        .flatMap(version => FileKind.SnapshotFile.readable("snapshot format", version))
        .flatMap(_ => Left(corrupt(SnapshotReadError.BadMagic)))
    else body(content, Magic.length)

  /** The sidecar and the Haskell bytes of the body that starts at `at`, after its length and
    * fingerprint have been checked.
    */
  private[storage] def body(
      content: Array[Byte],
      at: Int
  ): Either[StorageError, (SnapshotSidecar, Bytes)] =
    if content.length < at + 4 then
      Left(corrupt(SnapshotReadError.SidecarLength(0, content.length)))
    else
      val length = ByteBuffer.wrap(content, at, 4).getInt
      val fingerprint = SnapshotSidecar.fingerprint
      val start = at + 4
      val end = start.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(corrupt(SnapshotReadError.SidecarLength(length, content.length)))
      else
        val found = content.slice(start, start + fingerprint.length)
        if !java.util.Arrays.equals(found, fingerprint) then
          Left(
            StorageError.VersionMismatch(
              "snapshot sidecar schema",
              found.map(b => f"${b & 0xff}%02x").mkString,
              fingerprint.map(b => f"${b & 0xff}%02x").mkString
            )
          )
        else
          Codec
            .decode[SnapshotSidecar](
              content.slice(start + fingerprint.length, end.toInt)
            )(using SnapshotSidecar.codec)
            .leftMap(corrupt)
            .map(_ -> Bytes.wrap(content).drop(end.toInt))

  /** The ledger state with the node's UTxO set put back where the Haskell section has `map(0)`. */
  private def withUtxo(ext: ExtLedgerState, utxo: Utxo): Either[StorageError, ExtLedgerState] =
    ext.ledgerState.state match
      case _: EraLedgerState.ByronCurrent => Left(corrupt(SnapshotReadError.ByronLedgerInShelley))
      case i: EraLedgerState.InShelleyEra[e] =>
        val tables = ShelleyLedgerState
          .nes[e]
          .andThen(NewEpochState.es[e])
          .andThen(EpochState.ls[e])
          .andThen(LedgerState.utxo[e])
          .andThen(UTxOState.utxo[e])
        val state = EraLedgerState.InShelleyEra(i.era, tables.replace(UtxoTables(utxo))(i.state))
        Telescope
          .make(ext.ledgerState.past, ext.ledgerState.current.copy(state = state))
          .leftMap(e => corrupt(e))
          .map(telescope => ext.copy(ledgerState = telescope))
