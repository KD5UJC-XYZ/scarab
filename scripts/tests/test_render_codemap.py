# SPDX-License-Identifier: Apache-2.0
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import render_codemap as rc

SBT = '''
lazy val codec = scarab("codec")
lazy val blstNative = scarab("blstNative", "blst-native")
lazy val crypto = scarab("crypto")
  .dependsOn(codec % "compile->compile;test->test", blstNative)
lazy val models = scarab("models")
  .dependsOn(
    codec,
    modelsGen % "test->compile"
  )
lazy val modelsGen = scarab("modelsGen", "models-gen")
lazy val root = project
'''

ENTRY = {
    "module": "codec", "path": "codec", "responsibility": "Bytes", "entryPoints": ["Cbor"],
    "suites": ["CborSuite"], "docs": ["docs/adr/0005-x.md"],
}


def write(root: Path, name: str, content: str) -> None:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def fixture(entries=None, modules=None) -> tempfile.TemporaryDirectory:
    tmp = tempfile.TemporaryDirectory()
    root = Path(tmp.name)
    write(root, "build.sbt", SBT)
    write(root, "codec/src/main/scala/xyz/kd5ujc/scarab/codec/Cbor.scala", "object Cbor\n")
    write(root, "codec/src/test/scala/xyz/kd5ujc/scarab/codec/CborSuite.scala", "class CborSuite\n")
    write(root, "docs/adr/0005-x.md", "# x\n")
    data = {"modules": modules or {m: "M" for m in ("codec", "blst-native", "crypto", "models", "models-gen")},
            "packages": [ENTRY] if entries is None else entries}
    write(root, rc.DATA, json.dumps(data))
    return tmp


class GraphCase(unittest.TestCase):
    def test_edges_by_directory_and_test_only(self):
        graph = rc.parse_build(SBT)
        self.assertEqual(list(graph), ["codec", "blst-native", "crypto", "models", "models-gen"])
        self.assertEqual(graph["crypto"], [rc.Edge("codec", True), rc.Edge("blst-native", True)])
        self.assertEqual(graph["models"], [rc.Edge("codec", True), rc.Edge("models-gen", False)])
        self.assertEqual(graph["codec"], [])

    def test_graph_lines(self):
        lines = rc.graph_lines(rc.parse_build(SBT))
        self.assertIn("models      -> codec; test only: models-gen", lines)

    def test_real_build_matches_documented_direction(self):
        root = Path(__file__).resolve().parents[2]
        graph = rc.parse_build((root / "build.sbt").read_text(encoding="utf-8"))
        self.assertEqual([e.target for e in graph["node"]], ["storage", "legacy", "network"])


class CheckCase(unittest.TestCase):
    def check(self, entries=None, mutate=None) -> list[str]:
        tmp = fixture(entries)
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        if mutate:
            mutate(root)
        data, graph, text = rc.build(root)
        found = rc.problems(root, data, graph)
        target = root / rc.OUTPUT
        if not target.is_file() or target.read_text(encoding="utf-8") != text:
            found.append("stale markdown")
        return found

    def test_clean_when_rendered(self):
        def render(root):
            write(root, rc.OUTPUT, rc.build(root)[2])
        self.assertEqual(self.check(mutate=render), [])

    def test_stale_markdown(self):
        self.assertEqual(self.check(), ["stale markdown"])

    def test_missing_symbol(self):
        found = self.check([{**ENTRY, "entryPoints": ["Cbor", "Gone"]}])
        self.assertTrue(any("Gone is not defined" in f for f in found))
        self.assertFalse(any("Cbor is not" in f for f in found))

    def test_symbol_must_be_defined_not_mentioned(self):
        def mention(root):
            write(root, "codec/src/main/scala/xyz/kd5ujc/scarab/codec/Cbor.scala", "// Gone\nobject Cbor\n")
        found = self.check([{**ENTRY, "entryPoints": ["Gone"]}], mutate=mention)
        self.assertTrue(any("Gone is not defined" in f for f in found))

    def test_missing_suite(self):
        found = self.check([{**ENTRY, "suites": ["NopeSuite"]}])
        self.assertTrue(any("NopeSuite does not exist" in f for f in found))

    def test_missing_doc(self):
        found = self.check([{**ENTRY, "docs": ["docs/adr/9999-none.md"]}])
        self.assertTrue(any("9999-none.md does not exist" in f for f in found))

    def test_package_without_entry(self):
        def add(root):
            write(root, "codec/src/main/scala/xyz/kd5ujc/scarab/codec/extra/X.scala", "object X\n")
        found = self.check(mutate=add)
        self.assertTrue(any("codec:codec/extra: package directory has no codemap entry" in f for f in found))

    def test_entry_for_gone_package(self):
        found = self.check([ENTRY, {**ENTRY, "path": "codec/old"}])
        self.assertTrue(any("codec:codec/old: entry names a package that is gone" in f for f in found))

    def test_unknown_module_and_duplicate(self):
        found = self.check([ENTRY, ENTRY, {**ENTRY, "module": "ghost", "path": "ghost"}])
        self.assertTrue(any("duplicate entry codec:codec" in f for f in found))
        self.assertTrue(any("ghost:ghost: module is not defined" in f for f in found))

    def test_scalus_style_package_outside_scarab_root(self):
        def add(root):
            write(root, "codec/src/main/scala/scalus/uplc/V.scala", "object V\n")
        found = self.check([ENTRY, {**ENTRY, "path": "scalus/uplc", "entryPoints": ["V"]}], mutate=add)
        self.assertEqual(found, ["stale markdown"])


class RenderCase(unittest.TestCase):
    def test_render_is_marked_generated_and_stable(self):
        tmp = fixture()
        self.addCleanup(tmp.cleanup)
        _, _, text = rc.build(Path(tmp.name))
        self.assertTrue(text.startswith("<!-- GENERATED"))
        self.assertIn("[ADR 0005](adr/0005-x.md)", text)
        self.assertIn("No main sources.", text)
        self.assertEqual(text, rc.build(Path(tmp.name))[2])

    def test_committed_codemap_is_current_and_short(self):
        root = Path(__file__).resolve().parents[2]
        data, graph, text = rc.build(root)
        self.assertEqual(rc.problems(root, data, graph), [])
        self.assertEqual((root / rc.OUTPUT).read_text(encoding="utf-8"), text)
        self.assertLess(text.count("\n"), 300)


if __name__ == "__main__":
    unittest.main()
