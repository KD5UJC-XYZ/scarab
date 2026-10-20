# SPDX-License-Identifier: Apache-2.0
"""Explicit negotiated-version comparisons for the `ntc` scenario (REF-04).

The reference's client encoder enforces query availability; its server decoder accepts even
unsupported constructors (Shelley/Node/Serialisation.hs:368-373). Compare supported replies byte
for byte, and separately assert that Scarab refuses constructors outside that contract.
"""
import json

from . import ntcquery
from .ntcqueries import ask, hfc, sset, tip_point
from .util import MAGIC


def compare_versions(name, ref_socket, scala_socket, dump_dir):
    def reference(version=None):
        return ntcquery.LocalStateQuery(f"{name}-ref", ref_socket, MAGIC, version=version)

    def scala(version=None):
        return ntcquery.LocalStateQuery(None, scala_socket, MAGIC, host=True, version=version)

    with scala() as probe:
        probe.acquire()
        point = tip_point(probe)
    cases = [
        ("GetCurrentPParams", [3], 16, 23),
        ("GetGenesisConfig", [11], 16, 23),
        ("GetRewardInfoPools", [18], 16, 23),
        ("GetCBOR/GetRewardInfoPools", [9, [18]], 16, 23),
        ("GetNonMyopicMemberRewards", [2, sset([[0, 1000000000]])], 16, 23),
        ("GetCBOR/GetNonMyopicMemberRewards", [9, [2, sset([])]], 16, 23),
        ("GetLedgerPeerSnapshot/legacy", [34], 19, 23),
        ("GetCBOR/GetLedgerPeerSnapshot/legacy", [9, [34]], 19, 23),
        ("GetLedgerPeerSnapshot/all", [34, 0], 23, 23),
        ("GetLedgerPeerSnapshot/big", [34, 1], 23, 23),
        ("GetCBOR/GetLedgerPeerSnapshot/all", [9, [34, 0]], 23, 23),
        ("GetCBOR/GetLedgerPeerSnapshot/big", [9, [34, 1]], 23, 23),
        ("GetCBOR/GetCurrentPParams", [9, [3]], 16, 23),
        ("GetCBOR/GetGenesisConfig", [9, [11]], 16, 23),
        ("GetProposedPParamsUpdates", [4], 16, 19),
        ("GetPoolDistr", [21, []], 16, 20),
        ("GetStakeDistribution", [5], 16, 20),
        ("GetPoolDistr2", [36, []], 21, 23),
        ("GetStakeDistribution2", [37], 21, 23),
        ("GetMaxMajorProtocolVersion", [38], 21, 23),
        ("GetProposals", [31, sset([])], 17, 23),
        ("GetRatifyState", [32], 17, 23),
        ("GetFuturePParams", [33], 18, 23),
        ("QueryStakePoolDefaultVote", [35, bytes(28)], 20, 23),
        ("GetDRepDelegations", [39, sset([])], 23, 23),
        ("GetCBOR/GetPoolDistr", [9, [21, []]], 16, 20),
        ("GetCBOR/GetPoolDistr2", [9, [36, []]], 21, 23),
    ]
    results = {"point": [point[0], point[1].hex()], "compared": 0, "matched": 0,
               "expectedRejections": 0, "rejected": 0, "perVersion": {}}
    boundary = {}
    for version in range(16, 24):
        rows = []
        with reference(version) as ref:
            ref.acquire(point)
            for label, query, first, last in cases:
                supported = first <= version <= last
                expected = ask(ref, hfc(query)) if supported else None
                if version in (20, 21) and supported:
                    boundary[(version, label)] = expected
                with scala(version) as session:
                    session.acquire(point)
                    got = ask(session, hfc(query))
                matched = expected is not None and got == expected if supported else got is None
                rows.append({"query": label, "supported": supported, "matched": matched,
                             "referenceBytes": len(expected or b""), "scalaBytes": len(got or b"")})
                if supported:
                    results["compared"] += 1
                    results["matched"] += int(matched)
                else:
                    results["expectedRejections"] += 1
                    results["rejected"] += int(matched)
                if not matched and expected is not None and got is not None:
                    stem = f"diff-v{version}-{label.replace('/', '_')}"
                    (dump_dir / f"{stem}.reference").write_bytes(expected)
                    (dump_dir / f"{stem}.scala").write_bytes(got)
        results["perVersion"][str(version)] = rows
    results["boundary"] = {
        "ConwayPParamsEqual": boundary[(20, "GetCurrentPParams")] == boundary[(21, "GetCurrentPParams")],
        "genesisDiffers": boundary[(20, "GetGenesisConfig")] != boundary[(21, "GetGenesisConfig")],
        "wrappedGenesisEqual": boundary[(20, "GetCBOR/GetGenesisConfig")] == boundary[(21, "GetCBOR/GetGenesisConfig")],
    }
    (dump_dir / "ntc-versions.json").write_text(json.dumps(results, indent=2))
    return results
