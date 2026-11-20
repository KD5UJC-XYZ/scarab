// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, LeiosBlock}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** The acquired-endorser-block gate's pure parts against ouroboros-consensus 2416493b: the acquired
  * set (`LeiosDemoTypes.hs:213-290`), `isUnacquiredCertRB` and the parked blocks of
  * `chainSelReprocessLeiosEb` (`Storage/ChainDB/Impl/ChainSel.hs:654-673, 1797-1844`), and the
  * Leios fields of a block (`Storage/VolatileDB/Impl/Parser.hs:119-140`) on blocks of the public
  * Leios testnet (`fixtures/leios/testnet`, provenance in `fixtures/leios/README.md`).
  */
class LeiosChainSelSuite extends munit.FunSuite:
  private def ebHash(i: Int): EbHash = EbHash.fromBytes(Bytes(Vector.fill(32)(i.toByte)))
  private def slot(n: Long): Word64 =
    Word64.from(BigInt(n)).fold(e => fail(e.toString), identity)
  private def point(s: Long, i: Int): EbPoint = EbPoint(slot(s), ebHash(i))
  private def block(i: Int): Bytes = Bytes(Vector.fill(32)((100 + i).toByte))

  test("an endorser block is novel once, and held at the youngest slot it was announced at") {
    val first = AcquiredLeiosEbs.empty.inserted(point(10, 1))
    assertEquals(first.map(_._1), Some(true))
    val held = first.map(_._2).getOrElse(fail("not inserted"))
    // an older or equal announcement changes nothing; a younger one moves it, not novel
    assertEquals(held.inserted(point(10, 1)), None)
    assertEquals(held.inserted(point(5, 1)), None)
    val moved = held.inserted(point(20, 1))
    assertEquals(moved.map(_._1), Some(false))
    val younger = moved.map(_._2).getOrElse(fail("not moved"))
    // pruned by its youngest slot: kept at 20, gone past it
    assertEquals(younger.pruned(Some(BigInt(20))), None)
    assertEquals(younger.pruned(Some(BigInt(21))).map(_.contains(ebHash(1))), Some(false))
    assertEquals(younger.pruned(None), None)
  }

  test("the set from the open's scan holds each hash once") {
    val acquired = AcquiredLeiosEbs.of(Vector(point(3, 1), point(4, 2), point(9, 1)))
    assert(acquired.contains(ebHash(1)) && acquired.contains(ebHash(2)))
    // pruning at 5 drops hash 2 (youngest 4) and keeps hash 1 (youngest 9)
    val pruned = acquired.pruned(Some(BigInt(5))).getOrElse(fail("nothing pruned"))
    assertEquals((pruned.contains(ebHash(1)), pruned.contains(ebHash(2))), (true, false))
  }

  private val announcing =
    LeiosForestBlock(Some(block(0)), LeiosBlockInfo(LeiosCert.Absent, Some(ebHash(1))))
  private val silent = LeiosForestBlock(Some(block(0)), LeiosBlockInfo.Absent)
  private def certifying(previous: Option[Bytes]) =
    LeiosForestBlock(previous, LeiosBlockInfo(LeiosCert.Carried, None))

  test("isUnacquiredCertRB: every case of the reference") {
    val forest = Map(
      block(1) -> announcing,
      block(2) -> certifying(Some(block(1))),
      block(3) -> silent,
      block(4) -> certifying(Some(block(3))),
      block(5) -> certifying(Some(block(99))),
      block(6) -> certifying(None),
      block(7) -> silent
    )
    def hidden(acquired: AcquiredLeiosEbs) = LeiosCertGate.hidden(acquired, forest)
    val none = AcquiredLeiosEbs.empty
    val acquired = AcquiredLeiosEbs.of(Vector(point(1, 1)))
    // not in the forest, and no certificate: not hidden
    assert(!LeiosCertGate.unacquired(none, forest.get)(block(42)))
    // its predecessor announced nothing, is not in the forest, or it is on genesis: hidden always;
    // its predecessor's endorser block not acquired: hidden until it is
    assertEquals(hidden(none), Set(block(2), block(4), block(5), block(6)))
    assertEquals(hidden(acquired), Set(block(4), block(5), block(6)))
  }

  test("the blocks parked on an endorser block: the certifying successors of its announcers") {
    val forest = Map(
      block(1) -> announcing,
      block(2) -> certifying(Some(block(1))),
      block(3) -> certifying(Some(block(1))),
      block(4) -> LeiosForestBlock(Some(block(1)), LeiosBlockInfo.Absent),
      block(5) -> certifying(Some(block(3)))
    )
    assertEquals(LeiosCertGate.parkedOn(ebHash(1), forest, _ => false), Set(block(2), block(3)))
    // an invalid successor is not reprocessed
    assertEquals(LeiosCertGate.parkedOn(ebHash(1), forest, _ == block(3)), Set(block(2)))
    assertEquals(LeiosCertGate.parkedOn(ebHash(2), forest, _ => false), Set.empty[Bytes])
  }

  private val fixtures: Map[String, Bytes] =
    Files
      .readAllLines(Path.of("fixtures", "leios", "testnet", "blocks-rc-2026w44.tsv"))
      .asScala
      .toVector
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))
      .map(p => p(0) -> Bytes.fromHex(p(5)).fold(e => fail(e.toString), identity))
      .toMap

  private def info(label: String): LeiosBlockInfo =
    LeiosBlock
      .decode(fixtures(label))
      .fold(e => fail(s"$label: ${e.message}"), b => LeiosBlockInfo.of(Block.Dijkstra(b)))

  test("a testnet block's Leios fields: its body's certificate and its header's announcement") {
    val certifying = info("certifying")
    assertEquals(certifying.cert, LeiosCert.Carried)
    val announcing = info("announcing-with-txs")
    assertEquals(announcing.cert, LeiosCert.Absent)
    assert(announcing.announced.isDefined)
    assertEquals(info("block-0"), LeiosBlockInfo.Absent)
  }
