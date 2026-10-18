// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.Kleisli
import cats.effect.Sync
import cats.mtl.Ask
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
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
  NodeExtras,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.protocol.Era
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
  * versions 1 to 4 are brought here by [[SnapshotMigration]] (see [[migrated]]).
  */
object SnapshotV5:
  val Magic: Array[Byte] = "SCRBSNP5".getBytes("US-ASCII")

  /** Magic and the length of the sidecar. */
  private val HeaderSize = Magic.length + 4

  private type Pure[A] = Either[StateMirrorError, A]

  /** The conversion of the flat state of the old files, run purely: the environment is read from a
    * `Kleisli` and a refusal ends the computation as a `Left`.
    */
  private type M[A] = Kleisli[Pure, ConsensusMirrorEnv, A]
  private val flat: legacy.StateMirror[M] = legacy.StateMirror[M]

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
        env.ask[ConsensusMirrorEnv].flatMap(e => Sync[F].blocking(read(e, content)))

  /** The gaps of a state the era-indexed rules made: the pool ranking (`esNonMyopic`) is not
    * computed, nor the transition count of the consensus state. The rewards are attributed, the
    * delegators exact, the pulser the rules' own.
    */
  private val MirrorGaps: Set[Gap] = Set(Gap.NonMyopic, Gap.ShelleyTransition)

  /** What a migration carries over from the file it replaces: the sidecar's `observed` and `gaps`
    * stay as they were written (a migration never invents data).
    */
  private final case class Carried(observed: Map[Era, BigInt], gaps: Set[Gap])

  private def written(
      env: ConsensusMirrorEnv,
      snapshot: StateSnapshot[MirrorState],
      check: SnapshotCheck,
      carried: Option[Carried] = None
  ): Either[SnapshotRefusal, Array[Byte]] =
    encoded(env, snapshot, carried).flatTap { content =>
      check match
        case SnapshotCheck.Structure =>
          wellFormed(content).leftMap(SnapshotRefusal.WouldNotRead(_))
        case SnapshotCheck.Full =>
          for
            back <- read(env, content).leftMap(SnapshotRefusal.WouldNotRead(_))
            again <- encoded(env, back, carried)
            _ <- Either.cond(
              java.util.Arrays.equals(again, content) && back.anchor == snapshot.anchor,
              (),
              SnapshotRefusal.WouldDiffer(
                List("the snapshot written again is not the one written").filter(_ =>
                  !java.util.Arrays.equals(again, content)
                )
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
      snapshot: StateSnapshot[MirrorState],
      carried: Option[Carried]
  ): Either[SnapshotRefusal, Array[Byte]] =
    val m = snapshot.state
    def sidecar(chain: SnapshotSidecar.Chain, gaps: Set[Gap]) =
      SnapshotSidecar(
        snapshot.anchor,
        carried.fold(env.observed)(_.observed),
        carried.fold(gaps)(_.gaps),
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

  private def corrupt(reason: String): StorageError = StorageError.Corrupt("snapshot", reason)

  private def read(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[StorageError, StateSnapshot[MirrorState]] =
    sections(content).flatMap { (sidecar, haskell) =>
      val state: Either[StorageError, MirrorState] = sidecar.chain match
        case SnapshotSidecar.Chain.InByron(pending, extras) =>
          Either
            .cond(haskell.size == 0, (), corrupt("a Byron snapshot with a ledger state"))
            .flatMap { _ =>
              val seen = env.copy(observed = env.observed ++ sidecar.observed)
              ByronMirror
                .of(seen, sidecar.anchor, pending, extras)
                .leftMap(e => corrupt(s"the Byron state does not mirror: ${e.show}"))
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
      file <- HaskellLedgerFile.decode(haskell).leftMap(e => corrupt(e.render))
      decoded <- file.ext.toRight(corrupt("the ledger state is a Byron item"))
      ext <- withUtxo(decoded, utxo)
      _ <- Either.cond(
        StateMirror.anchorOf(ext.headerState.tip) == anchor,
        (),
        corrupt("the tip of the ledger state is not the anchor of the snapshot")
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
      val older =
        legacy.SnapshotV2
          .numberOf(content)
          .map(n => s"${new String(legacy.SnapshotV2.Magic, "US-ASCII")} v$n")
          .orElse(
            List(legacy.SnapshotV3.Magic, legacy.v4.SnapshotV4.Magic)
              .find(content.startsWith(_))
              .map(new String(_, "US-ASCII"))
          )
      older match
        case Some(found) =>
          Left(
            StorageError.VersionMismatch("snapshot format", found, kind.identifier(kind.current))
          )
        case None => Left(corrupt("bad magic or too short"))
    else
      val length = ByteBuffer.wrap(content, Magic.length, 4).getInt
      val fingerprint = SnapshotSidecar.fingerprint
      val end = HeaderSize.toLong + length
      if length < fingerprint.length || end > content.length then
        Left(corrupt(s"sidecar of $length bytes in a file of ${content.length}"))
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

  // ---- the migration of the older formats

  /** The content of the older snapshot `content` (version 1 or 2 of `CSLSNAP1`, or `SCRBSNP3` or
    * `SCRBSNP4`; without the checksum that closes the file), as a V5 snapshot.
    *
    *   - Versions 1 and 2 are decoded with the frozen reader and brought to the flat state of the
    *     old rules, which the frozen conversion turns into the mirror.
    *   - Versions 3 and 4 are the Haskell section, decoded, and a sidecar whose extras are the old
    *     rules' bookkeeping (and, in version 4 in Byron, the flat ledger state): the state is the
    *     mirror of that section, put in the terms of the era-indexed rules while the old
    *     bookkeeping is still at hand ([[legacy.LegacySeed]]: the reward update attributed, the
    *     pulser's parameters without the fields Conway lacks), and then written without it.
    *
    * The gaps and the eras the file saw begin are carried verbatim to the new sidecar: nothing is
    * invented. The result is read back and compared. A state that the live types cannot hold is
    * [[SnapshotRefusal.Unreadable]], and the file is left as it was.
    */
  def migrated(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[SnapshotRefusal, Array[Byte]] =
    def unreadable[A](e: Either[StorageError, A]) = e.leftMap(SnapshotRefusal.Unreadable(_))
    def vocab[A](e: Either[xyz.kd5ujc.scarab.ledger.state.VocabError, A]) =
      e.leftMap(v => SnapshotRefusal.Unreadable(corrupt(s"a value the live types cannot hold: $v")))
    val older: Either[SnapshotRefusal, (StateSnapshot[MirrorState], Carried)] =
      if content.startsWith(legacy.v4.SnapshotV4.Magic) then fromV4(env, content)
      else if content.startsWith(legacy.SnapshotV3.Magic) then fromV3(env, content)
      else
        for
          old <- unreadable(legacy.SnapshotV2.decodeNode(content))
          state <- vocab(legacy.Upgrade.node(old.state, env.forks, old.anchor))
          mirrored <- flat
            .toMirror(old.anchor, state)
            .run(env)
            .leftMap(SnapshotRefusal.Mirror(_))
        yield (StateSnapshot(old.anchor, mirrored.value), Carried(env.observed, mirrored.gaps))
    older.flatMap((snapshot, carried) => written(env, snapshot, SnapshotCheck.Full, Some(carried)))

  private def fromV4(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[SnapshotRefusal, (StateSnapshot[MirrorState], Carried)] =
    def unreadable[A](e: Either[StorageError, A]) = e.leftMap(SnapshotRefusal.Unreadable(_))
    for
      parts <- unreadable(legacy.v4.SnapshotV4.sections(content))
      (sidecar, haskell) = parts
      seen = env.copy(observed = env.observed ++ sidecar.observed)
      state <- sidecar.chain match
        case legacy.v4.SnapshotSidecar.Chain.InByron(_, pending, extras) =>
          unreadable(
            Either
              .cond(haskell.size == 0, (), corrupt("a Byron snapshot with a ledger state"))
              .flatMap(_ =>
                ByronMirror
                  .of(seen, sidecar.anchor, pending, extras)
                  .leftMap(e => corrupt(s"the Byron state does not mirror: ${e.show}"))
              )
          )
        case legacy.v4.SnapshotSidecar.Chain.InShelley(utxo, params, prevParams, extras) =>
          unreadable(
            shelleyState(
              sidecar.anchor,
              haskell,
              utxo,
              params,
              prevParams,
              MirrorExtras.InShelley(NodeExtras(extras.ledger.plutus), extras.consensus)
            ).map(m => legacy.LegacySeed(m, extras.ledger))
          )
    yield (StateSnapshot(sidecar.anchor, state), Carried(sidecar.observed, sidecar.gaps))

  private def fromV3(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[SnapshotRefusal, (StateSnapshot[MirrorState], Carried)] =
    def unreadable[A](e: Either[StorageError, A]) = e.leftMap(SnapshotRefusal.Unreadable(_))
    def vocab[A](e: Either[xyz.kd5ujc.scarab.ledger.state.VocabError, A]) =
      e.leftMap(v => SnapshotRefusal.Unreadable(corrupt(s"a value the live types cannot hold: $v")))
    for
      parts <- unreadable(legacy.SnapshotV3.sections(content))
      sidecar = parts._1
      seen = env.copy(observed = env.observed ++ sidecar.observed)
      state <- sidecar.chain match
        case legacy.SnapshotSidecar.Chain.InByron(old) =>
          for
            node <- vocab(legacy.Upgrade.node(old, seen.forks, sidecar.anchor))
            mirrored <- flat
              .toMirror(sidecar.anchor, node)
              .run(seen)
              .leftMap(SnapshotRefusal.Mirror(_))
          yield mirrored.value
        case legacy.SnapshotSidecar.Chain.InShelley(utxo, params, prevParams, extras) =>
          for
            byron <- vocab(legacy.Upgrade.byron(extras.byron))
            consensus = ConsensusExtras(
              extras.boot,
              byron,
              legacy.Upgrade.protocol(extras.protocol)
            )
            restored <- unreadable(
              shelleyState(
                sidecar.anchor,
                Bytes.wrap(parts._2),
                utxo,
                params,
                prevParams,
                MirrorExtras.InShelley(NodeExtras(extras.ledger.plutus), consensus)
              )
            )
          yield legacy.LegacySeed(restored, extras.ledger)
    yield (StateSnapshot(sidecar.anchor, state), Carried(sidecar.observed, sidecar.gaps))

  /** The ledger state with the node's UTxO set put back where the Haskell section has `map(0)`. */
  private def withUtxo(ext: ExtLedgerState, utxo: Utxo): Either[StorageError, ExtLedgerState] =
    ext.ledgerState.state match
      case _: EraLedgerState.ByronCurrent => Left(corrupt("a Byron ledger state in a Shelley era"))
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
          .leftMap(e => corrupt(s"the telescope: $e"))
          .map(telescope => ext.copy(ledgerState = telescope))
