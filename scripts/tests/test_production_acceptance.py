# SPDX-License-Identifier: Apache-2.0
"""Public archived-header and synthetic observation controls; no forging/production claim."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from observe_produced_block import header_identity, public_events
from production_acceptance import AuthenticatedAcceptance, validate
from publication_common import PublicationError, canonical
from prepare_reference_image import BINARY_HASHES

ROOT = Path(__file__).resolve().parents[2]


class ProductionAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.raw = (ROOT / 'fixtures/praos/evidence/preprod_70070331.cbor').read_bytes()
        self.point = header_identity(self.raw)
        self.value = dict(schemaVersion=1, repository='example/scarab', sourceCommit='a' * 40,
                          candidateSha256='b' * 64, jarSha256='c' * 64, imageDigest='sha256:' + 'd' * 64,
                          network=dict(name='preprod', magic=1, genesisSha256={era: 'e' * 64 for era in
                                       ['byron', 'shelley', 'alonzo', 'conway']}),
                          produced=dict(self.point, headerCbor=self.raw.hex(), forged=True, adopted=True),
                          native=dict(binarySha256=BINARY_HASHES['cardano-node'], acquiredSlot=self.point['slot'],
                                      acquiredHash=self.point['hash'], queryPointMatched=True),
                          observedAt='2026-10-23T12:00:00Z')

    def checked(self, value):
        return validate(value, 'example/scarab', 'a' * 40, 'b' * 64, 'c' * 64, 'sha256:' + 'd' * 64)

    def test_archived_original_header_slot_vrf_and_exact_bytes(self):
        self.assertEqual(self.point['slot'], 70070331)
        self.assertEqual(self.point['hash'], '076218aa483344e34620d3277542ecc9e7b382ae2407a60e177bc3700548364c')
        self.assertEqual(self.point['vrfKeyHash'], '41f661fcc0ee3e16b3c4105f993c169c89846bb77200bfb81ac15cac1eb38c6a')
        with self.assertRaises(PublicationError):
            header_identity(self.raw + b'\x00')

    def test_complete_synthetic_observation_is_not_authenticated_authority(self):
        self.assertEqual(self.checked(self.value), self.value)
        with self.assertRaises(PublicationError):
            AuthenticatedAcceptance(canonical(self.value), object())

    def test_follower_only_wrong_point_native_binary_date_and_artifact_refused(self):
        changes = [('produced', 'forged', False), ('produced', 'adopted', False), ('produced', 'hash', 'f' * 64),
                   ('produced', 'pool', 'f' * 56), ('native', 'binarySha256', 'f' * 64),
                   ('native', 'acquiredSlot', self.point['slot'] + 1), ('native', 'queryPointMatched', False),
                   ('network', 'magic', 2)]
        for group, key, value in changes:
            altered = deepcopy(self.value)
            altered[group][key] = value
            with self.subTest(group=group, key=key), self.assertRaises(PublicationError):
                self.checked(altered)
        for key, value in [('sourceCommit', 'f' * 40), ('jarSha256', 'f' * 64),
                           ('observedAt', '2026-10-23T12:00:00'), ('schemaVersion', True)]:
            altered = dict(self.value, **{key: value})
            with self.subTest(key=key), self.assertRaises(PublicationError):
                self.checked(altered)

    def test_public_events_need_same_producer_and_ordered_forged_adopted_point(self):
        import json
        started = dict(event='forgeStarted', pool=self.point['pool'], vrfKeyHash=self.point['vrfKeyHash'])
        forged = dict(event='forged', slot=self.point['slot'], hash=self.point['hash'])
        adopted = dict(event='adopted', block={key: self.point[key] for key in ['slot', 'hash']})
        lines = lambda rows: '\n'.join(json.dumps(row) for row in rows)
        public_events(lines([started, forged, adopted]), self.point)
        for rows in [[adopted], [started, adopted], [forged, started, adopted],
                     [dict(started, pool='f' * 56), forged, adopted],
                     [started, forged, dict(adopted, block=dict(slot=self.point['slot'] + 1, hash=self.point['hash']))]]:
            with self.subTest(rows=rows), self.assertRaises(PublicationError):
                public_events(lines(rows), self.point)


if __name__ == '__main__':
    unittest.main()
