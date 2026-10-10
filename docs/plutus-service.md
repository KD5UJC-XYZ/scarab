# Bounded same-epoch Plutus service

`lab.Main plutus-service` is a separate Compile-only service runner for the
existing isolated Conway PV9 Plutus V3 spending profile. The earlier
`plutus-research` diagnostic remains available unchanged. This runner does not
read an expected transaction descriptor or stop when a particular transaction
is included. Transactions enter through its loopback HTTP API.

```text
lab.Main plutus-service
  --profile isolated-conway-pv9-plutus-v3-spend-v1
  --initial /absolute/checked-bootstrap
  --manifest-sha256 <independently-retained-lowercase-sha256>
  --port <owned-loopback-reference-peer-port>
  --magic <isolated-network-magic>
  --output /absolute/fresh-output-directory
  --duration-seconds 45
  --max-blocks 64
```

All eight options are explicit and unique. Duration is 1–60 seconds and the block
limit is 1–128. The existing early epoch-zero bootstrap, testnet address network
zero, 1,000-slot epoch and 100ms slot geometry remain required. The effective
deadline is capped before the first epoch boundary. An announcement crossing
that boundary is rejected before fetching or publishing its block; this is not
an epoch-transition implementation. One immutable admission profile and one
confirmed-state owner apply for the entire run.

The output directory is private diagnostic evidence. `bootstrap-ready.json`
publishes the loopback API port and source/deadline bindings. The external local
supervisor supplies `peer-ready.json` using the existing owned-peer readiness
contract. Readiness waiting is part of the bounded lifetime. Publication records
bind full state pins and included transaction body/witness hashes. The final
result distinguishes a configured stop from failure; it does not itself claim
that a transaction succeeded. An external observer verifies acceptance and
inclusion. The HTTP client and endpoint oracle remain Test-only processes with
separate classpaths; the service runtime uses Compile artifacts only.

The pool is volatile. Duplicate/conflict outcomes and stale admission pins retain
their existing semantics; API requests do not mutate confirmed ledger state.
Confirmed state advances only through checked follower publication. API closure,
in-flight work, relay and service workers must settle before final evidence is
exported. Terminal UTxO/stake/protocol projections are observations, not checkpoint
images, restore capabilities or new authorities over confirmed state.

Evaluation evidence retains the checked execution without rerunning the script.
The store allows at most 128 records of at most 16 KiB; sink failure closes pool
eligibility. Its five-second timeout is cooperative: filesystem operations can
delay cancellation. File forcing and no-overwrite linking do not include a
directory-fsync or crash-durability guarantee. No durable pool/restart claim is
made. Relay retry limits and explicit exhaustion remain bounded as well.

The first acceptance controller is deliberately stricter than the general service
CLI: its requested duration must fit completely before the two-second epoch
margin. It accepts only duration/block-limit stops, so it cannot establish a live
`epochLimit` stop. Offline retained tests exercise the epoch-crossing refusal.
The existing slot-100 pre-funding and slot-300 bootstrap deadlines are unchanged.

The acceptance controller funds two disjoint script/collateral pairs, then uses
two independent successful spending transactions through HTTP. It checks that
the same API remains available after first inclusion, the second acceptance has
a fresh state pin, both original bodies/witnesses are included, collateral is
preserved, and the complete supported UTxO/fee/stake and represented protocol
fields match a separately acquired historical endpoint. The service does not
read these external expected outcomes. Funding snapshots are bracketed under
observed quiescence; the historical endpoint is acquired separately from a
running reference node. No atomic cross-query snapshot is claimed.

## Isolated service acceptance — 2026-10-10

Exact source `af8c589df14dee0342383f13abd3a8cf349bae15` passed the second
isolated attempt. The [curated receipt](../reference/plutus-admission/service-live-receipt.json)
binds source, full accepted pins, original transaction identities and evidence hashes.
Bootstrap was slot 154/block 3. First admission/inclusion were slots 369/414;
second admission/inclusion were slots 414/451. Both returned HTTP 202. The actual
duplicate returned HTTP 200 `AlreadyPresent`; the conflict returned HTTP 409
`InputsReserved`. The same API stayed available after each inclusion. The service
published 15 blocks, continued to slot 628/block 18 and stopped at `durationLimit`.
Recorded active service operation was **26.259 seconds** within the configured
30-second lifetime, whose budget also includes startup/readiness. All 19 opened
transports closed and owned-cluster cleanup was verified.

The exact historical endpoint matched all 10 supported UTxO entries, original
collateral, instantaneous stake and represented protocol fields. Fees increased
from 200,000 to 800,000 lovelace. Endpoint stake snapshots remained unchanged from
bootstrap; this is not a new runtime snapshot-transition claim. Both accepted
evaluations consumed 47,600 memory units and 19,269,788 steps against declared
100,000/30,000,000 budgets. Those are checked-runtime recordings, not a second
independent evaluation. Original bodies and witnesses were compared on inclusion;
whole-envelope byte equality is not asserted.

The first attempt failed before Scala startup: funding observation took 9.302s,
leaving insufficient time for the strict slot-300 snapshot check. Its failure and
verified cleanup are retained privately. The successful retry observed funding
in 0.821s. This establishes one successful bounded run, not timing reliability.

Final source verification passed 1,728 Scala/translator tests, 27 public gates,
466 Python tests run with two skips, and 20 separately mounted retained-evidence
tests. The latter do not ship with public fixtures. Full-suite log SHA256:
`f159f5f3d611c11f09cd8c6ab012b479f23d4073f06ec6b43703b8bcb30b0f73`;
gate/Python log: `b6d2a9f996880a260b843749118420fb6ffedb854a5489f12ee86eff7a0ebd31`;
retained log: `f258c6f917e824533e715b58b471c427dbe7668095ffbb806a736bc5f14eacb3`.

The [functional audit](functional-scala-audit.md) identifies follow-up correctness
work: typed termination/evidence events and an accurate contract for masked
evidence I/O under the owner fence. Those changes are separate from this exact
live-tested artifact; filesystem cancellation remains cooperative.

This phase does not enable repeated epochs, productive rewards, mixed admission
profiles, rollback/reconnect recovery, general scripts, full ledger validation or
complete consensus. Default reference initialization delay is a separate future
interoperability gate. All live acceptance remains isolated local Docker work
with disposable keys, no public-network submissions and no real funds.
