# SPDX-License-Identifier: Apache-2.0
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("packet",HERE/"packet.py")
packet=importlib.util.module_from_spec(spec); spec.loader.exec_module(packet)

class PacketTests(unittest.TestCase):
    def test_duplicate_or_nonfinite_json_rejects(self):
        for raw in ['{"x":1,"x":2}','{"x":NaN}','{"x":Infinity}']:
            with self.assertRaises(ValueError): packet.strict_json(raw)
    def test_preserves_native_indefinite_array_original(self):
        raw=bytes.fromhex("819f0102ff")
        value=packet.items(raw)
        self.assertEqual(value[3][0][2],bytes.fromhex("9f0102ff"))
        self.assertEqual(value[3][0][1],2)
    def test_incomplete_or_unbounded_cbor_rejects(self):
        for raw in [b"",bytes.fromhex("9f01"),bytes.fromhex("810100"),b"\x81"*18+b"\x00"]:
            with self.assertRaises(ValueError): packet.items(raw)
    def test_expected_roles_keep_old_completed_distinction(self):
        result=packet.expected({"previousParameters":b"p","currentParameters":b"c"})
        a,b,negative=result["cases"]
        self.assertNotEqual(a["before"],b["before"])
        self.assertEqual(a["after"],b["after"])
        self.assertEqual(b["before"]["completedCurrent"],b"p".hex())
        self.assertEqual(b["after"]["outerCurrent"],b"c".hex())
        self.assertFalse(negative["nativeSTSExecuted"])
    def test_reused_source_pins_and_dependency_count(self):
        pins=json.loads((HERE/"source-pins.json").read_text())
        root=HERE.parents[1]
        for name,digest in pins["localHarness"].items():
            self.assertEqual(hashlib.sha256((root/name).read_bytes()).hexdigest(),digest)
        text=(HERE/"audited-governance-roles-diff.cabal").read_text()
        self.assertEqual(text.count(" == "),16)

if __name__=="__main__": unittest.main()
