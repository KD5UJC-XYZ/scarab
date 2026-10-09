# Adaptive local sustained acceptance

This protocol replaces guessed block-count stopping points with one immutable completion point for each process. The isolated adaptive sustained-durable case passed on 2026-10-09 at source commit `d01360904287febbb7d2345409fd6e8aa51dc51f`. It demonstrates bounded same-epoch replay, acknowledged compaction and strict process handoff against a complete supported reference projection. Earlier fixed-target launchers and failed attempts remain unchanged. This is an isolated research profile, not full ledger or consensus validation.

## Required sequence

1. Bootstrap the private reference fixture. Stop both producing processes using identity-checked pidfd TERM, confirm their exits, and restart the same databases without forging credentials. Preserve all observed originals and process records. Observe all three reference nodes at the same point. This convergence observation does not prove that every network queue is empty.
2. Capture a bounded exact range and select its last two linked, empty original blocks. Acquire their exact predecessor once through native LocalStateQuery. Retain all four original CBOR payloads and native derived JSON from that acquisition. Replay the two originals into a capacity-two durable seed. This protocol does not claim that the validator ran during bootstrap forging.
3. Start process A while the reference producers are keyless. Check its exact intersection, full durable claim, and full projection. Record a conservative current-clock slot before restoring production. Every A successor must independently decode to a later slot. After A reaches the minimum depth of nine, demote both producers again and observe convergence. Select the observed completion point once, within depth 9–12, and publish the process/context-bound fence atomically. Do not move it to make a mismatch pass.
4. Require A's exact fence ACK after all runtime resources close, then successful process exit and retained checkpoint/journal bytes. Start a distinct process B by strict journal resume. Its full loaded claim and projection must equal A's; loading does not create an acknowledgement receipt. B must be ready while both reference producers are keyless.
5. After B readiness, restore reference production and verify the active producer's owned local Unix socket. Submit each of the two disposable local transactions once through that socket within the existing three-second submission allowance, with no intervening producer-role change. Require the IDs derived from the actual accepted original transaction-body bytes to show exactly the intended pair together in one newly produced B block before selecting B's completion point. Submission acceptance alone is insufficient. B must include at least three independently verified blocks produced after readiness, with total depth at most sixteen. Demote producers, select B's observed point once, and require the exact immutable fence ACK, exit and retained storage.
6. Acquire the exact final point once. The native helper checks point/block brackets around epoch, whole UTxO, chain-dependent protocol state and parameters queries. No latest-tip substitution, fallback acquisition, or retry is allowed. After verified helper exit and owned-container removal, map pinned native bytes to the Scala oracle manifest.
7. Restore production before starting the network-disabled audit so the two-epoch growth observation overlaps that work. Replay every original independently, compare the online projection and full supported reference projection, and verify the exact two transaction bodies together in the newly produced B suffix. Finish the independent audit and immutable source/binary pin checks within their original deadlines.

An immediate successor is retained and checked within the declared phase bounds; it is never silently dropped. A delayed historical block fails the readiness-slot predicate. A runtime that has already advanced beyond a declared fence fails without rollback, retargeting or fence ACK. Numeric maximum alone cannot signal success.

## Executed bounds

The fresh fixture uses 1,000 slots per epoch at 0.1 seconds per slot: 100 seconds per epoch. Security parameter 5 and active-slot coefficient 0.05 remain unchanged. The derived 400-slot randomness stabilization window remains unchanged; the relative candidate freeze cutoff becomes slot 600. These are new fixture parameters, not changes to a running cluster.

The executed workload limit was **500 seconds**, with an absolute **600-second** cleanup boundary. This preserved the explicitly reviewed 20-second increase over the earlier 480-second workload proposal. All stage deadlines are mapped once to a monotonic clock and bounded by the case deadline. Current UTC and genesis geometry, not a stale tip alone, determine epoch headroom.

| Post-admission reservation | Seconds |
| --- | ---: |
| Pre-query, role transitions, seed, readiness, handoff, submission and production opportunity, including epoch margin | 83 |
| B catch-up, exit and storage retention | 15 |
| Final native query including owned cleanup | 25 |
| Restore production | 5 |
| Two-epoch growth opportunity, overlapping audit and final pin checks | 210 |
| Final case guard | 25 |
| Total | **363** |

Admission must occur by elapsed **137 seconds** and have at least **83 seconds** of actual epoch headroom. The audit has a 65-second cap and final pin verification 10 seconds; these fit inside the overlapping 210-second tail reservation. Block arrivals and sufficient tail growth are stochastic: these bounds are admission limits, not timing guarantees. Bootstrap and pre-admission range capture consume the same overall case budget.

The complete accepted window is bounded by capacity two, sixteen blocks, 128 reported event-budget units and 32 MiB returned original bytes. Each operation receives only its remaining allowance; independent process limits do not reset aggregate accounting. Receive-byte overflow can only be detected after a bounded message read. The range command counts received ChainSync/BlockFetch messages, excluding the handshake; the existing runtime counts bounded next-event attempts. The controller sums these reported charges without resetting them. This is not a uniform count or bound on every network frame.

One reference cluster uses at most 2 CPU/2 GiB; sequential Scala processes use 1 CPU/1 GiB. The native query runs in a separate 1 CPU/1 GiB container with no network, read-only pinned executable/source/libraries, a shared owned Unix socket and private output. Its execution and cleanup share one absolute 25-second boundary from container operations beginning. The 20-second supervised helper may need up to two seconds of internal termination; external cleanup gets only the remaining allowance. Receipt presence alone is never success. Unknown creation outcomes require owner-scoped cleanup.

## Successful isolated case — 2026-10-09

The case replayed twelve linked original blocks from the pre-state at block 52, slot 1067 to the final state at block 64, slot 1412, all in epoch 1. A capacity-two seed supplied the first two originals. Process A reached cumulative depth nine; distinct process B loaded A's exact full durable claim and projection before peer use, then continued to depth twelve. Both processes acknowledged their exact immutable completion points after runtime resource finalization. Checkpoint and journal artifacts were retained across the handoff.

The intended two transactions appeared together in block 62, slot 1384, at zero-based index nine of the twelve-original sequence. The other eleven originals contained no transactions. The inclusion check used IDs computed from original accepted transaction-body CBOR bytes and independently checked the original bodies during the final audit; it did not substitute the intended submission IDs for observed inclusion.

Each native endpoint query performed one acquisition and zero reacquisitions. The acquired point/block brackets bound the supported epoch, whole-UTxO, protocol-state and parameter results at that endpoint. Release was sent; no release acknowledgement is claimed. These are two separately acquired endpoint states, not one acquisition spanning the run. Independent replay of every original matched the online final projection and the complete supported reference projection, with parameter endpoint equality checked.

After the final same-epoch capture, reference production resumed and the required two-epoch growth observation completed while the independent audit and pin checks ran. This later reference-chain growth does not demonstrate Scala validation across an epoch boundary. The supervised case finished with exit code zero in 319.56 seconds, within the original 500-second workload and 600-second absolute limits. Independent owner-scoped inventory found no remaining owned containers or volumes.

| Evidence commitment | Value |
| --- | --- |
| Executed source commit | `d01360904287febbb7d2345409fd6e8aa51dc51f` |
| Independent terminal review SHA-256 | `258a37f9e63d0fb0e5bf2c024b6e486746b5da17793f9aeabc89b576d0a9ddb7` |
| Terminal outcome SHA-256 | `6b7746b8b4491f6e8cfddc93f73c18ada75736d92fa9f5626a94deafd044e0f3` |
| Frozen source pin SHA-256 | `ecb743b5d04c2a663c9f8ab83bbc2c20e728dd615b2612e5a76aa17e3b06afe0` |
| Frozen inputs SHA-256 | `cbde7cea3a5095245fddd23b2a9a1ddca1f88a501c8f2f249e5417938fc04181` |

The compiled Scala artifacts came from the newly tested inclusion build and were bound to the verified source commit. No compiled-byte identity with an older baseline is asserted. Raw originals, native payloads, storage artifacts and private cluster material remain outside the public repository; the hashes above identify the retained evidence and do not make that private corpus publicly reproducible.

## Preserved failed attempts

Earlier fixed-target attempts and adaptive bootstrap failures remain failed observations with their original evidence. The immediately preceding adaptive attempt completed its process handoff and final-point acquisition but failed the final transaction-grouping audit: all twelve captured originals were empty, despite successful submission responses and relay mempool acceptance. It therefore established neither transaction inclusion nor final complete-reference acceptance.

The reference producer nodes' configured 60-second inbound node-to-node `TxSubmissionLogicV1` startup delay and their short producer lifetimes support a delivery-delay explanation. They do not prove that delay was the sole cause: the retained record lacks a per-protocol trace establishing exactly when that delay began or proving producer receipt. The successful case changed submission to the verified active producer socket after B readiness and production restoration, and required actual original-body inclusion before the final fence. It preserves the earlier failure rather than retrospectively treating submission acceptance as inclusion.

The failed attempt's outcome SHA-256 is `db8a26f7e4373df64e9b84541e368ff03de70c10abbe0ef78f5655c8bc0cc7c3`; its retained diagnosis SHA-256 is `fb6bcbcde7158f0ed4b9127cb03f3f2ae2ffa421d121ff48fec2c13f4d4033be`.


## Verification at the executed source pin

The full public regression at `d01360904287febbb7d2345409fd6e8aa51dc51f` passed 1,164 Scala tests, 63 translator tests and 25 public serial gates. The Python launcher suite collected 308 tests: 306 executed successfully and two optional retained-data cases were skipped. All 28 checkpoint guards passed. Separate runs passed 79 retained-data focused Scala tests, 57 controller tests with retained data, and 15 launcher tests. These separate scopes are not a single combined public-suite count. The live case and independent terminal evidence review also passed; these results do not assert hosted CI status.

## Evidence and claim boundaries

Original native payloads stay separate from derived native JSON. The mapping records source hashes and explicitly identifies tip records as the acquired first/final point brackets, not CLI output. Parameter bytes must remain identical across endpoints. Context manifests bind only pre-state; the post-state oracle is interpreted after independent original replay.

The independent audit covers the implemented bounded projection, original-body grouping, counters, nonce fields, registrations and parameter endpoint equality. It does not establish full ledger validation, monetary conservation, reward-seed admission, consensus chain selection, or power-loss durability. Retained-data compatibility tests and synthetic transport tests are reported separately from actual acquisition and live execution.

See [runtime fence contract](sustained-completion-fence.md) and [native oracle contract](../reference/sustained-query-client/README.md). Future runs still require their own source review, integration checks and bounded execution authorization. This acceptance record does not authorize another run.
