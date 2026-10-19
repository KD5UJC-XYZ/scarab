// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import xyz.kd5ujc.scarab.codec.DomainError
import cats.effect.Sync
import cats.mtl.Ask
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
  Gap,
  LedgerState,
  NewEpochState,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellState}

/** The snapshot of format version 5, the Haskell ledger state with a sidecar:
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
  */
object SnapshotV5:
  val Magic: Array[Byte] = "SCRBSNP5".getBytes("US-ASCII")

  /** Magic and the length of the sidecar. */
  private val HeaderSize = Magic.length + 4

  /** The format for a chain whose state is the mirror ([[MirrorState]]), writing with `check` (see
    * [[SnapshotCheck]]).
    *
    * With [[SnapshotCheck.Full]] the file is read back and its Haskell section is written again:
    * the bytes must be those written, and the sidecar's parts equal (a pool registered with the
    * empty relay list reads back as one with none, which the state does not tell apart, so the
    * bytes are what is compared, not the state).
    */
  def format[F[_]: Sync](check: SnapshotCheck)(using
      env: Ask[F, ConsensusMirrorEnv]
  ): SnapshotFormat[F, MirrorState] =
    new SnapshotFormat[F, MirrorState]:
      def encode(snapshot: StateSnapshot[MirrorState]): F[Either[SnapshotRefusal, Array[Byte]]] =
        env
          .ask[ConsensusMirrorEnv]
          .flatMap(e => Sync[F].blocking(written(e, snapshot, check)))

      def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[MirrorState]]] =
        env.ask[ConsensusMirrorEnv].flatMap(e => Sync[F].blocking(SnapshotV5.decode(e, content)))

  /** The gaps of a state the era-indexed rules made: the pool ranking (`esNonMyopic`) is not
    * computed, nor the transition count of the consensus state. The rewards are attributed, the
    * delegators exact, the pulser the rules' own.
    */
  private val MirrorGaps: Set[Gap] = Set(Gap.NonMyopic, Gap.ShelleyTransition)

  private def written(
      env: ConsensusMirrorEnv,
      snapshot: StateSnapshot[MirrorState],
      check: SnapshotCheck
  ): Either[SnapshotRefusal, Array[Byte]] =
    encoded(env, snapshot).flatTap { content =>
      check match
        case SnapshotCheck.Structure =>
          wellFormed(content).leftMap(SnapshotRefusal.WouldNotRead(_))
        case SnapshotCheck.Full =>
          for
            back <- decode(env, content).leftMap(SnapshotRefusal.WouldNotRead(_))
            again <- encoded(env, back)
            _ <- Either.cond(
              java.util.Arrays.equals(again, content) && back.anchor == snapshot.anchor,
              (),
              SnapshotRefusal.WouldDiffer(
                List(
                  Option.when(!java.util.Arrays.equals(again, content))(
                    SnapshotDifference.ReencodedBytes
                  ),
                  Option.when(back.anchor != snapshot.anchor)(SnapshotDifference.Anchor)
                ).flatten
              )
            )
          yield ()
    }

  /** The sidecar decodes and the Haskell section (empty in Byron) is one CBOR item under the limits
    * the state is read with: the index of its items is made and dropped.
    */
  private[storage] def wellFormed(content: Array[Byte]): Either[StorageError, Unit] =
    sections(content).flatMap { (_, haskell) =>
      if haskell.size == 0 then Right(())
      else Cbor.decode(haskell, HaskellState.limits).bimap(corrupt, _ => ())
    }

  private def encoded(
      env: ConsensusMirrorEnv,
      snapshot: StateSnapshot[MirrorState]
  ): Either[SnapshotRefusal, Array[Byte]] =
    val m = snapshot.state
    def sidecar(chain: SnapshotSidecar.Chain, gaps: Set[Gap]) =
      SnapshotSidecar(
        snapshot.anchor,
        env.observed,
        gaps,
        chain
      )
    (m.extras, m.ext.ledgerState.state) match
      case (
            MirrorExtras.InByron(pending, boot, protocol),
            EraLedgerState.ByronCurrent(b)
          ) =>
        Right(
          framed(
            sidecar(
              SnapshotSidecar.Chain.InByron(pending, ConsensusExtras(boot, b, protocol)),
              Set.empty
            ),
            Bytes.empty
          )
        )
      case (shelley: MirrorExtras.InShelley, i: EraLedgerState.InShelleyEra[e]) =>
        given xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra[e] = i.era
        val gov = i.state.nes.es.ls.utxo.gov
        for
          file <- HaskellLedgerFile
            .of(m.ext)
            .leftMap(e => SnapshotRefusal.Mirror(StateMirrorError.Telescope(e)))
          haskell <- HaskellLedgerFile.encode(file).leftMap(SnapshotRefusal.NotWritable(_))
        yield framed(
          sidecar(
            SnapshotSidecar.Chain.InShelley(
              i.state.nes.es.ls.utxo.utxo.utxo,
              EraLedger.curPParams(gov),
              Some(EraLedger.prevPParams(gov)),
              shelley
            ),
            MirrorGaps
          ),
          haskell
        )
      case (_, state) =>
        Left(SnapshotRefusal.Mirror(StateMirrorError.ExtrasMismatch(state.era)))

  /** The file content, in one array of its exact size: the header, the sidecar and the Haskell
    * bytes are copied to it once (the Haskell section is a couple of hundred megabytes, and a copy
    * of it is one more humongous allocation for the collector).
    */
  private[storage] def framed(sidecar: SnapshotSidecar, haskell: Bytes): Array[Byte] =
    val body = SnapshotSidecar.fingerprint ++ Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val out = new Array[Byte](HeaderSize + body.length + haskell.size)
    System.arraycopy(Magic, 0, out, 0, Magic.length)
    ByteBuffer.wrap(out, Magic.length, 4).putInt(body.length)
    System.arraycopy(body, 0, out, HeaderSize, body.length)
    haskell.copyTo(out, HeaderSize + body.length)
    out

  // ---- read

  private def corrupt[E <: DomainError](reason: E): StorageError =
    StorageError.Corrupt("snapshot", reason)

  /** Read current-format content without changing its carried state or metadata. */
  def decode(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[StorageError, StateSnapshot[MirrorState]] =
    sections(content).flatMap { (sidecar, haskell) =>
      val state: Either[StorageError, MirrorState] = sidecar.chain match
        case SnapshotSidecar.Chain.InByron(pending, extras) =>
          Either
            .cond(haskell.size == 0, (), corrupt(SnapshotReadError.ByronWithLedgerState))
            .flatMap { _ =>
              val seen = env.copy(observed = env.observed ++ sidecar.observed)
              ByronMirror
                .of(seen, sidecar.anchor, pending, extras)
                .leftMap(e => corrupt(e))
            }
        case shelley: SnapshotSidecar.Chain.InShelley =>
          shelleyState(
            sidecar.anchor,
            haskell,
            shelley.utxo,
            shelley.params,
            shelley.prevParams,
            shelley.extras
          )
      state.map(StateSnapshot(sidecar.anchor, _))
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
      extras: MirrorExtras.InShelley
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
    yield MirrorStep.withParams(MirrorState(ext, extras), params, prevParams)

  /** The sidecar of the content of a V5 file. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._1)

  /** The sidecar and the Haskell bytes of a file, after the magic, the length and the fingerprint
    * have been checked.
    */
  private[storage] def sections(
      content: Array[Byte]
  ): Either[StorageError, (SnapshotSidecar, Bytes)] =
    val kind = FileKind.SnapshotFile
    if content.length < HeaderSize || !content.startsWith(Magic) then
      SnapshotMigration
        .versionOf("snapshot format", content)
        .flatMap(version => kind.readable("snapshot format", version))
        .flatMap(_ => Left(corrupt(SnapshotReadError.BadMagic)))
    else
      val length = ByteBuffer.wrap(content, Magic.length, 4).getInt
      val fingerprint = SnapshotSidecar.fingerprint
      val end = HeaderSize.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(corrupt(SnapshotReadError.SidecarLength(length, content.length)))
      else
        val found = content.slice(HeaderSize, HeaderSize + fingerprint.length)
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
              content.slice(HeaderSize + fingerprint.length, end.toInt)
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
