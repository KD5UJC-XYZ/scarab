// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{Prepared, Verdicts}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.jdk.CollectionConverters.*

/** The cache is memoisation: whatever was warmed, whatever order and however often it is asked, a
  * [[VerificationCache]] answers exactly as [[Verdicts.Direct]]. Two instances never share
  * anything, so tests isolate their caches.
  */
class VerificationCacheSuite extends ScalaCheckSuite:
  private val fixtures = Path.of("fixtures/eras")
  private val setup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))

  private val prepared: Vector[Prepared] = raws.flatMap(NodeRules.decodeShelley)

  private val witnesses: Vector[(Bytes, Bytes, Bytes)] =
    prepared.flatMap(_.txs.flatMap(_.toOption)).flatMap { tx =>
      tx.witnesses.vkeys.map { case (k, s) => (k, s, tx.body.id) }
    }

  private def flip(b: Bytes): Bytes =
    Bytes(b.value.updated(0, (b.value.head ^ 1).toByte))

  test("the fixtures contain blocks and witnesses to check") {
    assert(prepared.nonEmpty)
    assert(witnesses.nonEmpty)
  }

  property("verdicts are identical with and without a cache, whichever blocks were warmed") {
    forAll(Gen.someOf(raws.indices)) { warmed =>
      val cache = VerificationCache.unsafe(2)
      cache.warm[IO](warmed.toVector.map(raws), setup.praos, 2).unsafeRunSync()
      val witnessesAgree = witnesses.forall { (k, s, m) =>
        // valid triples, then forged ones (a flipped bit in the signature or the message)
        Seq((k, s, m), (k, flip(s), m), (k, s, flip(m))).forall { (k2, s2, m2) =>
          cache.witness(k2, s2, m2) == Verdicts.Direct.witness(k2, s2, m2)
        }
      }
      val headersAgree = prepared.forall { p =>
        val h = p.block.header
        val g = setup.praos
        cache.opCert(h, g.slotsPerKesPeriod, g.maxKesEvolutions) ==
          Verdicts.Direct.opCert(h, g.slotsPerKesPeriod, g.maxKesEvolutions)
      }
      val hashesAgree =
        witnesses.forall((k, _, _) => cache.keyHash(k) == Verdicts.Direct.keyHash(k))
      cache.shutdown()
      Prop(witnessesAgree && headersAgree && hashesAgree)
    }
  }

  property(
    "the jobs of a batch are split in order, none lost, none twice, into chunks of 8 or more"
  ) {
    forAll(Gen.choose(0, 20000), Gen.choose(1, 64)) { (n, parallelism) =>
      val jobs = Vector.range(0, n)
      val chunks = VerificationCache.unsafe(1).chunked(jobs, parallelism)
      Prop(
        chunks.flatten == jobs &&
          chunks.dropRight(1).forall(_.size >= 8) &&
          (n == 0 || chunks.size <= parallelism * 4 || chunks.head.size == 8)
      )
    }
  }

  test(
    "a batch with many witnesses is verified across threads and every verdict is the serial one"
  ) {
    val cache = VerificationCache.unsafe(4)
    // the same blocks several times over: more checks than one chunk, and repeated keys
    val batch = Vector.fill(10)(raws).flatten
    cache.warm[IO](batch, setup.praos, 4).unsafeRunSync()
    assert(
      witnesses.forall((k, s, m) => cache.witness(k, s, m) == Verdicts.Direct.witness(k, s, m))
    )
    assert(prepared.forall(p => cache.takePrepared(p.block.header.hash).isDefined))
    cache.shutdown()
  }

  test("warming a block again verifies none of its witnesses again") {
    val cache = VerificationCache.unsafe(2)
    cache.warm[IO](raws, setup.praos, 2).unsafeRunSync()
    val computed = cache.warmedStats.misses.sum
    assert(computed >= witnesses.distinct.size.toLong)
    assertEquals(cache.warmedStats.hits.sum, 0L)
    // the same blocks again, as a second peer's batch or a range fetched again would be: every
    // verdict is found, none is computed
    cache.warm[IO](raws, setup.praos, 2).unsafeRunSync()
    assertEquals(cache.warmedStats.misses.sum, computed)
    assertEquals(cache.warmedStats.hits.sum, computed)
    assertEquals(cache.warmedStats.toString, s"$computed/${2 * computed}")
    // and what was kept is still the serial verdict
    assert(
      witnesses.forall((k, s, m) => cache.witness(k, s, m) == Verdicts.Direct.witness(k, s, m))
    )
    cache.shutdown()
  }

  test("a warmed verdict is consumed once and an independent cache never sees it") {
    val a = VerificationCache.unsafe(2)
    val b = VerificationCache.unsafe(2)
    a.warm[IO](raws, setup.praos, 2).unsafeRunSync()
    val (k, s, m) = witnesses.head
    assertEquals(a.witness(k, s, m), true)
    assertEquals(a.witnessStats.toString, "1/1")
    assertEquals(a.witness(k, s, m), true)
    assertEquals(a.witnessStats.toString, "1/2")
    assertEquals(b.witness(k, s, m), true)
    assertEquals(b.witnessStats.toString, "0/1")
    a.shutdown()
    b.shutdown()
  }
