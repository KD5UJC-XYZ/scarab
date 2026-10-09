#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import copy
import hashlib
import json
import unittest
from private_cluster_fork_audit import audit_evidence, audit_receipts, records


def fixture():
    context = "aa" * 32
    anchor = {"hash": "01"*32, "slot": 5}
    def state(label, revision, generation, depth):
        return {"projection": {"contextId": context, "tupleId": label*64},
                "revision": str(revision), "generation": str(generation), "depth": str(depth)}
    c, a, rolled = state("1", 0, 0, 0), state("2", 1, 1, 1), state("1", 2, 2, 0)
    b1, b2 = state("3", 3, 3, 1), state("4", 4, 4, 2)
    r = {"scope": "fork-audit", "passed": True, "capacity": 8, "nA": 1, "nB": 2,
         "contextId": context, "anchor": anchor, "offeredB": [{"hash":"02"*32,"slot":6}, anchor],
         "anchorState": c, "aState": a, "rollbackState": rolled, "bState": b2,
         "aPrefixes": [a], "bPrefixes": [b1,b2], "referencePostStateMatched": True,
         "followingPeerSelectedBranch": True, "independentChainSelection": False}
    receipts = {}
    def observed(s, confirmation="acknowledged"):
        token = {"format": "node-durable-acknowledged-v1", "storeId":"bb"*32,
                 "contextId":context,"digest":str(int(s["generation"])+1)*64,
                 "generation": s["generation"], "capacity":8}
        raw = json.dumps(token).encode()
        digest = hashlib.sha256(raw).hexdigest()
        receipts[digest] = raw
        return {"confirmation":confirmation,"contextId":context,"stateId":s["projection"]["tupleId"],
                "revision":int(s["revision"]),"confirmedGeneration":int(s["generation"]),
                "depth":int(s["depth"]),"compactedBlocks":0,"derivedAnchorId":None,
                "retainedBlocks":int(s["depth"]),"receiptPath":"/receipts/"+s["generation"]+".json",
                "receiptSha256":digest}
    def phase(label, initial, rollback, prefixes, offered):
        confirmation = "acknowledged" if label == "a" else "loaded-verified"
        rows = [dict(observed(initial,confirmation),record="node-bootstrap",mode="bounded-durable",
                     rollbackCapacity=8,auditEnabled=True)]
        if label == "b":
            rows.append(dict(observed(initial,confirmation),record="node-loaded",projection=initial["projection"]))
        rows += [{"record":"node-intersection-offered","offeredPoints":offered,"acquisitionOnly":True,"appliedClaim":False},
                 {"record":"node-intersection-selected","selectedPoint":anchor,"offeredMatch":True,"acquisitionOnly":True,"appliedClaim":False},
                 dict(observed(rollback),record="node-rollback",initialIntersection=True,projection=rollback["projection"])]
        for i, s in enumerate(prefixes):
            rows += [{"record":"transfer-range-block","headerEnvelopeHex":str(i),"rawBlockHex":str(i)},
                     dict(observed(s),record="node-applied")]
        end = prefixes[-1]
        rows += [{"record":"node-state","projection":end["projection"],"revision":end["revision"],
                  "depth":end["depth"],"compactedBlocks":"0","derivedAnchorId":None},
                 dict(observed(end),scope="bounded-node-outcome",typedStop="TargetReached",
                      scopedTargetReached=True,peerResourcesFinalized=True,cleanupFailure=None,
                      potentiallyOlderThanDisk=False,externalReceiptStale=False,peerOpens=1,peerCloses=1,reconnects=0)]
        return rows
    return phase("a", c,c,[a],[anchor]), phase("b",a,rolled,[b1,b2],r["offeredB"]), r, receipts


class ForkAuditTests(unittest.TestCase):
    def test_complete_trace_and_all_original_receipts(self):
        a,b,r,receipts = fixture()
        summary = audit_evidence(a,b,r)
        self.assertFalse(summary["receiptBytesVerified"])
        self.assertTrue(audit_receipts(summary,receipts)["receiptBytesVerified"])

    def test_missing_nonempty_rollback_or_acknowledgement(self):
        for change in ("remove", "noop", "volatile", "projection", "generation", "omitted", "omittedFalse"):
            a,b,r,_ = fixture()
            row = next(x for x in b if x.get("record") == "node-rollback")
            if change == "remove": b.remove(row)
            elif change == "noop": row["revision"] = 1
            elif change == "volatile": row["confirmation"] = "loaded-verified"
            elif change == "projection": row["projection"] = r["aState"]["projection"]
            elif change == "omitted": row["projectionOmitted"] = True
            elif change == "omittedFalse": row["projectionOmitted"] = False
            else: row["confirmedGeneration"] = 1
            with self.subTest(change=change), self.assertRaises(ValueError): audit_evidence(a,b,r)

    def test_offered_points_and_selected_common_point_are_actual_and_ordered(self):
        for change in ("offered", "selected", "notfound", "order", "extra", "oldschema", "applied"):
            a,b,r,_ = fixture()
            offer = next(x for x in b if x.get("record") == "node-intersection-offered")
            selected = next(x for x in b if x.get("record") == "node-intersection-selected")
            if change == "offered": offer["offeredPoints"] = [r["anchor"]]
            elif change == "selected": selected["selectedPoint"] = r["offeredB"][0]
            elif change == "notfound": selected["offeredMatch"] = False
            elif change == "oldschema": offer["candidates"] = offer.pop("offeredPoints")
            elif change == "applied": selected["appliedClaim"] = True
            elif change == "extra": b.insert(3,copy.deepcopy(offer))
            else: b.remove(selected); b.insert(0,selected)
            with self.subTest(change=change), self.assertRaises(ValueError): audit_evidence(a,b,r)

    def test_replacement_original_cannot_precede_checked_rollback(self):
        a,b,r,_ = fixture()
        row = next(x for x in b if x.get("record") == "transfer-range-block")
        b.remove(row); b.insert(0,row)
        with self.assertRaises(ValueError): audit_evidence(a,b,r)

    def test_wrong_branch_projection_or_loaded_generation_rejects(self):
        for change in ("loaded", "final", "generation"):
            a,b,r,_ = fixture()
            row = next(x for x in b if x.get("record") == ("node-state" if change=="final" else "node-loaded"))
            if change=="generation": row["confirmedGeneration"] += 1
            else: row["projection"] = r["anchorState"]["projection"]
            with self.subTest(change=change), self.assertRaises(ValueError): audit_evidence(a,b,r)

    def test_wrong_revision_generation_depth_arithmetic_rejects(self):
        for field in ("revision","generation","depth"):
            a,b,r,_ = fixture()
            r["rollbackState"][field] = "9"
            with self.subTest(field=field), self.assertRaises(ValueError): audit_evidence(a,b,r)

    def test_target_cleanup_failure_and_capacity_override_reject(self):
        for field,value in (("typedStop","TimeBudget"),("peerResourcesFinalized",False),
                            ("cleanupFailure","failed"),("potentiallyOlderThanDisk",True)):
            a,b,r,_ = fixture(); b[-1][field] = value
            with self.subTest(field=field),self.assertRaises(ValueError): audit_evidence(a,b,r)
        a,b,r,_ = fixture(); a[0]["rollbackCapacity"] = 4
        with self.assertRaises(ValueError): audit_evidence(a,b,r)

    def test_missing_tampered_pending_or_mixed_store_receipt_rejects(self):
        for change in ("missing","tamper","pending","store","generation"):
            a,b,r,receipts = fixture(); summary = audit_evidence(a,b,r)
            digest = summary["requiredReceipts"][-1]["sha256"]
            if change=="missing": del receipts[digest]
            elif change=="tamper": receipts[digest] += b" "
            else:
                token = json.loads(receipts[digest])
                field,value = {"pending":("format","node-durable-pending-v1"),
                               "store":("storeId","cc"*32),"generation":("generation","5")}[change]
                token[field] = value; raw = json.dumps(token).encode(); new = hashlib.sha256(raw).hexdigest()
                receipts[new] = raw
                for needed in summary["requiredReceipts"]:
                    if needed["sha256"] == digest: needed["sha256"] = new
            with self.subTest(change=change),self.assertRaises((ValueError,KeyError)): audit_receipts(summary,receipts)

    def test_strict_original_json_and_limits(self):
        for raw in (b'{"x":1,"x":2}',b'[]',b'{"x":NaN}',b'\xff',b'{}\n'*1025):
            with self.subTest(raw=raw[:20]),self.assertRaises((ValueError,UnicodeError)): records(raw)
        self.assertEqual(records(b'{"record":"x"}\n'),[{"record":"x"}])


if __name__ == "__main__":
    unittest.main()
