// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraClosure
import xyz.kd5ujc.scarab.node.mempool.RelayedTransactions
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** What [[ClosureBench]] reads: its arguments, and a submitted transaction turned into the form an
  * endorser block's closure holds, which the closure check decodes.
  */
class ClosureBenchSuite extends munit.FunSuite:
  private val envelope = Path.of("fixtures", "dijkstra-devnet", "transaction.json")

  test("the arguments: an address or all, a cached fraction from 0 to 1, a positive run count") {
    val base = List("config.json", "ledger/3347980")
    val parsed = ClosureBench.parse(base ++ List("all", "0.4", "3", "txs"))
    assertEquals(
      parsed.map(o => (o.address, o.cached, o.runs, o.txs.size)),
      Right((None, BigDecimal("0.4"), 3, 1))
    )
    assert(ClosureBench.parse(base ++ List("01ab", "1", "1", "a", "b")).exists(_.address.isDefined))
    for bad <- List(
        List("zz", "0.4", "3", "txs"),
        List("all", "1.5", "3", "txs"),
        List("all", "0.4", "0", "txs")
      )
    do assertEquals(ClosureBench.parse(base ++ bad), Left(ClosureBench.Problem.Usage), bad.toString)
    assertEquals(
      ClosureBench.parse(base ++ List("all", "0.4", "3")),
      Left(ClosureBench.Problem.Usage)
    )
  }

  test("a submitted transaction in the closure's form decodes as the closure check decodes it") {
    val read = TxSubmitPeer
      .signed(
        envelope,
        Files.readString(envelope),
        RelayedTransactions.of(ReferenceBuild.Leios),
        TxSubmitPeer.eras("dijkstra")
      )
      .fold(u => fail(u.message), identity)
    val inBlock = ClosureBench.inBlockForm(read.original).fold(e => fail(e.toString), identity)
    val decoded =
      DijkstraClosure.deserializeTxForLeios(inBlock).fold(e => fail(e.toString), identity)
    // the block form adds the validity flag; the body, and so the id, is the submitted one's
    assertNotEquals(inBlock, read.original)
    assertEquals(decoded.id, read.id)
  }

  test("the cached fraction seeds exactly that many, floored, spread evenly") {
    // 0.29 x 100 is 28.999999999999996 in binary floating point; as a decimal it is 29
    assertEquals(ClosureBench.seeded(100, BigDecimal("0.29")).size, 29)
    assertEquals(ClosureBench.seeded(8536, BigDecimal("0.404")).size, 3448)
    assertEquals(ClosureBench.seeded(10, BigDecimal("0.5")), Vector(1, 3, 5, 7, 9))
    assertEquals(ClosureBench.seeded(7, BigDecimal(0)), Vector.empty)
    assertEquals(ClosureBench.seeded(7, BigDecimal(1)), (0 until 7).toVector)
  }

  test("the product is exact before it is floored, however many digits the fraction has") {
    // within 34 digits, 0.666...6 x 3 would round up to 2; exactly it is 1.999...8, floored 1
    assertEquals(ClosureBench.seeded(3, BigDecimal("0.6666666666666666666666666666666666")).size, 1)
  }
