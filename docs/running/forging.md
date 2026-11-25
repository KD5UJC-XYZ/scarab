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

The headers the node forges carry the reference's major protocol version and, as their minor, Scarab's block
producer identifier 73 under CIP-0203's layout (cardano-foundation/CIPs#1276; registry entry requested in
cardano-foundation/CIPs#1334), where cardano-node writes
its own release minor: an approved Deviation (#1348). `--producer-id opt-out` writes the opt-out identifier 255
instead ([flags](flags.md)).

At start the node checks the VRF key file as `cardano-node` does ([IN-83](../implementer-notes.md)): a file with any
"other" permission bit (`0o007`, for example 0644) is refused with a typed `ConfigError`-style key-file error, a file with
a "group" bit (for example 0640) starts with a `keyFileGroupAccess` warning, and 0600 or 0400 passes. Only the VRF
key is checked, and nothing is checked on a file system without POSIX permissions. Unlike the reference the node also
locks a hidden file for the KES key (`--key-lock on`, the default), so a second Scarab instance on the same key
paths stops with an `InstanceLocked` error even with another `--db`; `--key-lock off` skips the lock (for a key
directory the node cannot write to, which the KES write-back needs anyway). The lock is taken before any credential
file is read, so the second instance reads none, and does not finish or remove a key replacement the first is
writing (see "Rotating the KES key"). The lock file is `.kes.skey.scarab-lock` beside a key at `keys/kes.skey`. When
the key's directory is a symbolic link, as `current` is in the rotation layout below, the file sits beside the link
instead, in the directory that holds it, and is named after both (`keys/.current.kes.skey.scarab-lock` for
`keys/current/kes.skey`): switching the link and removing the old generation neither moves it nor removes it. The
node must be able to create it there. An instance that reaches the same key by another path, for example through
the generation directory itself, is not stopped by it. See
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
   hash, the block appears at that height on an explorer, and a Haskell node's tip contains it. A
   `lost` line for the slot, after its `forged` or later when another block replaces it, is a lost
   block, not a success.
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
  disk for ever. Scarab retains a different file-backed policy ([ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)): the key evolves in memory at the first slot of each
  KES period, whether or not the pool leads, also while the node has no ledger view for the slot
  ([#1101](https://github.com/KD5UJC-XYZ/scarab/issues/1101)), and at a reload; once a slot of that period, or a later
  one, has passed the forecast check (its ledger view can be forecast from the tip of the chain), the
  new key is written back to the `--kes-skey` file and the earlier key
  is overwritten, before the key signs in the new period. The period written is never later than that of such a
  slot ([ADR 0015's amendment of 2026-11-18](../adr/0015-kes-key-written-back-and-checked-reload.md#amendment-2026-11-18-only-a-confirmed-period-is-written-back)):
  a key moved on a reading of the clock ahead of the true time, at a period's first slot past the forecast range or
  at a reload, is not written, and a restart reads the file as it is. At start-up, a key in its file at a later
  period than the current one is logged as `kesKeyAhead` (Warning): it cannot sign before that period; restore the
  unevolved key, or issue a new KES key and operational certificate. A remaining limit, until the KES agent: a
  reading ahead that stays inside the forecast range (one KES period past the tip on mainnet parameters) passes the
  check, and the key evolved to it is written; `kesKeyAhead` at the next start says so.
  The file stays a `cardano-cli` envelope: the period
  is read off the key itself (the erased seeds of the tree spell it), so there is no new format and no
  migration. A restart needs no earlier key than the live file contains. Before a pool moves to `cardano-node`, issue a new KES key and operational certificate: a file Scarab has written back is not a credential for it ([ADR 0015's amendment of 2026-11-22](../adr/0015-kes-key-written-back-and-checked-reload.md#amendment-2026-11-22-the-period-0-key-is-not-kept)). This reduces the state available from
  a later read of that path if earlier copies have not been retained; it does not establish complete
  past-key erasure. The replacement protocol writes every byte to `<file>.next`,
  forces the file, decodes it again to check the key structure, verification key, period and serialized bytes,
  then forces its directory entry before it erases any predecessor byte. On Linux `.next` then takes the
  file's place in one atomic exchange (`renameat2` with `RENAME_EXCHANGE`), the directory is forced, and the
  predecessor, now at `.next`, is overwritten fully, forced and removed; elsewhere it overwrites the predecessor
  fully, forces that file, renames `.next` over the file atomically and forces the directory again, and logs
  `kesWrittenByRename` once. Neither writes to a key file that is not there (see "Dynamic block forging").
  Recovery applies
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
  only moves forward in memory, except that a reload starts again from the file, as the reference's does; a
  period's signature is deterministic in the key, so it never signs one period with two states.
- **Reload on SIGHUP.** `kill -HUP` (or `docker kill -s HUP`) makes the node read the VRF key, the certificate
  and the KES key again, as the Haskell node does: `Cardano.Node.Run.installSigHUPHandler` (cardano-node 11.1.3,
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
  been evolved past the period it must sign in, neither the key in the file nor the key in use the reload keeps
  (`KeyPastPeriod`); and the certificate counter is the pool's
  counter on the chain or one above it (`Counter`, the rule of `doValidateKESSignature` in ouroboros-consensus-protocol
  `Protocol/Praos.hs` lines 620 to 647 at the tag above, which the node's header validation shares), so the chain will
  take the blocks. A good reload logs `kesReloaded` (`opCertCounter`, `kesStart`, `remainingKesPeriods`),
  erases the old key from memory and brings a period-0 key to the present period at once. Putting the same key
  back (a spurious HUP, or someone restoring the period-0 file) keeps the key in memory, which is further on,
  unless the key in memory is past the present period (as a reload while the clock read ahead leaves it): then the
  reload starts again from the key read from the file, as the reference's reload always does, and brings it to the
  present; a file's key past the present is refused (`KeyPastPeriod`), whatever the key in use. So after a fault of the clock that leaves led
  slots given up with `forgeFailed` (`KesEvolution`), a SIGHUP once the clock is right recovers, with no restart.
  No reload that finds the files can leave the node with a mixture; a reload while a block is being signed
  waits for it. A credential file that does not exist disables forging instead, as in the reference (see
  "Dynamic block forging" below); a file that disappears between that check and its read is a refusal
  (`Unreadable`), as the reference keeps its forging on such a read error.
  Other filesystem errors, verifier defects and failures to persist an evolved key are terminal,
  with their typed cause preserved. An interrupted key update prevents later signing until restart;
  cancellation itself remains cancellation.
- **Alerts.** `cardano_node_metrics_remainingKESPeriods_int`, `currentKESPeriod_int` and
  `operationalCertificate{Start,Expiry}KESPeriod_int` are set at each update of the forge state, at the first slot
  of each KES period (#1101), as the reference's `Forge.StateInfo` sets them (a failed update, for a key past its
  window, zeroes the first two),
  and `scarab_opcert_counter` at start-up and after every reload; `forgeStarted` and `kesReloaded` carry the
  certificate's numbers. A key past its window is poisoned as in the reference: every slot then logs
  `forgeStateUpdateError` until a reload installs a new key, and with seven periods or fewer left every slot logs
  `stateInfo` at Warning (Alert with one). Alert on `remainingKESPeriods_int` falling below the driver's threshold while the driver's service is
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
machine of the driver; the owner accepts that for a pool whose stake is test stake. It is never mounted into the
node's container, and of the `cardano-cli` containers only two get a part of it, one file each and read-only: the
verification key for `node new-counter`, the signing key for `node issue-op-cert`. `--cli-image` is run as the
local image store holds it (`--pull=never`), and nothing verifies it: name it by digest rather than by tag. The
driver does not refuse mainnet (no `--testnet-magic`): a run that finds the signing key there prints a `warning`
line and goes on. A *mainnet* pool keeps the cold key offline: do the same steps by hand
on the offline machine (`cardano-cli node key-gen-KES`, `node new-counter --counter-value <on-chain + 1>`,
`node issue-op-cert --kes-period <current period>`), carry only the new `kes.skey` and `node.cert` to the producer
as a new `gen-...` directory, switch `current`, and send SIGHUP; the node performs the same checks.

Not covered yet: a node that is killed in the middle of a rotation, and a rotation the node refuses, are tested
in units only, not in a scenario ([#122](https://github.com/KD5UJC-XYZ/scarab/issues/122)); a KES agent that
holds the key instead of a file is H2 ([#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)).

### Dynamic block forging

Forging can be switched off and on while the node runs, without a restart
([#906](https://github.com/KD5UJC-XYZ/scarab/issues/906)), as cardano-node 11.1.3 does it
(`updateBlockForging`, `cardano-node/src/Cardano/Node/Run.hs` lines 620 to 668). This is how a pool hands
forging from one block producer to a standby: stop it on one, start it on the other.

- **Off.** Move away or delete the credential files (`--vrf-skey`, `--opcert`, `--kes-skey`) and send SIGHUP.
  The node reads them in the reference's order (the VRF key, the certificate, the KES key) and when one does
  not exist (the reference's `FileDoesNotExistError`: the path, followed through links, is missing or is a
  directory) it stops forging. It logs `blockForgingUpdate` with `enabled` `DisabledBlockForging`, sets
  `cardano_node_metrics_forging_enabled_int` to 0 and `/forging`'s `enabled` to false, and erases the KES key
  from memory. From then on it starts no slot (no leader check, no trace of the slot, no block) and writes
  nothing back, so a key file moved away does not reappear. The line is logged once the switch is made: a
  script that waits for it knows that nothing signs any more. Every SIGHUP that finds a file absent logs it
  again, as the reference does.
- **On.** Put the files back and send SIGHUP. They are read and checked as at any reload (the cold key, the
  certificate's signature, its window and its counter, above). If they pass, the node logs `kesReloaded` and
  `blockForgingUpdate` `EnabledBlockForging`, sets the gauge to 1 and forges from the next slot with the key
  as the file holds it, brought to the present period (and written back if that moved it), and the VRF key
  read with it (below). Files that are
  present but refused are reported as any refused reload (`kesReloadRefused`), and forging stays as it was:
  disabled.
- **Start disabled.** `--start-as-non-producing-node` (or `"StartAsNonProducingNode": true` in the
  configuration file; the flag wins, and `--non-producing-node` is its deprecated alias) starts the node with
  forging disabled although its files are given. As in the reference the files are read and checked at
  start-up all the same, so a standby with broken keys stops at start rather than at failover. The node logs
  `blockForgingUpdate` `DisabledBlockForging` and forges once a SIGHUP finds the files. A node without
  `--forge` logs `DisabledBlockForging` at start and at each SIGHUP, and reports 0.

**A key file moved away stays away.** Scarab writes the evolved KES key back
([ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)), but never to a key file that is not there:
a period change between the move and the SIGHUP writes nothing, logs `kesFileAbsent` (`kesPeriod`, `file`), and
the key in memory signs on until the SIGHUP, as the reference's does: its key lives in memory only
(ouroboros-consensus-protocol `release-ouroboros-consensus-4.2.1.0`, `HotKey.hs` 385-436). On Linux the evolved
key takes the file's place in one atomic exchange (`renameat2` with `RENAME_EXCHANGE`), which fails when the file
is gone, so a move at any moment is safe; a restart's recovery does not make a missing key file from a stage
either (the start fails, as the reference's does). On another system, or a file system that refuses the
exchange, the node logs `kesWrittenByRename` once, checks that the file is there before it erases and renames,
and a move in the instant between that check and the rename can still put the key back; move the VRF key or the
certificate too there, since any absent credential file disables forging
([#918](https://github.com/KD5UJC-XYZ/scarab/issues/918)).

The KES key's lock (`--key-lock`) stays held while forging is disabled: a SIGHUP can enable forging at
any time, so the lock keeps stopping a second Scarab instance on the same key paths. It is a hidden file of its
own, made at start-up, and nothing else is written in the key directory while forging is off. A standby on the
same machine and the same key paths therefore needs its own copy of the files or `--key-lock off`.

**The VRF key** read at a reload is the one forging takes from then on, as the reference's new block forging
does; like the reference, the node does not check it against the pool's registration, so a key the chain does
not know makes blocks the chain refuses. A slot whose leader check used another VRF key than the one in force
when the block would be signed is given up unsigned, as the reference's forging thread ends when its block
forging is replaced ([#919](https://github.com/KD5UJC-XYZ/scarab/issues/919)).

**SIGHUP during start-up.** As in the reference, a SIGHUP ends the process only while the configuration, the
genesis, the credentials and the topology are first read. From the moment the node opens its stores it has
the reference's initial handler (`Run.hs` 412-435): it reads the topology again, and the network starts from
what it read, and it logs `blockForgingUpdate` `NotEffective` (and sets `forging_enabled_int` to 0 once the
metrics exist), since credentials are not read until the forger runs; not when the topology or its peer snapshot is
refused, which ends the reference's handler before that
([peer selection](../compatibility/peer-selection-governor.md#reading-the-topology-924)). Once the node runs, every node has the
full handler, with `--forge` or not and with `--topology` or not
([#919](https://github.com/KD5UJC-XYZ/scarab/issues/919)).

The reference's bulk credentials file
(`--bulk-credentials-file`), any failure to read which also disables forging (`CredentialsReadError`,
`Run.hs` 664-667), does not exist in Scarab ([#222](https://github.com/KD5UJC-XYZ/scarab/issues/222)); with a
KES agent ([#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)) the reference reads only the VRF key and the
certificate. The devnet scenario `dynamic-forging` runs the off-and-on cycle beside two Haskell pools
([devnet](devnet.md)).

The devnet scenario `kes-rotate` (below) runs all of this unattended: a short KES period, a small
`maxKESEvolutions`, the driver on a loop, several rotations while the node forges beside two Haskell pools.

### Keys in memory

A forging node holds its VRF signing key and its current KES key in the JVM heap for as long as it runs; so does a
node started with `--start-as-non-producing-node`, which reads its key files at start. Whatever writes the process's
memory to disk can write the keys with it: a core file or a heap dump holds the whole heap, and a crash report, a
swapped-out page or a hibernation image holds whatever was in the memory it covers. (The KES key file is on disk by
design, see "Rotating the KES key" above; this section is about the copies in memory.) The node does not lock the heap
in memory, and cannot erase every copy of a key in it: the collector moves objects and leaves the earlier copies in
memory it has not reused yet, which no program can address, and locking the whole process would pin a heap of
gigabytes and the stores' native memory. [ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md) accepts that copy;
what follows keeps it off disk.

**The launcher.** The release image starts the node through `scripts/scarab-jvm` (`docker/candidate.Dockerfile`), and
the Nix network apps ([nix.md](../nix.md)) end in it: `scarab-jvm <absolute path of java> <java arguments>`. It runs in
bash's privileged mode (`bash -p`), which reads no `BASH_ENV` or `ENV` file and takes no function from the environment,
and it ends in `exec <java>`, so the JVM is the process the container or the shell started, with its signals and its
exit status. Unless diagnostics are asked for (below), it

- sets the core file limit to 0, soft and hard, whatever Docker, systemd or the shell gave it;
- removes `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` and `JDK_ALTERNATE_VM` from the JVM's environment
  and names on standard error those it removed: the JVM reads the first three before and after its command line, and
  options there (`OnError`, `HeapDumpPath`, a recording started at launch) could write memory whatever the options
  below say; the last names the `libjvm.so` that `java` loads;
- starts the JVM with these options, before the arguments it was given:

| Option | What it stops |
|---|---|
| `-XX:-CreateCoredumpOnCrash` | a core file when the JVM crashes on a fault it catches (`SIGSEGV` in a native library, for example). The JVM does not catch `SIGABRT`, from a native library's `abort()` or from another process: the core file limit of 0 stops that core, except that a `kernel.core_pattern` that pipes cores to a program gets the dump whatever the limit (see the host's part below) |
| `-XX:ErrorFile=/dev/null`, `-XX:-ErrorFileToStdout`, `-XX:-ErrorFileToStderr` | the crash report `hs_err_pid<pid>.log`: it holds the registers, the top of the stack and the contents of an array or object a register points at, so a crash while signing can write a key into it. The summary on standard output, with the signal and the frame that failed, remains. A JVM that cannot open its error file writes the report to its working directory, else to the temporary directory, else to standard output; `/dev/null` exists in every OCI container and on a Linux host |
| `-XX:-DumpReplayDataOnError` | the replay file `replay_pid<pid>.log` that a crash of a JIT compiler thread writes, with values of static fields |
| `-XX:-HeapDumpOnOutOfMemoryError` | a heap dump when the heap runs out. It is off by default too |
| `-XX:+DisableAttachMechanism` | HotSpot's attach mechanism, which its tools use: `jcmd`, `jmap` and `jstack` from another process, so a heap dump on request, a JFR recording started from outside and heap dump options set in the running JVM. It does not stop a debugger or a reader of `/proc/<pid>/mem` (below) |
| `-XX:+PerfDisableSharedMem` | the performance data file `/tmp/hsperfdata_<user>/<pid>`, which `jps` and `jstat` read; the counters stay in the JVM's memory |

`SCARAB_HEAP_SIZE` (a size such as `3g`, for `-Xmx`) and `SCARAB_RUN_PROCESSORS` (a whole number, for
`-XX:ActiveProcessorCount`) size the JVM in either mode; the Nix apps set them to `3g` and 4 unless they are given. A
value the launcher cannot read stops it with exit status 2 before the JVM starts. The node writes no heap dump or JFR
recording of its own: `--stall-dump` writes the runtime's fiber snapshot to a file and `kill -QUIT` prints a thread
dump on standard output, both stack traces rather than the contents of the heap. Those two,
`--memory-report-seconds` and the metrics need no attach.

The arguments of `scarab-jvm` are trusted: they follow its options and can override them, add an agent, a recording
or an options file. The image's fixed entry point ends with `-jar /opt/scarab/scarab.jar`, so what `docker run` adds
after the image reaches the node, not the JVM. A command that calls the launcher itself chooses the JVM's arguments,
for example the small-host collector settings of [configuration.md](configuration.md):

```sh
docker run --entrypoint /opt/scarab/scarab-jvm <image> /opt/java/openjdk/bin/java -Xms3g -Xmx3g -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 --sun-misc-unsafe-memory-access=allow -jar /opt/scarab/scarab.jar follow ...
```

A JVM started some other way, with a command of your own or an entry point that does not go through
`scripts/scarab-jvm`, gets none of this; the [tester runbook](tester-runbook.md)'s node commands go through it. The
environment is trusted too: `PATH` and the native loader's inputs (`LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT`) are
read before the launcher runs or by the programs it starts, and it cannot defend against them. Whoever sets the
container's environment controls the process.

**Upgrading** from an image that started `java` directly: the launcher drops `JAVA_TOOL_OPTIONS` and the like, with
the one line on standard error, so a node that set its heap there runs with the JVM's default heap. Replace
`JAVA_TOOL_OPTIONS=-Xmx3g` with `SCARAB_HEAP_SIZE=3g`, `-XX:ActiveProcessorCount=N` with `SCARAB_RUN_PROCESSORS=N`, and
other JVM options with the launcher's arguments, as above.

**Diagnostics** are a deliberate step. With `SCARAB_JVM_DIAGNOSTICS=1` the launcher starts the JVM as the JVM itself
would: it keeps the four variables, passes none of the options above, leaves the core file limit as it found it, and
says so on standard error (`memory diagnostics enabled: do not use on a forging node`). `0` or no value is the
default; any other value stops it. The release image's base is a JRE, with no `jcmd` in it: run `jcmd` from a JDK
image in the node's process namespace, or on the host as the node's user.

```sh
docker run --name node -e SCARAB_JVM_DIAGNOSTICS=1 -e JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary <image> follow ...
docker run --rm --pid container:node --user 10001:10001 --entrypoint jcmd scarab-jdk:local 1 VM.native_memory summary
```

With diagnostics on, any process of the node's user, and root, can make the JVM write a heap dump, and a crash writes
a report and, with a core file limit above 0, a core file. Such a file of a node that holds keys can hold the keys:
keep it as you keep the key files, never attach it to a bug report, and delete it. Profile a node that was given no
key files, such as a relay. A KES key that may have leaked is replaced by a new KES key and certificate ("Rotating the
KES key" above); a VRF key only by a new pool registration.

**What stays with the operator.** The launcher cannot set the rest: where the host puts core files, swap and
hibernation, who can read the process, and the account it runs as.

- **Core files.** `kernel.core_pattern` is the host's, for containers too. When it pipes cores to a program
  (`|/usr/lib/systemd/systemd-coredump ...`), the kernel hands the program the dump whatever the core file limit, and
  the program decides: systemd-coredump stores no core for a limit of 0, and with `Storage=none` and
  `ProcessSizeMax=0` in `coredump.conf` it stores none at all. A JVM started without the launcher, or in diagnostics,
  needs the limit where it starts: `docker run --ulimit core=0`, `ulimits: {core: 0}` in a compose file,
  `LimitCORE=0` in a systemd unit, `ulimit -c 0` in a shell.
- **Swap and hibernation.** A page of the heap can be swapped out, and stays on the swap device until it is
  overwritten; hibernation writes all of memory to disk. Run the producer with no swap, or with swap encrypted under a
  key made at each boot, and with hibernation off. `--memory-swap` equal to `--memory` keeps a container out of swap
  where the kernel accounts for swap; `MemorySwapMax=0` does it in a systemd unit, with cgroup v2's memory controller.
- **Reading the process.** A process of the node's user, or root, can read the node's whole memory through
  `/proc/<pid>/mem` or `ptrace`, attach or no attach, unless `kernel.yama.ptrace_scope` limits it: 1 allows it only
  to the node's ancestors, to a tracer the node declares (it declares none) and to root, 2 only to root
  (`CAP_SYS_PTRACE`), 3 to nobody until the next boot. The setting is the host's, and holds in containers: a
  `docker exec` into the node's container runs as its user, so at 0 it can read the node's memory, and whoever can
  use a rootful Docker daemon's socket is root on the host. Do not checkpoint a producing container
  (`docker checkpoint`): that writes its memory to disk.
- **A user of its own.** Run the node as a dedicated user that runs nothing else, and owns the key directory. The
  image runs as `10001:10001`; on a host, make an account for the node rather than sharing one.
- **Where files land.** When a crash report or a heap dump is turned on, point `-XX:ErrorFile` or
  `-XX:HeapDumpPath` at a directory only the node's user can read, on an encrypted file system, and delete the files
  once they have been read.

The [threat model](../security/threat-model.md) reviews key material in the running node under boundary B7.
