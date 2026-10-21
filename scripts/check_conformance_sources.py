#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Report changes to pinned upstream conformance sources without updating fixtures."""

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Source:
    name: str
    repository: str
    commit: str
    prefixes: tuple[str, ...]
    tracked_ref: str = "HEAD"


class MissingWatchedRoots(ValueError):
    def __init__(self, prefixes: list[str]):
        self.prefixes = prefixes
        super().__init__(f"watched upstream prefixes matched no files: {', '.join(prefixes)}")


def sources(root: Path) -> list[Source]:
    ledger = json.loads((root / "node/src/test/resources/upstream/conway-govcert/manifest.json").read_text())
    network = json.loads((root / "fixtures/reference/ouroboros-network-protocol-codecs/keepalive.tsv.manifest.json").read_text())
    wallet = json.loads((root / "fixtures/reference/wallet-queries/manifest.json").read_text())
    consensus = json.loads((root / "fixtures/reference/genesis-density/manifest.json").read_text())
    plutus = (root / "docs/plutus-conformance.md").read_text()
    # The reference executable's package pin, rather than a moving package index.
    match = re.search(r"IntersectMBO/plutus tag `[^`]+`, commit `([0-9a-f]{40})`", plutus)
    if match is None:
        raise ValueError("missing reference Plutus commit in docs/plutus-conformance.md")
    rewards = (root / "scripts/oracle/rewards/README.md").read_text()
    reward_pin = re.search(r"source revision is\s+`([0-9a-f]{40})`", rewards)
    if reward_pin is None:
        raise ValueError("missing released reward API source commit")
    return [
        Source("ledger", "IntersectMBO/cardano-ledger", ledger["upstream"]["commit"],
               ("cabal.project", "libs/cardano-ledger-conformance/", "libs/cardano-ledger-test/",
                "libs/cardano-ledger-api/test/", "libs/cardano-ledger-api/testlib/",
                "libs/cardano-ledger-api/golden/")),
        Source("network", "IntersectMBO/ouroboros-network", network["sourceCommit"],
               ("cabal.project", "ouroboros-network/protocols/tests/", "ouroboros-network/protocols/tests-lib/",
                "ouroboros-network/tests/", "network-mux/test/", "cardano-diffusion/tests/",
                "cardano-diffusion/protocols/tests/", "cardano-diffusion/protocols/tests-lib/",
                "cardano-diffusion/protocols/cddl/",
                "cardano-diffusion/lib/Cardano/Network/Diffusion/Configuration.hs",
                "cardano-diffusion/lib/Cardano/Network/NodeToNode.hs",
                "ouroboros-network/lib/Ouroboros/Network/Diffusion/Policies.hs",
                "ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor/Types.hs",
                "ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerMetric.hs",
                "ouroboros-network/lib/Ouroboros/Network/BlockFetch/Decision.hs",
                "ouroboros-network/lib/Ouroboros/Network/BlockFetch/Decision/",
                "ouroboros-network/framework/lib/Ouroboros/Network/ConnectionId.hs",
                "ouroboros-network/lib/Ouroboros/Network/BlockFetch/DeltaQ.hs",
                "ouroboros-network/lib/Ouroboros/Network/DeltaQ.hs",
                "ouroboros-network/api/tests-lib/Ouroboros/Network/Mock/ChainGenerators.hs")),
        Source("consensus", "IntersectMBO/ouroboros-consensus", consensus["sourceCommit"],
               ("cabal.project",
                "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/Genesis/",
                "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/PointSchedule/",
                "ouroboros-consensus-diffusion/test/consensus-test/Test/Consensus/PeerSimulator/",
                "ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Node/",
                "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Genesis/",
                "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/",
                "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Storage/ChainDB/",
                "ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Util/LeakyBucket.hs",
                "ouroboros-consensus/src/unstable-consensus-testlib/Test/Util/TestBlock.hs",
                "ouroboros-consensus/test/consensus-test/Test/Consensus/MiniProtocol/ChainSync/CSJ.hs",
                "ouroboros-consensus/test/infra-test/Test/Ouroboros/Consensus/Util/LeakyBucket/")),
        Source("rewards", "IntersectMBO/cardano-ledger", reward_pin[1],
               ("eras/shelley/impl/src/Cardano/Ledger/Shelley/PoolRank.hs",
                "eras/shelley/impl/src/Cardano/Ledger/Shelley/Rewards.hs",
                "eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs")),
        Source("wallet-queries", "IntersectMBO/cardano-ledger", wallet["ledger"]["commit"],
               tuple(wallet["ledger"]["sources"])),
        # Released query encoders have a different pin from the moving network-test corpus.
        Source("peer-snapshot-queries", "IntersectMBO/ouroboros-network", wallet["network"]["commit"],
               tuple("ouroboros-network/api/lib/" + path for path in wallet["network"]["sources"])),
        Source("formal", "IntersectMBO/formal-ledger-specifications",
               ledger["dependencies"]["formal-ledger-specifications"], ("hs/",), "master-artifacts"),
        Source("constrained", "input-output-hk/constrained-generators",
               ledger["dependencies"]["constrained-generators"],
               ("cabal.project", "constrained-generators.cabal", "src/", "test/", "examples/")),
        Source("plutus", "IntersectMBO/plutus", match[1],
               ("cabal.project", "plutus-conformance/", "plutus-core/testlib/",
                "plutus-core/untyped-plutus-core/testlib/")),
    ]


def github(path: str) -> dict:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "scarab-conformance-sources"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urlopen(Request(f"https://api.github.com/{path}", headers=headers), timeout=30) as response:
        return json.load(response)


def selected_tree(repository: str, commit: str, prefixes: tuple[str, ...], fetch=github) -> dict:
    tree = fetch(f"repos/{repository}/git/trees/{commit}?recursive=1")
    if tree.get("truncated") or not isinstance(tree.get("tree"), list):
        raise ValueError("incomplete upstream tree; no freshness result can be claimed")
    selected = {row["path"]: row["sha"] for row in tree["tree"]
                if row["type"] == "blob" and row["path"].startswith(prefixes)}
    return selected


def inspect(source: Source, fetch=github) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", source.commit):
        raise ValueError("source pin must be a complete commit SHA")
    # Resolve once, then use only immutable SHAs even if the default branch moves.
    latest = fetch(f"repos/{source.repository}/commits/{source.tracked_ref}")["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", latest):
        raise ValueError("invalid upstream head SHA")
    before = selected_tree(source.repository, source.commit, source.prefixes, fetch)
    after = before if latest == source.commit else selected_tree(source.repository, latest, source.prefixes, fetch)
    roots = [{"prefix": prefix,
              "pinnedFiles": sum(path.startswith(prefix) for path in before),
              "observedFiles": sum(path.startswith(prefix) for path in after)}
             for prefix in source.prefixes]
    missing = [root["prefix"] for root in roots if root["pinnedFiles"] + root["observedFiles"] == 0]
    if missing:
        raise MissingWatchedRoots(missing)
    added = sorted(after.keys() - before.keys())
    removed = sorted(before.keys() - after.keys())
    changed = sorted(path for path in before.keys() & after.keys() if before[path] != after[path])
    return {"name": source.name, "repository": source.repository, "pin": source.commit,
            "head": latest, "trackedRef": source.tracked_ref, "watchedPrefixes": source.prefixes,
            "watchedRoots": roots,
            "status": "changed" if added or removed or changed else "unchanged",
            "added": added, "removed": removed, "changed": changed}


def summary(results: list[dict]) -> str:
    lines = ["# Upstream conformance source freshness", "",
             "This checks source drift, not generator execution or behavioral equivalence. "
             "Pins and fixtures are never updated automatically. Upstream changes are "
             "review leads, not a requirement to abandon the supported Haskell release.", "",
             "| Source / tracked ref | Pinned commit | Observed upstream head | Watched-source result |",
             "|---|---|---|---|"]
    for row in results:
        status = row["status"]
        if status != "error":
            status += f" ({len(row['added'])} added, {len(row['removed'])} removed, {len(row['changed'])} changed)"
        lines.append(f"| {row['name']} / {row.get('trackedRef', 'unavailable')} | `{row['pin'][:12]}` | `{row.get('head', 'unavailable')[:12]}` | {status} |")
    lines += ["", "The JSON artifact lists watched paths and every changed file. Changes outside those "
              "paths are not assessed. Moved paths require watch-list review. An API or incomplete-tree "
              "failure is an error, never an unchanged result.", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", required=True, type=Path)
    args = parser.parse_args()
    results = []
    for source in sources(ROOT):
        try:
            results.append(inspect(source))
        except (OSError, ValueError, KeyError, TypeError) as error:
            failure = {"name": source.name, "repository": source.repository,
                       "pin": source.commit, "trackedRef": source.tracked_ref,
                       "watchedPrefixes": source.prefixes,
                       "status": "error", "errorType": type(error).__name__}
            if isinstance(error, MissingWatchedRoots):
                failure["missingWatchedPrefixes"] = error.prefixes
            results.append(failure)
    args.output.write_text(json.dumps({"schema": "scarab-conformance-source-freshness-v1",
                                      "observedAt": datetime.now(timezone.utc).isoformat(),
                                      "sources": results}, indent=2) + "\n")
    args.summary.write_text(summary(results))
    print(summary(results))
    return int(any(row["status"] == "error" for row in results))


if __name__ == "__main__":
    raise SystemExit(main())
