# SPDX-License-Identifier: Apache-2.0
"""check_native_identity: every packaged blst library is a pinned one, and the Linux/amd64 one is gated."""
from pathlib import Path
import hashlib
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_native_identity as identity

AMD64 = b'portable linux amd64'
AARCH64 = b'linux aarch64'
GATED = dict(instructions={'mulx': 3}, extensionFunctions=1, gatedRegion=2, ungatedEntries=[])
UNGATED = dict(instructions={'mulx': 3}, extensionFunctions=1, gatedRegion=0,
               ungatedEntries=[{'from': 'f', 'to': 'g', 'instruction': 'call'}])
DEPENDENCIES = '    val jblst = "0.3.18"\n'


def sha(data):
    return hashlib.sha256(data).hexdigest()


PINS = (f'    "Linux/amd64/libblst.so" -> "{sha(AMD64)}",\n'
        f'    "Linux/aarch64/libblst.so" -> "{sha(AARCH64)}"\n')


class NativeIdentityTest(unittest.TestCase):
    def receipt(self, libraries, isa=GATED):
        with tempfile.TemporaryDirectory() as scratch:
            jar = Path(scratch) / 'x.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for name, data in libraries.items():
                    archive.writestr(identity.PREFIX + name, data)
                archive.writestr('supranational/blst/blstJNI.class', b'class')
            with patch.object(identity, 'analyse_library', return_value=isa):
                return identity.verify_texts(jar, PINS, DEPENDENCIES)

    def test_the_pinned_libraries_pass(self):
        receipt = self.receipt({'Linux/amd64/libblst.so': AMD64, 'Linux/aarch64/libblst.so': AARCH64})
        self.assertEqual(receipt['status'], 'passed')
        self.assertEqual(receipt['version'], '0.3.18')
        self.assertEqual(receipt['isa']['ungatedEntries'], 0)

    def test_other_bytes_for_a_pinned_library_fail(self):
        receipt = self.receipt({'Linux/amd64/libblst.so': b'other', 'Linux/aarch64/libblst.so': AARCH64})
        self.assertEqual(receipt['status'], 'failed')
        self.assertIn('Linux/amd64/libblst.so', receipt['error'])

    def test_a_missing_or_an_unpinned_library_fails(self):
        missing = self.receipt({'Linux/amd64/libblst.so': AMD64})
        self.assertEqual(missing['status'], 'failed')
        self.assertIn('Linux/aarch64/libblst.so', missing['error'])
        extra = self.receipt({'Linux/amd64/libblst.so': AMD64, 'Linux/aarch64/libblst.so': AARCH64,
                              'Mac/aarch64/libblst.dylib': b'unpinned'})
        self.assertEqual(extra['status'], 'failed')
        self.assertIn('Mac/aarch64/libblst.dylib', extra['error'])

    def test_ungated_extension_code_fails(self):
        receipt = self.receipt({'Linux/amd64/libblst.so': AMD64, 'Linux/aarch64/libblst.so': AARCH64}, UNGATED)
        self.assertEqual(receipt['status'], 'failed')
        self.assertIn('outside the CPU switch', receipt['error'])

    def test_the_pins_must_include_linux_amd64(self):
        with self.assertRaises(ValueError):
            identity.pins_from('    "Linux/aarch64/libblst.so" -> "' + 'a' * 64 + '"\n')


if __name__ == '__main__':
    unittest.main()
