# SPDX-License-Identifier: Apache-2.0
"""Negotiated-version comparisons for the `ntc` scenario (REF-04, #924).

The reference answers every block query it decodes at every negotiated version: its decoder ignores the
version (`decodeNodeToClient _ _ = decodeShelleyQuery`, Shelley/Node/Serialisation.hs:372, ouroboros-consensus
82ecba32), and the supported-version table binds its client encoder only. The version selects the encoding of
some answers. Every form below is asked of both nodes at every version from 16 to 23 and compared byte for
byte; a form either node does not answer fails the comparison, and the rows say which side closed.
"""
import contextlib
import json

from . import ntcquery
from .eraview import ERA_INDEX
from .ntcqueries import ask, hfc, sset, tip_point
from .util import MAGIC


def in_era(era, query):
    """A block query of another era than Conway: `[0, [0, [era, query]]]`."""
    return [0, [0, [ERA_INDEX[era], query]]]


CASES = [
    ("GetCurrentPParams", hfc([3])),
    ("GetGenesisConfig", hfc([11])),
    ("GetCBOR/GetCurrentPParams", hfc([9, [3]])),
    ("GetCBOR/GetGenesisConfig", hfc([9, [11]])),
    ("GetProposedPParamsUpdates", hfc([4])),
    ("GetPoolDistr", hfc([21, []])),
    ("GetStakeDistribution", hfc([5])),
    ("GetPoolDistr2", hfc([36, []])),
    ("GetStakeDistribution2", hfc([37])),
    ("GetMaxMajorProtocolVersion", hfc([38])),
    ("GetProposals", hfc([31, sset([])])),
    ("GetRatifyState", hfc([32])),
    ("GetFuturePParams", hfc([33])),
    ("QueryStakePoolDefaultVote", hfc([35, bytes(28)])),
    ("GetDRepDelegations", hfc([39, sset([])])),
    ("GetRewardInfoPools", hfc([18])),
    ("GetNonMyopicMemberRewards", hfc([2, sset([[0, 1000000000]])])),
    ("GetLedgerPeerSnapshot/legacy", hfc([34])),
    ("GetLedgerPeerSnapshot/all", hfc([34, 0])),
    ("GetLedgerPeerSnapshot/big", hfc([34, 1])),
    ("GetCBOR/GetRewardInfoPools", hfc([9, [18]])),
    ("GetCBOR/GetNonMyopicMemberRewards", hfc([9, [2, sset([])]])),
    ("GetCBOR/GetLedgerPeerSnapshot/legacy", hfc([9, [34]])),
    ("GetCBOR/GetLedgerPeerSnapshot/all", hfc([9, [34, 0]])),
    ("GetCBOR/GetLedgerPeerSnapshot/big", hfc([9, [34, 1]])),
    ("GetCBOR/GetPoolDistr", hfc([9, [21, []]])),
    ("GetCBOR/GetPoolDistr2", hfc([9, [36, []]])),
    ("GetCBOR/GetProposedPParamsUpdates", hfc([9, [4]])),
    ("GetCBOR/GetStakeDistribution", hfc([9, [5]])),
    ("GetCBOR/GetFuturePParams", hfc([9, [33]])),
    ("GetCBOR/GetDRepDelegations", hfc([9, [39, sset([])]])),
    # a query of an era the ledger is not in has the era mismatch for an answer
    ("Babbage/GetProposedPParamsUpdates", in_era("babbage", [4])),
    ("Babbage/GetStakeDistribution2", in_era("babbage", [37])),
    ("Babbage/GetLedgerPeerSnapshot/all", in_era("babbage", [34, 0])),
]


def ask_each(open_session, point, queries):
    """The raw answer to each query, None where the node closed the connection; a closed session is opened
    and acquired again for the next query, so that one close does not decide the rows after it."""
    answers = []
    with contextlib.ExitStack() as stack:
        session = None
        for query in queries:
            if session is None:
                session = stack.enter_context(open_session())
                session.acquire(point)
            answer = ask(session, query)
            answers.append(answer)
            if answer is None:
                stack.close()
                session = None
    return answers


def row(label, expected, got):
    """The comparison of one form at one version: equal only when the reference answered and the Scala node's
    answer is the same bytes, so that two closes never count as a match."""
    return {"query": label, "matched": expected is not None and got == expected,
            "referenceAnswered": expected is not None, "scalaAnswered": got is not None,
            "referenceBytes": len(expected or b""), "scalaBytes": len(got or b"")}


def compare_versions(name, ref_socket, scala_socket, dump_dir):
    def reference(version):
        return lambda: ntcquery.LocalStateQuery(f"{name}-ref", ref_socket, MAGIC, version=version)

    def scala(version):
        return lambda: ntcquery.LocalStateQuery(None, scala_socket, MAGIC, host=True, version=version)

    with ntcquery.LocalStateQuery(None, scala_socket, MAGIC, host=True) as probe:
        probe.acquire()
        point = tip_point(probe)
    queries = [query for _, query in CASES]
    results = {"point": [point[0], point[1].hex()], "compared": 0, "matched": 0,
               "referenceUnanswered": 0, "scalaUnanswered": 0, "perVersion": {}}
    answers = {}
    for version in range(16, 24):
        expected_all = ask_each(reference(version), point, queries)
        got_all = ask_each(scala(version), point, queries)
        rows = []
        for (label, _), expected, got in zip(CASES, expected_all, got_all):
            answers[(version, label)] = expected
            rows.append(row(label, expected, got))
            matched = rows[-1]["matched"]
            results["compared"] += 1
            results["matched"] += int(matched)
            results["referenceUnanswered"] += int(expected is None)
            results["scalaUnanswered"] += int(got is None)
            if not matched and expected is not None and got is not None:
                stem = f"diff-v{version}-{label.replace('/', '_')}"
                (dump_dir / f"{stem}.reference").write_bytes(expected)
                (dump_dir / f"{stem}.scala").write_bytes(got)
        results["perVersion"][str(version)] = rows

    def same(a, b, label):
        return answers[(a, label)] is not None and answers[(a, label)] == answers[(b, label)]

    results["boundary"] = {
        "ConwayPParamsEqual": same(20, 21, "GetCurrentPParams"),
        "genesisDiffers": answers[(20, "GetGenesisConfig")] is not None
        and answers[(21, "GetGenesisConfig")] is not None
        and answers[(20, "GetGenesisConfig")] != answers[(21, "GetGenesisConfig")],
        "wrappedGenesisEqual": same(20, 21, "GetCBOR/GetGenesisConfig"),
    }
    (dump_dir / "ntc-versions.json").write_text(json.dumps(results, indent=2))
    return results
