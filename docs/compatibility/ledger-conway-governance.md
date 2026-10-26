<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Conway governance

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-conway-governance` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: proposals, votes, ratification, enactment, DRep pulser, guardrails, committee

## Evidence

unit/property (Ratify, ConwayGovernance suites); devnet soak/e2e compares proposals, votes, committee and committee state, constitution, DRep stake distribution and ratify state; `soak --governance-extended` (44/44 matched, epochs 0 to 18, every stage reached) drives, against the reference: SPO default votes after the bootstrap phase (pool reward accounts delegated to always-abstain, always-no-confidence and a DRep key; of two competing hard forks only the one with two voting pools is enacted, a no-confidence action is enacted on default votes alone, of three committee updates only one is), the committee term limit (an update with a term beyond `committeeMaxTermLength` that every body accepts is never enacted), a member that expires, `committeeMinSize` raised inside a ratification run and a withdrawal that stays unratified once the member has expired, a treasury withdrawal paid into a script reward account and withdrawn with the Rewarding purpose, a script DRep (Cert and Voting purposes), unelected committee voters refused at protocol 11; Preview governance fixtures and sync

## Explicitly unchecked or unconfirmed

the `Proposing` script purpose (guardrails script) is not driven; an unregistered pool reward account is covered by unit tests only (all three devnet pools are registered); committee thresholds other than 1/2 and several simultaneous members expiring are not driven; the mempool refusal of unelected voters before protocol 11 is a unit test only
