# KES agent mode (H2, #38)

**Status**: Proposal. Designed against the pinned reference sources below; no implementation, no captured
fixtures and no ADR yet. The file-backed mode of [ADR 0015](../adr/0015-kes-key-written-back-and-checked-reload.md)
stays the default and is unchanged. This document is the review ADR 0015 says an agent-backed persistence
boundary needs before the policy can change.

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
| [`IntersectMBO/ouroboros-consensus`](https://github.com/IntersectMBO/ouroboros-consensus) | tag `release-ouroboros-consensus-4.2.1.0` | the node-side client (`Praos.AgentClient`), the dynamic hot key | Apache-2.0 |
| [`IntersectMBO/cardano-base`](https://github.com/IntersectMBO/cardano-base) | the pin of that consensus release (`cardano-crypto-class`) | the raw KES key image the bundle carries | Apache-2.0 |

`cardano-node.cabal` at 11.1.3 requires `kes-agent ^>=1.3` (line 170) and
`ouroboros-consensus ^>= 4.2.0.1` (line 179); 1.4.0.0 is inside `^>=1.3`. The flag is
`--shelley-kes-agent-socket` (`Cardano/Node/Parsers.hs`), and `Cardano.Node.Run.installSigHUPHandler`
(line 596) → `updateBlockForging` (line 626) re-creates the forging credentials, which is what makes a
SIGHUP in agent mode a reconnect rather than a file reload.

The node-side client is `Ouroboros.Consensus.Protocol.Praos.AgentClient.runKESAgentClient`, used by
`instantiatePraosCredentials (PraosCredentialsAgent path)` through `HotKey.mkDynamicHotKey`
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
| **key only**: `Word32` KES period of the key | 4 |
| **key only**: opcert: `vkHot` 32, `n` `Word64` 8, `kesPeriod` `Word64` 8, `sigma` 64 | 112 |

A key message body is therefore 724 bytes; the client answers every key or drop with one `Word32`
`RecvResult` (table below). A drop carries only the tag and the timestamp.

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
- That the agent validates anything the node can see: the opcert's cold signature is **not** in the bundle,
  so a node cannot verify it without the cold verification key out of band.
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
  forger's credentials under the same lock a SIGHUP reload takes.

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

1. **Shape**: 608-byte image, period within `Sum6KesSigning.Periods`, opcert fields well formed. A shape
   failure is a protocol error: refuse the bundle, keep the old key, and do not answer `RecvOK`.
2. **Binding**: the key image's verification key equals `ocertVkHot`. This is the check that the file path
   performs as `KeyNotCertified`.
3. **Certificate signature**: verify `sigma` over `(vkHot, n, kesPeriod)` under the pool's cold
   verification key. This needs a `--cold-vkey` file; see the open decision below.
4. **Window and counter**: the certificate's KES window contains the current period, and the counter is the
   chain's or one above it (`OCertRules.checkCounter`, the same code the header rules and the file reload
   use). A refusal keeps the old key and reports `kesReloadRefused` with the existing typed cause.

Answering `RecvResult`: `RecvOK` only after the key is adopted; `RecvErrorInvalidOpCert` for a binding or
signature failure; `RecvErrorKeyOutdated` for a bundle at or behind the last one on this connection; and
`RecvErrorUnknown` for anything we cannot classify. Answering `RecvOK` for a key we did not adopt would tell
the agent its push succeeded, which is the failure mode the protocol's confirmation exists to prevent.

**Deviation from the reference, deliberate and recorded**: the reference node trusts the agent's validation
entirely and adopts whatever it receives; steps 2 to 4 are stricter than `handleKey`. That is consistent
with the file path's documented improvement (ADR 0015 checks what the Haskell node does not) but it must be
recorded as a behavioural difference in the compatibility matrix, and it can reject a valid bundle if the
cold key is wrong on the node — hence the trust-mode decision below.

### Forging without a key

`HotKeys` currently holds `Either[Fatal, PoolCredentials]` and `current` raises when it is empty. Agent mode
needs a third state: "no key yet, keep trying". The design adds a `noKey` pause that the forger loop reports
as `forgeSkipped` with a new reason, alongside `tipTooOld` and `notConnected`, and never signs. A key that
arrives later resumes forging without a restart. This is the one change that touches the forging loop, and
it is a level-3 change.

### CLI and configuration

- `--kes-agent-socket <path>` enables agent mode. It is mutually exclusive with `--kes-skey` and `--opcert`
  (the bundle carries the certificate); `--vrf-skey` is still required because the VRF key does not come
  from the agent.
- `--cold-vkey <file>` supplies the cold verification key for step 3 above; see the decision list.
- `--key-lock` has no effect in agent mode: there is no KES file to lock. The VRF key checks (permission
  bits, the group-access warning) still apply.
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
by the agent, so the period-0 seed never has to exist on the node's disk.

**Not bought, and not claimed:** the JVM heap is not mlocked and can be swapped, dumped or read by a process
with the same privileges; a `byte[]` may be copied by the collector, so `forget()` overwrites the copy we
hold and cannot prove no other copy exists; core dumps and hibernation are outside our control. The upstream
agent's guarantee is about its own mlocked buffers, not the node's. Options are recorded in the decision
list: an off-heap buffer plus a small JNI `mlock`/`explicit_bzero` helper, or an explicit, documented
limitation. This design does not claim parity either way.

**Trust boundaries:** the service socket is a key-distribution channel. A process that can connect to it
gets the signing key, so the socket's permissions and its directory are part of the deployment, and the
design adds a start-up warning when the socket is accessible beyond the node's user. A socket forwarded
over SSH (which the upstream guide supports) puts the key on the wire between hosts and must be documented
as weaker than a local socket.

## Testing

1. **Codec** (`KesAgentWireSuite`): byte-exact frames built from the field table; round-trip properties;
   a truncation case at every offset of a 724-byte body and at every boundary of the handshake; bad version
   identifier; unknown tag; unknown `RecvResult`; a timestamp equal to the last one. Fuzz target over the
   frame reader.
2. **Fixtures** (`fixtures/kes-agent/`, provenance README with source commit, agent version, command and
   licence): frames captured from a real `kes-agent` 1.4.0.0 by a probe script that speaks the node side
   (`scripts/kes_agent_probe.py`), replayed offline in `check`. This is the conformance oracle; the probe
   doubles as an independent implementation check of this document's byte table.
3. **Behaviour** (`KesAgentClientSuite`): an in-process fake agent on a Unix socket covering push, drop,
   outdated bundle, malformed frame, EOF, refusal to handshake, slow write and reconnect; the forger's
   `noKey` state; a second instance's socket.
4. **Devnet** (level 3): a `kes-agent` scenario on the devnet with the upstream binary, `kes-agent-control`
   installing a key, the Scala node forging through it, then: kill the agent (node keeps forging on the held
   key), restart it (node re-served), drop the key (forging stops), and a node restart while the agent stays
   up (served immediately). Evidence row in the compatibility matrix.

## Delivery

| Stage | Content | Gate |
|---|---|---|
| 1 | Probe script, captured fixtures with provenance, and a byte-level verification of this document against them | fixtures replay in a Python test; the wire section corrected where the capture disagrees |
| 2 | `KesAgentWire` and its suite | `check` |
| 3 | `HotKeys` no-key state, `KesAgentSource`, client, CLI flag, fake-agent suite | `check` + `kes-rotate` (no regression in file mode) |
| 4 | Devnet `kes-agent` scenario and the real-agent matrix | `check` + the scenario (level 3) + evidence row |
| 5 | ADR accepted, `docs/running.md` operator section, `--kes-agent-socket` documented, #38 closed | owner review |

Stage 1 is deliberately first: it is cheap, it removes the risk that this document is subtly wrong about
the wire, and it produces the fixture the later stages test against.

## Decisions for the owner

1. **Cold verification key.** Requiring `--cold-vkey` lets the node verify the opcert itself (steps 2 to 4)
   at the cost of one more file and a deviation from the reference. The alternative is to trust the agent
   exactly as the reference does and validate only the binding and the window. Recommendation: require it,
   because #38 asks for certificate validation under the existing rules, and an operator running an agent
   already has the cold key that issued the certificate.
2. **Service protocol versions.** Recommendation: `Service:2.0` only. It is the only version whose messages
   carry timestamps and key deletions; 1.0 and 0.0 report a zero timestamp, so staleness cannot be judged.
   The agent offers 2.0 first, so this costs nothing against a current agent.
3. **Memory hardening.** Off-heap plus JNI `mlock` now, or document the limitation and defer. Recommendation:
   document the limitation for this stage and decide on JNI with the threat model (O1/#74), which is where
   the claim belongs.
4. **Counter check.** The reference agent checks the counter at install time; the node does not. Scarab can
   check it when it has a tip. Recommendation: check, and keep the existing typed refusal; record the
   stricter behaviour.
5. **ADR 0023.** On acceptance, record: agent mode is a credential source that keeps no KES material on
   disk; the node validates every pushed bundle; file mode stays supported and explicit; the node makes no
   mlocked-memory claim.

## Not in scope

Remote signing (the agent signs nothing for the node; the node signs locally) and a control client for the
control socket (operators use upstream `kes-agent-control`). TCP transport, Windows, and multi-agent
bootstrap for the node are out of scope; the node is a client and never a bootstrap peer.
