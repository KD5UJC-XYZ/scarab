# SPDX-License-Identifier: Apache-2.0
import copy
import unittest
from private_cluster import Runner
from private_cluster_coherent import fresh_genesis, complete_utxo, shelley_funds, FIXTURE


def fixture():
    funds = {("60" + bytes([i] * 28).hex()): 15000003000000 for i in range(3)}
    funds.update({("00" + bytes([i] * 56).hex()): 15000003000000 for i in range(3)})
    shelley = {"networkId": "Testnet", "networkMagic": 1082026,
        "maxLovelaceSupply": 100000020000000, "initialFunds": {}, "staking": {},
        "extraConfig": {"initialFunds": {"data": funds},
            "stakeCredentials": {"data": {"stake": "pool"}},
            "stakePools": {"data": {"pool1": {}, "pool2": {}}}},
        "protocolParams": {"protocolVersion": {"major": 10, "minor": 0}}}
    byron = {"protocolConsts": {"protocolMagic": 1082026}, "avvmDistr": {},
        "nonAvvmBalances": {"bootstrap1": "3000000000", "bootstrap2": "3000000000"},
        "bootStakeholders": {"keep": 1}, "heavyDelegation": {"keep": {}}, "startTime": 123}
    return byron, shelley


class CoherentFixtureTests(unittest.TestCase):
    def test_only_new_byron_allocations_change_and_inputs_are_preserved(self):
        byron, shelley = fixture()
        original = copy.deepcopy((byron, shelley))
        effective = fresh_genesis(byron, shelley)
        self.assertEqual((byron, shelley), original)
        self.assertEqual(effective, dict(byron, nonAvvmBalances={}))
        self.assertEqual(len(shelley_funds(shelley)), 6)

    def test_unexpected_byron_network_or_allocation_shape_rejects(self):
        for mutate in (
            lambda b: b["protocolConsts"].update(protocolMagic=1),
            lambda b: b.update(avvmDistr={"other": "1"}),
            lambda b: b.update(nonAvvmBalances={}),
            lambda b: b["nonAvvmBalances"].update(bootstrap1="2999999999")):
            b, s = fixture(); mutate(b)
            with self.assertRaises(ValueError): fresh_genesis(b, s)

    def test_supported_funds_staking_supply_and_network_required(self):
        for mutate in (
            lambda s: s.update(networkMagic=1),
            lambda s: s.update(maxLovelaceSupply=1),
            lambda s: s["extraConfig"]["stakePools"].update(data={}),
            lambda s: s["extraConfig"]["initialFunds"]["data"].pop(next(iter(s["extraConfig"]["initialFunds"]["data"]))),
            lambda s: s["extraConfig"]["initialFunds"]["data"].update(bad=1)):
            b, s = fixture(); mutate(s)
            with self.assertRaises(ValueError): fresh_genesis(b, s)

    def initial(self):
        _, s = fixture()
        funds = shelley_funds(s)
        decoded = {"addr-test-" + str(i): address for i, address in enumerate(funds)}
        queried = {bytes([i] * 32).hex() + "#0": {"address": address,
            "value": {"lovelace": funds[decoded[address]]}, "datum": None,
            "datumhash": None, "inlineDatum": None, "inlineDatumRaw": None,
            "referenceScript": None} for i, address in enumerate(decoded)}
        return s, queried, decoded

    def test_complete_state_matches_every_allocation_without_filtering(self):
        s, queried, decoded = self.initial()
        receipt = complete_utxo(s, queried, decoded)
        self.assertEqual(receipt["entries"], 6)
        self.assertTrue(receipt["allGenesisAllocationsMatched"])
        self.assertFalse(receipt["filtered"])

    def test_extra_missing_or_changed_actual_outputs_reject(self):
        for mutate in (
            lambda q: q.update({"ff" * 32 + "#0": copy.deepcopy(next(iter(q.values())))}),
            lambda q: q.pop(next(iter(q))),
            lambda q: next(iter(q.values()))["value"].update(lovelace=1),
            lambda q: next(iter(q.values())).update(referenceScript={"script": "x"}),
            lambda q: next(iter(q.values())).update(address="unknown")):
            s, q, d = self.initial(); mutate(q)
            with self.assertRaises(ValueError): complete_utxo(s, q, d)

    def test_duplicate_address_or_assets_cannot_hide_omitted_state(self):
        s, q, d = self.initial(); rows = list(q.values()); rows[1]["address"] = rows[0]["address"]
        with self.assertRaises(ValueError): complete_utxo(s, q, d)
        s, q, d = self.initial(); next(iter(q.values()))["value"]["policy"] = {}
        with self.assertRaises(ValueError): complete_utxo(s, q, d)

    def test_default_runner_hook_does_nothing_and_new_profile_is_named(self):
        self.assertIsNone(Runner.prepare_genesis(object()))
        self.assertEqual(FIXTURE, "conway-pv9-empty-byron-allocations-coherent-v1")


if __name__ == "__main__": unittest.main()
