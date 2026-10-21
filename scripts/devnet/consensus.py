# SPDX-License-Identifier: Apache-2.0
"""Select the Scala node's consensus mode without changing the reference pools."""
import json


def configuration(root, mode):
    """Write a separate configuration only when a mode was explicitly requested."""
    if mode is None:
        return []
    config = json.loads((root / "genesis" / "configuration.yaml").read_text())
    config["ConsensusMode"] = mode
    path = root / "genesis" / "scala-consensus-configuration.json"
    path.write_text(json.dumps(config, indent=2))
    return ["--config", f"/genesis/{path.name}"]


def arguments(args):
    mode = getattr(args, "consensus_mode", None)
    return ["--consensus-mode", mode] if mode is not None else []
