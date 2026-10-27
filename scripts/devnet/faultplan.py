# SPDX-License-Identifier: Apache-2.0
"""The pure parts of the `faults` scenario (issue 224): the seeded fault schedule and the verdicts on what
the nodes did under it.

A schedule is a function of the seed, the duration and three protocol parameters of the genesis (`k`, the
active slot coefficient and the slot length), all of which are written into the schedule file, so that the
file alone replays a night's run and the seed alone regenerates the file. Only `random.Random.random()` draws
the numbers (its stream is the Mersenne Twister's, which Python does not change between releases; `choice`
and `shuffle` are not given that promise).

Every fault is an episode with a start and an end, episodes never overlap, and between two episodes there is
a grace period (the nodes recover) followed by a quiet window in which the chain must grow.
"""
import json
import random

VERSION = 1
NODES = ("scala", "p2", "p3")   # the Scala pool and the two Haskell pools
LINKS = {"s-p2": ("scala", "p2"), "s-p3": ("scala", "p3"), "p2-p3": ("p2", "p3")}
KINDS = ("latency", "loss", "partition", "crash")
WEIGHTS = (("latency", 3), ("loss", 3), ("partition", 3), ("crash", 2))

FIRST_START = (30, 45)       # seconds after the chain runs before the first episode
QUIET = (25, 45)             # minimum checked quiet window after the grace period, drawn per gap
GRACE = {"latency": 20, "loss": 20, "partition": 20, "crash": 40}
MIN_PARTITION = 3
PARTITION_BLOCKS = 5         # blocks the network grows in the longest partition (the devnet's k of the first schedules)
MAX_CRASH_DOWN = 20
MIN_DURATION = 150           # the longest first start, episode, grace and quiet window all fit, so every seed holds an episode


def max_partition_seconds(k, active_slots_coeff, slot_length):
    """The longest partition: the time in which the whole network is expected to grow `PARTITION_BLOCKS` blocks,
    or `k` blocks when `k` is smaller.

    A node holding one third of the stake grows a third of that on its own side, so a partition of this
    length costs it about a third as many blocks of rollback on reunion, comfortably under the common-prefix bound
    (a branch deeper than `k` could not rejoin, which is the protocol working and not a defect). The bound does
    not grow with `k`: the faults devnet's mainnet-like `k` (issue 527) is room for the reference's reconnection
    delays after a fault, not for longer partitions, and a schedule of a seed is the same under any `k` from
    `PARTITION_BLOCKS` up.
    """
    return max(MIN_PARTITION, int(min(k, PARTITION_BLOCKS) * slot_length / active_slots_coeff))


class Draw:
    """The only source of randomness; draws integers and picks from `random()` alone."""

    def __init__(self, seed):
        self.rng = random.Random(seed)

    def integer(self, low, high):
        return low + int(self.rng.random() * (high - low + 1))

    def pick(self, items):
        return items[self.integer(0, len(items) - 1)]

    def weighted(self, pairs):
        total = sum(weight for _, weight in pairs)
        point = self.integer(0, total - 1)
        for item, weight in pairs:
            if point < weight:
                return item
            point -= weight
        raise AssertionError("unreachable")

    def subset(self, items):
        """A nonempty subset in the order of `items`."""
        while True:
            chosen = [item for item in items if self.rng.random() < 0.5]
            if chosen:
                return chosen


def isolation(node):
    """The groups and the links cut when `node` is cut off from the other two."""
    others = [n for n in NODES if n != node]
    return [[node], others], [name for name, ends in LINKS.items() if node in ends]


def links_between(groups):
    members = {n: i for i, group in enumerate(groups) for n in group}
    return [name for name, (a, b) in LINKS.items() if members[a] != members[b]]


def episode(draw, kind, start, max_partition):
    if kind == "latency":
        delay = draw.integer(50, 400)
        return {"kind": kind, "start": start, "end": start + draw.integer(10, 40),
                "links": draw.subset(list(LINKS)), "delayMs": delay, "jitterMs": draw.integer(0, delay // 2)}
    if kind == "loss":
        return {"kind": kind, "start": start, "end": start + draw.integer(10, 40),
                "links": draw.subset(list(LINKS)), "lossPercent": draw.pick((2, 5, 10, 20))}
    if kind == "partition":
        groups, _ = isolation(draw.pick(NODES))
        return {"kind": kind, "start": start, "end": start + draw.integer(MIN_PARTITION, max_partition),
                "groups": groups, "links": links_between(groups)}
    down = draw.integer(5, MAX_CRASH_DOWN)
    return {"kind": kind, "start": start, "end": start + down, "node": draw.pick(NODES), "downSeconds": down}


def actions(episodes):
    """The explicit, time-ordered list of what the executor does, from the episodes."""
    out = []
    for e in episodes:
        if e["kind"] == "crash":
            out.append({"at": e["start"], "op": "kill", "node": e["node"]})
            out.append({"at": e["end"], "op": "start", "node": e["node"]})
            continue
        for link in e["links"]:
            shape = {"delayMs": 0, "jitterMs": 0, "lossPercent": 0}
            if e["kind"] == "latency":
                shape.update(delayMs=e["delayMs"], jitterMs=e["jitterMs"])
            else:
                shape["lossPercent"] = 100 if e["kind"] == "partition" else e["lossPercent"]
            out.append({"at": e["start"], "op": "shape", "link": link, **shape})
            out.append({"at": e["end"], "op": "heal", "link": link})
    return sorted(out, key=lambda a: (a["at"], a["op"] != "heal"))   # a heal precedes a shape at the same second


def generate(seed, duration, k, active_slots_coeff, slot_length):
    """The schedule for `seed`: episodes, their actions, and the quiet windows in which growth is checked."""
    if duration < MIN_DURATION:
        raise ValueError(f"--duration must be at least {MIN_DURATION} seconds")
    draw = Draw(seed)
    max_partition = max_partition_seconds(k, active_slots_coeff, slot_length)
    episodes, quiet = [], []
    start = draw.integer(*FIRST_START)
    previous_end = 0
    while True:
        kind = draw.weighted(WEIGHTS)
        e = episode(draw, kind, start, max_partition)
        gap = draw.integer(*QUIET)
        # the last quiet window must also fit before the end of the run
        if e["end"] + GRACE[kind] + gap > duration:
            break
        quiet.append({"from": previous_end + (GRACE[episodes[-1]["kind"]] if episodes else 5), "to": start})
        episodes.append(e)
        previous_end = e["end"]
        start = e["end"] + GRACE[kind] + gap
    if not episodes:
        raise ValueError("--duration is too short for one episode")
    quiet.append({"from": previous_end + GRACE[episodes[-1]["kind"]], "to": duration})
    return {
        "version": VERSION, "seed": seed, "duration": duration,
        "inputs": {"k": k, "activeSlotsCoeff": active_slots_coeff, "slotLength": slot_length},
        "limits": {"maxPartitionSeconds": max_partition, "maxCrashDownSeconds": MAX_CRASH_DOWN},
        "episodes": episodes, "actions": actions(episodes), "quiet": quiet,
        "healedBy": previous_end,
    }


def render(plan):
    return json.dumps(plan, indent=2, sort_keys=True) + "\n"


def replay_command(name, plan, schedule=None):
    """The command that repeats this run: the seed regenerates the schedule when the genesis parameters match;
    a run of a schedule file repeats with that file."""
    if schedule:
        return f"python3 scripts/devnet.py --name {name} faults --schedule {schedule}"
    return f"python3 scripts/devnet.py --name {name} faults --seed {plan['seed']} --duration {plan['duration']}"


def validate(plan):
    """Why a schedule read from a file cannot be executed, else None."""
    try:
        if plan["version"] != VERSION:
            return f"schedule version {plan['version']}, expected {VERSION}"
        last = -1
        for a in plan["actions"]:
            if a["at"] < last:
                return "actions are not in time order"
            last = a["at"]
            if a["op"] in ("shape", "heal") and a["link"] not in LINKS:
                return f"unknown link {a['link']}"
            if a["op"] in ("kill", "start") and a["node"] not in NODES:
                return f"unknown node {a['node']}"
            if a["op"] not in ("shape", "heal", "kill", "start"):
                return f"unknown action {a['op']}"
        if last > plan["duration"]:
            return "an action lies after the end of the run"
        problem = crash_pairing(plan["actions"])
        if problem:
            return problem
        for w in plan["quiet"]:
            if not 0 <= w["from"] < w["to"] <= plan["duration"]:
                return f"bad quiet window {w}"
    except (KeyError, TypeError) as e:
        return f"malformed schedule: {e!r}"
    return None


def crash_pairing(actions):
    """Why the kills and starts do not pair up, else None: a node is killed only while running, started only
    while down, and every node runs again at the end (a node left dead would pass on its stale last tip)."""
    down = set()
    for a in actions:
        if a["op"] == "kill":
            if a["node"] in down:
                return f"{a['node']} is killed at {a['at']} s while already down"
            down.add(a["node"])
        elif a["op"] == "start":
            if a["node"] not in down:
                return f"{a['node']} is started at {a['at']} s without a crash before it"
            down.discard(a["node"])
    if down:
        return f"{', '.join(sorted(down))} not started again by the end of the schedule"
    return None


# ---------------------------------------------------------------- verdicts

def growth_failures(samples, quiet, nodes=NODES):
    """Each quiet window must show every node's block number going up.

    `samples` are `{"t": seconds, "heights": {node: block number or None}}`; the first and last reading of a
    node inside the window are compared.
    """
    failures = []
    for window in quiet:
        for node in nodes:
            seen = [s["heights"].get(node) for s in samples
                    if window["from"] <= s["t"] <= window["to"] and s["heights"].get(node) is not None]
            if len(seen) < 2:
                failures.append(f"{node}: fewer than two readings in the quiet window {window['from']}..{window['to']} s")
            elif seen[-1] <= seen[0]:
                failures.append(f"{node}: no growth in the quiet window {window['from']}..{window['to']} s "
                                f"(block {seen[0]} to {seen[-1]})")
    return failures


def convergence_failures(tips):
    """`tips` maps a node to its (block number, hash), or None if it did not answer."""
    missing = sorted(n for n, t in tips.items() if t is None)
    if missing:
        return [f"no tip from {', '.join(missing)}"]
    if len({t[1] for t in tips.values()}) != 1:
        return [f"tips differ after healing and settling: {tips}"]
    return []


def progress_failures(before, tips):
    """Every node must have a block newer than the one it had when the faults were healed: matching tips alone
    do not show a node that still runs (a wedged node can keep an old tip). Both maps are node -> (block, hash)."""
    return [f"{n}: no new block after healing (block {before[n][0]}, now {tips[n][0]})"
            for n in sorted(tips) if tips[n] is not None and before.get(n) is not None and tips[n][0] <= before[n][0]]


def rollback_failures(events, k):
    """A rollback deeper than k is a common-prefix violation (or a schedule beyond the protocol's bound)."""
    return [f"the Scala node rolled back {e['depth']} blocks, k is {k}" for e in events
            if e.get("event") == "rolledBack" and e.get("depth", 0) > k]


def scala_failures(events, other, text):
    """No error events, no output the log format does not parse, no escaped cats-mtl Submarine."""
    failures = [f"Scala error event: {e.get('message', e)}" for e in events if e.get("event") == "error"][:5]
    failures += [f"unexpected Scala output: {line[:200]}" for line in other[:5]]
    if "Submarine" in text:
        failures.append("a cats-mtl Submarine reached the runtime's failure reporter")
    return failures


def haskell_failures(logs, died):
    """`logs` maps a node to its log text; `died` lists nodes that exited without being crashed on purpose."""
    failures = [f"{n} exited without being crashed by the schedule" for n in died]
    for n, text in logs.items():
        bad = [line for line in text.splitlines() if "DecoderFailure" in line and "ExceptionExit" in line]
        if bad:
            failures.append(f"{n} reported a decoder failure on the wire: {bad[0][:200]}")
    return failures


def rejoin_failures(restarts, events):
    """After a restart of the Scala node, its log must show it following blocks again."""
    return [f"the Scala node did not extend its chain after the restart at {r['at']} s"
            for r in restarts if not any(e.get("event") == "extended" for e in events[r["eventsBefore"]:])]
