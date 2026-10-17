#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Rotate the KES key and operational certificate of a Scarab block producer, unattended (H2a, #116).

Run on a timer (see `rotate-kes.service` and `rotate-kes.timer`). Each run asks the node, through its
local socket, where the chain is and which issue counter the pool has on the chain; when the
operational certificate in use has `--threshold` KES periods or fewer left it

  1. generates a KES key pair and issues a certificate for it with the cold key (`cardano-cli`, in the
     `--cli-image` container; the cold key is mounted into that container only and never into the
     node's),
  2. checks the certificate (it names the new key, the counter and the start period are the ones meant),
  3. puts the pair in a new generation directory and switches the `current` link to it with one atomic
     rename, so a restart at any moment sees either the old pair or the new pair, never a mixture,
  4. sends SIGHUP to the node (`docker kill -s HUP`, or `--signal-command`) and waits for the log event that
     shows it took the new credentials (`kesReloaded` with the new counter, or `forgeStarted` if it was
     restarted meanwhile),
  5. overwrites the old generation's key files and removes them.

The key directory (`--key-dir`, the directory the node mounts) looks like

    current -> gen-000003-00000120        a relative link; the node reads kes.skey and node.cert through it
    gen-000003-00000120/{kes.skey,node.cert}
    rotation.json                         present only while a rotation is not confirmed

and the node runs with `--kes-skey <dir>/current/kes.skey --opcert <dir>/current/node.cert`. The node
writes its evolved KES key back into the generation it read.

Safe to run again after a crash at any step: an unconfirmed rotation is recorded in `rotation.json` before
the link moves and the previous generation is kept until the node has confirmed the reload; the next run
finishes it (the signal is sent again, which is harmless). A refused reload puts the link back.

Exit status: 0 nothing to do or rotated; 1 the node refused the new credentials (link restored); 2 not
confirmed in time (state kept, run again); 3 the node's socket does not answer (nothing changed); 4 the
configuration or the key directory is wrong.

Options are command-line flags or `ROTATE_KES_<FLAG>` variables (`--key-dir` is `ROTATE_KES_KEY_DIR`); an
`--env-file` of `NAME=value` lines (systemd's `EnvironmentFile` format) supplies them too. Python 3
standard library only.
"""
import argparse
import dataclasses
import datetime
import fcntl
import json
import os
import pathlib
import shlex
import subprocess
import sys
import time

EXIT_OK, EXIT_REFUSED, EXIT_UNCONFIRMED, EXIT_NODE_DOWN, EXIT_CONFIG = 0, 1, 2, 3, 4


class RotationError(Exception):
    """A failure with the exit status it ends the run with."""

    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


# ---------------------------------------------------------------- reading what cardano-cli writes

def envelope_bytes(text):
    """The CBOR bytes of a `cardano-cli` text envelope."""
    return bytes.fromhex(json.loads(text)["cborHex"])


def _head(b, i):
    """(major type, argument, next index) of the CBOR item head at `i`."""
    major, info = b[i] >> 5, b[i] & 31
    if info < 24:
        return major, info, i + 1
    width = {24: 1, 25: 2, 26: 4, 27: 8}[info]
    return major, int.from_bytes(b[i + 1:i + 1 + width], "big"), i + 1 + width


def _bytes_item(b, i):
    major, n, i = _head(b, i)
    if major != 2:
        raise ValueError("expected a byte string")
    return b[i:i + n], i + n


def _uint_item(b, i):
    major, n, i = _head(b, i)
    if major != 0:
        raise ValueError("expected an unsigned integer")
    return n, i


@dataclasses.dataclass(frozen=True)
class OpCert:
    hot_vkey: bytes
    counter: int
    start_period: int
    cold_vkey: bytes


def parse_opcert(text):
    """`[[hot vkey, counter, start period, signature], cold vkey]`, the `NodeOperationalCertificate` envelope."""
    b = envelope_bytes(text)
    major, n, i = _head(b, 0)
    if (major, n) != (4, 2):
        raise ValueError("expected [certificate, cold verification key]")
    major, n, i = _head(b, i)
    if (major, n) != (4, 4):
        raise ValueError("expected [hot vkey, counter, KES period, signature]")
    hot, i = _bytes_item(b, i)
    counter, i = _uint_item(b, i)
    start, i = _uint_item(b, i)
    _, i = _bytes_item(b, i)
    cold, i = _bytes_item(b, i)
    return OpCert(hot, counter, start, cold)


def parse_vkey(text):
    """The 32 bytes of a verification key envelope (`5820` and the key)."""
    return _bytes_item(envelope_bytes(text), 0)[0]


# ---------------------------------------------------------------- the decisions

def kes_period(slot, slots_per_kes_period):
    return slot // slots_per_kes_period


def periods_left(cert, period, max_evolutions):
    """KES periods the certificate is still good for, this one included (the node's `remainingKESPeriods`)."""
    return cert.start_period + max_evolutions - period


def next_counter(on_chain, local):
    """The counter of the new certificate. The chain accepts the pool's counter on the chain or one above; the
    node reloads the same. `on_chain` is None when the pool has no counter there yet (or the node could not say),
    then the last counter issued here (`local`, None for a pool never rotated here) stands in for it."""
    last = on_chain if on_chain is not None else (local if local is not None else 0)
    return last + 1


# ---------------------------------------------------------------- files

def fsync_dir(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_atomic(path, text, mode=0o600):
    """Write `text` to `path` through a temporary file in the same directory: all or nothing, on disk."""
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    try:
        os.write(fd, text.encode())
        os.fsync(fd)
    finally:
        os.close(fd)
    os.replace(tmp, path)
    fsync_dir(path.parent)


def scrub(path):
    """Overwrite a file with zeros and remove it (best effort on a file system that keeps old blocks)."""
    if path.is_dir() and not path.is_symlink():
        for child in sorted(path.iterdir()):
            scrub(child)
        path.rmdir()
        return
    try:
        size = path.stat().st_size
        fd = os.open(path, os.O_WRONLY)
        try:
            os.write(fd, bytes(size))
            os.fsync(fd)
        finally:
            os.close(fd)
    except FileNotFoundError:
        return
    path.unlink()


def generation_name(counter, period):
    return f"gen-{counter:06d}-{period:08d}"


def switch_link(key_dir, target):
    """Point `current` at the generation `target` (a name, so the link is the same inside the node's container) with
    one rename: the link is never missing and never points anywhere else."""
    tmp = key_dir / "current.tmp"
    if tmp.is_symlink() or tmp.exists():
        tmp.unlink()
    os.symlink(target, tmp)
    os.replace(tmp, key_dir / "current")
    fsync_dir(key_dir)


def current_generation(key_dir):
    link = key_dir / "current"
    if not link.is_symlink():
        raise RotationError(EXIT_CONFIG, f"{link} is not a symbolic link to a generation directory")
    target = os.readlink(link)
    if os.path.isabs(target) or "/" in target:
        raise RotationError(EXIT_CONFIG, f"{link} must point at a directory beside it by name, not at {target}")
    return target


def read_rotation(key_dir):
    path = key_dir / "rotation.json"
    return json.loads(path.read_text()) if path.exists() else None


# ---------------------------------------------------------------- the outside world

@dataclasses.dataclass
class Settings:
    key_dir: pathlib.Path
    cold_skey: pathlib.Path
    cold_vkey: pathlib.Path
    socket: pathlib.Path
    shelley_genesis: pathlib.Path
    testnet_magic: int | None
    container: str | None
    log_file: pathlib.Path | None
    signal_command: list
    cli_image: str
    user: str
    threshold: int
    confirm_timeout: float
    poll: float
    state_file: pathlib.Path


class Cli:
    """`cardano-cli` in a container. The key directory is read-only, the work directory a scratch directory, the
    cold key is mounted only for the command that signs, and the container has no network."""

    def __init__(self, settings, runner=subprocess.run):
        self.s = settings
        self.runner = runner

    def run(self, args, mounts=(), check=True):
        command = ["docker", "run", "--rm", "--pull=never", "--network", "none", "--user", self.s.user,
                   "--entrypoint", "cardano-cli"]
        for host, inside, mode in mounts:
            command += ["-v", f"{host}:{inside}:{mode}"]
        command += [self.s.cli_image, "latest", *args]
        done = self.runner(command, capture_output=True, text=True)
        if check and done.returncode != 0:
            raise RotationError(EXIT_CONFIG, f"cardano-cli {' '.join(args[:2])} failed: {done.stderr.strip()[:300]}")
        return done

    def network(self):
        return ["--mainnet"] if self.s.testnet_magic is None else ["--testnet-magic", str(self.s.testnet_magic)]

    def query(self, args):
        """A `query` command against the node's socket; None if the node does not answer it."""
        done = self.run(["query", *args, *self.network(), "--socket-path", "/ipc/" + self.s.socket.name],
                        mounts=[(self.s.socket.parent, "/ipc", "ro"), (self.s.key_dir, "/keys", "ro")], check=False)
        return done if done.returncode == 0 else None


class Node:
    """The running node: its tip, its pool's counter, and the signal and the log that carry a reload."""

    def __init__(self, settings, cli, runner=subprocess.run):
        self.s = settings
        self.cli = cli
        self.runner = runner

    def tip(self):
        done = self.cli.query(["tip"])
        return json.loads(done.stdout) if done else None

    def on_chain_counter(self):
        """The issue counter of the certificate in use on the chain, as the node reports it; None if the node
        cannot say (the query is not served, or the pool has not made a block yet)."""
        done = self.cli.query(["kes-period-info", "--op-cert-file", "/keys/current/node.cert", "--output-json"])
        if not done:
            return None
        try:
            return json.loads(done.stdout).get("qKesNodeStateOperationalCertificateNumber")
        except ValueError:
            return None

    def hangup(self):
        if self.s.signal_command:
            command = self.s.signal_command
        elif self.s.container:
            command = ["docker", "kill", "-s", "HUP", self.s.container]
        else:
            raise RotationError(EXIT_CONFIG, "neither --container nor --signal-command says how to signal the node")
        done = self.runner(command, capture_output=True, text=True)
        if done.returncode != 0:
            raise RotationError(EXIT_NODE_DOWN, f"signalling the node failed: {done.stderr.strip()[:200]}")

    def log_lines(self, since):
        """The node's JSON log lines from `since` (an ISO-8601 UTC time) on."""
        if self.s.log_file:
            text = self.s.log_file.read_text(errors="replace") if self.s.log_file.exists() else ""
        else:
            done = self.runner(["docker", "logs", "--since", since, self.s.container],
                               capture_output=True, text=True)
            text = done.stdout + done.stderr
        lines = []
        for raw in text.splitlines():
            try:
                event = json.loads(raw)
            except ValueError:
                continue
            if isinstance(event, dict) and event.get("at", "") >= since:
                lines.append(event)
        return lines


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def confirmation(events, counter, start):
    """What the node's log says about the new credentials: 'taken', 'refused' or None (nothing yet)."""
    for e in events:
        if e.get("event") in ("kesReloaded", "forgeStarted") and e.get("opCertCounter") == counter \
                and e.get("kesStart") == start:
            return "taken"
    for e in events:
        if e.get("event") == "kesReloadRefused":
            return "refused"
    return None


# ---------------------------------------------------------------- the rotation

def say(**fields):
    print(json.dumps(fields, sort_keys=True), flush=True)


def read_local_counter(s):
    try:
        return int(s.state_file.read_text().split()[0])
    except (FileNotFoundError, ValueError, IndexError):
        return None


def generate(s, cli, key_dir, name, counter, period):
    """Build the generation `name` in a staging directory and move it into place; returns its OpCert."""
    final = key_dir / name
    if final.exists():
        # a complete one from an earlier run of this period and counter, which crashed before the switch
        if (final / "READY").exists():
            cert = parse_opcert((final / "node.cert").read_text())
            if cert.counter == counter and cert.start_period == period:
                return cert
        scrub(final)
    staging = key_dir / f".staging-{name}"
    if staging.exists():
        scrub(staging)
    staging.mkdir(mode=0o700)
    work = [(staging, "/work", "rw")]
    try:
        cli.run(["node", "key-gen-KES", "--verification-key-file", "/work/kes.vkey",
                 "--signing-key-file", "/work/kes.skey"], mounts=work)
        cli.run(["node", "new-counter", "--cold-verification-key-file", "/cold/cold.vkey",
                 "--counter-value", str(counter), "--operational-certificate-issue-counter-file", "/work/issue.counter"],
                mounts=work + [(s.cold_vkey.parent, "/cold", "ro")])
        cli.run(["node", "issue-op-cert", "--kes-verification-key-file", "/work/kes.vkey",
                 "--cold-signing-key-file", "/cold/cold.skey",
                 "--operational-certificate-issue-counter-file", "/work/issue.counter",
                 "--kes-period", str(period), "--out-file", "/work/node.cert"],
                mounts=work + [(s.cold_skey.parent, "/cold", "ro")])
        cert = parse_opcert((staging / "node.cert").read_text())
        hot = parse_vkey((staging / "kes.vkey").read_text())
        cold = parse_vkey(s.cold_vkey.read_text())
        problems = [p for ok, p in ((cert.hot_vkey == hot, "the certificate names another KES key"),
                                    (cert.counter == counter, f"the counter is {cert.counter}, not {counter}"),
                                    (cert.start_period == period, f"the start period is {cert.start_period}"),
                                    (cert.cold_vkey == cold, "the certificate carries another cold key")) if not ok]
        if problems:
            raise RotationError(EXIT_CONFIG, "the new certificate is wrong: " + "; ".join(problems))
        for leftover in ("kes.vkey", "issue.counter"):
            (staging / leftover).unlink()
        (staging / "kes.skey").chmod(0o600)
        (staging / "node.cert").chmod(0o644)
        write_atomic(staging / "READY", "")
        os.rename(staging, final)
        fsync_dir(key_dir)
        return cert
    except BaseException:
        if staging.exists():
            scrub(staging)
        raise


def finish(s, node, key_dir, rotation):
    """From the switched (or about to be switched) link to a confirmed rotation: signal, wait, and clean up."""
    old, new = rotation["old"], rotation["new"]
    if current_generation(key_dir) != new:
        switch_link(key_dir, new)
    since = utc_now()
    node.hangup()
    say(step="signalled", counter=rotation["counter"], generation=new)
    deadline = time.monotonic() + s.confirm_timeout
    verdict = None
    while verdict is None and time.monotonic() < deadline:
        time.sleep(s.poll)
        verdict = confirmation(node.log_lines(since), rotation["counter"], rotation["startPeriod"])
    if verdict == "refused":
        switch_link(key_dir, old)
        scrub(key_dir / new)
        (key_dir / "rotation.json").unlink()
        fsync_dir(key_dir)
        raise RotationError(EXIT_REFUSED, "the node refused the new credentials (kesReloadRefused); the previous "
                                          "pair is back in place")
    if verdict is None:
        raise RotationError(EXIT_UNCONFIRMED, f"no kesReloaded with counter {rotation['counter']} within "
                                              f"{s.confirm_timeout:.0f} s; run again to finish")
    if old != new and (key_dir / old).exists():
        scrub(key_dir / old)
    (key_dir / "rotation.json").unlink()
    fsync_dir(key_dir)
    write_atomic(s.state_file, f"{rotation['counter']}\n")
    say(step="confirmed", counter=rotation["counter"], generation=new)


def clean_orphans(key_dir, keep):
    """Generations and staging directories that belong to no rotation: a crash left them, and they hold keys."""
    for child in sorted(key_dir.iterdir()):
        if child.name in keep or child.is_symlink():
            continue
        if child.is_dir() and (child.name.startswith("gen-") or child.name.startswith(".staging-")):
            scrub(child)
            say(step="removed", path=child.name)


def run(s, cli=None, node=None):
    key_dir = s.key_dir
    if not key_dir.is_dir():
        raise RotationError(EXIT_CONFIG, f"{key_dir} is not a directory")
    with open(key_dir / ".lock", "w") as lock:  # one run at a time
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            say(step="busy", message="another run holds the lock")
            return EXIT_OK
        return rotate(s, cli or Cli(s), node)


def rotate(s, cli, node):
    key_dir = s.key_dir
    node = node or Node(s, cli)
    current = current_generation(key_dir)
    # a rotation that was not confirmed (the previous run crashed or timed out) is finished first
    rotation = read_rotation(key_dir)
    if rotation:
        say(step="resuming", rotation=rotation)
        clean_orphans(key_dir, {rotation["old"], rotation["new"]})
        finish(s, node, key_dir, rotation)
        return EXIT_OK
    if not (key_dir / current).is_dir():
        raise RotationError(EXIT_CONFIG, f"{key_dir / 'current'} points at {current}, which does not exist")
    clean_orphans(key_dir, {current})
    genesis = json.loads(s.shelley_genesis.read_text())
    per_period, max_evolutions = genesis["slotsPerKESPeriod"], genesis["maxKESEvolutions"]
    tip = node.tip()
    if not tip or "slot" not in tip:
        raise RotationError(EXIT_NODE_DOWN, "the node's socket does not answer `query tip`")
    period = kes_period(tip["slot"], per_period)
    cert = parse_opcert((key_dir / current / "node.cert").read_text())
    left = periods_left(cert, period, max_evolutions)
    say(step="checked", slot=tip["slot"], kesPeriod=period, counter=cert.counter, start=cert.start_period,
        remaining=left, threshold=s.threshold)
    if left > s.threshold:
        return EXIT_OK
    on_chain = node.on_chain_counter()
    local = read_local_counter(s)
    if on_chain is None:
        say(step="warning", message="the node did not report the pool's counter (query kes-period-info); "
                                    "using the last counter issued here", local=local)
    counter = next_counter(on_chain, local if local is not None else cert.counter)
    name = generation_name(counter, period)
    new_cert = generate(s, cli, key_dir, name, counter, period)
    rotation = {"old": current, "new": name, "counter": new_cert.counter, "startPeriod": new_cert.start_period}
    write_atomic(key_dir / "rotation.json", json.dumps(rotation))
    say(step="issued", **rotation)
    finish(s, node, key_dir, rotation)
    return EXIT_OK


# ---------------------------------------------------------------- command line

def read_env_file(path):
    values = {}
    for line in pathlib.Path(path).read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            name, _, value = line.partition("=")
            values[name.strip()] = value.strip().strip("'\"")
    return values


def parse_args(argv, environ):
    early = argparse.ArgumentParser(add_help=False)
    early.add_argument("--env-file")
    pre, _ = early.parse_known_args(argv)
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--env-file", help="NAME=value lines (ROTATE_KES_*) read before the environment")
    env = dict(environ)
    if pre.env_file:
        env.update(read_env_file(pre.env_file))

    def default(name, fallback=None):
        return env.get("ROTATE_KES_" + name, fallback)

    p.add_argument("--key-dir", default=default("KEY_DIR"), help="the directory the node mounts, with `current`")
    p.add_argument("--cold-skey", default=default("COLD_SKEY"), help="the pool's cold signing key")
    p.add_argument("--cold-vkey", default=default("COLD_VKEY"), help="the cold verification key (default: beside the signing key)")
    p.add_argument("--socket", default=default("SOCKET"), help="the node's local (node-to-client) socket")
    p.add_argument("--shelley-genesis", default=default("SHELLEY_GENESIS"), help="the Shelley genesis file")
    p.add_argument("--testnet-magic", type=int, default=default("TESTNET_MAGIC"), help="network magic (omit for mainnet)")
    p.add_argument("--container", default=default("CONTAINER"), help="the node's container: signalled and read for the reload event")
    p.add_argument("--log-file", default=default("LOG_FILE"), help="read the node's events from this file instead of the container's log")
    p.add_argument("--signal-command", default=default("SIGNAL_COMMAND"), help="command that sends SIGHUP to the node (default docker kill -s HUP)")
    p.add_argument("--cli-image", default=default("CLI_IMAGE", "ghcr.io/intersectmbo/cardano-node:11.1.3"))
    p.add_argument("--user", default=default("USER", f"{os.getuid()}:{os.getgid()}"), help="uid:gid of the cardano-cli container")
    p.add_argument("--threshold", type=int, default=int(default("THRESHOLD", 10)), help="rotate when this many KES periods or fewer are left")
    p.add_argument("--confirm-timeout", type=float, default=float(default("CONFIRM_TIMEOUT", 60)), help="seconds to wait for the reload event")
    p.add_argument("--poll", type=float, default=float(default("POLL", 1)), help="seconds between looks at the log")
    p.add_argument("--state-file", default=default("STATE_FILE"), help="the last counter issued (default <key-dir>/last-counter)")
    args = p.parse_args(argv)
    missing = [n for n in ("key_dir", "cold_skey", "socket", "shelley_genesis") if not getattr(args, n)]
    if missing:
        p.error("missing " + ", ".join("--" + n.replace("_", "-") for n in missing))
    key_dir = pathlib.Path(args.key_dir)
    cold_skey = pathlib.Path(args.cold_skey)
    return Settings(
        key_dir=key_dir, cold_skey=cold_skey,
        cold_vkey=pathlib.Path(args.cold_vkey) if args.cold_vkey else cold_skey.with_suffix(".vkey"),
        socket=pathlib.Path(args.socket), shelley_genesis=pathlib.Path(args.shelley_genesis),
        testnet_magic=args.testnet_magic, container=args.container,
        log_file=pathlib.Path(args.log_file) if args.log_file else None,
        signal_command=shlex.split(args.signal_command) if args.signal_command else [],
        cli_image=args.cli_image, user=args.user, threshold=args.threshold,
        confirm_timeout=args.confirm_timeout, poll=args.poll,
        state_file=pathlib.Path(args.state_file) if args.state_file else key_dir / "last-counter")


def main(argv=None, environ=None):
    settings = parse_args(sys.argv[1:] if argv is None else argv, os.environ if environ is None else environ)
    try:
        return run(settings)
    except RotationError as e:
        say(step="failed", status=e.status, message=str(e))
        return e.status


if __name__ == "__main__":
    sys.exit(main())
