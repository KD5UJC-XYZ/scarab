<!-- SPDX-License-Identifier: Apache-2.0 -->
# Block production

Part of [Architecture](../architecture.md).

## Block production (`follow --forge`)

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The node produces Praos (Conway) blocks for one pool. The files are the ones `cardano-cli` writes: the text
envelopes `VrfSigningKey_PraosVRF` (64 bytes, seed then public key), `KesSigningKey_ed25519_kes_2^6` (the
608-byte period-0 key) and `NodeOperationalCertificate` (`[[hotVkey, counter, kesPeriod, sigma], coldVkey]`,
which also gives the cold key, so the cold signing key is never needed). On start the three are
cross-checked: the certificate must name this KES key and be signed by its cold key.

**Cryptography** (`crypto`, pure, with vectors):

- `Draft03Prover` is ECVRF-ED25519-SHA512-Elligator2 draft-03 proving as libsodium does it. It reproduces the
  IETF draft-03 vectors 16 and 17 (public key, 80-byte proof and 64-byte output), and a property test checks
  that every proof verifies with `StrictDraft03` and certifies the same output. Secret scalars only meet the
  constant-time arithmetic of `curve25519-elisabeth`; the hash-to-curve step takes public inputs only.
- `Sum6KesSigning` is the cardano-base `SumKES` layout: a level-`d` key is `subKey || r1 || vk0 || vk1`, the
  signature the leaf signature followed by the `vk0 || vk1` of each level (448 bytes), the verification key
  `Blake2b-256(vk0 || vk1)`. `generate(seed)` expands seeds with `Blake2b-256(0x01 || seed)` and
  `Blake2b-256(0x02 || seed)`; `fixtures/kes-gen` has the seeds `cardano-cli node key-gen-KES` stored in its keys
  together with the verification key it computed, and regenerating the depth-5 tree from the seed gives exactly
  that verification key. `evolve` moves one period and zeroes what cannot be used again (the seed of the right
  half when it is generated, the whole left half when the right one takes over); `fromBytes` recomputes every
  level's stored verification keys and refuses a damaged key. A key from a file is at period 0 and is evolved to
  the slot's period (`slot / slotsPerKESPeriod - certificate start`) before it signs.

**Forging** (`node/forge`). `Forger` runs one attempt per slot from the wall clock over the era history
(`EpochGeometry`); a passed slot is never forged late, and a slot that passes while an earlier attempt is still running is reported as `forgeSkipped` ("the forger was busy with an earlier slot"). For the slot it takes the tip's state to the slot's
epoch exactly as block validation does (cached per tip and epoch), reads the pool's stake and registered VRF
key from that epoch's pool distribution, proves `Blake2b-256(slot || epochNonce)` and compares the output with
the Praos threshold (`Leadership`, the same check as for received headers). When elected it:

1. selects transactions from the mempool and re-applies them one after another to the ledger ticked to the
   slot, in the block's own environment (`BlockBuilder.select`): `maxBlockBodySize`, `maxBlockExUnits` (the
   sum of the redeemers' execution units) and the per-block reference script limit are enforced, and a
   transaction that no longer applies is skipped;
2. encodes the body from the transactions' original bytes (bodies, witness sets, auxiliary data by index,
   invalid indices), hashes it as the header commits, and builds the ten-element Praos header body (block
   number, slot, previous hash, cold key, VRF key, VRF result, body size and hash, operational certificate,
   the ledger's protocol version);
3. evolves the KES key to the slot's period and signs the exact header body bytes;
4. offers the block to `Follower.adoptForged`, which runs it through the node's own `MirrorRules`, then commits it
   through the same path as any block (persistence, the chain server, mempool synchronisation). A block built on
   a tip that has moved is `Lost`; one our rules refuse is `Invalid`, and the forger then retries without
   transactions.

The forger does nothing until a peer session has intersected, and not from a tip older than the stability
window (a node that is still syncing cannot know the slot's ledger view). Only Praos with Conway transactions
is forged; a TPraos era or the bootstrap mode is `ForgeError.UnsupportedProtocol`.

Errors are a closed ADT, `ForgeError`: `KeyFile`, `KeyMismatch`, `KeyPeriodOutOfRange`, `KesFailure`,
`VrfFailure`, `NotLeader` (the ordinary answer), `LedgerViewUnavailable`, `UnsupportedProtocol`, `Assembly`,
`SelfRejected`. Log events: `forgeStarted`, `forged` (slot, block number, hash, transaction ids, KES periods,
stake), `adopted`, `lost` (the block did not join our chain, or was replaced at its height later),
`kesEvolved`, `kesReloaded`, `kesReloadRefused`, `kesPersistFailed` (see "Rotating the KES key" in
[running.md](../running.md)), `forgeSkipped`, `forgeFailed`.

The slot clock starts at the Byron genesis' `startTime` (whole seconds) when there is a Byron genesis
file, as the reference's hard-fork combinator does, and at the Shelley `systemStart` otherwise
(`Genesis.withSystemStart`; the `started` line logs the instant as `systemStart`). The two files agree on
the public networks, but a hand-made network can write them apart, and a node that used the Shelley value
then runs its slots a whole number of seconds away from the network's (roadmap H6).

Counters (`ForgeStats`, per epoch; roadmap H5): slots checked for leadership, leader slots, forged, adopted,
lost (slot battles lost at adoption, and blocks replaced later), failed, slots not checked by reason
(`busy`: the loop was still on an earlier slot; `tipTooOld`; `slotOccupied`: a block already held the slot or
a later one; `notConnected`), and the forge latency (slot start to adoption: count, mean, max). The first two
reasons are also logged one by one as `forgeSkipped`. A `forgeEpoch` line carries an epoch's counters when
the next epoch begins, an `adopted` line carries its `latencyMs`, and `GET /forging` on the query port
serves the counters per epoch and in total (the `pool` scenario reads it and asserts it against the log:
forged and adopted counts equal, no block took more than five slots from its slot's start to adoption).

Tests: `ForgeSuite` forges with real keys against the real `MirrorRules` over a synthetic Conway ledger (blocks are
accepted, consecutive, counted for the pool, transactions leave the mempool, the KES key crosses period
boundaries, an unregistered VRF key or a certificate outside its window never forges, lost and invalid
adoption, body size accounting and a decode round trip), `KeyFilesSuite` loads a real `cardano-cli`
certificate, `Draft03ProverSuite` and `Sum6KesSigningSuite` hold the vectors.
