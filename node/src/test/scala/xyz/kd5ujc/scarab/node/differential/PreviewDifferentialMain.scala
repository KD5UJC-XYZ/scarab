// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.io.file.Path as FsPath
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import org.rocksdb.{Options, RocksDB}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, NodeState}
import xyz.kd5ujc.scarab.ledger.{Block, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.state.{Difference, firstDifference}
import xyz.kd5ujc.scarab.node.{LegacySnapshots, MirrorRun, NodeRules, NodeSetup, VerificationCache}
import xyz.kd5ujc.scarab.storage.{ImmutableReader, RocksUtxoStore, StateSnapshot, UtxoStore}
import xyz.kd5ujc.scarab.ledger.NodeError

/** The differential harness over the real Preview chain (design `ledger-mirror-step2.md` §2.5,
  * J2.2): from the Scala node's snapshot at the end of epoch `e` and the UTxO checkpoint taken with
  * it, replay the blocks of the epoch that follows out of the ImmutableDB of the same run, with the
  * old rules alone and with the old and the new side by side (the new side is not ported: it is
  * seeded from the old one at the sampling points), and compare the state at the end with the
  * snapshot of epoch `e + 1`. It reports the divergences (none by construction while the new side
  * is not ported), the replay time with and without the harness, and what the conversion cost. Not
  * a test: the input is the recorded K1 sync, which is not in the repository, and the corpus is
  * only read (the UTxO checkpoint is copied into a scratch directory, the blocks are read without
  * opening the database).
  *
  * {{{
  * java -Xmx16g -cp <test classpath> xyz.kd5ujc.scarab.node.differential.PreviewDifferentialMain <db> [stride] [genesis-dir] [offset[,offset...]] [scratch]
  * }}}
  *
  * `<db>` is the `db` directory of the K1 Scala Preview run (`snapshots`, `immutable`,
  * `utxo/checkpoints`). Every `stride` -th snapshot, from `offset` (several, comma separated: the
  * starts of single epochs, with a stride larger than the corpus), is the start of a replay (stride
  * 72 is about twenty epochs of the 1,451). Exits non-zero on any divergence or on a final state
  * that is not the snapshot's.
  */
object PreviewDifferentialMain:
  private final case class Epoch(
      from: Path,
      to: Path,
      blocks: Int,
      baselineMillis: Long,
      differentialMillis: Long,
      stats: Stats,
      agrees: Option[Difference],
      stateAgrees: Boolean
  )

  private val SnapshotFile = """snapshot-(\d{20})-(\d+)\.bin""".r

  /** Loads the UTxO checkpoint of a snapshot into `store`. The checkpoints of the K1 run were
    * written before the key of the store became `txid ++ be16 ix` (K6, 2026-10-14): theirs is
    * `txid ++ be32 ix`, so the checkpoint is read as the files it is (RocksDB, read only) and its
    * entries are written through the store of this build.
    */
  private def seed(store: UtxoStore[IO], checkpoint: Path): IO[Unit] =
    IO.blocking {
      RocksDB.loadLibrary()
      val options = new Options()
      val db = RocksDB.openReadOnly(options, checkpoint.toString)
      val it = db.newIterator()
      try
        it.seekToFirst()
        var stamp = -1L
        val batches = Vector.newBuilder[Vector[(TxIn, Option[TxOut])]]
        var batch = Vector.newBuilder[(TxIn, Option[TxOut])]
        var n = 0
        while it.isValid do
          val key = it.key()
          if key.length == 1 then stamp = java.nio.ByteBuffer.wrap(it.value()).getLong
          else if key.length == 36 then
            val index = java.nio.ByteBuffer.wrap(key, 32, 4).getInt.toLong & 0xffffffffL
            val in = TxIn(Bytes.fromArray(java.util.Arrays.copyOfRange(key, 0, 32)), BigInt(index))
            UtxoStore.Coding
              .decodeValue(it.value())
              .fold(e => sys.error(s"${in.render}: $e"), out => batch += in -> Some(out))
            n += 1
            if n % 100000 == 0 then
              batches += batch.result()
              batch = Vector.newBuilder
          it.next()
        batches += batch.result()
        (batches.result(), stamp)
      finally
        it.close()
        db.close()
        options.close()
    }.flatMap((batches, stamp) => batches.traverse_(store.write(_, stamp)))

  /** The state as a comparison of two runs sees it: the UTxO is a handle over a RocksDB-backed set
    * whose bookkeeping (what was flushed, the diff) depends on when snapshots were taken, so it is
    * compared by what it holds (the counts and the stake maps); the snapshots of the K1 run are of
    * an earlier build, whose previous parameters in the first epoch of Conway lacked the Conway
    * fields ([[PreviousBuild]]).
    */
  private def comparable(s: NodeState): NodeState =
    val earlier = PreviousBuild.withoutConwayFields(s)
    val u = earlier.ledger.utxo
    earlier.copy(ledger =
      earlier.ledger.copy(utxo =
        Utxo.empty.copy(stake = u.stake, ptrStake = u.ptrStake, coin = u.coin, count = u.count)
      )
    )

  def main(args: Array[String]): Unit =
    val db = Path.of(args(0))
    val stride = args.lift(1).fold(72)(_.toInt)
    val setup = NodeSetup
      .fromDirectory(Path.of(args.lift(2).getOrElse("fixtures/preview/genesis")))
      .fold(e => sys.error(e.message), identity)
    val offsets = args.lift(3).fold(Set(0))(_.split(',').map(_.trim.toInt).toSet)
    val scratch =
      args.lift(4).map(Path.of(_)).getOrElse(Files.createTempDirectory("preview-differential"))
    val env = setup.mirrorEnv()
    val snapshots = Files
      .list(db.resolve("snapshots"))
      .iterator
      .asScala
      .filter(f => SnapshotFile.matches(f.getFileName.toString))
      .toVector
      .sortBy(_.getFileName.toString)
    val starts =
      snapshots.indices.filter(i => offsets.contains(i % stride) && i + 1 < snapshots.size)
    println(
      s"${snapshots.size} snapshots, ${starts.size} replays (stride $stride, offsets ${offsets.toList.sorted.mkString(",")})"
    )

    val cache = VerificationCache.unsafe()
    val praos = setup.praos.copy(verdicts = cache)

    def read(file: Path) =
      LegacySnapshots.read(file, env.forks).fold(e => sys.error(e.message), identity)

    /** Replays the blocks after `start` up to `end`, from the UTxO checkpoint of `start`, with the
      * rules `rules` builds over the store; returns the final pair and the number of blocks.
      */
    def replay(
        startFile: Path,
        start: StateSnapshot[NodeState],
        end: BlockRef,
        differential: Boolean
    )(using Handle[IO, NodeError], Raise[IO, Divergence]): IO[(Paired, Int, Stats)] =
      val stem = startFile.getFileName.toString.stripSuffix(".bin")
      val checkpoint = db.resolve("utxo").resolve("checkpoints").resolve(stem)
      RocksUtxoStore
        .resource[IO](FsPath.fromNioPath(Files.createTempDirectory(scratch, "utxo")))
        .use { store =>
          for
            _ <- seed(store, checkpoint)
            events <- Ref.of[IO, Vector[Event]](Vector.empty)
            blocks = ImmutableReader
              .blocks(db.resolve("immutable"), start.anchor.fold(BigInt(-1))(_.slot), end.slot)
              .toVector
            old = NodeRules[IO](
              praos,
              setup.globals,
              setup.byronRules,
              utxoStore = Some(store),
              afterBlock = Some((_, s) =>
                IO.delay(cache.noteEpochNonce(s.ledger.epoch, s.protocol.epochNonce))
              )
            )
            harness <- DifferentialRules.create[IO](
              old,
              NewRules.current[IO],
              IO.pure(env),
              Sampling.Default,
              Policy.Reseed,
              Repro.none[IO],
              e =>
                events.update(_ :+ e) *> (e match
                  case Event.Diverged(d, _) =>
                    IO.println(s"  DIVERGED ${cats.Show[Divergence].show(d)}")
                  case _ => IO.unit),
              Some((s, h) => NodeRules.tick(s, h, praos, setup.globals, setup.byronRules))
            )
            step = (p: Paired, raw: xyz.kd5ujc.scarab.codec.Bytes) =>
              val header = Block
                .decode(raw, setup.geometry.byron.epochSlots)
                .fold(e => sys.error(e.message), _.header)
              val next =
                if differential then harness(p, header, raw)
                else
                  old(p.old, header, raw).map(
                    Paired.of(
                      _,
                      Some(BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary))
                    )
                  )
              next.flatTap(n => UtxoStore.advance(store, n.old.ledger.utxo))
            last <- blocks
              .grouped(64)
              .toList
              .foldLeftM(Paired.of(start.state, start.anchor)) { (pair, batch) =>
                cache.warm[IO](batch.map(_.raw), praos, 4) *>
                  batch.toList.foldLeftM(pair)((p, b) => step(p, b.raw))
              }
            stats <- harness.stats
          yield (last, blocks.size, stats)
        }

    val program = Handle
      .allow[NodeError] {
        Handle
          .allow[Divergence] {
            // each epoch reads its two snapshots when it runs (not when the list of epochs is built:
            // at stride 4 the snapshots of 363 epochs would be in memory at once)
            starts.toList.traverse { i =>
              val (from, to) = (snapshots(i), snapshots(i + 1))
              for
                snapshotsOfEpoch <- IO.blocking((read(from), read(to)))
                (start, target) = snapshotsOfEpoch
                end = target.anchor.getOrElse(sys.error(s"$to has no anchor"))
                t0 <- IO.monotonic
                baseline <- replay(from, start, end, differential = false)
                t1 <- IO.monotonic
                run <- replay(from, start, end, differential = true)
                t2 <- IO.monotonic
                (paired, count, stats) = run
                // the end state is the snapshot's, up to the UTxO's bookkeeping
                same = comparable(paired.old) == comparable(target.state)
                where =
                  if same then None
                  else
                    val mirrored =
                      (s: NodeState) => MirrorRun.run(env)(_.toMirror(Some(end), s)).map(_.value)
                    (mirrored(comparable(paired.old)), mirrored(comparable(target.state))) match
                      case (Right(a), Right(b)) => a.firstDifference(b)
                      case _                    => None
                row = Epoch(
                  from,
                  to,
                  count,
                  (t1 - t0).toMillis,
                  (t2 - t1).toMillis,
                  stats,
                  where,
                  same && baseline._1.old == paired.old
                )
                _ <- IO.println(report(row, start.state.ledger.epoch))
              yield row
            }
          }
          .rescue(d => IO.raiseError(RuntimeException(s"halted: ${d.toString}")))
      }
      .rescue(e => IO.raiseError(RuntimeException(e.message)))
    val rows = program.unsafeRunSync()
    summary(rows)
    if rows.exists(r => r.stats.failures > 0 || !r.stateAgrees) then sys.exit(1)

  private def report(r: Epoch, epoch: BigInt): String =
    val toMirror = r.stats.samples.map(_.toMirrorNanos).sum / 1000000
    s"epoch $epoch -> ${epoch + 1}: ${r.blocks} blocks; replay ${r.baselineMillis} ms alone, " +
      s"${r.differentialMillis} ms differential; ${r.stats.sampled} samples, toMirror $toMirror ms; " +
      s"${r.stats.ticks.size} ticks compared (old ${r.stats.ticks.map(_.oldNanos).sum / 1000000} ms, " +
      s"new ${r.stats.ticks.map(_.newNanos).sum / 1000000} ms, seed ${r.stats.ticks.map(_.seedNanos).sum / 1000000} ms); " +
      s"${r.stats.failures} divergences; end state ${
          if r.stateAgrees then "= the snapshot's"
          else s"DIFFERS ${r.agrees.fold("")(d => d.path.render)}"
        }"

  private def summary(rows: List[Epoch]): Unit =
    val samples = rows.flatMap(_.stats.samples).toVector
    val toMirror = samples.map(_.toMirrorNanos).sorted
    val perEpoch = rows.map(_.stats.samples.map(_.toMirrorNanos).sum / 1000000)
    println(
      s"""${rows.size} epochs replayed, ${rows
          .map(_.blocks)
          .sum} blocks, ${rows.map(_.stats.failures).sum} divergences
         |  end states equal to the snapshots: ${rows.count(_.stateAgrees)} of ${rows.size}
         |  replay alone ${rows
          .map(_.baselineMillis)
          .sum} ms, with the harness ${rows.map(_.differentialMillis).sum} ms
         |  conversions: ${samples.size}; toMirror ms median ${Stats.quantile(
          toMirror,
          0.5
        ) / 1000000}, p99 ${Stats.quantile(toMirror, 0.99) / 1000000}, max ${toMirror.lastOption
          .getOrElse(0L) / 1000000}
         |  conversion per epoch ms: ${perEpoch.sorted
          .lift(perEpoch.size / 2)
          .getOrElse(0L)} median, ${perEpoch.maxOption.getOrElse(0L)} max
         |  not ported: ${rows
          .flatMap(_.stats.notPorted.toList)
          .groupMapReduce(_._1)(_._2)(_ + _)
          .toList
          .sortBy(_._1._1.ordinal)
          .map((k, n) => s"${k._1}/${k._2}=$n")
          .mkString(", ")}""".stripMargin
    )
