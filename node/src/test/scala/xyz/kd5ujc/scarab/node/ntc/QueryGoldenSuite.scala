// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.node.NodeSetup
import munit.FunSuite

/** The answers of the reference node (`cardano-node` 11.1.3) to the queries that do not depend on
  * activity, against the Scala answers over the same genesis (`fixtures/devnet/lsq`).
  */
class QueryGoldenSuite extends FunSuite:
  private val dir = Path.of("fixtures/devnet/lsq")
  private val setup =
    NodeSetup.fromDirectory(dir.resolve("genesis")).fold(e => fail(e.message), identity)
  private val golden = parser
    .parse(Files.readString(dir.resolve("golden.json")))
    .fold(e => fail(e.message), identity)
    .hcursor

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e), identity)
  private val slot = golden.get[BigInt]("slot").fold(e => fail(e.message), identity)
  private val point =
    Point.Block(UInt64.saturating(slot), hex(golden.get[String]("hash").toOption.get))

  private val env = QueryEnv(
    setup.byronParams
      .flatMap(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.byronParams.fold(EraHistory.ByronParams(50, 1000, 5))(b =>
      EraHistory.ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
    ),
    setup.globals.maxSupply,
    setup.genesis.raw
  )
  private val view = QueryView(point, Some(1), setup.start)

  private val entries = golden.downField("queries").values.toVector.flatten.map(_.hcursor)

  test("the fixture has the queries and the genesis is the devnet's") {
    assert(entries.size >= 10, entries.size.toString)
    assertEquals(setup.genesis.networkMagic, 1082026L)
  }

  entries.foreach { c =>
    val label = c.get[String]("label").toOption.get
    test(s"the answer to $label equals the reference's") {
      val query = NtcQuery
        .decode(hex(c.get[String]("query").toOption.get))
        .fold(e => fail(s"cannot decode the query: $e"), identity)
      val expected = c.get[String]("result").toOption.get
      QueryAnswers.answer(env, view, Map.empty, query) match
        case Some(got) => assertEquals(got.hex, expected)
        case None      => fail(s"$label is not answered")
    }
  }
