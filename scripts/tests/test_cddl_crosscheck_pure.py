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

class StrictVerdictTest(unittest.TestCase):
    def verdict(self, *, preserved=False, normalized_valid=1, accepted=1):
        import contextlib
        import io
        import tempfile
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as directory:
            work = pathlib.Path(directory)
            (work / "decode.tsv").write_text("".join(
                f"m:r\t{mode}\t1\t{accepted}\t0\t{accepted}\tdecode rejected\tkey order differs\n"
                for mode in ("exact", "twiddle")))
            (work / "rules.tsv").write_text("")
            (work / "unsupported.tsv").write_text("")
            results = [
                {"op": "validate", "id": "m:r", "valid": 1, "invalid": []},
                {"op": "validate", "id": "control:m:r", "valid": 0, "invalid": []},
            ]
            for mode in ("exact", "twiddle"):
                results.extend([
                    {"op": "gen", "id": "m:r", "mode": mode, "terms": ["00"], "errors": [], "count": 1},
                    {"op": "validate", "id": f"generated:{mode}:m:r", "valid": 1, "invalid": []},
                    {"op": "validate", "id": f"normalized:{mode}:m:r", "valid": normalized_valid, "invalid": []},
                ])
            with contextlib.redirect_stdout(io.StringIO()):
                return cc.judge(work, results, ["m:r"], {"m:r": "root"}, SimpleNamespace(),
                                {"m:r"} if preserved else set())

    def test_non_preserved_map_may_change_order_after_independent_validation(self):
        self.assertEqual(self.verdict(), [])

    def test_preserved_values_must_remain_byte_exact_in_both_modes(self):
        failures = self.verdict(preserved=True)
        self.assertEqual(sum("not-byte-exact" in failure for failure in failures), 2)

    def test_normalized_writer_output_must_pass_independent_validation(self):
        self.assertTrue(any("independent validation" in failure for failure in self.verdict(normalized_valid=0)))

    def test_no_decoder_rejection_is_suppressed(self):
        self.assertTrue(any("decode-reject" in failure for failure in self.verdict(accepted=0, normalized_valid=0)))

class GenerationAdaptationTest(unittest.TestCase):
    def test_unsigned_controls_keep_the_exact_named_domain(self):
        original = 'u8 = uint .size 1\nu64 = uint .size 8\nr = [u8 .ne 4, u64 .gt 2]\n'
        adapted, controls = cc.generation_controls(original)
        self.assertIn('(0..3 / 5..255)', adapted)
        self.assertIn('(3..18446744073709551615)', adapted)
        self.assertEqual([control['expression'] for control in controls], ['u8 .ne 4', 'u64 .gt 2'])
        self.assertIn('u8 .ne 4', original)

    def test_generation_adaptation_does_not_rewrite_literals_or_comments(self):
        original = 'u8 = uint .size 1\nr = ["u8 .gt 2", h\'7538202e67742032\'] ; u8 .ne 4\n'
        adapted, controls = cc.generation_controls(original)
        self.assertEqual(adapted, original)
        self.assertEqual(controls, [])

    def test_unproved_or_empty_domains_fail_instead_of_widening_the_schema(self):
        for original in ('u = tstr\nr = u .gt 2\n', 'u = uint .size 1\nr = u .gt 255\n'):
            with self.assertRaises(ValueError):
                cc.generation_controls(original)

    def test_uint_controls_encode_full_widths(self):
        self.assertEqual(cc.uint_hex(2), '02')
        self.assertEqual(cc.uint_hex(256), '190100')
        self.assertEqual(cc.uint_hex((1 << 64) - 1), '1bffffffffffffffff')


class NativeGeneratorAccountingTest(unittest.TestCase):
    def job(self):
        return {"op": "gen", "count": 2, "seed": 1, "size": 12, "twiddle": False, "rule": "r", "file": "r.cddl"}

    def test_only_exact_pinned_duplicate_key_abort_is_resampled_and_recorded(self):
        from unittest.mock import patch
        reason = "cuddle: toCanonical: encountered a map with duplicate keys, which is not currently supported.\ntrace"
        with patch.object(cc, "cuddle", side_effect=[(1, "", reason), (0, "01\n", ""), (0, "02\n", "")]):
            result = cc.do_job(self.job())
        self.assertEqual(result["terms"], ["01", "02"])
        self.assertEqual(result["generated_seeds"], [2, 3])
        self.assertEqual(result["attempts"], 3)
        self.assertEqual(result["errors"], [])
        self.assertEqual(result["native_generator_non_results"], [{"seed": 1, "attempt": 1, "rc": 1, "error": reason}])

    def test_a_returned_term_is_never_discarded_or_resampled(self):
        from unittest.mock import patch
        with patch.object(cc, "cuddle", side_effect=[(0, "f4\n", ""), (0, "01\n", "")]):
            result = cc.do_job(self.job())
        self.assertEqual(result["terms"], ["f4", "01"])
        self.assertEqual(result["attempts"], 2)
        self.assertEqual(result["native_generator_non_results"], [])

    def test_unknown_errors_and_exhausted_retry_budget_fail(self):
        from unittest.mock import patch
        with patch.object(cc, "cuddle", return_value=(1, "", "another error")):
            result = cc.do_job(self.job())
        self.assertTrue(result["errors"])
        self.assertEqual(result["attempts"], 1)
        reason = "cuddle: toCanonical: encountered a map with duplicate keys, which is not currently supported."
        with patch.object(cc, "cuddle", return_value=(1, "", reason)):
            result = cc.do_job(self.job())
        self.assertTrue(result["errors"])
        self.assertEqual(result["attempts"], 8)
        self.assertEqual(len(result["native_generator_non_results"]), 8)

    def test_literal_enum_generation_expansion_keeps_exact_choices(self):
        original = 'r = &("PubKey": 0, "Script": 1, "Redeem": 2) / uint\n'
        adapted, changes = cc.generation_controls(original)
        self.assertEqual(adapted, 'r = (0 / 1 / 2) / uint\n')
        self.assertEqual(changes[0]["choices"], [0, 1, 2])
        original = 'r = &("label: 123": 2)\n'
        self.assertEqual(cc.generation_controls(original)[1][0]["choices"], [2])


if __name__ == "__main__":
    unittest.main()
