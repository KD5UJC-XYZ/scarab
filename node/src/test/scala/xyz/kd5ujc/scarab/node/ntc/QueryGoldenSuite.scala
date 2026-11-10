// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import io.circe.parser
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{AnnTip, TipInfo}
import xyz.kd5ujc.scarab.ledger.state.{BlockNo, Hash32, ShelleyBasedEra, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import munit.FunSuite

/** The answers of the reference node (`cardano-node` 11.1.3) to the queries that do not depend on
  * activity, against the Scala answers over the same genesis (`fixtures/devnet/lsq`).
  */
class QueryGoldenSuite extends FunSuite:
  private val dir = Path.of("fixtures/devnet/lsq")
  private val setup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(dir.resolve("genesis"))
      .fold(e => fail(e.message), identity)
  private val golden = parser
    .parse(Files.readString(dir.resolve("golden.json")))
    .fold(e => fail(e.message), identity)
    .hcursor

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.message), identity)
  private val slot = golden.get[BigInt]("slot").fold(e => fail(e.message), identity)
  private val point =
    Point.Block(UInt64.saturating(slot), hex(golden.get[String]("hash").toOption.get))

  private val env = QueryEnv(
    setup.byronParams
      .map(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.byronParams.fold(EraHistory.ByronParams(50, 1000, 5))(b =>
      EraHistory.ByronParams(b.epochSlots, b.slotLengthMillis, b.securityParam)
    ),
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
  )

  // The state's tip is the point the reference answered at, as for every state the node acquires.
  private val view =
    val start = setup.mirrorStart
    val tip = AnnTip(
      SlotNo.fromBigInt(slot).fold(e => fail(e.toString), identity),
      BlockNo.fromUnsigned(1L),
      TipInfo.ShelleyBasedTip(
        ShelleyBasedEra.SConway,
        Hash32
          .fromBytes(hex(golden.get[String]("hash").toOption.get))
          .fold(e => fail(e.toString), identity)
      )
    )
    val atTip =
      start.copy(ext =
        start.ext.copy(headerState = start.ext.headerState.copy(tip = WithOrigin.At(tip)))
      )
    QueryView(point, Some(1), atTip)

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

  test("a query of Byron or of Dijkstra in Conway is answered with the era mismatch (#720)") {
    def text(t: String): String =
      f"${0x60 | t.length}%02x" + t.getBytes("UTF-8").map(b => f"$b%02x").mkString
    val conway = "06" + text("Conway")
    def answered(q: NtcQuery): String =
      QueryAnswers.answer(env, view, Map.empty, q).fold(fail(s"$q is not answered"))(_.hex)
    assertEquals(
      answered(NtcQuery.ByronUpdateInterfaceState),
      "8282" + "00" + text("Byron") + "82" + conway
    )
    assertEquals(
      answered(NtcQuery.IfCurrent(QueryEra.Dijkstra, LedgerQuery.GetEpochNo)),
      "8282" + "07" + text("Dijkstra") + "82" + conway
    )
    // GetEraStart of an era the chain has not reached: nothing
    assertEquals(answered(NtcQuery.EraStart(QueryEra.Dijkstra)), "80")
  }
