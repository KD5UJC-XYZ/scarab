# SPDX-License-Identifier: Apache-2.0
"""Select the Scala node's consensus mode without changing the reference pools."""
import json


def configuration(root, mode, ports=()):
    """Write a separate configuration only when a mode was explicitly requested."""
    if mode is None:
        return []
    config = json.loads((root / "genesis" / "configuration.yaml").read_text())
    config["ConsensusMode"] = mode
    # The reference template's paths belong to its container. The follower chooses its own
    # database with --db and starts no Unix listener; relay supplies an explicit mounted socket.
    for key in ("SocketPath", "DatabasePath"):
        config.pop(key, None)
    path = root / "genesis" / "scala-consensus-configuration.json"
    path.write_text(json.dumps(config, indent=2))
    flags = ["--config", f"/genesis/{path.name}"]
    if mode == "GenesisMode":
        flags += trusted_pools(root, ports)
    return flags


def trusted_pools(root, ports):
    """The private reference pools satisfy this run's honest-availability assumption.

    CLI peers are deliberately untrusted in Scarab. Genesis therefore needs an explicit
    topology identifying these controlled local pools, just as the reference does.
    """
    ports = list(ports)
    if not ports:
        raise ValueError("Genesis devnet needs at least one controlled reference pool")
    topology = {"bootstrapPeers": None, "publicRoots": [], "useLedgerAfterSlot": -1,
                "localRoots": [{"accessPoints": [{"address": "127.0.0.1", "port": port} for port in ports],
                                "advertise": False, "behindFirewall": False, "trustable": True,
                                "diffusionMode": "InitiatorAndResponder",
                                "hotValency": len(ports), "warmValency": len(ports)}]}
    path = root / "genesis" / "scala-consensus-topology.json"
    path.write_text(json.dumps(topology, indent=2))
    return ["--topology", f"/genesis/{path.name}"]


def arguments(args):
    mode = getattr(args, "consensus_mode", None)
    return ["--consensus-mode", mode] if mode is not None else []
