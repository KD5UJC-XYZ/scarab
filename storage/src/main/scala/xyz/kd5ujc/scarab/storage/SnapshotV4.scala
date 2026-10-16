// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.data.Kleisli
import cats.effect.Sync
import cats.mtl.Ask
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.syntax.all.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ConsensusExtras, NodeState}
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  ExtLedgerState,
  HeaderState,
  MirrorExtras,
  MirrorState,
  ShelleyLedgerState,
  StateMirror,
  StateMirrorError,
  Telescope
}
import xyz.kd5ujc.scarab.ledger.{
  FileKind,
  PoolParams,
  ProtocolParams,
  Snapshot,
  Snapshots,
  StorageError,
  Nonce,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.state.{
  VocabError,
  EpochState,
  Gap,
  LedgerState,
  NewEpochState,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile

/** The snapshot of format version 4, the Haskell ledger state with a sidecar:
  *
  * {{{
  * "SCRBSNP4" | sidecar length u32 | sidecar | Haskell state bytes
  * sidecar = schema fingerprint[8] | Codec of SnapshotSidecar
  * }}}
  *
  * The Haskell state bytes are the `state` file of the reference (`[1, ExtLedgerState]`, written by
  * [[HaskellLedgerFile]]) of the node's state through [[StateMirror]], less the UTxO, whose entries
  * stay in the RocksDB store (the file has `map(0)` there, as the reference's `state` file does).
  * The chain still in Byron has no Haskell section: the sidecar holds the node state whole. The
  * checksum that closes the file is the store's ([[FileLedgerSnapshots]]).
  *
  * Version 3 differed in the sidecar only: it held the node state as it was before the
  * chain-dependent state became the Haskell one (J1.5). Files of versions 1 to 3 are brought here
  * by [[SnapshotMigration]] (see [[migrated]]).
  *
  * The mirror's environment is asked for at each write and read, so the eras the node has seen
  * begin are those of that moment.
  */
object SnapshotV4:
  val Magic: Array[Byte] = "SCRBSNP4".getBytes("US-ASCII")

  /** Magic and the length of the sidecar. */
  private val HeaderSize = Magic.length + 4

  private type Pure[A] = Either[StateMirrorError, A]

  /** The mirror run purely: the environment is read from a `Kleisli` and a refusal ends the
    * computation as a `Left`.
    */
  private type M[A] = Kleisli[Pure, ConsensusMirrorEnv, A]
  private val mirror: StateMirror[M] = StateMirror[M]

  def format[F[_]: Sync](using env: Ask[F, ConsensusMirrorEnv]): SnapshotFormat[F, NodeState] =
    new SnapshotFormat[F, NodeState]:
      def encode(snapshot: StateSnapshot[NodeState]): F[Either[SnapshotRefusal, Array[Byte]]] =
        env.ask[ConsensusMirrorEnv].flatMap(e => Sync[F].blocking(written(e, snapshot)))

      def decode(content: Array[Byte]): F[Either[StorageError, StateSnapshot[NodeState]]] =
        env.ask[ConsensusMirrorEnv].flatMap(e => Sync[F].blocking(read(e, content)))

  /** The state as a V4 snapshot returns it: [[StateMirror.normalised]] (what the mirror is lossless
    * up to) and two differences of representation that the Haskell encoding has no room for and
    * that no rule reads. A pool registered with the empty relay list (`80`, the item as the
    * certificate gave it) reads back with no relays (`Bytes.empty`: the Haskell codec writes both
    * as the empty array and reads one back); and the enacted parameters of the DRep pulser, which
    * the node keeps as full parameters, have no field in Conway for the lovelace per UTxO word, the
    * minimum UTxO value, the decentralisation or the extra entropy.
    */
  def canonical(env: ConsensusMirrorEnv, s: NodeState): NodeState =
    val n = StateMirror.normalised(env, s)
    if StateMirror.eraOf(env, n) == Era.Byron then n
    else
      val ledger = n.ledger
      val emptySequence = Bytes.fromArray(Array(0x80.toByte))
      def bare(p: PoolParams): PoolParams =
        if p.relays == emptySequence then p.copy(relays = Bytes.empty) else p
      def conway(p: ProtocolParams): ProtocolParams = p.copy(
        minUTxOValue = 0,
        coinsPerUTxOWord = 0,
        d = Rational.zero,
        extraEntropy = Nonce.Neutral
      )
      def snapshot(x: Snapshot): Snapshot = x.copy(pools = x.pools.view.mapValues(bare).toMap)
      n.copy(ledger =
        ledger.copy(
          pools = ledger.pools.view.mapValues(p => p.copy(params = bare(p.params))).toMap,
          futurePools = ledger.futurePools.view.mapValues(bare).toMap,
          snapshots = Snapshots(
            snapshot(ledger.snapshots.mark),
            snapshot(ledger.snapshots.set),
            snapshot(ledger.snapshots.go),
            ledger.snapshots.fee
          ),
          poolDistribution = snapshot(ledger.poolDistribution),
          gov = ledger.gov.copy(pulsed = ledger.gov.pulsed.map { (snapshot, ratify) =>
            val enact = ratify.enact
            snapshot -> ratify.copy(enact =
              enact.copy(params = conway(enact.params), prevParams = conway(enact.prevParams))
            )
          })
        )
      )

  // ---- write

  /** What a migration carries over from the file it replaces: the sidecar's `observed` and `gaps`
    * stay as they were written (a migration never invents data).
    */
  private final case class Carried(observed: Map[Era, BigInt], gaps: Set[Gap])

  /** The content of the snapshot, after reading it back: a snapshot that would not restore to the
    * state it was taken of (its [[canonical]] form) is refused, so that a file is never written
    * that a restart cannot use. The check costs about one read.
    */
  private def written(
      env: ConsensusMirrorEnv,
      snapshot: StateSnapshot[NodeState],
      carried: Option[Carried] = None
  ): Either[SnapshotRefusal, Array[Byte]] =
    for
      content <- encoded(env, snapshot, carried)
      back <- read(env, content).leftMap(SnapshotRefusal.WouldNotRead(_))
      want = canonical(env, snapshot.state)
      _ <- Either.cond(
        back.anchor == snapshot.anchor && back.state == want,
        (),
        SnapshotRefusal.WouldDiffer(differing(back.state, want))
      )
    yield content

  /** The paths of the fields, down to the first collection or leaf, in which two values differ (at
    * most a few, for the log).
    */
  private def differing(a: Product, b: Product): List[String] =
    def go(x: Any, y: Any, at: String, depth: Int): List[String] = (x, y) match
      case _ if x == y => Nil
      case (p: Product, q: Product)
          if depth > 0 && p.productArity == q.productArity && p.productArity > 0 &&
            !p.isInstanceOf[Iterable[?]] && p.getClass == q.getClass =>
        p.productElementNames.toList
          .zip(p.productIterator.zip(q.productIterator).toList)
          .flatMap { case (n, (u, v)) => go(u, v, s"$at.$n", depth - 1) }
      case (p: Map[?, ?], q: Map[?, ?]) if depth > 0 =>
        // (a message for the log: the maps are only looked into)
        val (m, n) = (p.asInstanceOf[Map[Any, Any]], q.asInstanceOf[Map[Any, Any]])
        (m.keySet ++ n.keySet).toList.flatMap { k =>
          (m.get(k), n.get(k)) match
            case (Some(u), Some(v)) => go(u, v, s"$at[${k.toString.take(12)}]", depth - 1)
            case _                  => List(s"$at[${k.toString.take(12)}]")
        }
      case _ => List(s"$at: ${x.toString.take(30)} // ${y.toString.take(30)}")
    go(a, b, "state", 7).take(8)

  private def encoded(
      env: ConsensusMirrorEnv,
      snapshot: StateSnapshot[NodeState],
      carried: Option[Carried]
  ): Either[SnapshotRefusal, Array[Byte]] =
    val state = snapshot.state
    // the telescopes of the Haskell section are built under the eras the file being replaced saw
    val seen = carried.fold(env)(c => env.copy(observed = env.observed ++ c.observed))
    val era = StateMirror.eraOf(seen, state)
    def sidecar(chain: SnapshotSidecar.Chain, gaps: Set[Gap]) =
      SnapshotSidecar(
        snapshot.anchor,
        carried.fold(env.observed)(_.observed),
        carried.fold(gaps)(_.gaps),
        chain
      )
    if era == Era.Byron then
      Right(
        framed(
          sidecar(
            SnapshotSidecar.Chain.InByron(state.ledger, state.header.chainDep.state, state.extras),
            Set.empty
          ),
          Array.emptyByteArray
        )
      )
    else
      for
        mirrored <- mirror
          .toMirror(snapshot.anchor, state)
          .run(seen)
          .leftMap(SnapshotRefusal.Mirror(_))
        extras <- mirrored.value.extras match
          case shelley: MirrorExtras.InShelley => Right(shelley)
          case _: MirrorExtras.InByron =>
            Left(SnapshotRefusal.Mirror(StateMirrorError.ExtrasMismatch(era)))
        file <- HaskellLedgerFile
          .of(mirrored.value.ext)
          .leftMap(e => SnapshotRefusal.Mirror(StateMirrorError.Telescope(e)))
        haskell <- HaskellLedgerFile.encode(file).leftMap(SnapshotRefusal.NotWritable(_))
      yield framed(
        sidecar(
          SnapshotSidecar.Chain.InShelley(
            state.ledger.utxo,
            state.ledger.params,
            state.ledger.prevParams,
            extras
          ),
          mirrored.gaps
        ),
        haskell.toArray
      )

  private def framed(sidecar: SnapshotSidecar, haskell: Array[Byte]): Array[Byte] =
    val body = SnapshotSidecar.fingerprint ++ Codec.encode(sidecar)(using SnapshotSidecar.codec)
    val out = new ByteArrayOutputStream(HeaderSize + body.length + haskell.length)
    out.write(Magic)
    out.write(ByteBuffer.allocate(4).putInt(body.length).array())
    out.write(body)
    out.write(haskell)
    out.toByteArray

  // ---- read

  private def corrupt(reason: String): StorageError = StorageError.Corrupt("snapshot", reason)

  private def read(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[StorageError, StateSnapshot[NodeState]] =
    sections(content).flatMap { (sidecar, haskell) =>
      val state = sidecar.chain match
        case SnapshotSidecar.Chain.InByron(ledger, pending, extras) =>
          Either.cond(
            haskell.size == 0,
            NodeState(HeaderState.live(pending), ledger, extras),
            corrupt("a Byron snapshot with a ledger state")
          )
        case shelley: SnapshotSidecar.Chain.InShelley =>
          val seen = env.copy(observed = env.observed ++ sidecar.observed)
          restoredShelley(seen, sidecar.anchor, haskell, shelley)
      state.map(StateSnapshot(sidecar.anchor, _))
    }

  /** The sidecar of the content of a V4 file. */
  def sidecarOf(content: Array[Byte]): Either[StorageError, SnapshotSidecar] =
    sections(content).map(_._1)

  /** The sidecar and the Haskell bytes of a file, after the magic, the length and the fingerprint
    * have been checked.
    */
  private def sections(content: Array[Byte]): Either[StorageError, (SnapshotSidecar, Bytes)] =
    val kind = FileKind.SnapshotFile
    if content.length < HeaderSize || !content.startsWith(Magic) then
      val older =
        legacy.SnapshotV2
          .numberOf(content)
          .map(n => s"${new String(legacy.SnapshotV2.Magic, "US-ASCII")} v$n")
          .orElse(
            Option.when(content.startsWith(legacy.SnapshotV3.Magic))(
              new String(legacy.SnapshotV3.Magic, "US-ASCII")
            )
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

  private def restoredShelley(
      env: ConsensusMirrorEnv,
      anchor: Option[BlockRef],
      haskell: Bytes,
      shelley: SnapshotSidecar.Chain.InShelley
  ): Either[StorageError, NodeState] =
    for
      file <- HaskellLedgerFile.decode(haskell).leftMap(e => corrupt(e.render))
      decoded <- file.ext.toRight(corrupt("the ledger state is a Byron item"))
      ext <- withUtxo(decoded, shelley.utxo)
      restored <- mirror
        .fromMirror(MirrorState(ext, shelley.extras))
        .run(env)
        .leftMap(e => corrupt(s"the ledger state does not retract: ${e.show}"))
      _ <- Either.cond(
        restored._1 == anchor,
        (),
        corrupt("the tip of the ledger state is not the anchor of the snapshot")
      )
    yield canonical(
      env,
      restored._2.copy(ledger =
        restored._2.ledger.copy(params = shelley.params, prevParams = shelley.prevParams)
      )
    )

  // ---- the migration of the older formats

  /** The content of the older snapshot `content` (version 1 or 2 of `CSLSNAP1`, or `SCRBSNP3`;
    * without the checksum that closes the file), as a V4 snapshot.
    *
    * Versions 1 and 2 are decoded with the frozen reader and brought to the live state. Version 3
    * is the Haskell section as it is and the old sidecar, whose extras are converted: nothing is
    * recomputed that the file holds, and the gaps and the eras it saw begin are carried to the new
    * sidecar. A state that the live types cannot hold is [[SnapshotRefusal.Unreadable]], and the
    * file is left as it was.
    */
  def migrated(
      env: ConsensusMirrorEnv,
      content: Array[Byte]
  ): Either[SnapshotRefusal, Array[Byte]] =
    def unreadable[A](e: Either[StorageError, A]) = e.leftMap(SnapshotRefusal.Unreadable(_))
    def vocab[A](e: Either[VocabError, A]) =
      e.leftMap(v => SnapshotRefusal.Unreadable(corrupt(s"a value the live types cannot hold: $v")))
    if content.startsWith(legacy.SnapshotV3.Magic) then
      for
        parts <- unreadable(legacy.SnapshotV3.sections(content))
        sidecar = parts._1
        carried = Carried(sidecar.observed, sidecar.gaps)
        seen = env.copy(observed = env.observed ++ sidecar.observed)
        state <- sidecar.chain match
          case legacy.SnapshotSidecar.Chain.InByron(old) =>
            vocab(legacy.Upgrade.node(old, seen.forks, sidecar.anchor))
          case legacy.SnapshotSidecar.Chain.InShelley(utxo, params, prevParams, extras) =>
            for
              byron <- vocab(legacy.Upgrade.byron(extras.byron))
              consensus = ConsensusExtras(
                extras.boot,
                byron,
                legacy.Upgrade.protocol(extras.protocol)
              )
              restored <- unreadable(
                restoredShelley(
                  seen,
                  sidecar.anchor,
                  Bytes.wrap(parts._2),
                  SnapshotSidecar.Chain.InShelley(
                    utxo,
                    params,
                    prevParams,
                    MirrorExtras.InShelley(extras.ledger, consensus)
                  )
                )
              )
            yield restored
        out <- written(env, StateSnapshot(sidecar.anchor, state), Some(carried))
      yield out
    else
      for
        old <- unreadable(legacy.SnapshotV2.decodeNode(content))
        state <- vocab(legacy.Upgrade.node(old.state, env.forks, old.anchor))
        out <- written(env, StateSnapshot(old.anchor, state))
      yield out

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
