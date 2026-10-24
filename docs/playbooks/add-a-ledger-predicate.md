<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a ledger predicate with its reference citation

The reference decides order and content. A predicate is a typed failure case, a check at the reference's position, a wire encoding and a test.

1. Read the rule in the pinned upstream source (pins: `docs/data/reference-pins.json`). Note the file, the line range and the order of the checks around it.
2. Add the typed failure case to the rule's enum in `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PredicateFailure.scala` (`UtxoFailure`, `UtxowFailure`, `PoolFailure`, ...). Never a `String`; carry the values the reference reports (`Mismatch(supplied, expected)`).
3. Add the check in the rule file under `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/`, at the reference's position in the for-comprehension, with the citation in a comment before it, for example `f649f975: Babbage/Rules/Utxo.hs:368-375`. Upstream line numbers are fine because the revision is pinned; Scarab line numbers never are.
4. If the failure reaches a client, add its CBOR in `node/src/main/scala/xyz/kd5ujc/scarab/node/ntc/PredicateFailureCbor.scala` with the reference's constructor tag.
5. Write a test that fails without the change, in the area's suite under `ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/rules/` (`CollateralWitnessSuite` is a small model): one case that breaks the rule, one twin that keeps it, original bytes for anything hashed. Prefer a case taken from the reference or a real transaction.
6. Gates (level 2, or 3 for consensus-critical rules; [run the gates](run-the-gates.md); `sbtw` is the Docker wrapper of [build in Docker](build-in-docker-from-a-worktree.md)):

```sh
sbtw check
python3 scripts/devnet.py --name mine e2e
python3 scripts/devnet.py --name mine down
```

7. Paperwork: `changes/<issue>-<slug>.md` (`### Fixed (ledger, #N)`), the compatibility row, a `docs/findings.md` row if found on real data, and the roadmap line.

If the rule is decoding rather than validation, use [the decoding oracle](confirm-with-the-decoding-oracle.md) for the expected verdict.
