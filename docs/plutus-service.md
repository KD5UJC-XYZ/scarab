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

Verification results and exact tested source will be recorded after acceptance.
This phase does not enable repeated epochs, productive rewards, mixed admission
profiles, rollback/reconnect recovery, general scripts, full ledger validation or
complete consensus. Default reference initialization delay is a separate future
interoperability gate. All live acceptance remains isolated local Docker work
with disposable keys, no public-network submissions and no real funds.
