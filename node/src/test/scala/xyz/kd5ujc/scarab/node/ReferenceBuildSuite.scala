// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.node.tools.LedgerSnapshotImportCommand
import xyz.kd5ujc.scarab.protocol.{
  ReferenceBuild,
  ReferenceBuildNotYet,
  StateLayout,
  UnknownReferenceBuild
}
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout

/** `--reference-build` (#826): the closed list of builds, the flag strings, the pins and the
  * startup refusal of a build not implemented yet.
  */
class ReferenceBuildSuite extends FunSuite:
  test(
    "configuration defaults follow the selected build through file, directory and absent config setup"
  ) {
    val dir = Path.of("fixtures", "preview", "genesis")
    // 11.2's setup is refused for want of its layout (the test below); its configuration is read
    assertEquals(
      NodeSetup
        .readConfig(ReferenceBuild.Release11_2)(dir.resolve("config.json"))
        .map(_.settings.ledgerDb.snapshots.maximumDelaySeconds),
      Right(BigInt(21600))
    )
    List(ReferenceBuild.Release11_1 -> BigInt(600), ReferenceBuild.LeiosB004fe0c -> BigInt(21600))
      .foreach { (build, maximum) =>
        val options = Main
          .parse(
            List(
              "follow",
              "--genesis-dir",
              dir.toString,
              "--peer",
              "127.0.0.1:3001",
              "--reference-build",
              build.name
            )
          )
          .fold(e => fail(e.message), identity)
        val directory = Main.nodeSetup(options).fold(e => fail(e.message), identity)
        val configured = Main
          .nodeSetup(options.copy(configFile = Some(dir.resolve("config.json"))))
          .fold(e => fail(e.message), identity)
        val absent = NodeSetup
          .fromFiles(build) { name =>
            Option.when(!NodeSetup.ConfigNames.contains(name) && Files.exists(dir.resolve(name)))(
              Files.readAllBytes(dir.resolve(name))
            )
          }
          .fold(e => fail(e.message), identity)
        List(directory, configured, absent).foreach { setup =>
          assertEquals(
            setup.config.settings.ledgerDb.snapshots.maximumDelaySeconds,
            maximum,
            build.name
          )
        }
        val preliminary = NodeSetup
          .readConfig(build)(dir.resolve("config.json"))
          .fold(e => fail(e.message), identity)
        assertEquals(
          preliminary.settings.ledgerDb.snapshots.maximumDelaySeconds,
          maximum,
          build.name
        )
      }
  }

  test("every build prints as its flag string and parses back; any other text is a typed error") {
    ReferenceBuild.values.foreach(b => assertEquals(ReferenceBuild.parse(b.name), Right(b)))
    assertEquals(
      ReferenceBuild.values.map(_.name).toVector,
      Vector("11.1", "11.2", "leios-b004fe0c")
    )
    Vector(
      "11.1.3",
      "11.1.0",
      "11.2.1",
      "11.2.0",
      "11",
      "",
      " 11.1",
      "11.1 ",
      "mainline",
      "leios",
      "Leios-b004fe0c"
    )
      .foreach(text => assertEquals(ReferenceBuild.parse(text), Left(UnknownReferenceBuild(text))))
    assertEquals(ReferenceBuild.Default, ReferenceBuild.Release11_1)
  }

  test(
    "a stable and a pre-release line and an unstable Leios build; only the unstable one wants the flag"
  ) {
    assertEquals(ReferenceBuild.Release11_1.stability, ReferenceBuild.Stability.Stable)
    assertEquals(ReferenceBuild.Release11_2.stability, ReferenceBuild.Stability.PreRelease)
    assert(!ReferenceBuildFlag.warns(ReferenceBuild.Release11_2, false))
    assertEquals(ReferenceBuild.LeiosB004fe0c.stability, ReferenceBuild.Stability.Unstable)
    assert(!ReferenceBuildFlag.warns(ReferenceBuild.Release11_1, false))
    assert(!ReferenceBuildFlag.warns(ReferenceBuild.Release11_1, true))
    assert(ReferenceBuildFlag.warns(ReferenceBuild.LeiosB004fe0c, false))
    assert(!ReferenceBuildFlag.warns(ReferenceBuild.LeiosB004fe0c, true))
    assertEquals(
      ReferenceBuild.Release11_1.describe,
      "11.1 (stable, cardano-node 11.1.3)"
    )
    assertEquals(
      ReferenceBuild.Release11_2.describe,
      "11.2 (pre-release, cardano-node 11.2.1)"
    )
    assertEquals(
      ReferenceBuild.LeiosB004fe0c.describe,
      "leios-b004fe0c (unstable, cardano-node b004fe0c)"
    )
  }

  test("each build's pins are registered in docs/data/reference-pins.json, naming this source") {
    val source = "protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/ReferenceBuild.scala"
    val pins = parser
      .parse(Files.readString(Path.of("docs/data/reference-pins.json")))
      .flatMap(_.hcursor.downField("pins").as[Vector[io.circe.Json]])
      .fold(e => fail(e.getMessage), identity)
    def registered(repository: String, field: String, value: String): Unit =
      val found = pins.filter(p =>
        p.hcursor.get[String]("repository").contains(repository) &&
          p.hcursor.get[String](field).contains(value)
      )
      assert(found.nonEmpty, s"$repository $field $value is not registered")
      assert(
        found.exists(
          _.hcursor.get[Vector[String]]("dependents").exists(_.contains(source))
        ),
        s"$repository $value does not list $source"
      )
    val release = ReferenceBuild.Release11_1.pins
    registered("IntersectMBO/cardano-node", "version", release.node)
    registered("IntersectMBO/cardano-ledger", "revision", release.ledger)
    registered("IntersectMBO/cardano-ledger", "revision", release.binary)
    registered("IntersectMBO/ouroboros-consensus", "revision", release.consensus)
    val next = ReferenceBuild.Release11_2.pins
    registered("IntersectMBO/cardano-node", "version", next.node)
    registered("IntersectMBO/cardano-ledger", "revision", next.ledger)
    registered("IntersectMBO/cardano-ledger", "revision", next.binary)
    registered("IntersectMBO/ouroboros-consensus", "revision", next.consensus)
    val leios = ReferenceBuild.LeiosB004fe0c.pins
    registered("IntersectMBO/cardano-node", "revision", leios.node)
    registered("IntersectMBO/cardano-ledger", "revision", leios.ledger)
    registered("IntersectMBO/ouroboros-consensus", "revision", leios.consensus)
    assert(ReferenceBuild.LeiosB004fe0c.name.endsWith(leios.node.take(8)))
  }

  test(
    "11.1's and the Leios layouts are read and written, 11.2's not yet; follow refuses 11.2 alone"
  ) {
    assertEquals(StateLayout.of(ReferenceBuild.Release11_1), Right(StateLayout.Node11_1))
    assertEquals(StateLayout.of(ReferenceBuild.LeiosB004fe0c), Right(StateLayout.Leios))
    assertEquals(
      StateLayout.of(ReferenceBuild.Release11_2),
      Left(ReferenceBuildNotYet.Layout(ReferenceBuild.Release11_2))
    )
    assertEquals(
      ReferenceBuildFlag.layout(ReferenceBuild.LeiosB004fe0c).map(_.layout),
      Right(StateLayout.Leios)
    )
    val notYet = Left(
      ConfigError(
        ConfigProblem.ReferenceBuildNotYet(ReferenceBuildNotYet.Layout(ReferenceBuild.Release11_2))
      )
    )
    assertEquals(ReferenceBuildFlag.layout(ReferenceBuild.Release11_2).map(_.build), notYet)
    assertEquals(ReferenceBuildFlag.follows(ReferenceBuild.Release11_2).map(_.build), notYet)
    assertEquals(
      ReferenceBuildFlag.follows(ReferenceBuild.Release11_1).map(_.build),
      Right(ReferenceBuild.Release11_1)
    )
    // the Leios build's Dijkstra runs on the era rules (#54)
    assertEquals(
      ReferenceBuildFlag.follows(ReferenceBuild.LeiosB004fe0c).map(_.build),
      Right(ReferenceBuild.LeiosB004fe0c)
    )
    assert(ReferenceLayout.Release11_1.reads(ReferenceBuild.Release11_1))
    assert(!ReferenceLayout.Release11_1.reads(ReferenceBuild.LeiosB004fe0c))
    assert(!ReferenceLayout.Release11_1.reads(ReferenceBuild.Release11_2))
    assertEquals(
      ReferenceLayout
        .of(ReferenceBuild.LeiosB004fe0c)
        .map(l => (l.reads(ReferenceBuild.LeiosB004fe0c), l.reads(ReferenceBuild.Release11_2))),
      Right((true, false))
    )
  }

  private val follow = List("follow", "--genesis-dir", "/g", "--peer", "127.0.0.1:3001")

  test("follow takes --reference-build, 11.1 without it, and refuses an unknown one by type") {
    assertEquals(Main.parse(follow).map(_.referenceBuild), Right(ReferenceBuild.Release11_1))
    assertEquals(
      Main.parse(follow ++ List("--reference-build", "leios-b004fe0c")).map(_.referenceBuild),
      Right(ReferenceBuild.LeiosB004fe0c)
    )
    assertEquals(
      Main.parse(follow ++ List("--reference-build", "11.2")).map(_.referenceBuild),
      Right(ReferenceBuild.Release11_2)
    )
    Main.parse(follow ++ List("--reference-build", "11.1.3")).map(_.referenceBuild) match
      case Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.ReferenceBuildUnknown(e), _))) =>
        assertEquals(e, UnknownReferenceBuild("11.1.3"))
      case other => fail(s"not refused as an unknown build: $other")
    assert(
      Main
        .parse(follow ++ List("--reference-build", "11.1", "--reference-build", "11.1"))
        .isLeft
    )
  }

  test("the ledger of every setup follows the rules of the build it is given (#832)") {
    val config = Path.of("fixtures", "preview", "genesis", "config.json").toString
    ReferenceBuild.values.filter(b => StateLayout.of(b).isRight).foreach { build =>
      val options = Main
        .parse(
          List(
            "follow",
            "--config",
            config,
            "--peer",
            "127.0.0.1:9",
            "--reference-build",
            build.name
          )
        )
        .fold(e => fail(e.message), identity)
      val setup = Main.nodeSetup(options).fold(e => fail(e.message), identity)
      assertEquals(setup.globals.referenceBuild, build)
      // the tools that read a configuration themselves (`LeiosCheckMain`, the snapshot commands)
      val directory = NodeSetup
        .fromDirectory(build)(Path.of("fixtures", "preview", "genesis"))
        .fold(e => fail(e.message), identity)
      assertEquals(directory.globals.referenceBuild, build, build.name)
    }
  }

  test(
    "a build whose layout is not implemented yet has no setup: its genesis state has no layout"
  ) {
    val refused = Left(
      ConfigError(
        ConfigProblem.ReferenceBuildNotYet(ReferenceBuildNotYet.Layout(ReferenceBuild.Release11_2))
      )
    )
    val options = Main
      .parse(
        List("follow", "--genesis-dir", "fixtures/preview/genesis", "--peer", "127.0.0.1:3001") ++
          List("--reference-build", "11.2")
      )
      .fold(e => fail(e.message), identity)
    assertEquals(Main.nodeSetup(options).map(_.globals.referenceBuild), refused)
    assertEquals(
      NodeSetup
        .fromDirectory(ReferenceBuild.Release11_2)(Path.of("fixtures", "preview", "genesis"))
        .map(_.globals.referenceBuild),
      refused
    )
  }

  test("the help says the value has to be the build the network runs, and lists every build") {
    assert(ReferenceBuildFlag.Help.contains("the cardano-node build the network runs"))
    assert(ReferenceBuildFlag.Help.contains("follow other rules than its peers"))
    ReferenceBuild.values.foreach(b => assert(ReferenceBuildFlag.Help.contains(b.name), b.name))
    Main.parse(List("help")) match
      case Left(ConfigError(ConfigProblem.WithUsage(_, usage))) =>
        assert(usage.contains("[--reference-build <build>]"))
        assert(usage.contains(ReferenceBuildFlag.Help))
      case other => fail(s"no usage: $other")
  }

  test("import-ledger-snapshot takes the build that wrote its input database") {
    val required = List(
      "--haskell-ledger",
      "/l",
      "--db",
      "/d",
      "--genesis-dir",
      "/g",
      "--no-verify-ancillary"
    )
    assertEquals(
      LedgerSnapshotImportCommand.parse(required).map(_.build),
      Right(ReferenceBuild.Release11_1)
    )
    assertEquals(
      LedgerSnapshotImportCommand
        .parse(required ++ List("--reference-build", "leios-b004fe0c"))
        .map(_.build),
      Right(ReferenceBuild.LeiosB004fe0c)
    )
    assertEquals(
      LedgerSnapshotImportCommand
        .parse(required ++ List("--reference-build", "11.2"))
        .map(_.build),
      Right(ReferenceBuild.Release11_2)
    )
    assert(LedgerSnapshotImportCommand.parse(required ++ List("--reference-build", "x")).isLeft)
  }
