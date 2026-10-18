# SPDX-License-Identifier: Apache-2.0
"""The `leios-probe` scenario (docs/leios.md section 12): the Leios mini-protocols against a private
network of the Leios team's reference node.

Three pools of the `ouroboros-leios` proto-devnet run in containers of an internal Docker network (no
published ports), with the load generator of the same build. Probes of the Scala side (`LeiosRefProbe`,
network test sources) then speak to node 1, and the scenario asserts what the reference does:

  * the handshake is accepted, and the first message the LeiosNotify (18) and LeiosFetch (19) responders
    accept is the build's own request (a plain `RequestNext`; the numbering differs between builds);
  * 18 and 19 are answered: announcements, offers, votes, endorser blocks and their transactions arrive;
  * every message that arrived is read by the `protocol` codecs as the table of `EXPECTED` says (a build
    whose numbering is not the Blueprint's is read after renumbering; a codec change that fixes or breaks
    one shows up here as a changed verdict);
  * the other build's `RequestNext` ends the connection, with the reference's own decoder error in its trace;
  * a pipelined run of the CIP's usage (1,000 requests, then 100 per 100 received) is or is not refused;
  * a duplex connection serving the Praos protocols from an empty chain draws nothing on 18 and 19, while
    a reference relay that has this side as a local root starts both protocols toward it, and a multiplexer
    without 18 and 19 (as `NodeToNodeServer.Protocol` today) ends that connection.

It records the chain's first announcement and first certificate and the era configuration, in
`<run dir>/results.json`, for the follower work (X1, X2).

Needs `scripts/prepare_leios_reference_image.py --profile ...` done, and the classes built:
`scripts/sbtw node/runtimeClasspathFile network/Test/compile` (in the build container).
"""
import json
import re
import shutil
import subprocess
import sys
import time
from .. import leios_reference as ref
from ..util import REPO, data_root
from prepare_leios_reference_image import PROFILES

# the dialect each build speaks (see LeiosRefProbe): the Blueprint's numbering or the older one
DIALECT = {"w38a": "w38a", "main": "blueprint"}
OTHER = {"w38a": "blueprint", "main": "w38a"}
# the first message a responder accepts, in hex, and what the other numbering's message is refused as
REQUEST = {"w38a": "8100", "main": "8103"}
REFUSED_KEY = {"w38a": "unexpected key (3, 1)", "main": "unexpected key (0, 1)"}
# the pipelined requests the reference accepts at once: the build's leiosNotifyPipelineDepth
PIPELINE = {"w38a": 100, "main": 1000}

# What the protocol codecs make of each kind of message, as received and (older numbering only) renumbered.
# Anything else fails the scenario. `votes` of the older numbering reads as an announcement of the
# Blueprint's (same tag 4), a silent misreading rather than a refusal: recorded, not endorsed.
EXPECTED = {
    "w38a": {
        "announcement": ("refused NotAdmitted(1)", "ok"),
        "block-offer": ("refused WrongArity(2,0,2)", "ok"),
        "txs-offer": ("refused NotAdmitted(3)", "ok"),
        "votes": ("ok", "ok"),
        "block": ("refused NotAdmitted(1)", "ok"),
        "block-txs": ("refused NotAdmitted(3)", "ok"),
    },
    "main": {
        "announcement": ("ok", None),
        "block-offer": ("ok", None),
        "txs-offer": ("ok", None),
        "votes": ("ok", None),
        "block": ("ok", None),
        # the build carries only the transactions, each as CBOR-in-CBOR (consensus#2416): arity 1, not 3
        "block-txs": ("refused WrongArity(4,3,1)", None),
    },
}
MUST_SEE = ("announcement", "block-offer", "votes", "block", "block-txs")
TPS = 40


def rows(text):
    """The lines of a probe's output file: `(ms, protocol, kind, codec, hex)`."""
    out = []
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) == 5:
            out.append((int(parts[0]), int(parts[1]), parts[2], parts[3], parts[4]))
    return out


def verdicts(codec):
    """`(as received, renumbered or None)` from the codec column of a row."""
    match = re.fullmatch(r"as-received:(.*?)(?: renumbered:(.*))?", codec)
    return (match.group(1), match.group(2)) if match else (codec, None)


def check_verdicts(profile, seen):
    """The failures among `seen` rows against `EXPECTED[profile]`."""
    failures = []
    for _, _, kind, codec, _ in seen:
        if kind in EXPECTED[profile] and verdicts(codec) != EXPECTED[profile][kind]:
            failures.append(f"{kind}: codecs said {verdicts(codec)}, expected {EXPECTED[profile][kind]}")
    return sorted(set(failures))


def trace_reasons(logs):
    """The `reason` of every responder error in the kept traces, by mini-protocol number."""
    found = []
    for line in logs.splitlines():
        if '"ns":"Net.InboundGovernor.Remote.ResponderErrored"' in line:
            try:
                data = json.loads(line)["data"]
            except (json.JSONDecodeError, KeyError):
                continue
            found.append((data.get("miniProtocolNum", {}).get("num"), data.get("reason", "")))
    return found


def unexpected_leios_channel(text):
    """Every observed closure is the typed first-SDU violation on protocol 18."""
    lines = text.splitlines()
    closed = [line for line in lines if line.startswith("connection ended:")]
    expected = "connection ended: leios-notify protocol violation: data on unexpected mini-protocol 18"
    return bool(closed) and all(line == expected for line in closed) and not any(
        line.startswith("ENDED ") for line in lines)


def read_logs(root):
    return "\n".join(p.read_text(errors="replace") for p in sorted((root / "logs").glob("node*.log")))


class Run:
    """One profile's run: the network, the captured traces, the probes and the checks."""

    def __init__(self, args, profile):
        self.args, self.profile = args, profile
        self.name = f"{args.name}-{profile.name}"
        self.root = data_root(self.name)
        self.failures, self.results = [], {"profile": profile.name, "nodeRevision": profile.node_revision,
                                          "configRevision": profile.config_revision}
        self.classpath = None
        self.followers = []

    def check(self, condition, text):
        print(("ok   " if condition else "FAIL ") + text, flush=True)
        if not condition:
            self.failures.append(text)

    def probe(self, label, mode, *rest, dialect=None, timeout=400, host=None):
        """Run `LeiosRefProbe` on the private network; the output and the message rows."""
        program = [host or ref.node(self.name, 1), str(ref.NODE_PORT), str(ref.MAGIC),
                   dialect or DIALECT[self.profile.name], mode, f"/out/{label}.tsv", *map(str, rest)]
        text = ref.run_probe(self.name, self.root, self.classpath, "xyz.kd5ujc.scarab.network.LeiosRefProbe",
                             program, label, timeout)
        tsv = self.root / "probes" / f"{label}.tsv"
        return text, rows(tsv.read_text() if tsv.exists() else "")

    def settle(self, seconds=3):
        """Give the capture threads a moment to write what the nodes just traced."""
        time.sleep(seconds)

    def errors(self):
        self.settle()
        return trace_reasons(read_logs(self.root))

    def prepare(self):
        if not subprocess.run(["docker", "image", "inspect", self.profile.tag], capture_output=True).returncode == 0:
            raise SystemExit(f"{self.profile.tag} is missing: python3 scripts/prepare_leios_reference_image.py "
                             f"--profile {self.profile.name}")
        if not (REPO / "node/target/runtime-classpath.txt").exists() or not (REPO / ref.TEST_CLASSES).is_dir():
            raise SystemExit("build the classes first: scripts/sbtw node/runtimeClasspathFile network/Test/compile "
                             "(in the build container)")
        if self.root.exists():
            raise SystemExit(f"{self.root} exists; run `devnet.py --name {self.args.name} leios-probe-down` first")
        ref.prepare(self.root, self.profile, 10)
        self.classpath = ref.freeze_probes(self.root)
        ref.start_nodes(self.name, self.root, self.profile)
        self.followers = ref.capture_logs(self.name, self.root)

    def start_load(self):
        tip = ref.wait_for_block(self.name, 2, 300)
        self.results["tipWhenLoadStarted"] = tip
        ref.start_firehose(self.name, self.root, self.profile, TPS)

    def initiator(self):
        """An initiator-only peer: handshake, then the build's own `RequestNext`, one request in flight."""
        text, seen = self.probe("initiator", "initiator", 120)
        print(text)
        kinds = {kind for _, _, kind, _, _ in seen}
        self.check("request-next" in kinds, "the first message on 18 was sent")
        for kind in MUST_SEE:
            self.check(kind in kinds, f"{kind} answered by the reference")
        failures = check_verdicts(self.profile.name, seen)
        self.check(not failures, "the protocol codecs read every message as expected" + (f" ({failures})" if failures else ""))
        self.check(not self.errors(), "the reference traced no responder error for the accepted dialect")
        self.results["initiator"] = {kind: sum(1 for r in seen if r[2] == kind) for kind in sorted(kinds)}
        self.messages = seen

    def other_dialect(self):
        """The other build's `RequestNext` is refused by the decoder, and the connection ends."""
        before = len(self.errors())
        text, _ = self.probe("other-dialect", "initiator", 15, dialect=OTHER[self.profile.name])
        reasons = self.errors()[before:]
        self.check("ENDED" in text, "the connection is ended by the reference after the other numbering's request")
        self.check(any(p == 18 and REFUSED_KEY[self.profile.name] in r for p, r in reasons),
                   f"the reference's decoder traced `{REFUSED_KEY[self.profile.name]}` on protocol 18")

    def credit(self):
        """The CIP's usage: 1,000 requests at once, then 100 per 100 received; and 100 at once."""
        limit = PIPELINE[self.profile.name]
        before = len(self.errors())
        text, _ = self.probe("credit-1000", "credit", 1000, 100, 60)
        received = int(re.search(r"messages received (\d+)", text).group(1))
        reasons = self.errors()[before:]
        refused = any("ExcessiveRequests" in r for _, r in reasons)
        self.results["credit1000"] = {"received": received, "refused": refused, "limit": limit}
        self.check(refused == (limit < 1000), f"1,000 pipelined requests {'refused' if refused else 'accepted'} (limit {limit})")
        text, _ = self.probe("credit-100", "credit", 100, 100, 60)
        received = int(re.search(r"messages received (\d+)", text).group(1))
        self.check(received > 0, f"100 pipelined requests answered ({received} messages)")

    def duplex(self):
        text, _ = self.probe("duplex", "duplex", 40)
        print(text)
        self.check("duplex: nothing received on 18 or 19" in text,
                   "the reference starts neither protocol toward a duplex peer it has not made hot")
        self.check(re.search(r"chain-sync: \d+ updates", text) is not None, "ChainSync served and used on the duplex connection")

    def listener(self):
        """A reference relay with this side as a local root connects and starts 18 and 19."""
        for label, leios in (("listen-without", "no"), ("listen-with", "leios")):
            ref.docker("rm", "-f", ref.node(self.name, 4), check=False)
            container = f"{self.name}-probe-{label}"
            command = ref.probe_command(
                self.name, self.root, self.classpath, "xyz.kd5ujc.scarab.network.LeiosRefProbe",
                ["0.0.0.0", str(ref.NODE_PORT), str(ref.MAGIC), DIALECT[self.profile.name], "listen",
                 f"/out/{label}.tsv", str(ref.NODE_PORT), "150", leios], container, self.root / "probes")
            proc = subprocess.Popen(["docker", *command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            time.sleep(8)
            ref.start_relay(self.name, self.root, self.profile, container)
            text = proc.communicate()[0]
            ref.docker("rm", "-f", ref.node(self.name, 4), check=False)
            shutil.rmtree(self.root / "devnet" / "node4", ignore_errors=True)
            (self.root / "probes" / f"{label}.out").write_text(text)
            if proc.returncode:
                raise subprocess.CalledProcessError(proc.returncode, proc.args, output=text)
            if leios == "no":
                self.check(unexpected_leios_channel(text),
                           "a multiplexer without 18 and 19 ends the connection at the reference's first SDU on 18")
            else:
                first = [r for r in rows((self.root / "probes" / f"{label}.tsv").read_text())
                         if r[1] == 18 and r[2] == "first-message-from-reference"]
                self.check(bool(first) and first[0][4] == REQUEST[self.profile.name],
                           f"the reference's first message on 18 toward a hot peer is {REQUEST[self.profile.name]}")

    def chain(self):
        text, _ = self.probe("chain", "chain", 400)
        print(text)
        first = re.search(r"first announcement: (.*)", text)
        cert = re.search(r"first certificate: (.*)", text)
        self.check(first is not None and first.group(1) != "none", "the chain has an announcing block")
        self.check(cert is not None and cert.group(1) != "none", "the chain has a certifying block")
        config = (self.root / "devnet" / "node1" / "config.yaml").read_text()
        hard_fork = re.search(r"^TestDijkstraHardForkAtEpoch: (\d+)$", config, flags=re.M)
        self.results["dijkstraHardForkAtEpoch"] = int(hard_fork.group(1)) if hard_fork else None
        self.results["firstAnnouncement"] = first.group(1) if first else None
        self.results["firstCertificate"] = cert.group(1) if cert else None
        self.results["tip"] = ref.tip(self.name)

    def capture(self, directory):
        """Write the first message of each kind of the initiator run as fixtures of this profile."""
        out = directory / self.profile.name
        out.mkdir(parents=True, exist_ok=True)
        picked, seen = [], set()
        for _, protocol, kind, _, hexa in self.messages:
            if kind not in seen and kind not in ("requests-sent",) and len(hexa) <= 400_000:
                seen.add(kind)
                picked.append((protocol, kind, hexa))
        (out / "messages.tsv").write_text("".join(f"{p}\t{k}\t{h}\n" for p, k, h in picked))

    def run(self):
        try:
            self.prepare()
            self.start_load()
            ref.wait_for_block(self.name, 6, 600)
            self.chain()
            self.initiator()
            self.other_dialect()
            self.credit()
            self.duplex()
            self.listener()
            if self.args.capture:
                self.capture(self.args.capture)
        finally:
            ref.stop_capture(self.followers)
            self.results["failures"] = self.failures
            if self.root.exists():
                (self.root / "results.json").write_text(json.dumps(self.results, indent=2))
            if not self.args.keep:
                keep = self.root / "results.json"
                saved = keep.read_text() if keep.exists() else None
                ref.remove(self.name)
                if saved:
                    print(saved)
        return self.failures


def leios_probe(args):
    names = sorted(PROFILES) if args.profile == "all" else [args.profile]
    failures = []
    for profile in names:
        print(f"== profile {profile}", flush=True)
        failures += [f"{profile}: {f}" for f in Run(args, PROFILES[profile]).run()]
    if failures:
        sys.exit("leios-probe failed:\n  " + "\n  ".join(failures))
    print("leios-probe passed")


def leios_probe_down(args):
    for profile in PROFILES:
        ref.remove(f"{args.name}-{profile}")
    print(json.dumps({"down": True}))
