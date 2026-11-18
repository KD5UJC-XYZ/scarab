<!-- SPDX-License-Identifier: Apache-2.0 -->
# Observable upstream conformance (K2, #49)

Part of the [compatibility matrix](../compatibility.md); the register entry is `k2-upstream-conformance` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the matrix row for this component.

## Status as recorded

partial: bounded production adapters for nonempty GOV/positive ratification, native dependent LEDGERS, reward accounting/availability/V5 continuation and Mux closure/trailing bytes; GOV named empty-vote-map authorization corrected. The bounded closeout of 2026-10-20 is superseded by [CNF-1](../design-rules.md#cnf-1); the upstream cases these adapters do not run whole are required work under #49

## Evidence

Pinned native/formal governance outcomes, complete native batch states and typed failure payloads; released native RUPD/applyRUpd schedules; original Mux IO/IOSim outcomes plus controlled resource cleanup. [Scope and fixtures](../design/upstream-conformance.md#observable-functional-equivalence)

## Explicitly unchecked or unconfirmed

Broader seeds/branches, protocol scheduling, transport and timer campaigns remain under #49, where the upstream cases not yet run whole are required work; a case not yet run is incomplete, not a pass ([CNF-2](../design-rules.md#cnf-2)). Original invalid inputs and failed reference runs retain their explicit dispositions. The original GOV and size-zero NEWEPOCH judgments execute shared production kernels while strict full-certificate/state decoding retains its NonZero constraints. #268 removes the likelihood-generation mask; missing historical data and operational provenance rollout remain #129; no exact internal pulse or thread-schedule parity, formal RUPD or reference-process restart claim; #270 adds separately patched formal LEDGERS accepted-state observations
