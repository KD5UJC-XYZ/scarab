# SPDX-License-Identifier: Apache-2.0
"""sodium_oracle: the source archive is used only when it is the pinned one, is extracted only when every member is a
file or a directory under its top directory, and the library is kept only when open.c was compiled without
ED25519_COMPAT."""
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sodium_oracle as oracle

OPEN = f"libtool: compile:  gcc -DHAVE_PTHREAD=1 -I. {oracle.CFLAGS} -pthread -c ../../../src/{oracle.OPEN_C} -fPIC"


def member(name, kind=tarfile.REGTYPE):
    info = tarfile.TarInfo(name)
    info.type = kind
    return info


class ArchiveTest(unittest.TestCase):
    def test_an_archive_of_another_size_or_digest_is_refused(self):
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "a.tar.gz"
            path.write_bytes(b"not the archive")
            with self.assertRaisesRegex(ValueError, "size mismatch"):
                oracle.verify_archive(path)
            with patch.object(oracle, "ARCHIVE_SIZE", path.stat().st_size):
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    oracle.verify_archive(path)

    def test_members_outside_the_top_directory_or_of_another_type_are_refused(self):
        top = oracle.TOP
        oracle.safe_members([member(top, tarfile.DIRTYPE), member(f"{top}/configure")])
        for bad in (member("/etc/passwd"), member(f"{top}/../x"), member("other/configure"),
                    member(f"{top}\\x"), member(f"{top}/link", tarfile.SYMTYPE),
                    member(f"{top}/hard", tarfile.LNKTYPE), member(f"{top}/fifo", tarfile.FIFOTYPE)):
            with self.subTest(name=bad.name, kind=bad.type), self.assertRaises(ValueError):
                oracle.safe_members([bad])

    def test_a_verified_archive_is_extracted_under_its_top_directory(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            archive = root / "a.tar.gz"
            with tarfile.open(archive, "w:gz") as out:
                data = b"#!/bin/sh\n"
                info = tarfile.TarInfo(f"{oracle.TOP}/configure")
                info.size = len(data)
                out.addfile(info, io.BytesIO(data))
            source = oracle.extract_source(archive, root / "src")
            self.assertEqual(source, root / "src" / oracle.TOP)
            self.assertEqual((source / "configure").read_bytes(), b"#!/bin/sh\n")


class CompatTest(unittest.TestCase):
    def test_open_c_compiled_with_the_recorded_cflags_is_accepted(self):
        self.assertTrue(oracle.compiled_without_compat("other line\n" + OPEN))

    def test_a_log_without_open_c_or_with_ed25519_compat_is_refused(self):
        self.assertFalse(oracle.compiled_without_compat("libtool: compile: gcc -c other.c"))
        self.assertFalse(oracle.compiled_without_compat(OPEN.replace(oracle.CFLAGS, "-O2")))
        self.assertFalse(oracle.compiled_without_compat(OPEN + " -DED25519_COMPAT"))


if __name__ == "__main__":
    unittest.main()
