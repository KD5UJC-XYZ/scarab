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
      .map(l => Bytes.fromHex(l).fold(e => fail(e), identity))

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
