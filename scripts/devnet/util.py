# SPDX-License-Identifier: Apache-2.0
"""Shared constants and Docker/CLI helpers for the devnet scripts."""
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys


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


# ---------------------------------------------------------------- run directories and disk space

MIN_FREE_DISK = 20 * 1024**3  # default margin a scenario needs free before it starts (issue 610)
_SIZE = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*([kmgt]?)(?:i?b)?\s*$", re.IGNORECASE)
_FACTORS = {"": 1, "k": 1 << 10, "m": 1 << 20, "g": 1 << 30, "t": 1 << 40}


class InsufficientDisk(SystemExit):
    """A scenario refused to start because the filesystem holding its run directory has less free space than
    the configured margin. A `SystemExit` so the refusal is one line instead of a traceback, and typed so a
    caller or a test can tell it from every other refusal."""

    def __init__(self, path, margin, free):
        self.path, self.margin, self.free = path, margin, free
        super().__init__(f"refusing to start: the filesystem holding {path} has {human_size(free)} free, "
                         f"below the {human_size(margin)} margin (--min-free-disk {margin} lowers it)")


def parse_size(text):
    """Bytes of a size such as `20G`, `512M`, `1.5T`, `20 GiB` or a plain byte count (`21474836480`); the
    units are binary. Raises `ValueError`, which argparse turns into a usage error."""
    match = _SIZE.match(str(text))
    if not match:
        raise ValueError(f"not a size: {text!r} (write 20G, 512M, 1.5T or a byte count)")
    return int(float(match.group(1)) * _FACTORS[match.group(2).lower()])


def human_size(n):
    """A byte count in binary units, one decimal place (`20.0 GiB`, `512.0 MiB`)."""
    for unit, factor in (("TiB", 1 << 40), ("GiB", 1 << 30), ("MiB", 1 << 20), ("KiB", 1 << 10)):
        if n >= factor:
            return f"{n / factor:.1f} {unit}"
    return f"{n} B"


def filesystem_free(path):
    """Free bytes on the filesystem that would hold `path`: the nearest existing ancestor is measured, so a
    scenario checks its margin before it creates the run directory."""
    path = pathlib.Path(path)
    existing = path
    while not existing.exists() and existing != existing.parent:
        existing = existing.parent
    return shutil.disk_usage(existing).free


def require_free_disk(path, margin=None, free=None):
    """Refuse, with `InsufficientDisk`, unless `margin` bytes are free on the filesystem that would hold
    `path`, and return the free space measured. `free` lets a caller (or a test) supply the measurement."""
    margin = MIN_FREE_DISK if margin is None else margin
    free = filesystem_free(path) if free is None else free
    if free < margin:
        raise InsufficientDisk(path, margin, free)
    return free


def directory_size(root):
    """Bytes of every file under `root`, recursively; a missing directory is 0. Symbolic links are not
    followed, so a run directory linking another does not count the same bytes twice. An entry that vanishes
    or cannot be read while it is walked (a container still writing into the directory) is skipped: the
    report is a courtesy at the end of a run, never the thing that decides whether the run succeeded."""
    total = 0
    if not _is_dir(root):
        return 0
    try:
        entries = pathlib.Path(root).rglob("*")
        for entry in entries:
            try:
                if entry.is_file() and not entry.is_symlink():
                    total += entry.stat().st_size
            except OSError:
                continue
    except OSError:
        pass
    return total


def _is_dir(path):
    try:
        return pathlib.Path(path).is_dir()
    except OSError:
        return False


def report_run_size(root, emit=None):
    """Print the size of the run directory at the end of a scenario and return it as `{runDir, bytes, human}`;
    `None` when there is no run directory (a command that never made one, or `down`, which removes it).

    The line goes to stderr: a scenario's last stdout line is its JSON summary, which callers parse.
    """
    root = pathlib.Path(root)
    if not _is_dir(root):
        return None
    try:
        size = directory_size(root)
    except OSError:  # measured only to be printed; a failure here must not replace the run's own outcome
        return None
    report = {"runDir": str(root), "bytes": size, "human": human_size(size)}
    line = (f"run directory {root}: {report['human']} ({size} bytes); "
            f"`devnet.py --name {root.name} down` removes it")
    (emit or (lambda text: print(text, file=sys.stderr)))(line)
    return report


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


def stall_dump_arguments(root):
    """(node arguments, volume) that turn on the node's stall diagnostics (`--stall-dump`, issue
    371): a stall of the follower writes the runtime's fiber dump into `<run>/stall`, next to its
    `stallState` event in the follower log."""
    directory = root / "stall"
    directory.mkdir(exist_ok=True)
    directory.chmod(0o777)
    return ["--stall-dump", "/stall"], f"{directory}:/stall"


def stall_evidence(root):
    """What a run keeps of a follower stall: its `stalled`, `stallState` and `stallDump` events and the fiber
    dumps (name and size; the files stay in `<run>/stall`), or None when the follower never stalled."""
    events, _ = read_log(root / "follower.jsonl")
    kept = [e for e in events if e.get("event") in ("stalled", "stallState", "stallDump")]
    dumps = sorted((root / "stall").glob("fiber-dump-*.txt")) if (root / "stall").is_dir() else []
    if not kept and not dumps:
        return None
    return {"events": kept, "fiberDumps": [{"file": str(d.relative_to(root)), "bytes": d.stat().st_size}
                                           for d in dumps]}
