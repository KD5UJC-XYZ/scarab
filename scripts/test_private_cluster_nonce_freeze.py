# SPDX-License-Identifier: Apache-2.0
import unittest
from private_cluster_nonce_freeze import checked_window, PINS

class NonceFreezeTests(unittest.TestCase):
    def tip(self, slot, block):
        return {"era":"Conway", "epoch":slot//500, "slot":slot, "slotInEpoch":slot%500, "block":block}

    def test_explicit_complete_bound_accepts_more_than_eight_without_changing_checkpoint(self):
        result=checked_window(self.tip(1020,10),self.tip(1130,26))
        self.assertEqual(result["expectedCompleteBlocks"],16)
        self.assertEqual(result["stabilizationWindow"],400)
        self.assertFalse(result["epochTickChecked"])

    def test_epoch_tick_truncation_and_outside_brackets_reject(self):
        pre=self.tip(1020,10)
        for post in (self.tip(1510,15),self.tip(1099,15),self.tip(1201,15),self.tip(1130,11),self.tip(1130,27)):
            with self.assertRaises(ValueError): checked_window(pre,post)
        with self.assertRaises(ValueError): checked_window(self.tip(1050,10),self.tip(1130,15))

    def test_geometry_and_types_reject(self):
        for change in ({"epoch":3},{"slotInEpoch":False},{"slot":True},{"era":"Babbage"}):
            with self.assertRaises(ValueError): checked_window(dict(self.tip(1020,10),**change),self.tip(1130,15))

    def test_manifest_has_exact_separate_pre_post_sources(self):
        self.assertEqual(len(PINS),9)
        self.assertEqual(PINS["preProtocolSha256"],"pre-protocol-state.md")
        self.assertEqual(PINS["postProtocolSha256"],"post-protocol-state.md")
