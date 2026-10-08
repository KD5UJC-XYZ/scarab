# SPDX-License-Identifier: Apache-2.0
import copy
import unittest
from private_cluster_minimum_output import validate_pair

class MinimumOutputGuards(unittest.TestCase):
    def pair(self):
        def receipt(coin, change):
            return {"profile": "conway-pv9-testnet-ada-minimum-output-v1", "satisfied": coin == 849070,
                "outputs": [{"coin": coin, "required": 849070, "serializedBytes": 37,
                             "originalHex": "82", "satisfied": coin == 849070},
                            {"coin": change, "required": 866310, "serializedBytes": 41,
                             "originalHex": "82", "satisfied": True}]}
        return receipt(849070, 10000000), receipt(849069, 10000001)
    def test_exact_pair(self):
        validate_pair(*self.pair())
    def test_changed_context_size_or_balance_rejected(self):
        for key, value in [("required", 849071), ("serializedBytes", 38), ("coin", 849068), ("originalHex", "a2")]:
            p, n = self.pair(); n["outputs"][0][key] = value
            with self.assertRaises(ValueError): validate_pair(p, n)
        p, n = self.pair(); n["outputs"][1]["coin"] += 1
        with self.assertRaises(ValueError): validate_pair(p, n)
    def test_missing_or_wrong_predicate_rejected(self):
        p, n = self.pair(); n["satisfied"] = True
        with self.assertRaises(ValueError): validate_pair(p, n)
        p, n = self.pair(); n["outputs"].pop()
        with self.assertRaises(ValueError): validate_pair(p, n)

if __name__ == "__main__": unittest.main()
