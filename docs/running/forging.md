<!-- SPDX-License-Identifier: Apache-2.0 -->
# Forging

Part of [Running the node and the devnet scenarios](../running.md).

## Forging

```sh
follow ... --listen 127.0.0.1:3001 --forge --vrf-skey vrf.skey --kes-skey kes.skey --opcert opcert.cert
```

The pool must already be registered with stake in the ledger the node follows. Only Praos (Conway)
blocks are forged. Keep key files outside the repository and readable only by the node's user. The KES
key file must be writable by the node (mode 0600, in a directory it can write): the node writes the evolved
key back (see below); the VRF key and the certificate can stay read-only.

At start the node checks the VRF key file as `cardano-node` does ([IN-83](../implementer-notes.md)): a file with any
"other" permission bit (`0o007`, for example 0644) is refused with a typed `ConfigError`-style key-file error, a file with
a "group" bit (for example 0640) starts with a `keyFileGroupAccess` warning, and 0600 or 0400 passes. Only the VRF
key is checked, and nothing is checked on a file system without POSIX permissions. Unlike the reference the node also
locks a hidden file beside the KES key (`--key-lock on`, the default), so a second Scarab instance on the same key
files stops with an `InstanceLocked` error even with another `--db`; `--key-lock off` skips the lock (for a key
directory the node cannot write to, which the KES write-back needs anyway). With a rotating `current` link the
lock file sits in the directory the link points to. See
[architecture.md](../architecture.md) for the forging design and [compatibility.md](../compatibility.md) for what
is not covered (no KES agent; preprod since epoch 321, mainnet not run).

#### A public pool on a test network (H1/H1a, [#37](https://github.com/KD5UJC-XYZ/scarab/issues/37)/[#115](https://github.com/KD5UJC-XYZ/scarab/issues/115))

The node has forged on preprod since epoch 321; the evidence row with the pool ID and block hashes is
in [compatibility.md](../compatibility.md). The procedure that got there, without deployment specifics:

1. **Registration and stake.** Register the pool, have faucet stake delegated to it and wait for the
   stake snapshot: the pool can lead slots from the epoch after the one the snapshot was taken in.
   Keys come from `cardano-cli` (`node key-gen-KES`, `node key-gen-VRF`, `node issue-op-cert`). The
   cold signing key stays on the machine that issued the certificate and is never copied to the node.
2. **A database at the tip.** Start from a synced database (`--db`, or an imported one, see above):
   the mempool and the forger take the epoch history at start-up and do not run from an empty database.
3. **Run with `--forge`.** `--vrf-skey`, `--kes-skey` and `--opcert` as above, with `--listen` for the
   node-to-node address. `--max-inbound` counts connections per address and port, so behind a front
   proxy or NAT it limits connections rather than peers; that accounting is the operator's decision
   and the protocol option is tracked in [#35](https://github.com/KD5UJC-XYZ/scarab/issues/35).
4. **Verify the first leader slot.** The `forged` and `adopted` events carry the same slot and header
   hash, the block appears at that height on an explorer, and a Haskell node's tip contains it. An
   `adopted` that does not follow a `forged` of the same slot is a lost block, not a success.
5. **Leave it running.** Unattended operation needs the rotation below; a restart on the same key
   files continues from the period the file records.

A slot lost to another pool's block at the same height is normal: when the competing block has not
arrived by the slot, the node forges, then takes the network's block by the chain-selection tie-break
and rolls back, which is what the reference does. Start-up on a small host is dominated by the
volatile resume (minutes rather than seconds) and the forger waits for it. A KES agent
([H2 / #38](https://github.com/KD5UJC-XYZ/scarab/issues/38)) and a mainnet pool, whose cold key stays
offline ([#120](https://github.com/KD5UJC-XYZ/scarab/issues/120)), are not covered here.

### Rotating the KES key (H2a, [#116](https://github.com/KD5UJC-XYZ/scarab/issues/116))

A KES key signs for at most `maxKESEvolutions` periods (62 on mainnet, one period is `slotsPerKESPeriod` slots,
36 hours on mainnet), and so does the operational certificate that names it. A pool that is to run unattended
has to change both before they run out. The node does four things for that; a driver script does the rest.

- **Forward security.** The Haskell node never writes its KES key: it reads the period-0 file at every start
  and evolves it in memory (`Ouroboros.Consensus.Protocol.Ledger.HotKey.evolveKey`, which calls
  `forgetSignKeyKES` on the old key: `HotKey.hs` lines 385 to 436 in ouroboros-consensus-protocol at the tag
  `release-ouroboros-consensus-4.2.1.0`, the release series that cardano-node 11.1.3 requires, `^>= 4.2.0.1`
  in `cardano-node.cabal` line 179), so the period-0 key stays on
  disk for ever. Scarab retains a different file-backed policy ([ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)): when the key moves to a new period, at the first slot of the period
  whether or not the pool leads in it, the new key is written back to the `--kes-skey` file and the earlier key
  is overwritten, before the key signs in the new period. The file stays a `cardano-cli` envelope: the period
  is read off the key itself (the erased seeds of the tree spell it), so there is no new format and no
  migration. A restart needs no earlier key than the live file contains. This reduces the state available from
  a later read of that path if earlier copies have not been retained; it does not establish complete
  past-key erasure. The replacement protocol writes every byte to `<file>.next`,
  forces the file, decodes it again to check the key structure, verification key, period and serialized bytes,
  then forces its directory entry before it erases any predecessor byte. It overwrites the predecessor fully,
  forces that file, renames `.next` over the file atomically and forces the directory again. Recovery applies
  the same readback and pre-erasure directory force; a valid `.next` is retained and retried if rename fails,
  and a retry after a post-rename sync failure forces the main directory entry again. Thus process-crash recovery
  has a complete replacement after predecessor erasure. Power-loss durability depends on the filesystem,
  mount, device and storage controller honoring file and directory force and same-filesystem atomic rename;
  unsupported or failed directory force is surfaced as a typed persistence failure. Injected tests do not
  reproduce physical power loss. If persistence fails the node reports `kesPersistFailed`, counts
  `scarab_kes_persist_failures_total`, prevents further signing and stops through its typed fatal
  policy: storage failure exits 5, an internal durability failure exits 6, and a defect exits 7.
  Correct the cause and restart; the running process does not retry persistence or resume signing. A
  file system that keeps old blocks (copy-on-write, journalling) may keep the overwritten key; overwriting is
  not physical secure erasure. Use an encrypted filesystem and restrict key-directory access;
  encryption does not block plaintext reads of a mounted volume. The owner accepts this file-backed
  policy for now. The separate upstream KES agent process is tracked in [H2 / #38](https://github.com/KD5UJC-XYZ/scarab/issues/38). The key signs only after the lock that also guards a
  reload is held, so a block is never signed with a key and certificate of two different reloads, and the key
  only moves forward, so it never signs one period with two states.
- **Reload on SIGHUP.** `kill -HUP` (or `docker kill -s HUP`) makes the node read the KES key and the
  certificate again, as the Haskell node does: `Cardano.Node.Run.installSigHUPHandler` (cardano-node 11.1.3,
  `cardano-node/src/Cardano/Node/Run.hs` line 596) runs `updateBlockForging` (line 626), which reads the
  credentials with `mkConsensusProtocol` (line 627) and hands them to `setBlockForging` (line 648; the
  consensus side is `NodeKernel.setBlockForging` and `blockForgingController`, ouroboros-consensus-diffusion
  `NodeKernel.hs` lines 194, 416 and 428 at the same tag). The Haskell node checks that the KES key
  is the one the certificate names (`opCertKesKeyCheck`, `cardano-node/src/Cardano/Node/Protocol/Shelley.hs`
  line 214) and then switches; it does not check the certificate's signature, its window or its counter, so a
  bad one shows up as rejected blocks. Scarab, by a documented improvement, checks all of that first
  and switches only when all of these hold, and otherwise keeps the old credentials and logs a typed
  `kesReloadRefused` (`kind` is the case of `ReloadRefusal`) and counts `scarab_kes_reloads_total{outcome="refused"}`:
  the cold verification key hashes to the pool this node forges for (`WrongPool`); the certificate names the
  KES key in the file (`KeyNotCertified`) and carries the cold key's signature (`BadCertificateSignature`);
  malformed certificate input or a certificate verifier failure retains its typed cause as
  `CertificateFailure`, separately from a signature that was checked and rejected; the
  current KES period is inside `[start, start + maxKESEvolutions)` (`NotYetValid`, `Expired`); the key has not
  been evolved past the period it must sign in (`KeyPastPeriod`); and the certificate counter is the pool's
  counter on the chain or one above it (`Counter`, the rule of `doValidateKESSignature` in ouroboros-consensus-protocol
  `Protocol/Praos.hs` lines 620 to 647 at the tag above, which the node's header validation shares), so the chain will
  take the blocks. A good reload logs `kesReloaded` (`opCertCounter`, `kesStart`, `remainingKesPeriods`),
  erases the old key from memory and brings a period-0 key to the present period at once. Putting the same key
  back (a spurious HUP, or someone restoring the period-0 file) keeps the key in memory, which is further on.
  No reload can leave the node without credentials or with a mixture; a reload while a block is being signed
  waits for it. A missing replacement file (`NoSuchFileException`) is also a reload refusal.
  Other filesystem errors, verifier defects and failures to persist an evolved key are terminal,
  with their typed cause preserved. An interrupted key update prevents later signing until restart;
  cancellation itself remains cancellation.
- **Alerts.** `cardano_node_metrics_remainingKESPeriods_int`, `currentKESPeriod_int`,
  `operationalCertificate{Start,Expiry}KESPeriod_int` and `scarab_opcert_counter` are set at start-up and after
  every reload (the first two also after every evolution); `forgeStarted` and `kesReloaded` carry the same
  numbers. Alert on `remainingKESPeriods_int` falling below the driver's threshold while the driver's service is
  failing: the driver's exit status is the signal (below), and "Pool monitoring" lists the alerts and the dashboard.
- **Nothing in the node needs the cold key.** It is never read by the node and need not be mounted into its
  container.

**The rotation driver** `scripts/pool/rotate_kes.py` (Python 3 standard library, `cardano-cli` from the
`ghcr.io/intersectmbo/cardano-node:11.1.3` image through `docker run`) does the cold-key work. Run it from
a timer: `scripts/pool/rotate-kes.service`, `rotate-kes.timer` and `rotate-kes.env.example` are generic
systemd examples. The node's key directory has the shape

```
keys/current -> gen-000003-00000120      a relative link
keys/gen-000003-00000120/kes.skey, node.cert
keys/vrf.skey
```

and the node runs with `--kes-skey keys/current/kes.skey --opcert keys/current/node.cert --local-socket <socket>`
(the driver needs the socket). Each run reads the tip through the socket, computes the KES period from the
genesis' `slotsPerKESPeriod`, reads the certificate in use, and when it has `--threshold` periods or fewer left:
generates a KES pair and a certificate with the next counter (the pool's counter on the chain plus one, from
`query kes-period-info`) and the current period as its start, checks the certificate, switches `current` to
the new generation with one atomic rename, sends SIGHUP, waits up to `--confirm-timeout` seconds for the
`kesReloaded` line (counter and start period equal to the new certificate's) and then overwrites and removes
the old generation. It is idempotent and crash-safe: the rotation is recorded in `rotation.json` before the link
moves, the previous generation stays until the node confirmed, and the next run finishes what was left (the
signal is sent again, which is harmless); a `kesReloadRefused` puts the link back. Exit status: 0 done or
nothing to do, 1 the node refused (link restored), 2 not confirmed in time (run again), 3 the socket does not
answer, 4 configuration or tools wrong. When the node's `query kes-period-info` is not answered, the driver
says so and counts from the last counter it issued (`last-counter`); that fallback can issue a counter the
chain refuses if a rotation was not yet followed by a block, and the node then refuses the reload (typed, the old
pair stays).

**Cold key.** The driver signs with the pool's cold key, so on a *test network* the cold key is online, on the
machine of the driver, mounted into the `cardano-cli` container only (never into the node's); the owner accepts
that for a pool whose stake is test stake. A *mainnet* pool keeps the cold key offline: do the same steps by hand
on the offline machine (`cardano-cli node key-gen-KES`, `node new-counter --counter-value <on-chain + 1>`,
`node issue-op-cert --kes-period <current period>`), carry only the new `kes.skey` and `node.cert` to the producer
as a new `gen-...` directory, switch `current`, and send SIGHUP; the node performs the same checks.

Not covered yet: a node that is killed in the middle of a rotation, and a rotation the node refuses, are tested
in units only, not in a scenario ([#122](https://github.com/KD5UJC-XYZ/scarab/issues/122)); a KES agent that
holds the key instead of a file is H2 ([#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)).

The devnet scenario `kes-rotate` (below) runs all of this unattended: a short KES period, a small
`maxKESEvolutions`, the driver on a loop, several rotations while the node forges beside two Haskell pools.
