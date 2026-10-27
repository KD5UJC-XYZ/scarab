# KES agent mode (H2, #38)

**Status**: Proposal. Designed against the pinned reference sources below; no implementation and no captured
fixtures yet. All decisions are settled: [ADR 0023](../adr/0023-two-kes-credential-sources.md) keeps both
credential paths with the file path exactly as [ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)
defines it, and [ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md) adopts the upstream agent as it is —
client only, no agent of our own, no remote signing.

## Why

A KES sign key must not exist on disk: the forward security the key-evolving scheme provides is lost if the
period-0 seed stays readable, and the file-backed mode we accepted for v0.2 writes every evolution back to
the `--kes-skey` file, so an attacker who reads that file can sign for the key's remaining periods (the
earlier ones are overwritten, but the current one is there). The upstream design moves the key into a
separate process that holds it in mlocked memory, evolves it, and hands the current credentials to the node
over a local socket. The node then holds no key file at all, and a node restart is served the current key
by the agent instead of reading one from disk.

H2 buys two things, and the design has to keep them separate:

1. **Conformance**: the node speaks the reference agent's wire protocol, so an operator can run the upstream
   `kes-agent` binary, use the upstream `kes-agent-control` for key generation and opcert installation, and
   get the same restart and recovery behaviour as `cardano-node`.
2. **No persistence on the node side**: in agent mode the node reads and writes no KES key file, and it
   drops the key it holds when the agent says the key is gone.

It does **not** buy equivalent memory protection. The JVM cannot mlock the heap; see [Security](#security-what-conformance-does-and-does-not-buy).

## Pinned reference inputs

| Source | Pin | What it defines | Licence |
|---|---|---|---|
| [`input-output-hk/kes-agent`](https://github.com/input-output-hk/kes-agent) | tag `kes-agent-1.4.0.0`, commit `44fdcec892` | the service protocol, the control protocol, the key bundle and its raw encoding | Apache-2.0 |
| [`IntersectMBO/cardano-node`](https://github.com/IntersectMBO/cardano-node) | tag `11.1.3` | the node flag, the SIGHUP behaviour, the dependency ranges | Apache-2.0 |
| [`IntersectMBO/ouroboros-consensus`](https://github.com/IntersectMBO/ouroboros-consensus) | tag `release-ouroboros-consensus-4.2.1.0` | the node-side client (`Ouroboros.Consensus.Protocol.Praos.AgentClient`), the dynamic hot key | Apache-2.0 |
| [`IntersectMBO/cardano-base`](https://github.com/IntersectMBO/cardano-base) | the pin of that consensus release (`cardano-crypto-class`) | the raw KES key image the bundle carries | Apache-2.0 |

`cardano-node.cabal` at 11.1.3 requires `kes-agent ^>=1.3` (line 170) and
`ouroboros-consensus ^>= 4.2.0.1` (line 179). **`^>=1.3` means `>=1.3 && <1.4`**, so the version 11.1.3
resolves is 1.3.0.0 (a CHaP release with no git tag), and **1.4.0.0 is outside that range**. We cite the
1.4.0.0 tag because it is the newest tagged source and its `Service:2.0` protocol and V2 driver are
byte-identical to 1.1.0.0's apart from language pragmas and lambda syntax — and identical to 1.0.0.0's
protocol outright. The wire is the contract here, not the package version: nothing in the node's client
depends on 1.4.0.0-only API. The flag is `--shelley-kes-agent-socket`
(`Cardano/Node/Parsers.hs`), and `Cardano.Node.Run.installSigHUPHandler` (line 596) →
`updateBlockForging` (line 626) re-creates the forging credentials, which is what makes a SIGHUP in agent
mode a reconnect rather than a file reload.

The node-side client is `Ouroboros.Consensus.Protocol.Praos.AgentClient.runKESAgentClient`, used by
`instantiatePraosCredentials (PraosCredentialsAgent path)` through the `HotKey` module's `mkDynamicHotKey`
(`Ouroboros/Consensus/Protocol/Praos/Common.hs`, lines 295 to 330; `.../Ledger/HotKey.hs`, lines 281 to 360).

## The reference system

Three processes, two Unix sockets:

- **`kes-agent`** holds the KES key. It serves keys on the **service socket** and takes commands on the
  **control socket**. It generates staged keys, installs them against an opcert, evolves the installed key
  as periods pass, and pushes a new bundle to every connected node when the installed key changes.
- **`cardano-node`** connects to the service socket, receives the current bundle at connect time and on
  every change, evolves the key it holds between pushes, and forges with it.
- **`kes-agent-control`** talks to the control socket: `gen-staged-key`, `install-key`, `info`,
  `drop-key`. The node never speaks this protocol.

The agent's key lifecycle matters to the node only through the service protocol: a staged key is not
served; an installed key is; a dropped key is pushed as a key deletion.

## What H2 implements, and what it does not

H2 is a **client**. The agent process is upstream `kes-agent` (Haskell, with the key in mlocked memory); we do
not write one, and this design contains no agent-side code. Writing our own would duplicate a
security-critical component, and inventing a protocol would lose the interoperability that is the point of
conformance.

**Decided 2026-10-25** ([ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md)): adopt the upstream agent as
it is, write no agent, and add no remote signing. The node's in-memory copy of the key is accepted and
documented rather than engineered away.

Other implementations have made different choices, and neither is a reference for us ([ADR 0001](../adr/0001-haskell-node-as-reference.md)
makes the Haskell node the reference):

- **Blink Labs** ship a Go replica, `bursa kes-agent`, described in its own documentation as "a Go replica of
  IOG's kes-agent, serving a Go block producer (dingo) rather than cardano-node". It speaks a JSON-framed
  protocol (`bursa-kes-agent/1`) rather than the reference's binary one, and adds a **`sign` mode** in which
  the producer sends a header body and the agent returns the signature, so "the KES signing key never leaves
  the agent". Dingo's `kesagent` package is a client for that protocol, not for the reference's.
- **Amaru** has no agent: KES signing happens inside the forging process (`amaru-ouroboros/src/kes`,
  `forging_credentials`), with the key loaded from files.

Why that matters for memory hardening: the reference's `serve-key` mode hands the key to the node, so the node
holds it to sign **whatever language the agent is written in**. A Rust agent would still push the key into the
JVM. Removing the copy requires a `sign` mode, which upstream deliberately does not have (the agent supplies
credentials, not per-block signatures), which adds a round trip per block, and which changes availability: in
`serve-key` mode a node whose agent dies keeps forging on the key it holds, while in `sign` mode it cannot
forge at all. That is a separate proposal for the threat model (O1/#74), not part of H2.

## Two credential paths, and why this design keeps both

The reference maintains two credential sources, not one, and it does not treat them as equals
(`Ouroboros/Consensus/Protocol/Praos/Common.hs`, `PraosCredentialsSource`):

| Constructor | How the key arrives | The reference's own words |
|---|---|---|
| `PraosCredentialsUnsound` | `--shelley-kes-key` with `--shelley-operational-certificate` | "does not provide mlocking guarantees, violating the rule that KES secrets must never be stored on disk, but allows the sign key to be loaded from a local file. This method is provided for backwards compatibility." |
| `PraosCredentialsAgent` | `--shelley-kes-agent-socket` | the forward-secure path |

The choice is per node and is a parser alternative (`parseKesSourceFilePath`,
`Cardano/Node/Parsers.hs`): `asum` over the two flags, file first, with no exclusion check anywhere in the
configuration path. Agent mode still requires the opcert file (`Cardano/Node/Protocol/Shelley.hs`, lines 200
to 202): the node reads it for the cold verification key and the pool identity, while the agent supplies the
live key and its own copy of the certificate. There is an explicit TODO there that the node does not check
the two certificates against each other.

So "the reference has one path" is not the case, and removing Scarab's file path would make us refuse a
configuration the reference accepts — a CLI-parity regression (#46, #222) and a break for every existing
deployment. What this design does instead is **demote** the file path: agent mode is the path a pool that
runs longer than one key should use, and file mode stays as the reference's compatibility path, deprecated by
documentation rather than removed by code. The lifecycle differences are in [Lifecycle parity](#lifecycle-parity).

**Decided 2026-10-25** in [ADR 0023](../adr/0023-two-kes-credential-sources.md): both sources stay, the file
path keeps ADR 0015's write-back and predecessor erasure exactly as it is (we do not adopt the reference's
period-0 retention, which upstream itself calls unsound), the agent path is the recommended one, and neither
path is removed by this work.

## Wire conformance

Everything below is from the pinned sources, not from a capture. Capturing real frames and pinning them as
fixtures is stage 1 of [Delivery](#delivery); until that lands, this section is a source-derived
specification and must be treated as such.

### Transport and framing

- Unix stream socket (`AF_UNIX`, `SOCK_STREAM`; `SockAddrUnix` in the reference client). No TCP, no
  datagrams. The agent accepts many service connections concurrently and runs one handler per connection.
- No length prefixes. Every message is a fixed sequence of fields read in order, so both sides must read
  exactly the expected byte counts. A short read is a protocol error, not a retry.
- Integers are big-endian (`Data.Binary` instances in `Serialization/RawUtil.hs`).
- `Maybe a` is a `Word8` (0 or 1) followed by the value when 1. A list is a `Word32` count followed by
  that many elements.
- An end of stream is a normal disconnect. There is no in-band error frame: a protocol error is signalled
  by closing the connection (the `ProtocolErrorMessage` and the three disconnect messages encode zero
  bytes).

### Version handshake (`VersionHandshake:0.1`)

1. Server → client: `Word32` count, then `count × 32` bytes. Each is a `VersionIdentifier`: ASCII,
   zero-padded to exactly 32 bytes (`Protocols/VersionedProtocol.hs`, lines 27 to 46).
2. The agent offers `[Service:2.0, Service:1.0, Service:0.0]` in that order
   (`Processes/Agent/ServiceDrivers.hs`, `availableServiceDrivers`).
3. Client → server: `Maybe VersionIdentifier` — `0x00` to reject, or `0x01` followed by 32 bytes to accept.
4. The server looks the accepted identifier up in its own list; an unknown one ends the session. The client
   in the same position logs a handshake failure and reconnects later.

### Service protocol (`Service:2.0`)

After the handshake the service protocol starts with the server sending its **own** version identifier
again (32 bytes, `Service:2.0`); the client checks it and aborts on a mismatch. Then the server pushes:

| Server → client | Bytes |
|---|---|
| `Word8` tag, `0` = key / `1` = key drop | 1 |
| `Word64` timestamp, microseconds since the Unix epoch | 8 |
| **key only**: key image, exactly `sizeSignKeyKES` bytes (608 for `Sum6KES`) | 608 |
| **key only**: `Word32` **evolution index** of the key within its own series, 0 to 63 | 4 |
| **key only**: opcert: `vkHot` 32, `n` `Word64` 8, `startPeriod` `Word64` 8, `sigma` 64 | 112 |

The index is not an absolute KES period. `cardano-crypto-class` types it as `Period` on
`SignKeyWithPeriodKES` (`type Period = Word`, encoded here as a `Word32`), and it counts evolutions from 0
for the key in hand. The **absolute** period a block is signed in is `startPeriod + index`, and that is the
number the ledger and the certificate's window speak about. The two are easy to confuse; the design uses
"index" for the wire field and "absolute period" everywhere else.

So a key message body is 724 bytes, and the frame including the tag and timestamp is 733; the client
answers every key or drop with one `Word32` `RecvResult` (table below). A drop carries only the tag and the
timestamp.

`RecvResult` codes (`Serialization/RawUtil.hs`, `encodeRecvResult`):

| Value | Meaning |
|---|---|
| 0 | `RecvOK` |
| 1 | `RecvErrorKeyOutdated` — a newer or equal key is already held |
| 2 | `RecvErrorInvalidOpCert` |
| 3 | `RecvErrorNoKey` |
| 4 | `RecvErrorUnsupportedOperation` |
| anything else | decoded as `RecvErrorUnknown` |

The timestamp is a conflict resolver between agents and between pushes, not a wall clock the node uses:
microseconds, monotonic **per connection**. The reference client keeps the last accepted timestamp in a
per-session variable and answers `RecvErrorKeyOutdated` for any bundle whose timestamp is less than **or
equal to** it (`Processes/ServiceClient.hs`, `runServiceClient`). The variable is per connection, so a
reconnect accepts an older bundle again.

### The key image

`SignKeyKES (Sum6KES Ed25519DSIGN Blake2b_256)` (`Protocols/StandardCrypto.hs`), the production crypto.
`cardano-crypto-class` defines `SignKeySumKES sk r1 vk0 vk1` and serialises it as
`raw(subKey) || r1 (32) || vk0 (32) || vk1 (32)`, depth 0 being the 32-byte Ed25519 seed
(`Cardano/Crypto/KES/Sum.hs`, the `data SignKeyKES (SumKES h d)` declaration and the `DirectSerialise`
instance; `Sum6KES d h = SumKES h (Sum5KES d h)`). Six levels give 608 bytes.

**This is byte-identical to the layout Scarab already implements.** `crypto/.../kes/Sum6KesSigning.scala`
documents and uses the same `subKey(d-1) || r1 || vk0 || vk1` layout, the same 608 bytes, and the same
mutable-and-erase discipline. Agent mode therefore needs no new KES mathematics: it needs a decoder that
checks the length, adopts the bytes, and sets the period from the `Word32` on the wire.

The opcert is not the ledger's CBOR group; kes-agent redefines it with the default field-by-field encoding,
which is why the four fields above are laid out plainly (`KES/OCert.hs` says so explicitly).

### What the node must not assume

- That a bundle is newer than the one it holds: it is only newer than the previous bundle **on this
  connection**, unless the node applies its own policy (ours does; see below).
- That a second key follows a drop: the agent may hold no key, and a session's current bundle can then be a
  deletion rather than a key.
- That the agent validates anything the node can see: the bundle carries the opcert's **cold signature** but
  not the cold verification key, so a node can only check it with the key from the `--opcert` envelope (or
  another out-of-band copy).
- That the socket is trustworthy. Anyone who can connect to the service socket receives the signing key.
  The agent's guide tells operators to restrict the directory; Scarab should say so too and warn when the
  socket is group- or world-accessible.

## Node behaviour the reference defines

From `HotKey.hs` and `AgentClient.hs`:

- **Start without a key.** The dynamic hot key starts in the poisoned state. The node runs, does not forge,
  and installs a key when one arrives. Startup does not wait for the agent and does not fail without it.
- **Replace, don't merge.** A received bundle replaces the state and the previous key is forgotten
  (`poisonState` → `forgetSignKeyKES`). A drop poisons the state the same way.
- **Evolve locally.** Between pushes the node evolves the key once per KES period; the key only moves
  forward, and moving past the end poisons it. The agent also evolves and pushes, so a long-lived node
  mostly receives a bundle only when it connects or when the agent installs a new key.
- **Reconnect forever.** `runKESAgentClient` loops: run one session, catch everything, wait 10 s, connect
  again. A clean EOF and an exception are the same from the node's point of view.
- **SIGHUP re-creates the credentials**, which tears down the old client and hot key and starts a new one;
  the agent serves the current key on the new connection, so the node is keyed again within one round trip.
- **Agent loss is not fatal.** A node that holds a key keeps forging until the key runs out of evolutions.
  The reference's recovery table is explicit: node restart is automatic; agent restart or host reboot in a
  single-agent setup needs a key reinstall; a backup agent self-heals.

## Lifecycle parity

Three lifecycles are in play. The middle one is ours, and it is a deliberate deviation rather than an
accident.

| | Reference file path | Scarab file path (ADR 0015) | Agent path (this design) |
|---|---|---|---|
| What is on disk | the period-0 key, for ever | the current evolution; the predecessor is overwritten | no KES material |
| Who evolves | the node, in memory only | the node, and each step is persisted before signing | the agent, with the node evolving between pushes |
| A reader of the file can sign | every period, past and future, because the seed derives the whole key | the current and future periods, not the past ones | nothing |
| Rotation | operator SIGHUP with a new key and certificate | the same, plus `scripts/pool/rotate_kes.py` | agent `gen-staged-key` / `install-key`, pushed to the node |
| Persistence failure | does not apply | typed, prevents further signing | does not apply |
| mlock | no | no | in the agent, not in the node |

The reference's file path is the weakest of the three: it keeps the seed from which the entire key derives,
which is exactly why its constructor is called `Unsound`. Ours removes the predecessor from the file when it
evolves, so what a reader can reach is the current and future periods rather than the whole key — at the cost
of a persistence protocol the reference does not have. "Removes from the file" is the claim, not "erased from
the medium": on copy-on-write, journalling or SSD media the old bytes may survive the overwrite (ADR 0015 says
the same). The agent path is the only one where the node holds nothing durable.

Two consequences follow, and they are the answer to "should the file path go":

- **Do not align the file path with the reference by deleting the write-back.** That would put the period-0
  seed back on disk for the sake of matching a constructor upstream itself calls unsound.
- **Do not remove the file path either.** The reference keeps it, our preprod producer ran on it
  (H1/H1a), and the devnet scenarios sign with files. Removal is a policy change that needs its own issue
  and an owner decision, not a side effect of the agent design.

## Design for Scarab

### Credential source

`HotKeySource` is already the seam (`node/.../forge/HotKeys.scala`): `read` returns a candidate plus an
`adopt` action, `persist` writes an evolved key back. Agent mode adds a second implementation:

- `read` returns the currently installed bundle, validated. The result becomes a small ADT
  (`Installed(candidate)` or `NoKey`) so that "no key yet" is a state, not a failure: startup must not fail
  when the agent has not been reached. The change is confined to `HotKeySource` and its two implementations.
- `persist` returns `Persisted.Unchanged`: the agent owns key evolution. The node must never write a KES
  file in this mode, and the design keeps file mode and agent mode explicit — no implicit fallback.
- `adopt` becomes the push handler: when the agent pushes a bundle the client validates it and swaps the
  forger's credentials under the same lock a SIGHUP reload takes. **The old key is forgotten under that lock
  too**: `Sum6KesSigning` is mutable-and-erase, so a `forget()` racing a sign or an evolve would leave a
  partially erased key. The swap therefore follows `HotKeys.usingKey`'s discipline — the lock covers the
  remember-and-erase, not only the pointer swap — and the client hands the key over and touches no part of it
  afterwards.

That last point is the only structural change to existing forging code: today credentials are installed at
startup and on SIGHUP; agent mode adds a third, asynchronous install path.

### New modules

| Module | Contents |
|---|---|
| `node/.../forge/kesagent/KesAgentWire.scala` | the pure codec: version identifiers, frame decode/encode, `RecvResult`, closed error ADT. No IO. |
| `node/.../forge/kesagent/KesAgentClient.scala` | one connection as a `Resource`: connect, handshake, read loop, reply with `RecvResult`, emit events. Returns `Either` at its fiber boundary (ADR 0017). |
| `node/.../forge/kesagent/KesAgentSource.scala` | `HotKeySource` implementation and the install/validate path. |
| `node/.../forge/kesagent/KesAgentEvent.scala` | typed events and their metrics, in the shape of the existing `ForgeEvent`s. |

The codec is a decoder for attacker-influenced bytes and gets the same treatment as a block decoder: a
`KesAgentFrame` ADT, bounds checked on every fixed-size field, and a fuzz target over the frame reader
(the fuzz gate already covers decoder changes).

### Validation on every bundle

The file path's checks (`ReloadRefusal`, `HotKeys`) apply, in this order, before a key is adopted:

1. **Shape**: exactly 608 bytes, index within `Sum6KesSigning.Periods` (0 to 63), opcert fields well formed.
2. **Binding**: the key image's verification key equals `ocertVkHot`. This is the check the file path performs
   as `KeyNotCertified`.
3. **Consistency**: sign a fixed probe message with the decoded key at the claimed index and verify it with
   `Sum6Kes.verify(vkHot, index, probe, signature)`. Nothing else ties the wire index to the key's actual
   evolution state — a mislabelled index would sign invalid blocks — and this catches it for one signature and
   one verification per push.
4. **Certificate signature**: verify `sigma` over `(vkHot, n, startPeriod)` under the cold verification key
   carried by the configured `--opcert` envelope, and check that the cold key is the pool this node forges for
   (the pool id is the cold key's hash; the file path reports this as `WrongPool`). No equality with the file's
   own certificate is required, so a rotation issued by the same cold key is accepted while the agent runs. This
   is exactly the check the reference's `Shelley.hs` TODO leaves out.
5. **Window and counter**: the certificate's window contains the current absolute period, and the counter is
   the chain's or one above it (`OCertRules.checkCounter`, the same code the header rules and the file reload
   use). During bootstrap the counter part is deferred — the node has no judged chain to compare with — and
   forging is blocked then anyway by the forecast bound (`noLedgerView`), so a key adopted without that check
   cannot produce a block.
6. **Progress**: the bundle must not move the credentials backwards. Compare **lexicographically: counter
   first, then absolute period** (`startPeriod + index`). A rotation is a higher counter and a fresh key at
   index 0, which is ahead on the first component and therefore accepted; a same-key push must not lower the
   absolute period; equal counter and period are allowed, because reinstalling the same key is legitimate.
   Comparing the raw index instead would refuse every rotation — the index goes back to 0 — which is the trap
   this ordering exists to avoid.

**Reply or close, per failure class.** The protocol expects one `Word32` per key or drop, so the session stays
open for everything the agent can reasonably fix:

| Failure | Reply |
|---|---|
| shape, binding, consistency, certificate signature, window, counter | `RecvErrorInvalidOpCert` (2) |
| progress / replayed or older bundle | `RecvErrorKeyOutdated` (1) |
| internal error we cannot classify | `RecvErrorUnknown` (`0xFFFF`) |

A **framing** violation is different: an unknown tag, a short body, or a bad version identifier means we can
no longer trust the byte stream, so the client closes the connection without replying and lets the reconnect
loop start again. The node never sends `RecvErrorNoKey` (3) or `RecvErrorUnsupportedOperation` (4); those
exist for the control side.

Answering `RecvOK` for a key we did not adopt would tell the agent its push succeeded, which is the failure
mode the protocol's confirmation exists to prevent.

**The progress memory does not survive a restart.** The node persists nothing in agent mode, so after a restart
there is no "in force" value and a rogue socket owner could replay an older valid bundle. The bound is the
ledger: a block signed outside the current absolute period is rejected by every peer, so the worst case is
failed forging until the next push, not a forged block — and status 5's window check already rejects a bundle
whose certificate has left its window. Recorded as a limitation rather than fixed with a state file, because
persisting key metadata would reintroduce the on-disk artifact the mode exists to remove.

**Deviation from the reference, deliberate and recorded**: the reference node trusts the agent's validation
entirely and adopts whatever it receives; steps 2 to 6 are stricter than `handleKey`. That is consistent
with the file path's documented improvement (ADR 0015 checks what the Haskell node does not) but it must be
recorded as a behavioural difference in the compatibility matrix. It also means a node whose `--opcert`
belongs to another pool will refuse every bundle — a misconfiguration that should fail loudly rather than
silently forge with a key the pool did not authorise.

### Forging without a key

`HotKeys` currently holds `Either[Fatal, PoolCredentials]` and `current` raises when it is empty, beside a
non-fatal poisoned state for a key past its window. Agent mode needs "no key yet, keep trying": the reference
starts the agent's hot key empty (`mkEmptyHotKey`, a poisoned key), so every slot that reaches the forge
state update traces `forgeStateUpdateError` (`KESKeyAlreadyPoisoned`) and never signs, and the design reuses
the poisoned state for it. A key that arrives later resumes forging without a restart. This is the one change
that touches the forging loop, and it is a level-3 change.

### CLI and configuration

- `--kes-agent-socket <path>` enables agent mode. `--kes-skey` is refused with it — the two sources are
  alternatives, as in the reference. `--vrf-skey` and `--opcert` stay required, exactly as the reference
  requires them in agent mode: the opcert envelope supplies the cold verification key and the pool identity,
  while the bundle supplies the live key and its own certificate.
- Passing both `--kes-skey` and `--kes-agent-socket` is a configuration error in Scarab. The reference has no
  exclusion check and tries the file alternative first, so we do not rely on its behaviour when both are
  given; refusing is a small, deliberate deviation.
- `--key-lock` has no effect in agent mode: there is no KES file to lock. The VRF key checks (permission
  bits, the group-access warning) still apply.
- `--kes-agent-peer-uid <uid>` pins the expected owner of the agent process, so a peer credential that does
  not match is a refusal rather than a warning; without it the node accepts its own uid or root and warns on
  anything else.
- In file mode on a public network, log a one-time `kesFileMode` warning that the signing key is on disk and
  that the agent is the reference's forward-secure path. A log line, not a refusal: the reference accepts
  this configuration, so refusing it would break parity, but an operator should not be able to run a public
  pool on the compatibility path without having been told.
- The flag name differs from the reference's `--shelley-kes-agent-socket`; the CLI parity register (#222)
  records the mapping until the `--shelley-*` names exist.

### Telemetry

Events mirror the reference client's traces, with the same information and no key material: connected,
version negotiated, key received (serial, verification key, period, timestamp), key refused (typed cause),
key dropped, disconnected (reason), reconnect scheduled, protocol error. Metrics:
`scarab_kes_agent_connected`, `scarab_kes_agent_key_period`,
`scarab_kes_agent_keys_received_total{outcome}`, `scarab_kes_agent_reconnects_total`,
`scarab_kes_agent_protocol_errors_total`. The existing KES gauges (period, remaining periods) continue to
read from the hot key, so dashboards and alert rules do not need a second source.

## Security: what conformance does and does not buy

**Bought:** the node never reads or writes a KES key file in agent mode; its copy is replaced, not
accumulated; a dropped key is forgotten and forging stops; a node restart is served the current evolution
by the agent, so the period-0 seed never has to exist on the node's disk; and every pushed bundle is checked
against the pool's cold key, which the reference does not do.

**Not bought, and not claimed:** the JVM heap is not mlocked and can be swapped, dumped or read by a process
with the same privileges; a `byte[]` may be copied by the collector, so `forget()` overwrites the copy we
hold and cannot prove no other copy exists; core dumps and hibernation are outside our control. The upstream
agent's guarantee is about its own mlocked buffers, not the node's. **Decided** ([ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md)):
accept the in-process copy and document it. An off-heap buffer with a JNI `mlock` would change
`Sum6KesSigning`'s representation and is not part of H2.

What lands with the implementation, in place of a guarantee:

- the node holds the current evolution only, replaces it on every push, and forgets it on a drop;
- the JVMs run with `-XX:+DisableAttachMechanism` (no `jmap`/`jcmd` heap dump from another process) and
  `-XX:-CreateCoredumpOnCrash`; `-XX:+HeapDumpOnOutOfMemoryError` is already off by default, so there is
  nothing to disable there;
- swap, hibernation and core dumps are **host** settings, not image settings: the operator documentation
  ships a compose/run example with `ulimit -c 0`, no swap and no hibernation, repeating the upstream guide's
  own list;
- in agent mode no KES material is on disk at all, which removes the at-rest exposure the file path has by
  design;
- the operator documentation states the limitation in one sentence rather than implying the key is protected
  in memory.

**Trust boundaries:** the service socket is a key-distribution channel. A process that can connect to it
gets the signing key, so the socket's permissions and its directory are part of the deployment, and the
design adds a start-up warning when the socket is accessible beyond the node's user. Beyond permissions, the
client checks **who is on the other end**: `jdk.net.ExtendedSocketOptions.SO_PEERCRED` gives the peer's uid and
gid on JDK 16+ (a `UnixDomainPrincipal`), and the client accepts a peer that is the node's own uid or root,
warns otherwise, and lets an operator pin the expected agent uid (`--kes-agent-peer-uid`) so a mismatch is a
refusal instead. `getOption` throws `UnsupportedOperationException` where the platform has no peer
credentials, so the check degrades to the permission warning rather than failing the connection. A socket
forwarded over SSH (which the upstream guide supports) puts the key on the wire between hosts, has no
meaningful peer credentials, and must be documented as weaker than a local socket.

## Testing

1. **Codec** (`KesAgentWireSuite`): byte-exact frames built from the field table; round-trip properties;
   truncation at every offset of the **724-byte body** and at every boundary of the **733-byte frame**
   (the tag and timestamp included), plus the client's 4-byte reply; bad version identifier; unknown tag;
   unknown `RecvResult`; a timestamp equal to the last one. Fuzz target over the frame reader.
2. **Fixtures** (a `kes-agent` directory under `fixtures/`, provenance README with source tag and commit, the
   built image id or digest, command and licence): frames captured from a real agent — the image built by the
   in-repo recipe — by a probe script under `scripts/` that speaks the node side, replayed offline in
   `check`. The fixtures are what the test path uses, so
   neither `check` nor a normal test run needs an agent, a container or a Haskell toolchain. This is the
   conformance oracle; the probe doubles as an independent implementation check of this document's byte
   table.
3. **Behaviour** (`KesAgentClientSuite`): an in-process fake agent on a Unix socket covering push, drop,
   outdated bundle, malformed frame, EOF, refusal to handshake, slow write and reconnect; the forger's
   `noKey` state; a second instance's socket; a bundle signed by another cold key is refused; a rotated
   certificate from the same cold key (a higher counter and a new key at index 0) is accepted without a
   restart; a bundle with a **mislabelled index** fails the consistency probe; a peer credential that is
   neither the node's uid nor root warns, and a pinned `--kes-agent-peer-uid` mismatch refuses.
4. **Devnet** (level 3): a new `kes-agent` scenario — there is none today; the devnet has `e2e`, `pool`,
   `relay`, `ntc`, `restart`, `kes-rotate` and `soak`, and this one is added by stage 4. It runs the upstream
   binary (provenance above), has `kes-agent-control` install a key, and lets the Scala node forge through it;
   then kills the agent (node keeps forging on the held key), restarts it (node re-served), drops the key
   (forging stops), and restarts the node while the agent stays up (served immediately). Evidence row in the
   compatibility matrix.

## Delivery

| Stage | Content | Gate |
|---|---|---|
| 1 | A `kes-agent` directory under `scripts/` — a two-stage Dockerfile (pinned tag, commit and base-image digest) plus a build script, and a dispatch workflow that publishes `ghcr.io/kd5ujc-xyz/scarab-kes-agent` with labels, SBOM, signature and LICENSE/NOTICE; the probe script; captured fixtures with provenance; a byte-level verification of this document against them | the image publishes and pulls by digest; fixtures replay in a Python test; the wire section corrected where the capture disagrees |
| 2 | `KesAgentWire` and its suite | `check` |
| 3 | `HotKeys` no-key state, `KesAgentSource`, client, CLI flag, fake-agent suite | `check` + `kes-rotate` (no regression in file mode) |
| 4 | Devnet `kes-agent` scenario, added the way `docs/playbooks/add-a-devnet-scenario.md` prescribes, and the real-agent matrix | `check` + the scenario (level 3) + evidence row |
| 5 | ADR 0023/0024 already accepted; `docs/running.md` operator section (mode recommendation, the memory limitation in plain words, where to obtain the upstream agent), compatibility matrix row, #38 closed | owner review |

Stage 1 is deliberately first: it is cheap, it removes the risk that this document is subtly wrong about
the wire, and it produces the fixture the later stages test against.

### Why not the published Linux binary

Because it does not run outside Nix. The 1.1.0.0 release's `x86_64-linux` asset is Nix-built: its
ELF interpreter is `/nix/store/cg9s562sa33k78m63njfn1rw47dp9z0i-glibc-2.40-66/lib/ld-linux-x86-64.so.2`, it
links against `libblst.so`, and its RPATHs point into `/nix/store` (glibc, a patched `libsodium-vrf`,
numactl, gcc's lib directory). Run on an ordinary Debian host it fails at the loader, before `--help`; run
through the system loader it fails on `libblst.so`. The `kes-agent-1.4.0.0` release has no assets at all. So
"pin the published binary" is not an option, and building once is the honest one — the cost is an image,
not a test-path dependency.

What the build costs: the upstream `Dockerfile` is single-stage on `haskell:9.10-bookworm`, installs
libsodium-dev, builds secp256k1 and blst from source, then `cabal install`s both executables. Expect tens of
minutes cold and a multi-gigabyte image; a two-stage variant of ours would be much smaller if that
matters. That happens once per pin, in the publishing workflow rather than on each test host — not per test
run, not in `check`, and not as a build in the nightly `e2e` matrix once the image is published.

## Publishing the test image

Yes, we publish it, and the answer to "each host builds" is no. A published image pinned by digest is one
artifact every host and agent shares; a per-host build is a Haskell toolchain on every host, thirty-odd
minutes of CPU each time, and a binary that can differ from the next host's even with `index-state` pinned —
GHC version, base image, libsodium and blst builds all vary. Build-from-source becomes the fallback for a
host that cannot pull, and the reproducibility check on the published artifact, not the normal path.

- **Where.** `ghcr.io/kd5ujc-xyz/scarab-kes-agent:<upstream tag>`, a public package beside the node image the
  release workflow already publishes to `ghcr.io/kd5ujc-xyz/scarab`. Published by dispatch rather than on
  every node release: the two upstreams move independently. Consumed by digest, never by tag alone.
- **What goes in it.** Built by the two-stage recipe from the pinned tag and commit; labelled with
  `org.opencontainers.image.{source,revision,version,licences}`; SBOM attached and the manifest signed with
  the same keyless cosign flow the node image uses. The digest and that label set are what the devnet pins.
- **Licence.** Upstream is Apache-2.0 with a `NOTICE` ("Copyright INTERSECT 2024-2025"), so redistributing a
  built image is permitted. The image carries `LICENSE` and `NOTICE` under `/usr/share/doc/kes-agent/`, keeps
  the base image's own copyright files, and the package description says plainly that it is an unmodified
  build of `input-output-hk/kes-agent` at a named commit — not an official upstream image, no endorsement
  implied. We do not patch the source; if that ever changes, the description must state the modification
  (Apache-2.0 §4(b)).
- **How tests use it.** The devnet scenario pulls the digest; `KES_AGENT_IMAGE` overrides it with a local
  build. `check` still uses the committed fixtures and needs no image at all, so a runner without Docker or
  network is unaffected.
- **What it costs us.** Publishing makes us the redistributor: an upstream security fix means we rebuild and
  republish — one dispatch — and an old digest left in a config is our stale pin to notice. The node image
  already carries the same obligation.

This changes two earlier recommendations. A two-stage build is worth it after all, because a published image
is pulled by everyone and its size is paid on every pull: two binaries plus libsodium, blst and glibc rather
than GHC and a cabal store. And "no registry yet" is withdrawn: GHCR is the registry this project already
uses, and public packages are free to store and serve.

## Decisions

### Decided 2026-10-25

No decisions remain open for H2.

- **Credential sources.** [ADR 0023](../adr/0023-two-kes-credential-sources.md): both stay, the file path is
  unchanged, the agent path is preferred and documented as such.
- **Scope.** [ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md): the upstream agent as it is, the client
  only, and no remote signing.
- **Certificate validation.** Verify every pushed bundle under the cold key in the `--opcert` envelope, with the
  six checks above. Stricter than the reference's explicit TODO, and it bounds what a hijacked service socket
  can achieve.
- **Counter check.** Apply `OCertRules.checkCounter` when the node has a judged chain — a selected chain whose
  tip is at or beyond the certificate's start period — and defer only that part during bootstrap, where the
  forger is blocked by the forecast bound (`noLedgerView`) anyway, so a key adopted without the counter check
  cannot produce a block.
- **Memory hardening.** Accept and document the in-process copy, with `-XX:+DisableAttachMechanism`,
  `-XX:-CreateCoredumpOnCrash` and a host-level compose example (`ulimit -c 0`, no swap, no hibernation); no
  off-heap/JNI key representation in H2.
- **Agent provenance.** **Corrected 2026-10-25 after testing the artifacts**: the `kes-agent-1.4.0.0` release
  has no assets, and the `1.1.0.0` `x86_64-linux` binary is a Nix closure (a `/nix/store` ELF interpreter, a
  `libblst.so` dependency and `/nix/store` RPATHs), so it does not run on an ordinary host. The agent is
  **built once by a publishing workflow and pulled by digest**: `ghcr.io/kd5ujc-xyz/scarab-kes-agent`, beside
  the node image, Apache-2.0 obligations carried (LICENSE and NOTICE in the image, upstream commit named, no
  implied endorsement). The two-stage recipe under `scripts/` is the fallback for a host that cannot
  pull and the reproducibility check on the published artifact. The test path uses the captured fixtures, so
  no host builds the agent to run tests, and no particular host is named anywhere. We do not ship the agent
  to operators as a supported product; the documentation points at upstream's releases.
- **Operator tooling.** Upstream `kes-agent-control`; no Scarab wrapper in H2. There is no devnet scenario to
  motivate one yet, and the scenario we add in stage 4 can call the upstream tool directly.
- **Confirmations.** `--kes-agent-socket` as the flag name (reference mapping in #222); both flags together
  refused; a group- or world-accessible service socket warned about at start-up; `Service:2.0` only, which
  costs nothing — the service protocol has existed since the 1.0.0.0 release, and every cardano-node that supports an agent requires
  `kes-agent ^>=1.2`.

## Not in scope

Remote signing — decided against for now ([ADR 0024](../adr/0024-adopt-the-upstream-kes-agent.md)); the agent
signs nothing for the node, which signs locally. An agent of our own: the agent is upstream. A Scarab control
client: operators use upstream `kes-agent-control`. TCP transport, Windows, and multi-agent bootstrap for the
node; the node is a client and never a bootstrap peer.
