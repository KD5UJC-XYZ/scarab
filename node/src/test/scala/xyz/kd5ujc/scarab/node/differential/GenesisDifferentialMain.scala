// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path as FsPath
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent}
import xyz.kd5ujc.scarab.ledger.{Block, NodeError}
import xyz.kd5ujc.scarab.node.{NodeRules, NodeSetup, VerificationCache}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.{ImmutableReader, RocksUtxoStore, UtxoStore}

/** The differential harness over a whole chain from genesis (design `ledger-mirror-step2.md` §2.5,
  * the jump gate "genesis to tip with `--rules differential` ", here as a replay of the blocks of a
  * recorded sync): every block of the ImmutableDB of a Scala run, up to a slot, through the old
  * rules and the new ones side by side, with the states compared at the sampling points and the
  * ticks at every boundary. It does what `follow --rules differential` does, from disk and with no
  * network, so a divergence at the preprod epochs 187 to 193 (648,752 committee votes) is found in
  * the time of a replay. Not a test: the input is a recorded sync, which is not in the repository
  * (the corpus is only read: the blocks are read without opening the database).
  *
  * {{{
  * java -Xmx16g -cp <test classpath> xyz.kd5ujc.scarab.node.differential.GenesisDifferentialMain <db> <genesis-dir> <end slot> [sampling-blocks] [scratch] [harness-from-slot]
  * }}}
  *
  * With `harness-from-slot`, the blocks before it run on the old rules alone (a third of the time)
  * and the harness takes over there, seeding the new side from the old state.
  *
  * Exits non-zero on a divergence.
  */
object GenesisDifferentialMain:
  private final case class Progress(pair: Paired, blocks: Long, epoch: BigInt)

  def main(args: Array[String]): Unit =
    val db = Path.of(args(0))
    val setup = NodeSetup
      .fromDirectory(Path.of(args(1)))
      .fold(e => sys.error(e.message), identity)
    val endSlot = BigInt(args(2))
    val sampling = args.lift(3).fold(Sampling.Default)(n => Sampling.Boundaries(n.toLong))
    val scratch =
      args.lift(4).map(Path.of(_)).getOrElse(Files.createTempDirectory("genesis-differential"))
    val from = args.lift(5).fold(BigInt(0))(BigInt(_))
    val cache = VerificationCache.unsafe()
    val praos = setup.praos.copy(verdicts = cache)
    given xyz.kd5ujc.scarab.node.read.ChainRead[xyz.kd5ujc.scarab.consensus.NodeState] =
      xyz.kd5ujc.scarab.node.read.ChainRead.nodeState(setup.mirrorEnv())

    val program = Handle
      .allow[NodeError] {
        Handle
          .allow[Divergence] {
            RocksUtxoStore
              .resource[IO](FsPath.fromNioPath(Files.createTempDirectory(scratch, "utxo")))
              .use { store =>
                for
                  eraStarts <- Ref.of[IO, Map[Era, BigInt]](Map.empty)
                  diverged <- Ref.of[IO, Long](0L)
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
                    NewRules.current[IO](Some(store)),
                    eraStarts.get.map(setup.mirrorEnv),
                    sampling,
                    Policy.Reseed,
                    Repro.none[IO],
                    {
                      case Event.Diverged(d, _) =>
                        diverged.update(_ + 1) *>
                          IO.println(s"DIVERGED ${cats.Show[Divergence].show(d)}")
                      case _ => IO.unit
                    },
                    Some((s, h) => NodeRules.tick(s, h, praos, setup.globals, setup.byronRules))
                  )
                  began <- IO.monotonic
                  step = (progress: Progress, raw: xyz.kd5ujc.scarab.codec.Bytes) =>
                    val header = Block
                      .decode(raw, setup.geometry.byron.epochSlots)
                      .fold(err => sys.error(err.message), _.header)
                    for
                      next <-
                        if header.slot < from then
                          old(progress.pair.old, header, raw).map(
                            Paired.of(
                              _,
                              Some(
                                BlockRef(
                                  header.slot,
                                  header.hash,
                                  header.blockNo,
                                  header.isBoundary
                                )
                              )
                            )
                          )
                        else harness(progress.pair, header, raw)
                      _ <- UtxoStore.advance(store, next.old.ledger.utxo)
                      at = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
                      entered = NodeRules
                        .observe(setup.geometry)(progress.pair.old, next.old, at)
                        .collect { case FollowerEvent.EraEntered(_, to, _, epoch) => to -> epoch }
                      _ <- entered.traverse_((to, epoch) =>
                        eraStarts.update(_.updatedWith(to)(_.orElse(Some(epoch))))
                      )
                      epoch = next.old.ledger.epoch
                      _ <-
                        if epoch == progress.epoch then IO.unit
                        else
                          (harness.stats, IO.monotonic).tupled.flatMap { (s, t) =>
                            IO.println(
                              s"epoch $epoch: ${progress.blocks + 1} blocks, ${(t - began).toSeconds} s, " +
                                s"${s.failures} divergences, ${s.sampled} samples, ${s.ticks.size} ticks"
                            )
                          }
                    yield Progress(next, progress.blocks + 1, epoch)
                  batches = ImmutableReader
                    .blocks(db.resolve("immutable"), BigInt(-1), endSlot)
                    .grouped(64)
                    .map(_.toVector)
                  end <- Stream
                    .fromIterator[IO](batches, 1)
                    .evalScan(Progress(Paired.of(setup.start), 0L, BigInt(-1))) {
                      (progress, batch) =>
                        cache.warm[IO](batch.map(_.raw), praos, 4) *>
                          batch.foldLeftM(progress)((p, b) => step(p, b.raw))
                    }
                    .compile
                    .lastOrError
                  _ <- IO.println(s"${end.blocks} blocks to slot $endSlot")
                  stats <- harness.stats
                  _ <- IO.println(cats.Show[Stats].show(stats))
                  failures <- diverged.get
                yield failures
              }
          }
          .rescue(d => IO.raiseError(RuntimeException(s"halted: ${d.toString}")))
      }
      .rescue(e => IO.raiseError(RuntimeException(e.message)))
    if program.unsafeRunSync() > 0 then sys.exit(1)
