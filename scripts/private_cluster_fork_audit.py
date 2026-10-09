#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Strict ordinary-node fork telemetry auditor; no cluster or process operations."""
import hashlib
import json
import re

HEX = re.compile(r"[0-9a-f]{64}\Z")
DEC = re.compile(r"0|[1-9][0-9]*\Z")


def require(ok, message):
    if not ok:
        raise ValueError(message)


def records(raw):
    require(isinstance(raw, bytes) and 0 < len(raw) <= 20 * 1024 * 1024, "bounded stdout bytes")
    def unique(items):
        out = {}
        for k, v in items:
            require(k not in out, "duplicate JSON field")
            out[k] = v
        return out
    lines = [x for x in raw.decode("utf-8", "strict").splitlines() if x]
    require(len(lines) <= 1024, "stdout record bound")
    result = [json.loads(x, object_pairs_hook=unique,
                         parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-JSON number"))) for x in lines]
    require(all(type(x) is dict for x in result), "object records required")
    return result


def integer(v):
    require(type(v) is int and 0 <= v < 1 << 64, "canonical unsigned JSON integer")
    return v


def decimal(v):
    require(type(v) is str and DEC.fullmatch(v) and len(v) < 21, "canonical decimal string")
    return int(v)


def exact(left, right):
    # JSON's booleans and numbers are distinct even though Python equates False/0.
    return json.dumps(left, sort_keys=True, separators=(",", ":"), allow_nan=False) == json.dumps(
        right, sort_keys=True, separators=(",", ":"), allow_nan=False)


def one(rows, name, scope=False):
    selected = [x for x in rows if x.get("scope" if scope else "record") == name]
    require(len(selected) == 1, "unique " + name + " required")
    return selected[0]


def audit_evidence(a_records, b_records, checked_report):
    """Return checked trace summary and ALL receipt requirements for external-byte audit.

    The caller must run audit_receipts(summary, original_receipts_by_sha256) before
    accepting: telemetry paths/hashes alone are not retained acknowledgement evidence.
    The checked report must come from the separately pinned ForkAuditCommand process.
    """
    require(type(checked_report) is dict and checked_report.get("scope") == "fork-audit"
            and checked_report.get("passed") is True, "successful checked replay report required")
    r = checked_report
    a, b = integer(r["nA"]), integer(r["nB"])
    require(1 <= a <= 2 and a < b <= 4 and type(r["capacity"]) is int and r["capacity"] == 8,
            "fixed capacity-eight bounded competing branches")
    require(r.get("referencePostStateMatched") is True and r.get("followingPeerSelectedBranch") is True
            and r.get("independentChainSelection") is False, "checked scope required")
    context = r["contextId"]
    require(type(context) is str and HEX.fullmatch(context), "checked context hash")
    require(len(r["aPrefixes"]) == a and len(r["bPrefixes"]) == b, "complete prefix report")
    expected = [(r["anchorState"], 0, 0, 0), (r["aState"], a, a, a),
                (r["rollbackState"], 2*a, a+1, 0), (r["bState"], 2*a+b, a+1+b, b)]
    expected += [(s, i+1, i+1, i+1) for i, s in enumerate(r["aPrefixes"])]
    expected += [(s, 2*a+i+1, a+2+i, i+1) for i, s in enumerate(r["bPrefixes"])]
    for s, rev, gen, depth in expected:
        require((decimal(s["revision"]), decimal(s["generation"]), decimal(s["depth"])) == (rev, gen, depth),
                "checked replay revision/generation/depth arithmetic")
        require(s["projection"]["contextId"] == context, "prefix context binding")
    require(r["rollbackState"]["projection"] == r["anchorState"]["projection"], "complete C undo projection")
    require(r["aState"] == r["aPrefixes"][-1] and r["bState"] == r["bPrefixes"][-1], "final prefix binding")
    required = []

    def observed(row, state, confirmation):
        projection = state["projection"]
        require(row.get("confirmation") == confirmation, "typed confirmation required")
        require(row.get("contextId") == context and row.get("stateId") == projection["tupleId"], "checked state identity")
        require(integer(row.get("revision")) == decimal(state["revision"])
                and integer(row.get("confirmedGeneration")) == decimal(state["generation"])
                and integer(row.get("depth")) == decimal(state["depth"]), "observed R/G/depth mismatch")
        require(integer(row.get("compactedBlocks")) == 0 and row.get("derivedAnchorId") is None
                and integer(row.get("retainedBlocks")) == decimal(state["depth"]), "bounded original window required")
        require(type(row.get("receiptPath")) is str and row["receiptPath"].startswith("/")
                and type(row.get("receiptSha256")) is str and HEX.fullmatch(row["receiptSha256"]),
                "external immutable receipt reference required")
        required.append({"path": row["receiptPath"], "sha256": row["receiptSha256"],
                         "contextId": context, "generation": state["generation"]})

    def phase(rows, label, initial, prefixes, final, initial_confirmation, candidates):
        require(type(rows) is list and all(type(row) is dict for row in rows), "parsed object records required")
        bootstrap = one(rows, "node-bootstrap")
        require(bootstrap.get("mode") == "bounded-durable" and bootstrap.get("rollbackCapacity") == 8
                and bootstrap.get("auditEnabled") is True, "ordinary bounded durable audited node required")
        observed(bootstrap, initial, initial_confirmation)
        offered = one(rows, "node-intersection-offered")
        selected = one(rows, "node-intersection-selected")
        rollbacks = [row for row in rows if row.get("record") == "node-rollback"]
        # This acceptance covers one handshake rollback notification after the selected
        # intersection, not arbitrary rollback streams or a second fork transition.
        require(1 <= len(rollbacks) <= 2, "one initial rollback and at most one protocol no-op")
        rollback = rollbacks[0]
        require(offered.get("offeredPoints") == candidates and selected.get("selectedPoint") == r["anchor"]
                and selected.get("offeredMatch") is True, "actual offered candidates and selected C")
        require(all(row.get("acquisitionOnly") is True and row.get("appliedClaim") is False
                    for row in (offered, selected)), "intersection telemetry is acquisition-only")
        require(rows.index(bootstrap) < rows.index(offered) < rows.index(selected) < rows.index(rollback),
                "bootstrap/offered/selected/checked rollback order")
        require(rollback.get("initialIntersection") is True, "actual initial intersection rollback")
        rollback_state = r["anchorState"] if label == "a" else r["rollbackState"]
        observed(rollback, rollback_state, "acknowledged")
        require("projectionOmitted" not in rollback and rollback.get("projection") == rollback_state["projection"],
                "post-ack complete rollback projection without omission marker")
        announced_rollbacks = [row for row in rows if row.get("record") == "node-download"
                               and row.get("phase") == "rollback-announced"]
        extras = rollbacks[1:]
        require(len(announced_rollbacks) == len(extras), "all additional rollbacks require exactly one announcement")
        activity = [i for i, row in enumerate(rows)
                    if row.get("record") in ("transfer-range-block", "node-applied")
                    or (row.get("record") == "node-download" and row.get("phase") in ("announced", "fetched"))]
        require(activity, "branch acquisition required")
        first_forward = min(activity)
        require(rows.index(rollback) < first_forward, "initial checked rollback before forward acquisition")
        for announced, extra in zip(announced_rollbacks, extras):
            require(extra.get("initialIntersection") is False,
                    "additional rollback must not claim initial intersection")
            require(exact({k: v for k, v in extra.items() if k != "initialIntersection"},
                          {k: v for k, v in rollback.items() if k != "initialIntersection"}),
                    "additional rollback must preserve complete acknowledged row and receipt")
            require(rows.index(rollback) < rows.index(announced)
                    and rows.index(announced) + 1 == rows.index(extra) < first_forward,
                    "paired protocol no-op before any forward announcement or fetch")
            require(announced.get("downloadCursor") == r["anchor"]
                    and announced.get("downloadIsApplied") is False
                    and integer(announced.get("payloadBytes")) == 0,
                    "protocol no-op must announce C without applied claim or payload")
            observed(announced, rollback_state, "acknowledged")
            observed(extra, rollback_state, "acknowledged")
            transport_fields = {"record", "phase", "downloadCursor", "downloadIsApplied", "payloadBytes"}
            require(all(key in rollback and exact(value, rollback[key]) for key, value in announced.items()
                        if key not in transport_fields), "announcement must preserve current acknowledged state")
        if label == "b":
            loaded = one(rows, "node-loaded")
            observed(loaded, r["aState"], "loaded-verified")
            require(loaded.get("projection") == r["aState"]["projection"], "exact loaded A projection")
            require(rows.index(bootstrap) < rows.index(loaded) < rows.index(offered), "loaded before any peer intersection")
        else:
            require(not any(x.get("record") == "node-loaded" for x in rows), "create cannot claim loaded state")
        applied = [x for x in rows if x.get("record") == "node-applied"]
        captures = [x for x in rows if x.get("record") == "transfer-range-block"]
        require(len(applied) == len(prefixes) == len(captures), "all branch originals and acknowledgements required")
        for index, (row, state) in enumerate(zip(applied, prefixes)):
            observed(row, state, "acknowledged")
            require(rows.index(rollback) < rows.index(captures[index]) < rows.index(row), "rollback precedes B fetch and publication")
            if index:
                require(rows.index(applied[index-1]) < rows.index(captures[index]), "sequential original/publication order")
        state_row = one(rows, "node-state")
        require(state_row.get("projection") == final["projection"]
                and state_row.get("revision") == final["revision"] and state_row.get("depth") == final["depth"]
                and state_row.get("compactedBlocks") == "0" and state_row.get("derivedAnchorId") is None,
                "final complete online projection")
        terminal = one(rows, "bounded-node-outcome", scope=True)
        observed(terminal, final, "acknowledged")
        require(terminal.get("typedStop") == "TargetReached" and terminal.get("scopedTargetReached") is True
                and terminal.get("peerResourcesFinalized") is True and terminal.get("cleanupFailure") is None
                and terminal.get("potentiallyOlderThanDisk") is False and terminal.get("externalReceiptStale") is False
                and terminal.get("peerOpens") == 1 and terminal.get("peerCloses") == 1
                and terminal.get("reconnects") == 0, "clean exact target and peer finalization")
        require(rows.index(applied[-1]) < rows.index(state_row) < rows.index(terminal)
                and rows[-1] is terminal, "complete finalization ordering")
        require(len(prefixes) + len(extras) <= integer(terminal.get("events")) <= 64,
                "bounded event count covers every forward and protocol no-op")
        return len(extras)

    a_noops = phase(a_records, "a", r["anchorState"], r["aPrefixes"], r["aState"], "acknowledged", [r["anchor"]])
    b_noops = phase(b_records, "b", r["aState"], r["bPrefixes"], r["bState"], "loaded-verified", r["offeredB"])
    return {"scope": "fork-trace-audit", "passed": True, "receiptBytesVerified": False,
            "nA": a, "nB": b, "requiredReceipts": required,
            "protocolNoopRollbacks": {"a": a_noops, "b": b_noops},
            "rollbackRecordsChecked": 2 + a_noops + b_noops,
            "followingPeerSelectedBranch": True, "independentChainSelection": False}


def audit_receipts(summary, original_receipts_by_sha256):
    """Verify every referenced immutable external artifact; reject pending/stale/mixed stores."""
    from private_cluster_durable_node import acknowledged_receipt
    require(summary.get("scope") == "fork-trace-audit" and summary.get("passed") is True,
            "checked trace summary required")
    stores = set()
    by_generation = {}
    for needed in summary["requiredReceipts"]:
        digest = needed["sha256"]
        token = acknowledged_receipt(original_receipts_by_sha256[digest], digest, needed["contextId"])
        require(token["generation"] == needed["generation"], "receipt generation matches observed publication")
        stores.add(token["storeId"])
        prior = by_generation.setdefault(token["generation"], token)
        require(prior == token, "one token identity per generation")
    require(len(stores) == 1 and set(by_generation) == {str(i) for i in range(summary["nA"] + summary["nB"] + 2)},
            "one store and complete acknowledged generation chain")
    return dict(summary, receiptBytesVerified=True)
