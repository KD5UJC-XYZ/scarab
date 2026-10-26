<!-- SPDX-License-Identifier: Apache-2.0 -->
# Add a ledger predicate with its reference citation

**Status: Current.** A ledger rule change is level 3
([CONTRIBUTING](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)): its gates and
paperwork are there. The reference decides the check, its order and what the failure reports
([ADR 0001](../adr/0001-haskell-node-as-reference.md)).

1. Read the rule in the upstream source at a revision registered in `docs/data/reference-pins.json`.
   Note the file, the line range and the checks around it.
2. Add the typed failure case to the rule's enum in
   [PredicateFailure.scala](../../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PredicateFailure.scala)
   (`UtxoFailure`, `UtxowFailure`, ...), carrying the values the reference reports (for example
   `Mismatch(supplied, expected)`).
3. Add the check in the rule under `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/` at the
   reference's position, with the pinned citation in a comment before it, as in `rules/Utxo.scala`
   (`// f649f975: Babbage/Rules/Utxo.hs:368–375, ...`).
4. If a client can receive the failure, encode it in
   [PredicateFailureCbor.scala](../../node/src/main/scala/xyz/kd5ujc/scarab/node/ntc/PredicateFailureCbor.scala)
   with the reference's constructor tag.
5. Write a test that fails without the change, next to the rule's suite under
   `ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/rules/` (`CollateralWitnessSuite` is a small
   example): a case that breaks the rule and a twin that keeps it, from the reference or a real
   transaction where one exists, with original bytes for anything hashed.

If the rule is about decoding rather than validation, the expected verdict comes from the reference
decoders: [confirm-with-the-decoding-oracle.md](confirm-with-the-decoding-oracle.md).
