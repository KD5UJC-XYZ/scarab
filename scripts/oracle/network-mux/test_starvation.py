# SPDX-License-Identifier: Apache-2.0
"""Controls for lossless native-input storage and outcome/domain attribution."""
import hashlib
import unittest
from starvation import normalize, recipe


class StarvationExportTest(unittest.TestCase):
    def native(self, a=b'a'*3000, b=b'b'*4000, verdict='pass'):
        return ['mux-starvation-raw-v1', '512001', '0', '30', a.hex(), b.hex(),
                '8200440a0a0a0a', (b'prefix'+a).hex(), (b'prefix'+b).hex(), '2,3,2,3', verdict]

    def test_recipe_retains_exact_generated_bytes_and_digest(self):
        chunk = bytes(range(256))*4
        original = (chunk*3)[:2500]
        size, stored, digest = recipe(original)
        self.assertEqual((bytes.fromhex(stored)*3)[:int(size)], original)
        self.assertEqual(digest, hashlib.sha256(original).hexdigest())

    def test_nonrepeating_native_bytes_are_never_silently_rewritten(self):
        with self.assertRaisesRegex(ValueError, 'original chunk repetition'):
            recipe(b'a'*1024 + b'b')

    def test_native_failure_remains_failure_on_an_applicable_input(self):
        row = normalize(self.native(verdict='upstream-failure'))
        self.assertEqual(row[-2:], ['upstream-failure', 'applicable'])

    def test_implication_discards_are_retained_and_cannot_be_claimed_as_passes(self):
        row = self.native(a=b'a'*4, verdict='precondition-discard')
        row[9] = ''
        self.assertEqual(normalize(row)[-2:], ['precondition-discard', 'precondition-discard'])
        row[-1] = 'pass'
        with self.assertRaisesRegex(ValueError, 'implication/outcome mismatch'):
            normalize(row)

    def test_empty_generated_payload_is_retained_as_an_implication_discard(self):
        row = self.native(a=b'', verdict='precondition-discard')
        row[9] = ''
        result = normalize(row)
        self.assertEqual(result[4:7], ['0', '', hashlib.sha256(b'').hexdigest()])
        self.assertEqual(result[-2:], ['precondition-discard', 'precondition-discard'])

    def test_native_large_input_is_retained_with_explicit_local_exclusion(self):
        row = normalize(self.native(b=b'b'*2500000))
        self.assertEqual(row[-2:], ['pass', 'unsupported-message-limit'])
        self.assertEqual(int(row[7]), 2500000)

    def test_corrupt_native_wire_cannot_be_bound_to_a_different_payload(self):
        row = self.native()
        row[8] = '00'
        with self.assertRaisesRegex(ValueError, 'retain original payload'):
            normalize(row)


if __name__ == '__main__':
    unittest.main()
