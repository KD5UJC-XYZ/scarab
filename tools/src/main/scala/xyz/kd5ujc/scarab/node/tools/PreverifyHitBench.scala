// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.Praos
import xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate as Vrf
import xyz.kd5ujc.scarab.ledger.{Block, HeaderVrf, Nonce, Prepared, Tx}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayBlock
import xyz.kd5ujc.scarab.node.{NodeSetup, VerificationCache}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Development tool (#1499): where the selection worker's preparation of a block whose checks were
  * all made ahead spends its time, on captured blocks (`CaptureMain`).
  *
  * `PreverifyHitBench <blocks.hex> [<rounds>] [<threads>]` warms every block once (the checks made
  * ahead), then, for `<rounds>` rounds after one warm-up round, times per block:
  *   - `warm`: `VerificationCache.warm` of the block alone, every verdict already there (what the
  *     worker's `preverify` costs on a hit), with the cache's `<threads>`;
  *   - `holds`: `VerificationCache.holds` of the block (what skipping it costs);
  *   - its parts, outside the cache: the block's decode (`ConwayBlock.read`), its transactions'
  *     decode (`Tx.fromBlock`), the hashing of their bodies (`TxBody.id`), the witness keys with
  *     their `distinct`, the cache-key lookups those keys make, and the VRF alpha of the header.
  *
  * Only the blocks the cache holds are timed (`VerificationCache.holds`, checked before each).
  * Prints the mean milliseconds per block of each, and how many warmings were timed. The parts
  * follow the flat decoder's path (blocks before Conway) and are measured one after another on one
  * thread; `warm` less their sum is unattributed: mostly the scheduling (the blocking threads, the
  * permits, the fibers), and the cache's own bookkeeping.
  *
  * With a fourth argument, `<ahead threads>`, it also measures the contention the checks made ahead
  * can cause: the decoding of every block on one thread (work of the kind the worker does), alone
  * and while the blocks' checks are made over and over, one block at a time, into fresh caches on
  * `<ahead threads>` threads (the verifier never idle: an upper bound).
  */
object PreverifyHitBench:
  private final case class Key(vkey: Bytes, signature: Bytes, message: Bytes)

  def main(args: Array[String]): Unit =
    val raws = Files
      .readAllLines(Path.of(args(0)))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => sys.error(e.message), identity))
    val rounds = args.lift(1).fold(20)(_.toInt)
    val threads = args.lift(2).fold(VerificationCache.defaultParallelism)(_.toInt)
    val setup = NodeSetup
      .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures/mainnet/genesis"))
      .fold(e => sys.error(e.message), identity)
    val praos = setup.praos
    val layout = setup.layout
    val cache = VerificationCache.unsafe(threads)
    val shelley = raws.flatMap(r =>
      Prepared.decode(r, layout).map(p => (r, p.envelope.header.hash, p.envelope.header))
    )

    val nonce = Nonce.Hash(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(Bytes(Vector(7))))
    // the epoch of the blocks' nonce, as the node notes its tip's (a made-up nonce: the proofs then
    // fail, which costs as much as passing, and the verdicts are kept all the same)
    shelley.headOption.foreach((_, _, h) =>
      cache.noteEpochNonce(praos.geometry.epochOf(h.slot), nonce)
    )

    // the checks made ahead, once
    cache.warm[IO](raws, praos, threads).unsafeRunSync()
    println(
      s"blocks=${raws.size} shelley=${shelley.size} held=${shelley.count((_, h, _) => cache.holds(h))} " +
        s"bytes/block=${raws.map(_.size).sum / raws.size.max(1)} threads=$threads"
    )

    def keysOf(txs: Vector[Tx]): Vector[Key] =
      txs.flatMap { tx =>
        (tx.witnesses.vkeys ++ tx.witnesses.bootstrap.map(w => w.vkey -> w.signature)).map {
          (vkey, signature) => Key(vkey, signature, tx.body.id)
        }
      }.distinct

    // the lookups of the keys, as `witnessJob` and `keyHash` make them on a hit
    val present = new ConcurrentHashMap[Key, java.lang.Boolean]()
    val hashes = new ConcurrentHashMap[Bytes, Bytes]()
    shelley.foreach { (raw, _, _) =>
      ConwayBlock.read(raw, layout).toOption.foreach {
        case Left(b: Block.Shelley) =>
          keysOf(b.transactions.flatMap(t => Tx.fromBlock(t, b.era).toOption)).foreach { k =>
            present.put(k, true)
            hashes.put(k.vkey, k.vkey)
          }
        case _ => ()
      }
    }
    final class Sums:
      val total = scala.collection.mutable.LinkedHashMap.empty[String, Long]
      private val samples = new java.util.concurrent.atomic.AtomicLong(0)
      def count(): Unit = samples.incrementAndGet(): Unit
      def blocks: Long = samples.get
      def time[A](name: String)(body: => A): A =
        val t0 = System.nanoTime
        val a = body
        total.updateWith(name)(n => Some(n.getOrElse(0L) + (System.nanoTime - t0)))
        a

    def round(sums: Sums): Unit =
      // only the blocks the cache holds: the hits the worker's call skips
      shelley.filter((_, hash, _) => cache.holds(hash)).foreach { (raw, hash, header) =>
        sums.count()
        sums.time("warm")(cache.warm[IO](Vector(raw), praos, threads).unsafeRunSync())
        sums.time("holds")(cache.holds(hash): Unit)
        sums.time("block decode")(ConwayBlock.read(raw, layout)).toOption.foreach {
          case Left(b: Block.Shelley) =>
            val txs = sums.time("tx decode")(
              b.transactions.flatMap(t => Tx.fromBlock(t, b.era).toOption)
            )
            sums.time("tx body hashing")(txs.foreach(_.body.id: Unit))
            val keys = sums.time("witness keys")(keysOf(txs))
            sums.time("key lookups")(keys.foreach { k =>
              present.containsKey(k): Unit
              hashes.get(k.vkey): Unit
            })
          case _ => ()
        }
        sums.time("vrf alpha")(header.vrf match
          case HeaderVrf.Praos(_) =>
            Praos.vrfInput(nonce, header.slot).flatMap(Vrf.alpha): Unit
          case _ => ())
      }

    args.lift(3).map(_.toInt).foreach { aheadThreads =>
      def decoding(): Long =
        val t0 = System.nanoTime
        shelley.foreach((raw, _, _) => Prepared.decode(raw, layout): Unit)
        System.nanoTime - t0
      val verifying = IO
        .blocking {
          val fresh = VerificationCache.unsafe(aheadThreads)
          shelley.headOption
            .foreach((_, _, h) => fresh.noteEpochNonce(praos.geometry.epochOf(h.slot), nonce))
          fresh
        }
        .flatMap { fresh =>
          raws
            .traverse_(r => fresh.warm[IO](Vector(r), praos, aheadThreads))
            .guarantee(IO(fresh.shutdown()))
        }
        .foreverM
      (1 to 5).foreach(_ => decoding()) // JIT
      val alone = (1 to rounds).map(_ => decoding()).sum
      val beside = verifying.background
        .surround(IO.blocking((1 to rounds).map(_ => decoding()).sum))
        .unsafeRunSync()
      val per = (ns: Long) => ns / 1e6 / (rounds * shelley.size.max(1))
      println(
        f"decode alone ${per(alone)}%.3f ms/block, beside checks on $aheadThreads threads ${per(beside)}%.3f ms/block (${beside.toDouble / alone}%.2fx)"
      )
    }

    round(Sums())
    val sums = Sums()
    (1 to rounds).foreach(_ => round(sums))
    val per = (ns: Long) => ns / 1e6 / sums.blocks.max(1)
    println(s"timed: ${sums.blocks} warmings of blocks the cache held")
    sums.total.foreach((name, ns) => println(f"$name%-16s ${per(ns)}%8.3f ms/block"))
    val parts = sums.total.view.filterKeys(k => !Set("warm", "holds")(k)).values.sum
    println(
      f"${"unattributed"}%-16s ${per(sums.total.getOrElse("warm", 0L) - parts)}%8.3f ms/block"
    )
    cache.shutdown()
