<!-- SPDX-License-Identifier: Apache-2.0 -->
# G1.3 as built and its acceptance run

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7c. G1.3a as built

Branch `g1/3a-ledger-peers`; the pure part of [#137](https://github.com/KD5UJC-XYZ/scarab/issues/137): new files only, nothing of the
decision function, the governor loop, `Peer` or `Main` is touched. The governor integration (G1.3b) is below.

**Placement.** `ledger/.../PoolRelays.scala`: `PoolRelay` (the reference's `StakePoolRelay`: `SingleHostAddr`, `SingleHostName`,
`MultiHostName`), `LedgerRelay` (`Address`, `Domain`, `Srv`; `Srv` is resolved by `NodeRootProvider` under D9), `Ipv4`/`Ipv6`/`RelayIp`, `RelayPort`, `PoolRelayError`
(ten cases, no strings in control flow), `PoolRelays.decode`/`encode`/`ledgerRelays`/`connectable`/`peerRelays`.
`network/.../selection/LedgerPeers.scala`: the stake maps (`StakeMap[K, R]` with the phantom kinds `LedgerKind.All`/`Big`, `StakeMaps`),
the `Kind` type class (weighting, draw scale, which map) and `object LedgerPeerStake` with `recompute`, `weighted`
(`accPoolStake`), `accumulated`/`accumulate` (`accumulateBigLedgerStake`, the quota), `draw` (`pickPeers`), `answer`,
`supply` (`getLedgerPeers`), `snapshotBeatsLedger`/`stakeMapWithSlotOverSource`, `lifetime`/`expired`/`refresh` (the worker's cache) and
the snapshot types (`LedgerPeerSnapshot.V2`/`V23`, `SnapshotPool`, `SnapshotPoint`). The relay type is a parameter `R`, as the peer address
`A` is, so the governor's tests use strings and the node uses `LedgerRelay`. `node/.../PeerSnapshot.scala`: the JSON reader of
`peerSnapshotFile` (circe is in `node`, not in `network`), with `PeerSnapshotError` (six cases).

**Facts the reading established** (the reference is the pinned set of section 1.1, plus cardano-ledger-core 1.21.0.0 rev f649f97,
cardano-ledger-binary 1.9.1.0 rev 048b78d and ouroboros-consensus 4.2.1.0 rev 82ecba3 of the pin file):

1. *Wire format of a relay* (`State/StakePool.hs:389-421`): `[0, port/null, ipv4/null, ipv6/null]`, `[1, port/null, dns_name]`, `[2, dns_name]`;
   the port is a `Word16`, a DNS name is text of at most 128 bytes (64 before protocol 9, the larger bound reads both).
2. *IPv4 is four octets in network order.* cardano-ledger-binary 1.9.1.0 writes `putWord32be` and its changelog says "Fix IPv4 encoding
   order to use network order instead of swapped"; the tree at rev f649f97 still has `putWord32le` of the numeric word (the swapped write),
   which the release corrects. The registration in `fixtures/mainnet/shelley-pool-network-block.hex` (slot 4,495,760) carries
   `4403652f85` with port 3001: 3.101.47.133, an address in the cloud range, not 133.47.101.3. The decoder reads network order.
3. *IPv6 is sixteen octets in network order* by the same reading (the four host-address words, each written little-endian,
   `Encoder.hs:569-575`), **not pinned by a real registration**: no registration with an IPv6 relay is in the repository. Open item (below).
   **Wrong, corrected in 7d (fact 8):** each four-octet word is written little-endian, so the octets are not in network order; three real registrations show it.
4. *The relays a pool offers* (`relayToLedgerRelayAccessPoint`, ouroboros-consensus `PeerSelection.hs:37-66`): with both an IPv4 and an
   IPv6 address the IPv4 one is the relay; an address without a port, or a `SingleHostAddr` without an address, is unusable; **a
   `SingleHostName` without a port is an SRV domain** as well as every `MultiHostName`; a pool without a usable relay is no ledger peer.
   The ledger's list is ordered by stake, largest first, and its relays are the pool's current relays followed by its future relays
   (`queryStakePoolRelays`, cardano-ledger-api `State/Query.hs:720-736`), with the stake of the pool distribution (`nesPd`, the *set* snapshot).
5. *SRV pools stay in the maps and resolve after selection.* The reference weighs and accumulates pools with SRV relays, and a draw can return `LedgerRelay.Srv`. D9 resolves the selected SRV domain through RootPeersDNS and returns every address for its selected target.
6. *The snapshot replaces both maps* (`stakeMapWithSlotOverSource`, `LedgerPeers.hs:419-431`): section 1.12 says "for big ledger peers"; the
   ordinary map is weighted (square root) from the snapshot's pools as well. The snapshot wins when it is at a block (not the origin), at
   or after the ledger's slot (origin = 0) and at or after `useLedgerAfterSlot` (`Always` = 0); the maps already made from a snapshot are
   kept while its slot is the cached one.
7. *The draw* (`LedgerPeers.hs:158-211`): a 64-bit number scaled to `[0, 1]`, or `[0, 0.9]` for big ledger peers, then `lookupGE`. The pool
   that crosses the 90% line is therefore drawn with only the part of its stake below 0.9 (in `LedgerPeersSuite`: pools of 64, 25 and 11 per
   cent give 71.1, 27.8 and 1.1 per cent). A number that finds no pool still uses up a draw, draws are with replacement, and the empty
   *ordinary* map ends a draw of either kind (line 169).
8. *Cache* (`LedgerPeers.hs:213-223, 270-278`): 30 s only while the ordinary map is empty **and** ledger peers are on, 1847 s otherwise;
   expired when the age is strictly more.

**Deviations from the reference, in one place.**

1. The object is `LedgerPeerStake`, not `LedgerPeers`: `node/Topology.scala` already has an enum `LedgerPeers` (the setting of
   `useLedgerAfterSlot`), and `PeerNetwork` imports the package of the governor with a wildcard, so a second `LedgerPeers` breaks it. G1.3b
   should replace the enum by `UseLedgerPeers` (same cases) and may then rename the object.
2. The square root goes through a `Double` as the reference's does (`Utils.hs:87`), converted from a 40-digit quotient: it differs from GHC's
   `fromRational` only for a stake within 1e-40 of a rounding tie. The conversion back is exact.
3. The random streams are `Rng` (SplitMix64) draws: the Word64 of a draw is the generator's `nextLong`, the relay index is `Rng.between`
   (the reference's `randomR`). Proportions are the reference's; the byte stream is not.
4. A total weight of zero gives empty maps (the reference divides by zero and fails: ouroboros-network issue 5091).
5. The reference wraps a snapshot port outside 0 to 65535 into a `Word16`; the reader refuses it. Stakes are read as exact decimals.
6. A draw returns the relays in the order drawn (the reference's list is reversed; it is made a set afterwards).

**Tests.** `PoolRelaysSuite` (19: hand-built vectors of each relay shape, ten refusals by case, the 128-byte bound in UTF-8, the mainnet
registration read back and re-encoded to the same bytes, properties: round trip, the consensus mapping, arbitrary and mutated bytes never
throw, 2,000 relays on a 256 KiB stack); `LedgerPeersSuite` (34: the quota by example including the exact-90% boundary and by property,
`recomputeRelativeStake` sums to one, draw proportions over 200,000 draws with a fixed seed against the reference's formulas for both
weightings (4.5 sigma), `prop_pick100`, `prop_pick`, determinism, the cache lifetimes and the strict comparison, the slot rule by example and
by property over all slot combinations, a 1,500-pool Zipf distribution checked against an independent count in doubles, and 100,000 pools
accumulated, weighted and drawn on a 256 KiB stack: ADR 0011); `PeerSnapshotSuite` (8: the Leios testnet file as the official version-23
example, versions 1 and 2, the relay forms, exact stakes, the refusals by case); two fuzz targets, `pool-relays` and `peer-snapshot`.
`check` runs all of them.

**Not done, and why.**

- *No real distribution of a large network is in the repository*, so the 90% quota is shown on a synthetic Zipf distribution and a 100,000
  pool one; a mainnet or Preview pool distribution fixture (from `GetPoolDistr` of a node) is for G1.3b's `LedgerPeersSource` test.
- *No Preview registration with relays*: the repository has none and the public Preview pools were not fetched. The real registration used
  is mainnet's (provenance in `fixtures/mainnet/README.md`); the devnet registers its three pools without relays (`relays: []` in the genesis),
  so the devnet cannot show a non-empty decode until a pool is registered with relays in a scenario.
- *IPv6 is read from the code, not from a registration* (fact 3): the first real IPv6 relay (a Preview or mainnet registration, or a devnet
  pool registered with `cardano-cli ... --pool-relay-ipv6`) pins it; tracked on #137. **Done in G1.3b (7d, fact 8):** the order was wrong and is fixed.

**What G1.3b must wire.**

1. `node/.../LedgerPeersSource.scala`: read the pool distribution (`nesPd`) and the registered pools' relays (current, then future) from the
   volatile chain's ledger state through a read interface; build `Vector[LedgerPool[LedgerRelay]]` in stake order from
   `PoolRelays.decode(relays)` and `PoolRelays.peerRelays`; log and skip a pool whose relays do not decode (`PoolRelayError`), do not fail.
2. A ledger-peers worker over `LedgerPeersCache`/`LedgerPeerStake.refresh`/`answer`, with the monotonic clock, `UseLedgerPeers` from the
   topology (replace the enum in `Topology.scala`, `useLedgerAfterSlot` > 0: `After`, 0: `Always`, absent or negative: `Disabled`), the
   latest slot of the ledger as `WithOrigin`, and `PeerSnapshot.read` of `peerSnapshotFile` (read once at start; as built, an unreadable file is ignored and a legacy, origin, foreign or too old one stops the node, 7d fact 7
   with the typed error); an `Rng` split from the governor's.
3. The `RootProvider`: `requestPublicRoots` answers from `answer[All]`, and `Source.Ledger`/`Source.BigLedger` for the peers; drop
   `LedgerRelay.Srv` picks (`skipped`) and resolve `Domain` at connect time; `ttl` is `LedgerPeerStake.ReRequestInterval`.
4. In `decide`: the big-ledger families, their targets, the churn steps 1 to 6 (G1.2's `Churn`), and the YoungEnough transition that lets
   ledger peers replace the bootstrap peers.
5. The level-4 row of section 7: Preview, official files, no `--peer`, past `useLedgerAfterSlot`, 30 minutes.

## 7d. G1.3b as built

Branch `g1/3b-ledger-peers-wiring`, on `main` after G1.1, G1.2 and G1.3a: [#137](https://github.com/KD5UJC-XYZ/scarab/issues/137) completed. Level 3 (it
touches the decision function, the network wiring, the topology and a decoder). Sources: the files of section 1.1; the governor files of the
copy of ouroboros-network `main` in the workspace (1.3.0.0), which agree with the pin at the lines cited (`BigLedgerPeers.hs`,
`EstablishedPeers.hs` 342-430 and 780-882, `ActivePeers.hs` 122-198 and 679-754, `Types.hs` 1228-1336, `PublicRootPeers.hs`,
`RootPeers.hs` 150-260, `Cardano/.../Monitor.hs` 474-682, `PeerSelectionActions.hs`), `cardano-node` `Run.hs` 705-760 and
`TopologyP2P.hs` 60-170 for the snapshot file, and cardano-ledger-binary 1.9.1.0 `Encoder.hs` 555-580 for the relays.

**Placement.**

| Piece | Where |
|---|---|
| `Origin.Ledger`, `Origin.BigLedger` (the design's `Source`; the code has called it `Origin` since G1.1) | `network/.../selection/Status.scala` |
| `View.big` (the sets of the big ledger peers), the real `*BigLedger*` counters, `Counters.byOrigin` | `GovernorState.scala` |
| The big ledger families, in a file of their own: `BigLedgerGuards` (`request`, `forget`, `connect`, `close`, `activate`, `deactivate`) | `BigLedgerGuards.scala`; `decide` lists them in the reference's order, nothing else of it changed but the clean-state test and the visibility of `Guard` |
| `Job.RequestBigLedger`, `Outcome.BigLedgerFound/Failed`, `PublicRoots.ledger`, six `Trace` cases, the completions | `Vocabulary.scala`, `Events.scala` |
| `LedgerPeersSource` (reads `nesPd` and the relays through `ChainRead`/`EraRead`), `LedgerPeersWorker` (the cache, one request at a time), `LedgerPeersTrace` | `node/.../LedgerPeersSource.scala` |
| `NodeRootProvider` (the roots the governor asks for) | `node/.../NodeRootProvider.scala` |
| `SnapshotUse`, `PeerSnapshotRefusal`, `PeerSnapshot.use` | `node/.../PeerSnapshot.scala` |
| `UseLedgerPeers` in the topology (the node's own enum `LedgerPeers` is gone, the name clash of 7c is resolved by keeping `LedgerPeerStake` for the object) | `Topology.scala` |
| The log event `ledgerPeers`, the gauges `scarab_peer_selection_peers{origin}`, `scarab_ledger_peers_pools{map}`, the three big targets, the ten `*BigLedger*` gauges filled | `log/`, `metrics/` |

**What the reading established** (the reference's behaviour, each of which the code follows):

1. *Big ledger peers are roots.* `rootPeersSet` is `PublicRootPeers.toSet` (ledger, big ledger and the extra peers: configured roots and bootstrap peers)
   with the local roots (`Types.hs` 1310), so a big ledger peer counts towards the root target, and the request for public roots does not
   turn a known big ledger peer into an ordinary one (`RootPeers.hs` 163-168, `fromDisjointSets`, `PublicRootPeers.hs` 154-158).
2. *The ordinary views exclude them* (`Types.hs` 1314-1316: "number of known peers excluding big ledger peers"), so `Counters.known`, `established`
   and `active` are the ordinary ones, no ordinary family sees a big ledger peer, and the two targets are independent. `View.known` and the sets
   beside it leave out `Origin.BigLedger`; `View.big` has the same names for them.
3. *The order of the families* (`Governor.hs` 695-727 and the `<>` of each module): the request and the forgetting of big ledger peers
   between the local roots and the request for public roots; in each of connect, close, activate and deactivate the big ledger family first.
4. *`enableProgressMakingActions` is `not requiresBootstrapPeers`*: it switches off the request for big ledger peers and the promotions of
   big ledger peers to warm and to hot. Their forgetting and their demotions stay on, which is how the targets of zero of a node that became too old shed them.
5. *The clean state counts every known peer* (`waitForSystemToQuiesce` tests `KnownPeers.toSet`, and `inProgressBigLedgerPeersReq`), the big
   ledger peers included, and `outboundConnectionsState` every established one.
6. *The request for roots* (`requestPublicRootPeersImpl`): in the sensitive state the bootstrap peers and nothing else; otherwise the
   ledger's answer, and the configured public roots only when the ledger gives none (`Nothing`: ledger peers off, before the slot, no pool
   with a relay). The answer's ttl is 5 s, `LedgerPeerStake.ReRequestInterval`.
7. *The peer snapshot file* (`updateLedgerPeerSnapshot`, `Run.hs` 705-760): path relative to the topology file (`TopologyP2P.hs` 109); used only
   when ledger peers are on; a file that cannot be read is traced and ignored in Praos mode (a startup error in Genesis mode); a version 1 or 2
   file ("Unsupported legacy peer snapshot version"), a snapshot at the origin, one of another network and one older than `useLedgerAfterSlot`
   stop the node at start. `PeerSnapshot.use` is that, with a typed refusal for each; 7c's "a bad file stops the node" is therefore
   corrected: an unreadable file is ignored with a `snapshotIgnored` event, as the reference does.
8. *IPv6 in a registration is four little-endian 32-bit words* (`ipv6ToBytes`, `Encoder.hs` 569-575: `putWord32le` of each word of `fromIPv6w`;
   `getHostAddress6`, `Decoder.hs` 1363-1368). **7c's fact 3 was wrong**: it read the sixteen octets in network order. Three real registrations
   (two mainnet, one Preview, the exact CBOR from the Koios API's `tx_cbor` beside the address its `pool_updates` shows) decide it: for
   `2a02:c206:2242:2138::1` the transaction holds `06c2022a 38214222 00000000 01000000`, for `2600:1700:f660:4ad0::30` it holds
   `00170026 d04a60f6 00000000 30000000`, and for the Preview pool's `2504:4523:0:a12c:6705:0:b523:7356` it holds `23450425 2ca10000 00000567 567323b5`.
   Read as network order the first is `6c2:22a:3821:4222::1`, not an address; with each word reversed it is the Contabo range the pool's
   host lives in. `Ipv6.wire`/`Ipv6.fromWire` are that form; the decoder and the encoder use them; the fixtures are
   `fixtures/mainnet/pool-relays-ipv6.json`, `fixtures/preview/pool-relays-ipv6.json` (provenance in their READMEs) and
   `PoolRelaysRegistrationSuite` decodes them. (The IPv4 order of 7c stands: `ipv4ToBytes` is `putWord32be`.)

**The flow.** `LedgerPeersWorker.request[K](count)` takes the lock, and if the cache has passed its lifetime (30 s while the ordinary map is empty and
ledger peers are on, else 1847 s) rebuilds it with `LedgerPeerStake.rebuild` from the snapshot when it is not older than the ledger's tip,
else from the ledger once the tip is at the `useLedgerAfterSlot` slot, reading `LedgerPeersSource.pools` only then; it draws with its own
`Rng`, split from the governor's seed, and answers `Picked` or `FallBackToPublicRoots`. `NodeRootProvider` resolves selected IP, hostname, and SRV relays into peer addresses and answers the governor's two requests. The governor files the answer by `Events.completed`: new peers only (not a local root,
not a root already), `Origin.Ledger` or `Origin.BigLedger`, the same exponential backoff as the public roots when nothing is new, the ttl otherwise.
`LedgerPeersSource.of` is pure over an `EraRead`: for each pool of `nesPd` (its share is the ratio of `IndividualPoolStake`), the registered relays
followed by the pending ones (`queryStakePoolRelays`, `State/Query.hs` 720-736), each decoded on its own, the pools with no usable relay dropped,
a pool whose relays do not decode skipped and reported (`poolSkipped`), stable sort by share, largest first.

**Deviations from the reference, in one place.**

1. **Resolved by #311.** Configured big-ledger targets are retained even when ledger querying is disabled, and
   all twelve churn actions run. Small-network profiles must explicitly configure the target population they want.
2. **Resolved by #311.** Both root-provider helpers retain a typed distinction between actual big-ledger
   answers and configured fallback extras. Fallback extras become ordinary public roots, count as discovery
   progress for retry/backoff, and never increase big-ledger population. The native big-ledger completion
   disables known-peer advertisement even if the fallback root's configuration allows it.
3. *The retry time of the request for big ledger peers is a wake-up* (`Skip(Some(retryAt))`); the reference's `GuardedSkip Nothing` waits for
   an unrelated event.
4. *Close and deactivate subtract the transitions in progress among the big ledger peers*; the reference subtracts those of all peers
   (`EstablishedPeers.hs` 848-849), so an ordinary demotion in flight delays a big one there.
5. *DNS root addresses are resolved by the provider.* The reference resolves in RootPeersDNS (`pickDomainAddrs`); Scarab retains all successful A/AAAA addresses for the governor. SRV priority, weight, selected-target TTL, and empty-answer TTL follow the pinned policy.
6. *An unreadable snapshot file is ignored* (Praos mode, as the reference); Genesis-mode snapshot validation is implemented in G1.5.
7. *`Source` is `Origin`; the traces* of the big ledger families are cases of their own (`ForgetBigLedger`, `PromoteBigLedger`, `DemoteBigLedger`,
   `BigLedgerRequest/Results/Failure`) rather than a flag on the ordinary ones, and are logged as the same events with `bigLedger: true`.
8. *Ledger relays are not filtered.* A pool can register a private, loopback or documentation address (`10.60.132.169`, `127.0.0.1`,
   `100.100.100.100` were drawn on Preview); the node dials what is drawn, as the reference does, and a failed connect is backed off like any other.

**Ported properties** (`BigLedgerPropertiesSuite`, on `TestControl` in the mock environment of G1.1, which now offers relays for both requests):
`prop_governor_target_known_big_ledger_peers_below` (the reference's `prop_governor_target_known_5_no_shrink_big_ledger_peers_below`),
`prop_governor_target_known_big_ledger_peers_above`, `prop_governor_target_established_big_ledger_peers` (the reference only tabulates; here
the sets nest and the generator is shown to reach the family), `prop_governor_target_established_big_ledger_peers_below`, `..._above`,
`prop_governor_target_active_big_ledger_peers_below`, `..._above`, `prop_governor_uses_ledger_peers`, and the sensitive-state properties
`prop_governor_only_bootstrap_peers_in_fallback_state` (with the big ledger peers among the known ones), `prop_governor_nofail` (invariants), and
one of this port: no request for big ledger peers is decided in the sensitive state. Mutants that drop `big.activate` or `big.deactivate` fail
`prop_governor_target_active_big_ledger_peers_below` and `..._above`. Not ported: `prop_governor_stops_using_bootstrap_peers` (its signal is
over a flag this governor does not keep; the YoungEnough forgetting is covered by "young enough again" of G1.1 and `BigLedgerSuite`),
`prop_governor_target_known_6_peershare_objective` (G1.4). `BigLedgerSuite` (17) shows each family, the completions, the sensitive state,
the clean state and ten thousand big ledger peers on a 256 KiB stack; `LedgerPeersWiringSuite` (8) the pools of a Conway state, the worker
and the roots; `ChurnLoopSuite` the twelve steps with `bigLedger`; `PeerSnapshotUseSuite` the snapshot refusals.

**Remaining work, and where it is tracked.**

- Full Genesis density, ledger-dependent fork choice and the consensus use of trusted-state output remain G3
  ([#33](https://github.com/KD5UJC-XYZ/scarab/issues/33)).
- The rename of `LedgerPeerStake` to `LedgerPeers`: not needed once the enum is gone, and the churn in the diff buys nothing.
- DNSSEC validation is not implemented or claimed. DNS root selection and TTL refresh are described in section 6.1. Registered non-public addresses are dialled as the reference does; an optional operator filtering policy remains separate from DNS and ledger decoding.
- One live Preview churn round exercised all twelve steps (section 7e); steps 11 and 12 timed out below their targets. More rounds and transitions back into the sensitive state remain nightly/long-run coverage.

## 7e. G1.3b acceptance run

The level-4 run of section 7 for G1.3: a Preview follower from the official `config.json` and `topology.json` (the same files as 7b: one
bootstrap peer, no roots, `useLedgerAfterSlot` 119231973, `peerSnapshotFile` at slot 123,624,952), no `--peer`, 31.8 minutes of wall time
(`--seconds 1860`), read-only towards the public network. Commit d05e38d8, classes built in `scarab-jdk:local` and frozen, one container
of 3 CPUs, `-Xmx3g`, `MALLOC_ARENA_MAX=2`, `--metrics-listen` and `--query-port`. The database was a copy of the format-1 Preview database
of 7b's run (immutable tip slot 125,430,904, newest snapshot at slot 125,404,831): the immutable chunks hard-linked except the newest two,
the indexes, those two chunks, the snapshot and its UTxO checkpoint copied; the original was checked unchanged afterwards (format file,
snapshot list, modification times). The copy was removed after the run. (A first, shorter trial run of an earlier build found nothing to fix in
the wiring but read IPv6 relays in the wrong order; its numbers are not used.)

**Catch-up.** The database opened (formats migrated), the snapshot was restored and replayed, and the governor started `TooOld` at +42 s
(sensitive state: targets of the bootstrap regime, active 2 and active big ledger 1, `UntrustedState`, clean at once, then
`TrustedStateWithExternalPeers` as the bootstrap peer was activated), GSM `preSyncingToSyncing` at +43 s. The snapshot file was loaded
(`ledgerPeers snapshotLoaded`, slot 123,624,952, 66 pools). At +70.8 s the GSM entered `CaughtUp`, the judgement turned `YoungEnough`, and in
the same instant the worker rebuilt its stake maps **from the ledger** (`ledgerPeers refreshed`, `source: Ledger`, 583 pools with relays, 68 of them
big ledger pools, ledger tip slot 125,624,422: the snapshot is 1.8 M slots older than the ledger, so the ledger wins) and the governor asked for
15 big ledger peers and for the ordinary roots; the fetch mode went to `Deadline` and the targets to the relay regime (root 60, known 150,
established 30, active 20, big ledger 15, 10, 5).

**What the governor then held** (`GET /connections` and `/metrics`, sampled every 70 s for 28 minutes; the table is every sample after the first
two minutes, all alike):

| | |
|---|---|
| big ledger peers known / established / active | 15 / 10 / 5 at every sample (targets 15 / 10 / 5); first met at +83.9 s; at most 15 / 10 / 5 at any event |
| ordinary peers cold / warm / hot, known, root | 15 to 21 / 4 to 10 / 20 (19 until +250 s, then 20), known 40 to 46, root 55 to 61 (the root target counts the big ledger peers) |
| known peers by origin | Bootstrap 1, Ledger 39 to 45, BigLedger 15; hot: Bootstrap 1, Ledger 19, BigLedger 5 |
| `scarab_ledger_peers_pools` | ordinary map 583, big map 68 |
| the tip | slot 125,626,249 at the last scrape, equal to the peer tip of the last `extended` event (125,626,249): 6,992 blocks applied from the snapshot's block 4,759,871 to 4,768,268 |

So the node reached the tip and held 25 hot sessions (20 ordinary, 5 big ledger) of which 24 were not the bootstrap peer and 19 + 5 were taken from
the ledger. The known set stayed at 40 to 46 against a target of 150 by the reference's rule: the request for public roots stops at the root
target (60, big ledger peers included), and only peer sharing (G1.4) fills the known target further.

**A churn round ran** (the node started in bulk sync, so the first sleep was the 900 s interval, not 3300 s): at +956.6 s steps 1 to 6 for the big
ledger peers, then 7 to 12, in the reference's order. Steps 1 to 10 met their targets (the decrease of active big ledger peers took 42.6 s, the
hot ordinary decrease 15.5 s; the others under 7 s); steps 11 and 12 timed out (80 s and 60 s): the known target of 150 cannot be met (above) and
established was 29 of 30 when the 60 s ended. The next round was set for 3,620 s later. The round decided four demotions: one hot and one warm big ledger peer, four hot and two warm ordinary peers (a fifth of the
active targets, rounded as `decrease` does), picked by the metrics and at random as in G1.2. No error, no stall; 19 `reconnecting` events (12 `unavailable: closed by peer`, 7 `the peer's chain shares no point with ours`), 432 failed connects (Transient; unreachable, private and test addresses of the registrations: `100.100.100.100:100`,
`10.60.132.169:6000` and the like, dialled as the reference dials them), 69 cold peers forgotten, 50 demotions.

**Cost.** CPU of the container (100% is one CPU), sampled every 10 s: mean 14.3% over the run, 7.5% after the first 100 s (restore, replay and catch-up
are the first minute, peak 229%); memory mean 1.44 GiB, peak 1.59 GiB. Against 7b's one hot peer (5.4% at the tip, 1.74 GiB mean) the 25 hot sessions cost
about 2 points of one CPU more at the tip, and less memory (1.44 against 1.74 GiB mean; the heap bound is the same).

**Not shown.** Demotions by the metrics at scale (one round), a second round, the sensitive state entered from `CaughtUp` (a run of days), Genesis mode,
the snapshot as the source (the ledger was always newer; `LedgerPeersWiringSuite` shows the rule), IPv6 relays on Preview (none was
drawn in this run; the decoder is pinned by the registrations of 7d).

**Other gates** (level 3, commit d05e38d8): `check` with `scalafixAll --check` green on the commit before it (the last commit adds tests and the `started`
rendering; `check` is rerun on the final commit), devnet `relay --seconds 300` passed (3 hot local roots, tips equal, 0 errors, big ledger targets zero
and no big ledger churn steps because the topology has `useLedgerAfterSlot -1`), `peercut` failed twice with the reconnect reason `diverged`
([#152](https://github.com/KD5UJC-XYZ/scarab/issues/152), the same flake G1.2 saw once in two runs; `Follower` is untouched), `governor` passed (5 churn rounds, the slow peer demoted each of the 3 times it was hot, the hot set changed).
