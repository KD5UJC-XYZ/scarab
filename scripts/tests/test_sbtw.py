# SPDX-License-Identifier: Apache-2.0
"""Where `scripts/sbtw` puts sbt's two sockets, the boot socket (`XDG_RUNTIME_DIR`) and the command server's
(`SBT_GLOBAL_SERVER_DIR`): each container gets a directory of its own, outside the shared cache, and the commands of
one container share theirs (#1094)."""
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import unittest


SBTW = Path(__file__).resolve().parents[1] / "sbtw"


class SbtwSocketTests(unittest.TestCase):
    """`sbtw` runs from a copy whose launcher pin names a stand-in jar, with a stand-in `java` that prints the two
    directories sbt would put its sockets under. A container is stood in for by its own temporary directory."""

    def setUp(self):
        # /tmp, not the caller's TMPDIR, which may itself be too long for a socket's path.
        self.scratch = tempfile.TemporaryDirectory(dir="/tmp")
        root = Path(self.scratch.name)
        self.cache = root / "shared-cache"
        version = re.search(r"^version=(\S+)$", SBTW.read_text(), re.M).group(1)
        jar = self.cache / "scarab" / f"sbt-launch-{version}.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"stand-in launcher")
        pin = hashlib.sha256(jar.read_bytes()).hexdigest()
        self.checkout = root / "work"
        (self.checkout / "scripts").mkdir(parents=True)
        script = self.checkout / "scripts" / "sbtw"
        script.write_text(re.sub(r"^sha=\S+$", f"sha={pin}", SBTW.read_text(), count=1, flags=re.M))
        script.chmod(0o755)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        java = bin_dir / "java"
        java.write_text('#!/usr/bin/env bash\nprintf "%s\\n%s\\n" "$XDG_RUNTIME_DIR" "$SBT_GLOBAL_SERVER_DIR"\n')
        java.chmod(0o755)
        self.path = f"{bin_dir}:{os.environ['PATH']}"
        self.root = root

    def tearDown(self):
        self.scratch.cleanup()

    def temp(self, container: str) -> Path:
        temp = self.root / container
        temp.mkdir(parents=True, exist_ok=True)
        return temp

    def run_sbtw(self, temp: Path) -> subprocess.CompletedProcess:
        env = {k: v for k, v in os.environ.items()
               if k not in ("XDG_RUNTIME_DIR", "SBT_GLOBAL_SERVER_DIR", "SBT_GLOBAL_BASE")}
        env.update(PATH=self.path, TMPDIR=str(temp), SCARAB_CACHE_DIR=str(self.cache))
        return subprocess.run(["bash", "scripts/sbtw", "compile"], cwd=self.checkout, env=env,
                              capture_output=True, text=True, timeout=60)

    def socket_dirs(self, container: str) -> tuple[Path, Path]:
        done = self.run_sbtw(self.temp(container))
        self.assertEqual(done.returncode, 0, done.stderr)
        boot, server = done.stdout.splitlines()
        return Path(boot), Path(server)

    def test_two_containers_on_one_cache_get_sockets_of_their_own(self):
        first, second = self.socket_dirs("a"), self.socket_dirs("b")
        self.assertNotEqual(first[0], second[0], "two containers sharing the cache got one boot-socket directory")
        self.assertNotEqual(first[1], second[1], "two containers sharing the cache got one server-socket directory")
        for directory in (*first, *second):
            self.assertFalse(directory.is_relative_to(self.cache),
                             f"the socket directory {directory} is inside the shared cache {self.cache}")

    def test_the_server_socket_lives_in_the_private_directory(self):
        boot, server = self.socket_dirs("a")
        self.assertTrue(server.is_relative_to(boot), f"the server's directory {server} is not under {boot}")

    def test_one_containers_commands_share_their_sockets(self):
        self.assertEqual(self.socket_dirs("a"), self.socket_dirs("a"),
                         "two commands in one container got different socket directories, so a client would not "
                         "reach its container's server")

    def test_the_directory_is_private_to_its_user_even_when_it_existed(self):
        boot, _ = self.socket_dirs("a")
        boot.chmod(0o755)
        again, _ = self.socket_dirs("a")
        mode = stat.S_IMODE(again.stat().st_mode)
        self.assertEqual(mode, 0o700, f"{again} has mode {oct(mode)}, not 0o700")

    def test_a_symbolic_link_in_its_place_is_refused(self):
        temp = self.temp("a")
        elsewhere = self.temp("elsewhere")
        (temp / f"scarab-sbt-{os.getuid()}").symlink_to(elsewhere, target_is_directory=True)
        done = self.run_sbtw(temp)
        self.assertNotEqual(done.returncode, 0, "sbtw ran sbt with its sockets behind a symbolic link")
        self.assertIn("symbolic link", done.stderr)
        self.assertEqual(done.stdout, "", "sbt was started although the directory was refused")

    def test_a_directory_too_long_for_a_socket_path_is_refused(self):
        done = self.run_sbtw(self.temp("t" * 60))
        self.assertNotEqual(done.returncode, 0, "sbtw ran sbt with socket paths over the 107-byte limit")
        self.assertIn("too long", done.stderr)
        self.assertEqual(done.stdout, "", "sbt was started although the directory was refused")


if __name__ == "__main__":
    unittest.main()
