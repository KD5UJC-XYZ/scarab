// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
import cats.effect.{IO, IOApp}
import cats.mtl.{Ask, Handle}
import cats.syntax.all.*
import fs2.io.file.Path
import io.circe.parser.parse
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path as JPath}
import java.security.MessageDigest
import munit.Assertions
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{ConsensusMirrorEnv, MirrorClock, MirrorStep, Placement}
import xyz.kd5ujc.scarab.ledger.{StorageError, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayBlock
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayBlockRun
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes, Globals, Hfc}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.storage.{FileLedgerSnapshots, RocksUtxoStore, SnapshotCheck, SnapshotV10}

/** Opt-in test-side benchmark. Decoding, store reads, assertions and complete result rendering are
  * outside timing. Every timed call receives an identical immutable historical input. A block is
  * applied as the node applies a Conway block (#85): the state ticked to its slot
  * ([[EraBlock.ticked]]), then Conway's era run on its body as the node builds it
  * ([[MirrorRules.conwayBody]], [[ConwayBlockRun]]), run serially; its clock is the one the node
  * reads ([[MirrorClock.conway]]), of the snapshot's mirror at the block's slot, which the pinned
  * window, within one epoch, does not move.
  */
object PreviewRuleBench extends IOApp.Simple with Assertions:
  private val bean = ManagementFactory.getThreadMXBean match
    case b: com.sun.management.ThreadMXBean => b
    case _                                  => fail("allocation counter unavailable")
  private def checked[E, A](value: Either[E, A]): A = value match
    case Right(a) => a
    case Left(e)  => fail(s"benchmark fixture refused: $e")
  private def hash(value: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(value).map("%02x".format(_)).mkString
  private def fingerprint[A](value: A): String = hash(value.toString.getBytes("UTF-8"))
  private def measure[A](name: String, warmup: Int = 256, batch: Int = 8)(body: => A): A =
    val expected = body
    (0 until warmup).foreach(_ => assert(body == expected, s"warmup result changed: $name"))
    val rows = (0 until 31).map { sample =>
      val allocation = bean.getCurrentThreadAllocatedBytes
      val start = System.nanoTime()
      var result = expected
      var index = 0
      while index < batch do
        result = body
        index += 1
      val elapsed = System.nanoTime() - start
      val bytes = bean.getCurrentThreadAllocatedBytes - allocation
      assert(result == expected, s"sample result changed: $name/$sample")
      println(
        s"SAMPLE $name sample=$sample ns=${elapsed / batch} allocated=${bytes / batch} batch=$batch"
      )
      (elapsed / batch, bytes / batch)
    }
    val ns = rows.map(_._1).sorted
    val bytes = rows.map(_._2).sorted
    println(
      s"METRIC $name median_ns=${ns(15)} min_ns=${ns.head} max_ns=${ns.last} allocated=${bytes(15)}"
    )
    println(s"DIGEST $name ${fingerprint(expected)}")
    expected
  private def readSet(nes: EraNes): Utxo = nes match
    case i: EraNes.InEra[e] => i.nes.es.ls.utxo.utxo.utxo
  private def conway(nes: EraNes): NewEpochState[Conway] = nes match
    case i: EraNes.InEra[e] =>
      i.era match
        case ShelleyBasedEra.SConway => i.nes
        case other                   => fail(s"Conway state required, not $other")
  private def resident(nes: EraNes, fetched: Map[TxIn, TxOut]): EraNes = nes match
    case i: EraNes.InEra[e] =>
      val ls = i.nes.es.ls
      EraNes.InEra(
        i.era,
        i.nes.copy(es =
          i.nes.es.copy(ls =
            ls.copy(utxo = ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo.withResident(fetched))))
          )
        )
      )

  val run: IO[Unit] = Handle
    .allow[StorageError] {
      RocksUtxoStore.resource[IO](Path("/scratch/rocks")).use { store =>
        val setup = checked(NodeSetup.fromDirectory(ReferenceBuild.Default)(JPath.of("/configs")))
        given Ask[IO, ConsensusMirrorEnv] = Ask.const(setup.mirrorEnv())
        val globals = Globals(setup.globals, setup.geometry)
        // This pinned window is entirely Conway/protocol 11; no era transition is synthesized.
        val context = EraBlock.Context(
          globals,
          _ => None,
          0L,
          evaluator = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.default,
          decoder = xyz.kd5ujc.scarab.plutus.ScarabEvaluator.defaultDecoder
        )
        val backend = PlutusBackend.default
        val name = "snapshot-00000000000125231955-4753556"
        for
          raw <- IO.blocking(Files.readAllBytes(JPath.of(s"/native/$name.bin")))
          _ <- IO.println(s"INPUT native sha256=${hash(raw)}")
          snapshot <- SnapshotV10
            .format[IO](SnapshotCheck.Full, ReferenceLayout.Release11_1)
            .decode(checked(FileLedgerSnapshots.verified(raw).flatMap(NativeSnapshots.current)))
            .map(checked(_))
          mirror = snapshot.state
          layout = mirror.ext.layout.stateLayout
          initial <- IO(MirrorStep.nesOf(mirror).getOrElse(fail("Shelley input required")))
          _ <- IO.println(s"INPUT nes ${fingerprint(initial)} epoch=${initial.epoch}")
          restored <- store.restore(Path(s"/input/utxo/checkpoints/$name"))
          _ <- IO(assert(restored))
          stamp <- store.flushed
          _ <- IO(assert(stamp == 4753556L))
          records <- IO(
            checked(parse(Files.readString(JPath.of("/bench/records.json")))).asArray
              .getOrElse(fail("records array required"))
          )
          _ <- records.drop(1).take(16).zipWithIndex.foldLeftM(initial) {
            case (before, (record, index)) =>
              val cursor = record.hcursor
              val chunk = checked(cursor.get[String]("chunk"))
              val offset = checked(cursor.get[Long]("offset"))
              val length = checked(cursor.get[Int]("length"))
              for
                bytes <- IO.blocking {
                  val file = new java.io.RandomAccessFile(s"/input/immutable/$chunk", "r")
                  try
                    val out = new Array[Byte](length)
                    file.seek(offset)
                    file.readFully(out)
                    out
                  finally file.close()
                }
                _ <- IO(assert(hash(bytes) == checked(cursor.get[String]("rawSha256"))))
                read <- IO(
                  checked(ConwayBlock.read(Bytes.fromArray(bytes), layout))
                    .getOrElse(fail("Conway block required"))
                )
                block = read.envelope
                header = block.header
                eraBlock = read.block.fold(e => fail(s"Conway block refused: $e"), identity)
                fetched <- store.read(readSet(before).missing(read.inputs))
                from = resident(before, fetched)
                next <- IO {
                  // the configuration and constants of the chain within Shelley, its geometry
                  // resolved from the first Shelley epoch, and the clock, as `MirrorRules` takes
                  // them
                  val entry = checked(
                    NodeRules.entry(
                      mirror.consensus.byron,
                      header,
                      setup.praos,
                      setup.globals,
                      setup.byronConfig
                    )
                  )
                  val placement = Placement(
                    entry.configuration.geometry,
                    setup.byronHistory,
                    entry.configuration.forks
                  )
                  val clock =
                    MirrorClock.conway(entry.constants.systemStart, mirror, placement, header.slot)
                  // the block's body on the state ticked to its slot, by Conway's era run
                  def ticked(state: EraNes): NewEpochState[Conway] =
                    conway(checked(EraBlock.ticked(context)(state, header, block)))
                  def applied(state: EraNes): ConwayBlockRun.Result =
                    checked(
                      MirrorRules.conwayBody(
                        globals,
                        ticked(state),
                        header.blockNo,
                        eraBlock,
                        clock,
                        backend,
                        setup.praos.verdicts,
                        None
                      )
                    ).serial
                  println(
                    s"INPUT block-$index slot=${header.slot} txs=${block.transactions.size} sha256=${hash(bytes)}"
                  )
                  val next = measure(s"block-$index")(applied(from)) match
                    case Right(Right(after)) =>
                      val nes = ticked(from)
                      EraNes.InEra(
                        ShelleyBasedEra.SConway,
                        nes.copy(
                          bcur = after.state.blocksMade,
                          es = nes.es.copy(ls = after.state.ls)
                        )
                      )
                    case other => fail(s"historical block refused: $other")
                  if read.inputs.nonEmpty then
                    measure(s"missing-input-$index")(applied(resident(before, Map.empty))) match
                      case Right(Left(_)) => ()
                      case other          => fail(s"expected refusal: $other")
                  next
                }
              yield next
          }
          _ <- IO {
            val slot = checked(SlotNo.fromBigInt(globals.firstSlot(initial.epoch.toBigInt + 1)))
            println(s"INPUT epoch-boundary slot=$slot")
            assert(
              measure("epoch-boundary", warmup = 64, batch = 1)(
                Hfc.tick(globals, context.transition)(initial, context.afterVoting, slot)
              ).isRight
            )
          }
        yield ()
      }
    }
    .rescue(e => IO(fail(s"benchmark storage failure: $e")))
