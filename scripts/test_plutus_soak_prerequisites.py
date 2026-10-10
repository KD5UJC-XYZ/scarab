import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import private_cluster_plutus_early_restart as restart
import plutus_soak_fixture as fixture

class SoakPrerequisiteTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def test_native_mount_option_requires_independent_complete_library_pin(self):
        binary=self.root/"binary";binary.write_bytes(b"test-only-not-executed");binary.chmod(0o700)
        libs=self.root/"libs";libs.mkdir();(libs/"a.so.1").write_bytes(b"lib");(libs/"a.so").symlink_to("a.so.1")
        record=restart.library_manifest(libs)
        mounts=restart.NativeRuntimeMounts(binary,restart.digest(binary.read_bytes()),libs,restart.digest(restart.encoded(record)))
        self.assertEqual(len(mounts.checked_mounts()),2)
        (libs/"a.so.1").write_bytes(b"changed")
        with self.assertRaises(ValueError):mounts.checked_mounts()

    def test_native_library_symlink_escape_rejects(self):
        libs=self.root/"libs";libs.mkdir();(self.root/"outside").write_bytes(b"x");(libs/"escape").symlink_to("../outside")
        with self.assertRaises(ValueError):restart.library_manifest(libs)

    def test_native_library_traversal_counts_empty_directories_before_sorting(self):
        libs=self.root/"libs";libs.mkdir();(libs/"a.so").write_bytes(b"x")
        for index in range(256):(libs/f"empty-{index:03d}").mkdir()
        with self.assertRaises(ValueError):restart.library_manifest(libs)

    def test_long_fixture_changes_only_expiry_and_requires_explicit_profile(self):
        original=dict(build=("cli","transaction","build-raw","--invalid-hereafter","999","--fee","300000"),sign=("sign",))
        with patch.object(fixture.short,"spend_commands",return_value=original):
            result=fixture.spend_commands(None,None,None,None,0,False,profile="early-restart-two-service-soak-v1")
            self.assertEqual(result["build"],tuple("8000" if x=="999" else x for x in original["build"]))
            self.assertEqual(result["sign"],original["sign"]);self.assertEqual(original["build"][4],"999")
            with self.assertRaises(ValueError):fixture.spend_commands(None,None,None,None,0,False,profile="unknown")


if __name__ == "__main__":unittest.main()
