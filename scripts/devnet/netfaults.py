# SPDX-License-Identifier: Apache-2.0
"""Network faults for the `faults` scenario with Linux `tc netem`, in the reference container's network
namespace, with no change to any node image.

All nodes of a devnet share that namespace and talk over loopback, so there is no interface to shape per
node. Every link between two nodes is therefore a pair of proxy ports (one per direction, `lagproxy.py`
listens on them), and a `prio` queueing discipline on `lo` sends the packets of a link's ports (matched by
a `u32` filter on source or destination port) to a band of its own, whose `netem` carries the fault. Traffic
on any other port, including the node's query port and the proxies' own connections to the nodes, is
untouched. The commands run in a short-lived helper container that has `NET_ADMIN` and joins the namespace
(the reference image ships `tc`); the namespace is the only thing that changes, nothing on the host.
"""
import os
from .faultplan import LINKS
from .util import REFERENCE_IMAGE, REPO, docker, jdk_image, ref

SCALA_PORT = 3350   # the Scala node's listener (as the relay and pool scenarios)
PROXY_DIR = REPO / "scripts" / "devnet"

# link -> the two proxy ports, one for each node's outgoing connection to the other
# (first: first node of the link dials, second: second node dials)
PORTS = {"s-p2": (3402, 3352), "s-p3": (3403, 3353), "p2-p3": (3423, 3432)}
assert PORTS.keys() == LINKS.keys()


def routes(node_ports):
    """`(listen, upstream)` of every proxy: dialing the first port of a link reaches the link's second node
    and the other way round. `node_ports` has the Haskell pools' listeners under `p2` and `p3`."""
    target = {"scala": SCALA_PORT, **node_ports}
    out = []
    for link, (a, b) in LINKS.items():
        first, second = PORTS[link]
        out.append((first, target[b]))    # a dials `first` and reaches b
        out.append((second, target[a]))   # b dials `second` and reaches a
    return out


def peer_ports(node):
    """The proxy ports `node` dials, one per other node."""
    out = {}
    for link, (a, b) in LINKS.items():
        first, second = PORTS[link]
        if node == a:
            out[b] = first
        elif node == b:
            out[a] = second
    return out


def band(link):
    """The prio band of a link; 1:1 is the unshaped default."""
    return 2 + list(LINKS).index(link)


def setup_lines():
    """tc batch that creates the bands, the netem of each link (a pass-through) and the port filters."""
    bands = 1 + len(LINKS)
    lines = [f"qdisc add dev lo root handle 1: prio bands {bands} priomap " + " ".join(["0"] * 16)]
    for link in LINKS:
        b = band(link)
        lines.append(f"qdisc add dev lo parent 1:{b} handle {b}0: netem")
        for port in PORTS[link]:
            for end in ("dport", "sport"):
                lines.append(f"filter add dev lo parent 1: protocol ip prio 1 u32 match ip {end} {port} 0xffff flowid 1:{b}")
    return lines


def shape_line(link, delay_ms=0, jitter_ms=0, loss_percent=0):
    """tc line that sets a link's netem; all zero is a pass-through (the heal)."""
    b = band(link)
    parts = [f"qdisc change dev lo parent 1:{b} handle {b}0: netem"]
    if delay_ms:
        parts.append(f"delay {delay_ms}ms" + (f" {jitter_ms}ms" if jitter_ms else ""))
    if loss_percent:
        parts.append(f"loss {loss_percent}%")
    return " ".join(parts)


def teardown_lines():
    return ["qdisc del dev lo root"]


class Shaper:
    """Runs tc batches in the namespace of the reference container `name`."""

    def __init__(self, name, scratch):
        self.name = name
        self.scratch = scratch
        self.installed = False
        self.serial = 0

    def batch(self, lines, check=True):
        self.serial += 1
        path = self.scratch / f"tc-{self.serial}.batch"
        path.write_text("\n".join(lines) + "\n")
        path.chmod(0o644)
        # root in the helper: tc needs NET_ADMIN, and the helper writes nothing to the mounted directory
        return docker("run", "--rm", "--pull=never", "--cap-add", "NET_ADMIN", "--network", f"container:{ref(self.name)}",
                      "-v", f"{path}:/tc.batch:ro", "--entrypoint", "tc", REFERENCE_IMAGE, "-force", "-batch", "/tc.batch",
                      check=check, timeout=60)

    def install(self):
        done = self.batch(setup_lines(), check=False)
        if done.returncode != 0:
            raise SystemExit("cannot set up tc netem in the network namespace (kernel modules sch_netem, sch_prio and "
                             f"cls_u32 are needed): {done.stderr.strip()}")
        self.installed = True

    def shape(self, link, delay_ms=0, jitter_ms=0, loss_percent=0):
        self.batch([shape_line(link, delay_ms, jitter_ms, loss_percent)])

    def heal(self, links=tuple(LINKS)):
        self.batch([shape_line(link) for link in links])

    def remove(self):
        """Idempotent: removes the root qdisc, and with it the netem of every link and the filters."""
        if self.installed:
            self.batch(teardown_lines(), check=False)
            self.installed = False


def start_proxies(name, root, node_ports):
    """One container in the reference namespace that serves every link's proxy ports."""
    command = ["run", "-d", "--rm", "--pull=never", "--name", f"{name}-proxy", "--network", f"container:{ref(name)}",
               "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{PROXY_DIR}:/proxy:ro", "-v", f"{root / 'proxy'}:/ctl",
               "--entrypoint", "python3", jdk_image(), "/proxy/lagproxy.py", "--delay", "0", "--control", "/ctl/freeze"]
    for listen, upstream in routes(node_ports):
        command += ["--route", f"{listen}:{upstream}"]
    docker(*command)
