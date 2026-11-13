# SPDX-License-Identifier: Apache-2.0
"""Shared constants and Docker/CLI helpers for the devnet scripts."""
import contextlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
from dataclasses import dataclass


REPO = pathlib.Path(__file__).resolve().parents[2]
USER = f"{os.getuid()}:{os.getgid()}"  # containers run as the invoking user so mounted run directories stay writable
ENTRY = REPO / "scripts" / "devnet.py"  # re-invoked by the scenarios as `devnet.py --name N load ...`
REFERENCE_IMAGE = "cardano-reference-11.1.3:local"
JDK_IMAGE = "scarab-jdk:local"
FORMER_JDK_IMAGE = "cardano-scala-jdk:local"  # the image's name before the rename; used while only it exists
DATA_DIR = pathlib.Path("/tmp/scarab")
FORMER_DATA_DIR = pathlib.Path("/tmp/cardano-scala-lab")  # the data directory's name before the rename
MAGIC = 1082026
ENV = "/work/env"
QUERY_PORT = 7071
METRICS_PORT = 12798  # the Scala node's `--metrics-listen` port in the devnet (`metrics.py` reads it)
PLUTUS_VMS = ("scalus", "scarab")  # the node's `--plutus-vm` values


@dataclass(frozen=True)
class FollowerSite:
    """Where one Scala follower of a run lives: its container, its files in the run directory and its ports.

    A run's only follower (`key` None) has the names and ports runs have always had: the container `<name>-scala`,
    `follower.jsonl`, `scala-sock`, `stall`, `db`, `dump`, `classes`, `compare.jsonl` and `whole-state`, the
    query port `QUERY_PORT` and the metrics port `METRICS_PORT`. With several followers in one run (`e2e` with
    one per Plutus evaluator), each is named by its `key`, a value of `PLUTUS_VMS`: `<name>-scala-<key>`,
    `follower-<key>.jsonl`, `scala-sock-<key>` and so on. Every follower runs in the reference container's network
    namespace, so each of them also takes a query and a metrics port of its own: the default ones plus the
    position of its key in `PLUTUS_VMS`, plus one."""
    key: str | None = None

    def __post_init__(self):
        if self.key is not None and self.key not in PLUTUS_VMS:
            raise ValueError(f"a follower is keyed by a Plutus evaluator of {PLUTUS_VMS}, not {self.key!r}")

    def named(self, base):
        """`base`, suffixed with `-<key>` for a keyed follower."""
        return base if self.key is None else f"{base}-{self.key}"

    def container(self, name):
        return self.named(f"{name}-scala")

    def log(self, root):
        return root / f"{self.named('follower')}.jsonl"

    def directory(self, root, base):
        """The follower's own directory `base` of the run directory (`scala-sock`, `stall`, `db`, `dump`, ...)."""
        return root / self.named(base)

    @property
    def offset(self):
        return 0 if self.key is None else PLUTUS_VMS.index(self.key) + 1

    @property
    def query_port(self):
        return QUERY_PORT + self.offset

    @property
    def metrics_port(self):
        return METRICS_PORT + self.offset

    @property
    def role(self):
        """How messages name it: `follower`, or `<key> follower`."""
        return "follower" if self.key is None else f"{self.key} follower"


SINGLE_FOLLOWER = FollowerSite()
ALL_FOLLOWERS = (SINGLE_FOLLOWER, *(FollowerSite(vm) for vm in PLUTUS_VMS))  # every site a run may use


def follower_plan(plutus_vms):
    """[(site, evaluator)] of a run's followers for its `--plutus-vm` values (None or a list, in order): with none
    the run's only follower and the node's default evaluator (None), with one the only follower and that
    evaluator, with several one keyed follower per evaluator. The same evaluator twice is refused."""
    vms = list(plutus_vms or [])
    if len(set(vms)) != len(vms):
        raise SystemExit(f"--plutus-vm names each evaluator once, not {vms}")
    if len(vms) <= 1:
        return [(SINGLE_FOLLOWER, vms[0] if vms else None)]
    return [(FollowerSite(vm), vm) for vm in vms]


def _populated(path):
    """Whether `path` holds a file. A tree of empty directories, such as the `.cache/scarab` mount
    point a build container leaves in a worktree, holds none."""
    return path.is_dir() and any(not p.is_dir() for p in path.rglob("*"))


def main_checkout(repo, common_dir=None):
    """The main checkout of `repo`, a linked worktree or not: the parent of the repository's common
    directory.

    `common_dir` is the output of the repository query for it (asked of the repository when
    omitted); a checkout without one resolves to itself.
    """
    if common_dir is None:
        done = subprocess.run(["git", "-C", str(repo), "rev-parse", "--git-common-dir"], text=True, capture_output=True)
        common_dir = done.stdout.strip() if done.returncode == 0 else ""
    if not common_dir:
        return repo
    common = (repo / common_dir).resolve()  # an absolute value replaces `repo`
    return common.parent if common.name == ".git" else repo


def cache_dir(repo=REPO, env=None, common_dir=None):
    """The shared dependency cache the classpath file points into (`/work/.cache/...`).

    `SCARAB_CACHE_DIR` wins; else the checkout's own `.cache` if it holds anything; else, in a
    linked worktree (whose `.cache` is only the empty mount point the build container leaves), the
    `.cache` of the main checkout. Jars are never copied into a worktree. The directory is the
    parent of the cache proper, which is `.cache/scarab` (formerly `.cache/cardano-scala-lab`, kept
    as a symlink to it after `scripts/sbtw` moved it), so both layouts resolve here and the
    classpath files' `/work/.cache/...` paths work with either name.
    """
    env = os.environ if env is None else env
    if env.get("SCARAB_CACHE_DIR"):
        return pathlib.Path(env["SCARAB_CACHE_DIR"]).resolve()
    own = repo / ".cache"
    if _populated(own):
        return own
    shared = main_checkout(repo, common_dir) / ".cache"
    return shared if _populated(shared) else own


def data_dir(env=None, exists=pathlib.Path.exists, notice=lambda text: print(text, file=sys.stderr)):
    """The directory run data lives in: `SCARAB_DATA_DIR`, else its former name `CSL_DATA_DIR`, else
    `/tmp/scarab`. If neither variable is set, `/tmp/scarab` is absent and `/tmp/cardano-scala-lab`
    exists, the latter is used (one line on stderr), so a run directory made before the rename is
    still found.
    """
    env = os.environ if env is None else env
    chosen = env.get("SCARAB_DATA_DIR") or env.get("CSL_DATA_DIR")
    if chosen:
        return pathlib.Path(chosen)
    if not exists(DATA_DIR) and exists(FORMER_DATA_DIR):
        notice(f"using the former data directory {FORMER_DATA_DIR} ({DATA_DIR} does not exist; move it to rename)")
        return FORMER_DATA_DIR
    return DATA_DIR


def data_root(name):
    return data_dir() / name


GIB = 1 << 30


@dataclass(frozen=True)
class LowDiskSpace:
    """The file system that holds `path` has `free` bytes, fewer than the `required` margin."""
    path: pathlib.Path
    free: int
    required: int

    def message(self):
        return (f"not enough free space for a devnet run: {human_bytes(self.free)} free on the file system of "
                f"{self.path}, the margin is {human_bytes(self.required)} (lower it with --min-free-gib, 0 skips "
                f"the check, or set SCARAB_DATA_DIR to a larger file system)")


def existing_ancestor(path):
    """`path` or its nearest ancestor that exists: a run directory may not have been created yet."""
    return next(p for p in (path, *path.parents) if p.exists())


def free_space_shortfall(path, required, usage=shutil.disk_usage):
    """A `LowDiskSpace` when the file system of `path` has less than `required` bytes free, else None."""
    where = existing_ancestor(path)
    free = usage(where).free
    return LowDiskSpace(where, free, required) if free < required else None


def tree_size(path):
    """Bytes the tree under `path` occupies on disk (as `du`), symbolic links not followed, entries that
    cannot be read skipped; None when `path` does not exist."""
    if not path.exists():
        return None
    total = 0
    stack = [path]
    while stack:
        current = stack.pop()
        with contextlib.suppress(OSError):
            stat = current.lstat()
            total += stat.st_blocks * 512
            if current.is_dir() and not current.is_symlink():
                stack.extend(current.iterdir())
    return total


def human_bytes(count):
    """`count` bytes in the largest binary unit that keeps it at least 1, one decimal."""
    value, unit = float(count), "B"
    for larger in ("KiB", "MiB", "GiB", "TiB"):
        if value < 1024:
            break
        value, unit = value / 1024, larger
    return f"{value:.0f} {unit}" if unit == "B" else f"{value:.1f} {unit}"


def jdk_image(present=None):
    """The JDK image to run the node in: `scarab-jdk:local`, or the former `cardano-scala-jdk:local`
    while only that one exists on this machine (`present` tells whether a tag exists).
    """
    present = present or (lambda tag: subprocess.run(["docker", "image", "inspect", tag], capture_output=True).returncode == 0)
    return FORMER_JDK_IMAGE if not present(JDK_IMAGE) and present(FORMER_JDK_IMAGE) else JDK_IMAGE


def docker(*args, check=True, capture=True, timeout=None):
    result = subprocess.run(["docker", *args], text=True, capture_output=capture, timeout=timeout)
    if check and result.returncode != 0:
        raise SystemExit(f"docker {' '.join(args[:3])} failed: {result.stderr.strip()}")
    return result


def ref(name):
    return f"{name}-ref"


def in_ref(name, script, timeout=60, check=True):
    return docker("exec", ref(name), "/bin/sh", "-c", script, check=check, timeout=timeout)


def query(name, kind, node=1):
    out = in_ref(name, f"cardano-cli conway query {kind} --testnet-magic {MAGIC} "
                       f"--socket-path {ENV}/socket/node{node}/sock").stdout
    return json.loads(out)


TX = "/work/tx"


def put_file(name, path, text):
    r = subprocess.run(["docker", "exec", "-i", ref(name), "/bin/sh", "-c", f"cat > {path}"],
                       input=text, text=True, capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"cannot write {path}: {r.stderr.strip()}")


def read_json(name, path):
    return json.loads(in_ref(name, f"cat {path}").stdout)



def without_resource_statistics(name, config):
    """The reference node's configuration, unchanged where its kernel has per-task I/O accounting.

    cardano-node starts a linked thread that reads `/proc/self/io` every `TraceOptionResourceFrequency`
    milliseconds (default 1000), and the thread's exception stops the node
    (cardano-node 11.1.3, `Cardano/Node/Tracing/Tracers/Resources.hs:23-36`). A kernel built without
    `CONFIG_TASKSTATS` has no `/proc/<pid>/io`: the WSL 6.18.54.1 kernel of WSL 3.0.3, fixed in 6.18.58.1
    (WSL 3.0.5). There, and only there, the frequency is set to 0, which the node takes as "no resource
    statistics" and starts no thread; nothing else about the node changes. The check runs in the reference
    container, whose kernel is the one that matters (Docker Desktop's is not the WSL distribution's)."""
    if in_ref(name, "test -e /proc/self/io", check=False).returncode == 0:
        return config
    print(json.dumps({"referenceResourceStatistics": "off",
                      "reason": "the kernel has no /proc/self/io (CONFIG_TASKSTATS is not set)"}), flush=True)
    return dict(config, TraceOptionResourceFrequency=0)


def adapt_reference_config(name, path):
    """Rewrite the reference configuration at `path` with [[without_resource_statistics]] when the kernel needs it."""
    config = read_json(name, path)
    adapted = without_resource_statistics(name, config)
    if adapted is not config:
        put_file(name, path, json.dumps(adapted, indent=2))


CLI = "cardano-cli conway"
SOCKET = f"--testnet-magic {MAGIC} --socket-path {ENV}/socket/node1/sock"
LOAD = "/work/load"


# ---------------------------------------------------------------- era fixtures and e2e

ERAS = ["byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway"]
TAG_ERA = {0: "byron", 1: "byron", 2: "shelley", 3: "allegra", 4: "mary", 5: "alonzo", 6: "babbage", 7: "conway"}


# cats-effect prints this when the host starves the JVM of CPU (e.g. several clusters at once);
# it is a scheduling hint, not a node failure, so scenarios count it instead of failing on it.
STARVATION_MARKERS = ("Your app's responsiveness to a new asynchronous event", "starvation-and-tuning")


def is_starvation_warning(line):
    return any(m in line for m in STARVATION_MARKERS)


def starvation_warnings(path):
    """Number of cats-effect CPU-starvation warnings in the follower log."""
    if not path.exists():
        return 0
    return sum(1 for line in path.read_text(errors="replace").split("\n") if is_starvation_warning(line))


def scheduling_summary(events, log, args=None):
    """What the node measured about its own scheduling (D8): the epoch lines of the follower log carry
    `scheduling.maxTimerLatenessMs` and `scheduling.longestStepMs`; the cats-effect starvation warnings are counted."""
    windows = [e["scheduling"] for e in events if isinstance(e.get("scheduling"), dict)]
    longest = max(windows, key=lambda w: w.get("longestStepMs", 0), default={})
    return {
        "scalaCpus": getattr(args, "scala_cpus", None),
        "cpuStarvationWarnings": starvation_warnings(log),
        "epochWindows": len(windows),
        "maxTimerLatenessMs": max((w.get("maxTimerLatenessMs", 0) for w in windows), default=None),
        "timersLateOver100Ms": sum(w.get("timersLateOver100Ms", 0) for w in windows),
        "longestStepMs": longest.get("longestStepMs"),
        "longestStep": longest.get("longestStep"),
    }


def read_log(path):
    """(events, other lines) of the follower log; CPU-starvation warnings are not counted as other."""
    events, other = [], []
    if path.exists():
        text = path.read_text(errors="replace")
        lines = text.split("\n")[:-1]  # a last line without newline is still being written
        for line in lines:
            if line.startswith("{"):
                try:
                    events.append(json.loads(line))
                    continue
                except json.JSONDecodeError:
                    pass
            if line.strip() and not line.startswith("SLF4J") and not is_starvation_warning(line):
                other.append(line)
    return events, other


def stall_dump_arguments(root, site=SINGLE_FOLLOWER):
    """(node arguments, volume) that turn on the node's stall diagnostics (`--stall-dump`, issue
    371): a stall of the follower writes the runtime's fiber dump into `<run>/stall` (the stall directory of
    `site`), next to its `stallState` event in the follower log."""
    directory = site.directory(root, "stall")
    directory.mkdir(exist_ok=True)
    directory.chmod(0o777)
    return ["--stall-dump", "/stall"], f"{directory}:/stall"


def stall_evidence(root, site=SINGLE_FOLLOWER):
    """What a run keeps of a follower stall: its `stalled`, `stallState` and `stallDump` events and the fiber
    dumps (name and size; the files stay in `<run>/stall`, the stall directory of `site`), or None when the
    follower never stalled."""
    events, _ = read_log(site.log(root))
    kept = [e for e in events if e.get("event") in ("stalled", "stallState", "stallDump")]
    stall = site.directory(root, "stall")
    dumps = sorted(stall.glob("fiber-dump-*.txt")) if stall.is_dir() else []
    if not kept and not dumps:
        return None
    return {"events": kept, "fiberDumps": [{"file": str(d.relative_to(root)), "bytes": d.stat().st_size}
                                           for d in dumps]}
