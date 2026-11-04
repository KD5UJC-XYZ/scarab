// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.state.Word64

class LeiosSettingsSuite extends FunSuite:
  private def read(text: String): ConfigSettings =
    NodeConfig.parse(text).fold(e => fail(e.message), _.settings)

  test("Double bit patterns agree with the executed aeson 2.3.1.0 and scientific 0.3.8.1 reader") {
    // ghc -package aeson -e: fmap GHC.Float.castDoubleToWord64 (eitherDecode input :: Either String Double)
    val cases = List(
      "0" -> "0",
      "-0" -> "0",
      "0.1" -> "4591870180066957722",
      "0.84551240822557006" -> "4605790918278520032",
      "2.2250738585072011e-308" -> "4503599627370495",
      "5e-324" -> "1",
      "1e400" -> "9218868437227405312",
      "-1e400" -> "18442240474082181120"
    )
    cases.foreach { (input, bits) =>
      val value = read(s"""{"EgressBudgetMbps":$input}""").leios.egressBudgetMbps
      assertEquals(
        java.lang.Long.toUnsignedString(java.lang.Double.doubleToRawLongBits(value)),
        bits
      )
    }
  }

  test("absent and null optional settings use reference defaults without unused-setting warnings") {
    val absent = read("{}")
    val nulled = read(
      """{"EgressBudgetMbps":null,"LeiosDbConfig":null,"LeiosMinOfferLead":null,"LeiosAdaptiveEndorsements":null,"LedgerDB":null}"""
    )
    assertEquals(absent, nulled)
    assertEquals(absent.leios, LeiosSettings())
    assertEquals(absent.ledgerDb, LedgerDbSettings())
    assertEquals(absent.unsupported, Nil)
  }

  test("Dijkstra fields are ignored unless experimental hard forks are enabled") {
    val invalid =
      """"DijkstraGenesisFile":5,"DijkstraGenesisHash":false,"TestDijkstraHardForkAtEpoch":-1,"TestDijkstraHardForkAtVersion":[]"""
    val disabled = NodeConfig.parse("{" + invalid + "}").fold(e => fail(e.message), identity)
    assertEquals(disabled.files.dijkstra, None)
    assertEquals(disabled.hashes.dijkstra, None)
    assertEquals(disabled.settings.dijkstraHardForkEpoch, None)
    assertEquals(disabled.settings.dijkstraHardForkVersion, None)
    assert(NodeConfig.parse("{\"ExperimentalHardForksEnabled\":true," + invalid + "}").isLeft)
  }

  test("enabled Dijkstra triggers retain the full unsigned Word64 range") {
    val settings = read(
      """{"ExperimentalHardForksEnabled":true,"TestDijkstraHardForkAtEpoch":18446744073709551615,"TestDijkstraHardForkAtVersion":1.2e1}"""
    )
    assertEquals(settings.dijkstraHardForkEpoch, Some(Word64.max.toBigInt))
    assertEquals(settings.dijkstraHardForkVersion, Some(BigInt(12)))
    for key <- List("TestDijkstraHardForkAtEpoch", "TestDijkstraHardForkAtVersion") do
      for value <- List("-1", "18446744073709551616", "1.1", "\"12\"") do
        assert(NodeConfig.parse(s"""{"ExperimentalHardForksEnabled":true,"$key":$value}""").isLeft)
  }

  test("Leios backend requires its selector, while lead accepts zero and maximum Word64") {
    assertEquals(
      read("""{"LeiosDbConfig":{"Backend":"InMemory"},"LeiosMinOfferLead":0}""").leios.database,
      LeiosDbBackend.InMemory
    )
    assertEquals(
      read("""{"LeiosMinOfferLead":18446744073709551615}""").leios.minOfferLead,
      Some(Word64.max)
    )
    for value <- List("{}", "{\"Backend\":null}", "{\"Backend\":\"Other\"}", "\"SQLite\"") do
      assert(NodeConfig.parse(s"""{"LeiosDbConfig":$value}""").isLeft)
    for value <- List("-1", "18446744073709551616", "0.5", "\"1\"") do
      assert(NodeConfig.parse(s"""{"LeiosMinOfferLead":$value}""").isLeft)
  }

  test("adaptive presence enables defaults and disabled settings still parse every field") {
    assertEquals(
      read("""{"LeiosAdaptiveEndorsements":{}}""").leios.adaptiveEndorsements,
      Some(LeiosAdaptiveSettings())
    )
    assertEquals(
      read(
        """{"LeiosAdaptiveEndorsements":{"Enabled":false,"AliveWindow":-9223372036854775808}}"""
      ).leios.adaptiveEndorsements,
      None
    )
    for (key, value) <- List(
        "GainP" -> "\"2\"",
        "AliveWindow" -> "9223372036854775808",
        "AliveWindow" -> "0.5",
        "Enabled" -> "0"
      )
    do
      assert(
        NodeConfig
          .parse(s"""{"LeiosAdaptiveEndorsements":{"Enabled":false,"$key":$value}}""")
          .isLeft
      )
    assertEquals(
      read(
        """{"LeiosAdaptiveEndorsements":{"AliveWindow":4294967296}}"""
      ).leios.adaptiveEndorsements.map(_.aliveWindow),
      Some(4294967296L)
    )
  }

  test("Double uses aeson's infinity strings and has no positivity or finite-value constraint") {
    assertEquals(
      read("""{"EgressBudgetMbps":"+inf"}""").leios.egressBudgetMbps,
      Double.PositiveInfinity
    )
    assertEquals(
      read("""{"EgressBudgetMbps":"-inf"}""").leios.egressBudgetMbps,
      Double.NegativeInfinity
    )
    assertEquals(
      read("""{"EgressBudgetMbps":1e400}""").leios.egressBudgetMbps,
      Double.PositiveInfinity
    )
    assertEquals(read("""{"EgressBudgetMbps":-2.5}""").leios.egressBudgetMbps, -2.5)
    for value <- List("\"250\"", "\"Infinity\"", "\"NaN\"", "true", "[]") do
      assert(NodeConfig.parse(s"""{"EgressBudgetMbps":$value}""").isLeft)
  }

  test("nested snapshots win over deprecated keys, but malformed top-level values still fail") {
    val settings = read(
      """{"SnapshotInterval":10,"NumOfDiskSnapshots":3,"LedgerDB":{"Snapshots":{"SnapshotInterval":20,"NumOfDiskSnapshots":4}}}"""
    ).ledgerDb
    val expected =
      read("""{"LedgerDB":{"Snapshots":{"SnapshotInterval":20,"NumOfDiskSnapshots":4}}}""").ledgerDb
    assertEquals(settings.snapshots, expected.snapshots)
    assertEquals(settings.deprecated, List("SnapshotInterval", "NumOfDiskSnapshots"))
    assert(
      NodeConfig
        .parse("""{"SnapshotInterval":0,"LedgerDB":{"Snapshots":{"SnapshotInterval":20}}}""")
        .isLeft
    )
    assert(
      NodeConfig
        .parse("""{"SnapshotInterval":10,"LedgerDB":{"Snapshots":{"SnapshotInterval":0}}}""")
        .isLeft
    )
  }

  test(
    "named Mithril ignores valid deprecated overrides, and rejects shadowed flat options first"
  ) {
    val settings = read(
      """{"SnapshotInterval":10,"NumOfDiskSnapshots":9,"LedgerDB":{"Snapshots":"Mithril"}}"""
    ).ledgerDb
    assertEquals(settings.snapshots, LedgerSnapshotSettings())
    for value <- List("\"Mithril\"", "{}", "false") do
      assert(NodeConfig.parse(s"""{"LedgerDB":{"SlotOffset":0,"Snapshots":$value}}""").isLeft)
    assertEquals(
      read("""{"LedgerDB":{"SlotOffset":null,"Snapshots":"Mithril"}}""").ledgerDb.snapshots,
      LedgerSnapshotSettings()
    )
  }

  test(
    "snapshot integer durations accept negatives and only a complete delay pair overrides defaults"
  ) {
    val settings = read(
      """{"LedgerDB":{"RateLimit":-10,"MinDelay":-20,"MaxDelay":-1,"QueryBatchSize":0,"NumOfDiskSnapshots":0}}"""
    ).ledgerDb
    assertEquals(settings.snapshots.rateLimitSeconds, BigInt(-10))
    assertEquals(settings.snapshots.minimumDelaySeconds, BigInt(-20))
    assertEquals(settings.snapshots.maximumDelaySeconds, BigInt(-1))
    assertEquals(settings.snapshots.number, Word64.zero)
    assertEquals(settings.queryBatchSize, LedgerQueryBatchSize.Requested(Word64.zero))
    assertEquals(
      read("""{"LedgerDB":{"MinDelay":90000}}""").ledgerDb.snapshots,
      LedgerSnapshotSettings()
    )
    assert(NodeConfig.parse("""{"LedgerDB":{"MinDelay":"300"}}""").isLeft)
    assert(NodeConfig.parse("""{"LedgerDB":{"MinDelay":10,"MaxDelay":9}}""").isLeft)
    assert(NodeConfig.parse("""{"LedgerDB":{"RateLimit":1e1025}}""").isLeft)
  }

  test("LSM paths are read only for the LSM backend") {
    assertEquals(
      read("""{"LedgerDB":{"LSMDatabasePath":false}}""").ledgerDb.backend,
      LedgerDbBackend.V2InMemory
    )
    assertEquals(
      read(
        """{"LedgerDB":{"Backend":"V2LSM","LSMDatabasePath":"data","LSMExportPath":"export"}}"""
      ).ledgerDb.backend,
      LedgerDbBackend.V2LSM(Some("data"), Some("export"))
    )
    assert(NodeConfig.parse("""{"LedgerDB":{"Backend":"V2LSM","LSMDatabasePath":false}}""").isLeft)
  }

  test("explicit unused settings are reported, including disabled adaptive settings") {
    val settings =
      read("""{"LeiosAdaptiveEndorsements":{"Enabled":false},"EgressBudgetMbps":0,"LedgerDB":{}}""")
    assertEquals(
      settings.unsupported,
      List(
        "EgressBudgetMbps: recognised but unused",
        "LeiosAdaptiveEndorsements: recognised but unused",
        "LedgerDB: recognised but unused"
      )
    )
  }
