# SPDX-License-Identifier: Apache-2.0
"""Unit tests for the pure parts of the cuddle cross-check (no Docker, stdlib unittest).

Run from the repository root:  python3 -m unittest discover -s scripts/tests -v
"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import cddl_crosscheck as cc  # noqa: E402

BASE = """;# import other as o
; a comment mentioning hash that is not a reference
hash = bytes .size 32 ; trailing hash
pair<a, b> = [a, b]
point = [] / [slot, hash]
slot = uint .size 8
msg = [0, point, o.tip, pair<slot, hash>, "hash", h'00', 0..max_slot]
max_slot = 18446744073709551615
$sock /= 1
$sock /= 2
"""
OTHER = """tip = [point, uint]
point = [uint]
"""
OVERRIDE = """hash = bytes
"""


class ChunkTest(unittest.TestCase):
    def test_rule_heads_and_generics(self):
        chunks = cc.chunk_rules(BASE)
        self.assertEqual([c[0] for c in chunks], ["hash", "pair", "point", "slot", "msg", "max_slot", "$sock", "$sock"])
        self.assertEqual(chunks[1][1], ["a", "b"])
        self.assertEqual([c[2] for c in chunks[-2:]], ["/=", "/="])

    def test_a_map_arrow_at_line_start_is_not_a_rule(self):
        chunks = cc.chunk_rules("m = {\nk => v\n}\nv = uint\n")
        self.assertEqual([c[0] for c in chunks], ["m", "v"])

    def test_formatted_output_rule_set(self):
        text = "hash = bytes .size 32\n\npair<a, b> = [a, b]\n\n$sock /= 1\n$sock /= 2\n"
        self.assertEqual(cc.rules_of_formatted(text), {("hash", 0), ("pair", 2), ("$sock", 0)})


class ClosureTest(unittest.TestCase):
    FILES = {"base.cddl": BASE, "other.cddl": OTHER, "override.cddl": OVERRIDE}
    MODULES = {"base": ["base.cddl"], "other": ["other.cddl"], "patched": ["base.cddl", "override.cddl"]}

    def table(self, skip=()):
        read = self.FILES.__getitem__
        # `patched` imports nothing, so give it the alias table of `base` through a second module
        return {m: cc.module_rules(read, self.MODULES, m, skip) for m in self.MODULES}

    def test_mangle(self):
        self.assertEqual(cc.mangle("network.base", "header"), "network_base__header")
        self.assertEqual(cc.mangle("a-b", "$$sock"), "$$a_b__sock")

    def test_closure_follows_local_and_imported_rules_and_renames_only_references(self):
        out = cc.closure(self.table(), [("base", "msg")])
        self.assertIn("base__msg = [0, base__point, other__tip, base__pair<base__slot, base__hash>", out)
        self.assertIn('"hash", h\'00\', 0..base__max_slot]', out)  # strings untouched, ranges resolved
        self.assertIn("other__point = [uint]", out)  # the other module's own `point`, not base's
        self.assertIn("base__hash = bytes .size 32", out)
        self.assertIn("pair<a, b>", cc.closure(self.table(), [("base", "pair")]).replace("base__", ""))
        self.assertNotIn("$sock", out)  # not reachable from msg

    def test_extensions_accumulate_and_a_later_file_replaces(self):
        t = self.table()
        self.assertEqual(len(t["base"]["$sock"].chunks), 2)
        self.assertEqual(t["patched"]["hash"].chunks[0][3].strip(), "hash = bytes")
        self.assertIn(".size 32", self.table()["base"]["hash"].chunks[0][3])

    def test_unknown_alias_member_is_an_error(self):
        t = self.table()
        t["base"]["bad"] = cc.Rule("base", [("bad", [], "=", "bad = o.nope\n")], {"o": "other"})
        with self.assertRaises(KeyError):
            cc.closure(t, [("base", "bad")])

    def test_type_parameters_shadow_rules(self):
        t = {"m": {"hash": cc.Rule("m", [("hash", [], "=", "hash = uint\n")], {}),
                   "box": cc.Rule("m", [("box", ["hash"], "=", "box<hash> = [hash]\n")], {})}}
        self.assertEqual(cc.closure(t, [("m", "box")]).strip(), "m__box<hash> = [hash]")


class AllowlistTest(unittest.TestCase):
    def test_parse(self):
        got = cc.read_allowlist("# c\n\nkind\tsubject x\twhy it is fine\n")
        self.assertEqual(got, [("kind", "subject x", "why it is fine")])

    def test_rejects_entries_without_reason(self):
        with self.assertRaises(SystemExit):
            cc.read_allowlist("kind\tsubject\t\n")

    def test_shipped_allowlist_parses(self):
        if cc.ALLOWLIST.exists():
            cc.read_allowlist(cc.ALLOWLIST.read_text())

    def test_models_conf_modules_and_roots(self):
        modules, roots = cc.load_conf((cc.CDDL_DIR / "models.conf").read_text())
        self.assertIn("conway", modules)
        self.assertIn("conway:block", roots)

    def test_every_vendored_module_resolves_without_a_dangling_reference(self):
        # the closure of every non-generic rule only raises for a reference into a module that lacks the rule
        modules, _ = cc.load_conf((cc.CDDL_DIR / "models.conf").read_text())
        read = lambda f: (cc.CDDL_DIR / f).read_text()
        table = {m: cc.module_rules(read, modules, m) for m in modules}
        for m, rules in table.items():
            cc.closure(table, [(m, r) for r in rules if not rules[r].chunks[0][1]])


if __name__ == "__main__":
    unittest.main()
