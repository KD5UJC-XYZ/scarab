// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.file.{Files, Path}
import munit.FunSuite

class PlutusReferenceSuite extends FunSuite:
  test("every release releases.json records has a PlutusRelease, and no other") {
    assertEquals(
      PlutusRelease.values.map(_.tag).toVector.sorted,
      PlutusReference.recordedTags.sorted
    )
  }

  test("each release's view has its conformance cases, cost models and parameter names") {
    PlutusRelease.values.foreach { release =>
      val view = PlutusReference.view(release)
      assert(view.under("conformance").exists(_._1.endsWith(".uplc")), release.tag)
      assert(view.contains("cost-model/builtinCostModelE.json"), release.tag)
      assert(view.contains("cost-model/cekMachineCostsE.json"), release.tag)
      assert(view.contains("ledger-api/V3-param-names.txt"), release.tag)
      assert(!view.files.keys.exists(_.endsWith("README.md")), release.tag)
      assert(!view.files.keys.exists(_.startsWith("overlays/")), release.tag)
    }
  }

  private def write(root: Path, name: String, text: String): Unit =
    val file = root.resolve(name)
    Files.createDirectories(file.getParent)
    Files.writeString(file, text)

  test("an older release reads its overlay over the base, without its absent files") {
    val root = Files.createTempDirectory("plutus-reference")
    write(
      root,
      "releases.json",
      """{"schemaVersion": 1, "base": "2", "generated": [], "releases": [
        |  {"tag": "1", "absent": ["conformance/b/new.uplc"]},
        |  {"tag": "2", "absent": []}]}""".stripMargin
    )
    write(root, "README.md", "about")
    write(root, "conformance/a/a.uplc", "base program")
    write(root, "conformance/a/a.budget.expected", "base budget")
    write(root, "conformance/b/new.uplc", "only in 2")
    write(root, "overlays/1/conformance/a/a.budget.expected", "older budget")
    write(root, "overlays/1/conformance/c/gone.uplc", "only in 1")

    val newer = PlutusReference.viewOf(root, "2")
    val older = PlutusReference.viewOf(root, "1")
    assertEquals(
      newer.files.keySet,
      Set("conformance/a/a.uplc", "conformance/a/a.budget.expected", "conformance/b/new.uplc")
    )
    assertEquals(newer.read("conformance/a/a.budget.expected"), "base budget")
    assertEquals(
      older.files.keySet,
      Set("conformance/a/a.uplc", "conformance/a/a.budget.expected", "conformance/c/gone.uplc")
    )
    assertEquals(older.read("conformance/a/a.budget.expected"), "older budget")
    assertEquals(older.read("conformance/a/a.uplc"), "base program")
    assertEquals(older.under("conformance/a").map(_._1), Vector("a.budget.expected", "a.uplc"))
  }
