<!-- SPDX-License-Identifier: Apache-2.0 -->
# Not checked at all

Part of the [compatibility matrix](../compatibility.md#not-checked-at-all).

Collected from the table, for quick scanning:

- continuous preprod epoch-boundary comparisons and mainnet ledger identity (preprod epoch 320 and a captured synced point match; Preview to the tip and devnet boundaries are checked, K1/K7);
- full upstream conformance suites (K2 / [#49](https://github.com/KD5UJC-XYZ/scarab/issues/49), I1): bounded adapters cover fourteen Conway rule families, nonempty governance/positive ratification, dependent native transaction batches, reward accounting/boundaries/V5 continuation, Mux closure/trailing bytes, generic Handshake wire/selection and ordinary ChainSync/BlockFetch codecs. Original upstream failures and unsupported domains remain visible. The [ledger family inventory](../design/upstream-conformance.md) and [exact network inventory](../../fixtures/reference/ouroboros-network-protocol-codecs/COVERAGE.md) distinguish sampled behavior from remaining applicable properties and unsupported features. Shared production codecs/runtimes retain mock values without coercing them into Cardano hashes or addresses; pure selection and structural payload equality do not establish interpreter or scheduling parity. The pinned Plutus evaluation corpus passes 1,009/1,009 without exclusions under the upstream term/counting profile; ledger admission remains separately tested. The other adapters are bounded results, not full-suite completion;
- days-long stability, memory growth, file handles, reconnect behaviour (K4);
- mainnet (D6); preprod is synced from genesis to the tip (C4), and epoch 320 plus a captured synced point match Haskell; continuous comparisons remain K7;
- security properties: independent audit, side-channel analysis and hostile-deployment assessment remain unperformed; controlled adversarial bearer/state tests are recorded above.
