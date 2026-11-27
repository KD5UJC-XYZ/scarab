# SPDX-License-Identifier: Apache-2.0
"""sodium_oracle: the source archive is used only when it is the pinned one, fetched over HTTPS at every hop, and
extracted from the very bytes whose digest was checked, only when every member is a file or a directory under its top
directory; and the library is kept only when every compile command of open.c leaves ED25519_COMPAT undefined."""
import hashlib
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sodium_oracle as oracle

OPEN = (f"libtool: compile:  gcc -DPACKAGE_NAME=\\\"libsodium\\\" \"-DPACKAGE_STRING=\\\"libsodium 1.0.18\\\"\" -I. "
        f"{oracle.CFLAGS} -pthread -c ../../../src/{oracle.OPEN_C} -fPIC")


def member(name, kind=tarfile.REGTYPE):
    info = tarfile.TarInfo(name)
    info.type = kind
    return info


def archive_bytes(files):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w:gz") as archive:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return out.getvalue()


def pinned(data):
    """The pins patched to `data`'s size and digest, so that a test archive is the pinned one."""
    return patch.multiple(oracle, ARCHIVE_SIZE=len(data), ARCHIVE_SHA256=hashlib.sha256(data).hexdigest())


class Response(io.BytesIO):
    def __init__(self, data, url):
        super().__init__(data)
        self.url = url

    def geturl(self):
        return self.url


class Opener:
    def __init__(self, data, url):
        self.data, self.url = data, url

    def open(self, request, timeout):
        return Response(self.data, self.url)


class ArchiveTest(unittest.TestCase):
    def test_bytes_of_another_size_or_digest_are_refused(self):
        data = b"not the archive"
        with self.assertRaisesRegex(ValueError, "size mismatch"):
            oracle.verify_archive(data)
        with patch.object(oracle, "ARCHIVE_SIZE", len(data)):
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                oracle.verify_archive(data)

    def test_members_outside_the_top_directory_or_of_another_type_are_refused(self):
        top = oracle.TOP
        oracle.safe_members([member(top, tarfile.DIRTYPE), member(f"{top}/configure")])
        for bad in (member("/etc/passwd"), member(f"{top}/../x"), member("other/configure"),
                    member(f"{top}\\x"), member(f"{top}/link", tarfile.SYMTYPE),
                    member(f"{top}/hard", tarfile.LNKTYPE), member(f"{top}/fifo", tarfile.FIFOTYPE)):
            with self.subTest(name=bad.name, kind=bad.type), self.assertRaises(ValueError):
                oracle.safe_members([bad])

    def test_the_verified_bytes_are_extracted_under_their_top_directory(self):
        data = archive_bytes({f"{oracle.TOP}/configure": b"#!/bin/sh\n"})
        with tempfile.TemporaryDirectory() as scratch, pinned(data):
            source = oracle.extract_source(data, Path(scratch) / "src")
            self.assertEqual(source, Path(scratch) / "src" / oracle.TOP)
            self.assertEqual((source / "configure").read_bytes(), b"#!/bin/sh\n")

    def test_extraction_checks_the_bytes_it_extracts(self):
        data = archive_bytes({f"{oracle.TOP}/configure": b"#!/bin/sh\n"})
        other = archive_bytes({f"{oracle.TOP}/configure": b"#!/bin/sh\nevil\n"})
        with tempfile.TemporaryDirectory() as scratch, pinned(data):
            with self.assertRaises(ValueError):
                oracle.extract_source(other, Path(scratch) / "src")
            self.assertFalse((Path(scratch) / "src").exists(), "nothing is extracted from unverified bytes")


class DownloadTest(unittest.TestCase):
    def test_a_redirect_to_plain_http_is_refused(self):
        handler = oracle.HttpsRedirects()
        request = urllib.request.Request(oracle.ARCHIVE_URL)
        with self.assertRaisesRegex(ValueError, "redirected off HTTPS"):
            handler.redirect_request(request, None, 302, "Found", {}, "http://example.invalid/archive")
        followed = handler.redirect_request(request, None, 302, "Found", {}, "https://example.invalid/archive")
        self.assertEqual(followed.full_url, "https://example.invalid/archive")

    def test_the_default_opener_follows_redirects_with_that_handler(self):
        built = []
        real = urllib.request.build_opener
        with patch.object(urllib.request, "build_opener", side_effect=lambda *h: built.append(h) or real(*h)), \
                tempfile.TemporaryDirectory() as scratch, patch.object(oracle.urllib.request.OpenerDirector, "open",
                                                                       side_effect=ValueError("no network")):
            with self.assertRaisesRegex(ValueError, "no network"):
                oracle.download_archive(Path(scratch))
        self.assertTrue(any(isinstance(h, oracle.HttpsRedirects) for h in built[0]), built)

    def test_a_download_that_ended_off_https_or_is_not_the_archive_is_refused_and_not_kept(self):
        data = archive_bytes({f"{oracle.TOP}/configure": b"x"})
        with tempfile.TemporaryDirectory() as scratch, pinned(data):
            with self.assertRaisesRegex(ValueError, "left HTTPS"):
                oracle.download_archive(Path(scratch), Opener(data, "http://example.invalid/archive"))
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                oracle.download_archive(Path(scratch), Opener(b"y" * len(data), oracle.ARCHIVE_URL))
            self.assertEqual(sorted(p.name for p in Path(scratch).iterdir()), [])

    def test_a_verified_download_is_kept_and_reused(self):
        data = archive_bytes({f"{oracle.TOP}/configure": b"x"})
        with tempfile.TemporaryDirectory() as scratch, pinned(data):
            self.assertEqual(oracle.download_archive(Path(scratch), Opener(data, oracle.ARCHIVE_URL)), data)
            self.assertEqual((Path(scratch) / oracle.ARCHIVE_NAME).read_bytes(), data)
            self.assertEqual(oracle.download_archive(Path(scratch), Opener(b"", "http://unused")), data)


class CompatTest(unittest.TestCase):
    def test_open_c_compiled_with_the_recorded_cflags_is_accepted(self):
        self.assertTrue(oracle.compiled_without_compat("other line\n" + OPEN))

    def test_a_log_without_open_c_or_without_the_cflags_is_refused(self):
        self.assertFalse(oracle.compiled_without_compat("libtool: compile: gcc -c other.c"))
        self.assertFalse(oracle.compiled_without_compat(OPEN.replace(oracle.CFLAGS, "-O2 -g0")))

    def test_any_definition_of_ed25519_compat_on_the_line_is_refused(self):
        for define in ("-DED25519_COMPAT", "-D ED25519_COMPAT", "-DED25519_COMPAT=1", "'-DED25519_COMPAT'",
                       "-D'ED25519_COMPAT'", "-D \"ED25519_COMPAT=1\"", "-UED25519_COMPAT -DED25519_COMPAT"):
            with self.subTest(define=define):
                self.assertFalse(oracle.compiled_without_compat(OPEN + " " + define))
                self.assertFalse(oracle.compiled_without_compat(OPEN.replace("-pthread", define + " -pthread")))

    def test_a_line_that_is_not_shell_words_is_refused(self):
        self.assertFalse(oracle.compiled_without_compat(OPEN + " '-DUNCLOSED"))


if __name__ == "__main__":
    unittest.main()
