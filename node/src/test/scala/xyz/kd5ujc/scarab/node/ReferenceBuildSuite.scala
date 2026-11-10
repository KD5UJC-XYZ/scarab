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
  test("every build prints as its flag string and parses back; any other text is a typed error") {
    ReferenceBuild.values.foreach(b => assertEquals(ReferenceBuild.parse(b.name), Right(b)))
    assertEquals(ReferenceBuild.values.map(_.name).toVector, Vector("11.1", "leios-b004fe0c"))
    Vector("11.1.3", "11.1.0", "11", "", " 11.1", "11.1 ", "mainline", "leios", "Leios-b004fe0c")
      .foreach(text => assertEquals(ReferenceBuild.parse(text), Left(UnknownReferenceBuild(text))))
    assertEquals(ReferenceBuild.Default, ReferenceBuild.Release11_1)
  }

  test(
    "a stable release line and an unstable Leios build, and only the unstable one wants the flag"
  ) {
    assertEquals(ReferenceBuild.Release11_1.stability, ReferenceBuild.Stability.Stable)
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
    val leios = ReferenceBuild.LeiosB004fe0c.pins
    registered("IntersectMBO/cardano-node", "revision", leios.node)
    registered("IntersectMBO/cardano-ledger", "revision", leios.ledger)
    registered("IntersectMBO/ouroboros-consensus", "revision", leios.consensus)
    assert(ReferenceBuild.LeiosB004fe0c.name.endsWith(leios.node.take(8)))
  }

  test("both layouts are read and written; follow refuses the Leios build's rules, not yet") {
    assertEquals(StateLayout.of(ReferenceBuild.Release11_1), StateLayout.Node11_1)
    assertEquals(StateLayout.of(ReferenceBuild.LeiosB004fe0c), StateLayout.Leios)
    assertEquals(
      ReferenceBuildFlag.layout(ReferenceBuild.LeiosB004fe0c).layout,
      StateLayout.Leios
    )
    assertEquals(
      ReferenceBuildFlag.follows(ReferenceBuild.Release11_1).map(_.build),
      Right(ReferenceBuild.Release11_1)
    )
    assertEquals(
      ReferenceBuildFlag.follows(ReferenceBuild.LeiosB004fe0c).map(_.build),
      Left(
        ConfigError(
          ConfigProblem.ReferenceBuildNotYet(
            ReferenceBuildNotYet.Rules(ReferenceBuild.LeiosB004fe0c)
          )
        )
      )
    )
    assert(ReferenceLayout.Release11_1.reads(ReferenceBuild.Release11_1))
    assert(!ReferenceLayout.Release11_1.reads(ReferenceBuild.LeiosB004fe0c))
    assert(ReferenceLayout.of(ReferenceBuild.LeiosB004fe0c).reads(ReferenceBuild.LeiosB004fe0c))
  }

  private val follow = List("follow", "--genesis-dir", "/g", "--peer", "127.0.0.1:3001")

  test("follow takes --reference-build, 11.1 without it, and refuses an unknown one by type") {
    assertEquals(Main.parse(follow).map(_.referenceBuild), Right(ReferenceBuild.Release11_1))
    assertEquals(
      Main.parse(follow ++ List("--reference-build", "leios-b004fe0c")).map(_.referenceBuild),
      Right(ReferenceBuild.LeiosB004fe0c)
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
    assert(LedgerSnapshotImportCommand.parse(required ++ List("--reference-build", "x")).isLeft)
  }
