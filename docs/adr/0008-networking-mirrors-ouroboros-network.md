# 0008. Networking mirrors ouroboros-network

- **Status**: Accepted
- **Date**: 2026-10-25
- **Issue**: [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477) (alignment plan)

## Context

Wire compatibility alone is not enough for the network layer: a structure of its own lets behaviour
drift from the reference where the wire does not show it. Two structural divergences show this:
[#430](https://github.com/KD5UJC-XYZ/scarab/issues/430) (offer-driven fetch logic instead of
the `fetchDecisions` loop) and [#448](https://github.com/KD5UJC-XYZ/scarab/issues/448) (two loss
reporters instead of one `peerMonitoringLoop`). The alignment plan is #477.

## Decision

The networking layer mirrors ~~ouroboros-network `2fb69829`~~
([amended 2026-11-13](#amendment-2026-11-13-the-packages-cardano-node-1113-links)) in both structure
and behaviour, as [ADR 0001](0001-haskell-node-as-reference.md) requires everywhere else. Each component cites its
reference module at that pin:

| Component | Reference module |
|---|---|
| Mux | `network-mux/src/Network/Mux.hs` |
| ConnectionManager | `ouroboros-network/framework/lib/Ouroboros/Network/ConnectionManager/Core.hs` |
| InboundGovernor | `ouroboros-network/framework/lib/Ouroboros/Network/InboundGovernor.hs` |
| Server | `ouroboros-network/framework/lib/Ouroboros/Network/Server.hs` and `Server/RateLimiting.hs` |
| PeerSelection governor | `ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor.hs` and `Governor/Monitor.hs` |
| PeerStateActions | `ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerStateActions.hs` |
| BlockFetch | `ouroboros-network/lib/Ouroboros/Network/BlockFetch.hs`, `BlockFetch/Decision.hs`, `BlockFetch/ClientState.hs` |
| ChainSync | `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/{Type,Client,Server}.hs` |

Agents may not deviate on their own accord. A deviation needs the owner's explicit approval first,
as ADR 0001 says.

## Approved deviations

~~The only~~ ([superseded](#amendment-2026-11-13-later-approved-deviations)) owner-approved network deviations (decisions of 2026-10-25, recorded in
[#474](https://github.com/KD5UJC-XYZ/scarab/issues/474)):

- **Per-address inbound limit** (row 20). Scarab keeps a limit on inbound connections per remote
  address (`maxInboundPerAddress`, default raised from 8 to 64), beyond the reference's global
  accepted-connection limits (`Server/RateLimiting.hs`). Trade-off: it protects inbound slots from
  a single address.
- **MurmurHash3 peer-share order** (row 29). The salted order of a peer-share answer is derived with
  MurmurHash3 instead of the reference's `hashWithSalt`. Trade-off: the reference's order is keyed by
  a secret random per-node salt, so peers cannot observe the hash function; mirroring it would mean
  reimplementing version- and platform-dependent `hashable` internals.
- **LocalStateQuery nesting bound** (row 35). A client's query is bounded in nesting depth
  (32,767); the reference has no bound. Trade-off: it guards the JVM against a stack overflow on a
  deeply nested query, and it applies to the local socket only.

## Consequences

- A difference from the reference in the network layer is a defect to align, tracked in #474 and #477,
  unless it is listed ~~above~~ ([superseded](#amendment-2026-11-13-later-approved-deviations)).
- Era indexing does not reach the mini-protocols: they move bytes and points; decoders at the edge
  produce era-tagged values.

## Amendment (2026-11-13): later approved deviations

The approved network deviations are those of [Approved deviations](#approved-deviations) and these, each
taken by the owner after 2026-10-25 and indexed in
[decisions.md](../decisions.md#networking-and-local-clients):

- **The local socket's bind and path edges** (#541, 2026-10-27, recorded in #474 comments of 2026-10-28:
  [bind and listen](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6458538736),
  [path edges](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6459436297)). They were
  restated on 2026-11-13 in #949 ([its record](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6864911271)
  and [its correction](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6866875724)), after the
  sources and runs of cardano-node 11.1.3 found their first wording wrong at these edges. They are two
  deviations of the node-to-client socket:
  - **Bind and listen.** The JDK binds and listens in one call (OpenJDK jdk-25+36,
    `src/java.base/share/classes/sun/nio/ch/ServerSocketChannelImpl.java` 319-323). The reference binds,
    checks the socket file, and only then listens (`ouroboros-network/lib/Ouroboros/Network/Diffusion/Utils.hs`
    121-129). So a client may queue in the backlog during that check. A listen that fails is reported as the
    bind's failure, straight after `ConfiguringLocalSocket`. Whenever the listen succeeds, the traces and
    the failure handling are the reference's.
  - **Path length.** network 3.2.8.0 binds up to 107 characters (`Network/Socket/Types.hsc` 1159-1164).
    The JDK binds at most 106 bytes of path (`src/java.base/unix/native/libnio/ch/nio_util.h` 45-49,
    `UnixDomainSockets.c` 85-86), counted after it collapses repeated `/`
    (`src/java.base/unix/classes/sun/nio/fs/UnixPath.java` 75-123). So an ASCII path of 107 characters
    with no repeated `/` is refused where the reference binds it, as a run of cardano-node 11.1.3 shows.
    From 108 characters on, both refuse ~~the path~~ an ASCII path
    ([amendment of 2026-11-14](#amendment-2026-11-14-path-names)) with `pokeSockAddr`'s error.

  Not taken for now: calling `socket`, `bind`, `listen` and `accept` through the foreign-function
  interface, which would remove both deviations.
- **A local socket path beyond ASCII** (#949, 2026-11-13, [recorded](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6866875724)):
  - **What the reference does.** Runs of cardano-node 11.1.3 with `café` paths ~~show it~~ under
    `LANG=C.UTF-8` or with no `LANG` show it ([amendment of 2026-11-14](#amendment-2026-11-14-path-names)).
    - Its bind makes the socket under the path's characters truncated to a byte each (`castCharToCChar`,
      `Types.hsc` 1160).
    - Its check of the socket at the path's own name then finds nothing.
    - It stops after `ConfiguringLocalSocket` with `<path>: getFileStatus: does not exist (No such file or
      directory)` and leaves the mis-named file.
    - Its length and trailing-`/` refusals come before all this. Runs show a 108-character path beyond
      ASCII stopping with `pokeSockAddr`'s error, and one ending in `/` failing at the bind.
  - **What Scarab does.** The owner decided that Scarab refuses every path beyond ASCII~~, an improvement on
    the reference~~ ([amendment of 2026-11-14](#amendment-2026-11-14-path-names)). After the same length and trailing-`/` checks, it stops at that step with that error
    and binds nothing.
  - **Trade-off.** Scarab never makes the mis-named file, so on a second start, and in a directory that does
    not exist, it gives that first error. The reference gives its bind's: `Address in use` from the file its
    first start left, and `does not exist`.
- **`SocketPath` in the configuration file** (#949, 2026-11-13, same record):
  - **What the reference does.** It parses `SocketPath` (`cardano-node/src/Cardano/Node/Configuration/POM.hs`
    328, cardano-node 11.1.3) and never uses it: its command line's `SocketConfig`
    (`Cardano/Node/Parsers.hs` 90-95) replaces the file's in the merge (`Cardano/Node/Run.hs` 217). So its
    socket path comes from `--socket-path` only.
  - **What Scarab does.** `SocketPath` is the default of `--local-socket`, and the flag overrides it.
  - **A path that holds NUL.** It can reach Scarab only this way. The stale-socket removal refuses it before
    calling `unlink`, which would stop at the NUL and remove another file, with the `IOError` of unix
    2.8.6.0's `removeLink` (`useAsCStringSafe`, `System/Posix/PosixPath/FilePath.hsc` 154-171). So nothing
    is removed and the start is refused.
- **`ECONNABORTED` on accept** (#944, 2026-11-13, recorded in a
  [#944 comment](https://github.com/KD5UJC-XYZ/scarab/issues/944#issuecomment-6855601945) and
  [its scope correction](https://github.com/KD5UJC-XYZ/scarab/issues/944#issuecomment-6856168855)). The JDK's native
  accept restarts on `ECONNABORTED` (OpenJDK jdk-25+36, `src/java.base/unix/native/libnio/ch/Net.c`
  417-427, which the JDK's blocking server-socket channel calls, as the node-to-node listener `TcpSockets`
  uses it). So the node-to-node server neither
  traces it (`TrAcceptError`) nor waits `server_CONNABORTED_DELAY` (500 ms) before the next accept, as the
  reference's accept loop does (`ouroboros-network/framework/lib/Ouroboros/Network/Server.hs` 81-84 and
  218-231). Every other accept failure is traced and stops the node, as the reference's does. The
  node-to-client socket's accept does not restart (`UnixDomainSockets.c` 162-169), so that server traces it
  and waits as the reference does. Trade-off: after an `ECONNABORTED` the server keeps accepting, as the
  reference's does. What is missing for the aborted attempt is that trace, that pause, and the rate-limit
  check the reference runs again before its next accept (`runConnectionRateLimits`, `Server.hs` 209-215
  and 226-228; in Scarab, `beforeAccept`). Calling `accept(2)` through the foreign-function
  interface would reproduce them; that is not taken for now.

## Amendment (2026-11-13): the packages cardano-node 11.1.3 links

The owner decided on 2026-11-13 that the network reference is what the reference build, cardano-node
11.1.3, actually links, not ouroboros-network `2fb69829`: the release merge of 2026-10-01, which is
in neither reference build
([#836](https://github.com/KD5UJC-XYZ/scarab/issues/836)). This replaces the struck-through pin of the
decision. Each package is mirrored at the revision of the version 11.1.3 links:

| Package | Version | Revision |
|---|---|---|
| `network-mux` | 0.11.0.0 | `c45735a56c567fa977969173d18943bac6bb3821` |
| `ouroboros-network`, with its `api`, `framework`, `protocols`, `tracing` and `orphan-instances` sublibraries | 1.2.0.0 | `c45735a56c567fa977969173d18943bac6bb3821` |
| `monoidal-synchronisation` | 0.1.0.8 | `c45735a56c567fa977969173d18943bac6bb3821` |
| `cardano-diffusion`, with its `api`, `protocols`, `tracing` and `orphan-instances` sublibraries | 1.1.1.0 | `c5117705f6d5f8ea57ce3e772b6f9c35bfdb9167` |

How each was established:

- **The versions** are those of the units linked into the release binary: the static
  `cardano-node` of the checksum-pinned 11.1.3 release archive (`scripts/prepare_reference_image.py`)
  records GHC's link line, whose `-lHS` entries name `network-mux-0.11.0.0`,
  `ouroboros-network-1.2.0.0`, `cardano-diffusion-1.1.1.0` and `monoidal-synchronisation-0.1.0.8`.
  cardano-node 11.1.3's `cabal.project` fixes the CHaP index at 2026-09-23T18:57:23Z, before the
  releases of 2026-10-01. `cardano-ping` (now `cardano-diffusion:ping`) and `ntp-client` are not
  linked.
- **The revisions** are those CHaP builds each version from (`_sources/<package>/<version>/meta.toml`,
  no revisions of these versions), and the upstream release tags `network-mux-0.11.0.0`,
  `ouroboros-network-1.2.0.0`, `monoidal-synchronisation-0.1.0.8` (all `c45735a5`) and
  `cardano-diffusion-1.1.1.0` (`c5117705`) agree.

`c5117705` is seven commits after `c45735a5`, and those commits also change `network-mux`
(`Network/Mux/Codec.hs`); the node's `network-mux` is still 0.11.0.0 at `c45735a5`. A citation names
the revision of the package whose file it cites.

`2fb69829` stays registered (`docs/data/reference-pins.json`) for what was made from it, and does not
describe the reference: the corpora and fixtures exported from it, whose manifests record that run,
and texts that are not rewritten (evidence logs, changelog fragments, the accepted ADRs 0017 and
0022), whose cited files are the same at the revisions above. The sources those corpora compile are
the same at the revisions above, or differ only in types or in refactoring that keeps their
behaviour, except for three:
`fixtures/peer-selection/policies` records `2fb69829`'s warm promotion and hot-demotion scores; the
property behind `fixtures/reference/network-mux-sdu` (`prop_sdu_codec`) was added after `c45735a5`,
whose encoder it uses, so the bytes are the same; and the mux lifecycle corpus was exported by running
`2fb69829`'s `network-mux`, whose `JobPool` deregisters a job only after registering it, where
`c45735a5`'s lets a job remove its entry first (`Control/Concurrent/JobPool.hs` 85-95), and the job
count decides between `Stopped` and `Failed` on a closed bearer (`Network/Mux.hs` 477-487). Whether
that corpus's observations hold at `c45735a5` is not checked. Code that follows a change made after
these revisions, or follows neither, differs from the reference, as the first consequence above says;
the places found when the reference was re-pinned, and two races of the linked packages that Scarab
does not reproduce, ~~each wait on the owner's decision in
[#956](https://github.com/KD5UJC-XYZ/scarab/issues/956)~~
([decided 2026-11-14](#amendment-2026-11-14-the-re-pins-deviations-decided)). The approved deviations
above are unchanged.

## Amendment (2026-11-14): path names

Corrections to the [amendment of 2026-11-13](#amendment-2026-11-13-later-approved-deviations), and one later
decision, indexed in [decisions.md](../decisions.md#networking-and-local-clients):

- **A local socket path beyond ASCII** (#949, [correction](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6867502879),
  [confirmation](https://github.com/KD5UJC-XYZ/scarab/issues/949#issuecomment-6874231942)).
  - **The runs.** The runs of `café` paths behind that amendment had `LANG=C.UTF-8` or no `LANG`. Under
    `LANG=C`, and with a lone byte 0xE9, cardano-node 11.1.3 kept the argument's bytes and served the path,
    where Scarab refuses it. The agreement from 108 characters on holds for ASCII paths.
  - **Why the refusal stays.** The owner confirmed it on 2026-11-14. network 3.2.8.0 truncates each
    character to a byte on connect as on bind (`Network/Socket/Types.hsc` 1097, 1141, 1158-1160). So with the
    socket bound at the UTF-8 bytes of `/w/café.socket`, `cardano-cli` 11.1.3 reached it under `LANG=C` and
    not under `LANG=C.UTF-8` or with no `LANG`: no bytes bound for such a path reach the reference's clients
    in every locale.
- **Names and the command line's encoding** (#953, 2026-11-14,
  [recorded](https://github.com/KD5UJC-XYZ/scarab/issues/953#issuecomment-6874230519)). A local socket path is
  ASCII only; any other path may be any valid UTF-8 under a UTF-8 locale and must be ASCII under any other,
  and the node refuses to start on a name it does not take: under a locale that is not UTF-8, a socket path
  beyond ASCII is refused that way, before the socket's set-up. Standard output and error are UTF-8 whatever
  the locale. The rule and its reasons are in the [decisions register](../decisions.md#networking-and-local-clients).

## Amendment (2026-11-14): the re-pin's deviations decided

The owner decided on 2026-11-14 the items of [#956](https://github.com/KD5UJC-XYZ/scarab/issues/956) that
[the amendment on the packages cardano-node 11.1.3 links](#amendment-2026-11-13-the-packages-cardano-node-1113-links)
left to that issue ([the decision](https://github.com/KD5UJC-XYZ/scarab/issues/956#issuecomment-6874322050)),
indexed in [decisions.md](../decisions.md#networking-and-local-clients). Five are approved deviations, each
following an upstream change made after the packages cardano-node 11.1.3 links; they add to those of
[Approved deviations](#approved-deviations) and of
[the amendment of 2026-11-13](#amendment-2026-11-13-later-approved-deviations). Five are bugs of the linked
packages, fixed upstream after them, which Scarab does not reproduce
([#474 comment](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849)). Each
reference citation is at the revision of its package in that amendment's table, `c45735a5`, or `c5117705` for
cardano-diffusion; a short path is in the directory of the path cited before it.

Approved deviations:

- **D1. A duplicate transaction id in one `MsgRequestTxs`.**
  - **What the reference does.** Its outbound side looks each requested id up in its window and sends
    the transaction, while it is still in the mempool, once for each time the request names it; only an id outside the window is an error,
    `ProtocolErrorRequestedUnavailableTx` (`ouroboros-network/lib/Ouroboros/Network/TxSubmission/Outbound.hs`
    171-193 at `c45735a5`).
  - **What Scarab does.** `TxSubmissionPolicy.bodies` refuses the request with `Violation.DuplicateBody`,
    and the connection ends.
  - **Decision.** Approved. It is the rule upstream added after 11.1.3: `ProtocolErrorDuplicateTxIds`,
    ouroboros-network pull request [#5435](https://github.com/IntersectMBO/ouroboros-network/pull/5435),
    in `2fb69829`, and the network specification's `MsgRequestTxs`, whose ids "MUST NOT contain
    duplicates" ([docs/network-spec/miniprotocols.tex](https://github.com/IntersectMBO/ouroboros-network/blob/2fb69829a19b5c04e4fb212bdd78fb63f9d2d776/docs/network-spec/miniprotocols.tex#L1396-L1398)
    1396-1398 at `2fb69829`). It ends no connection
    between Scarab and 11.1.3, checked in the source. The reference's inbound side builds every body
    request as a `Map` from id to size (`SendMsgRequestTxsPipelined`,
    `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/TxSubmission2/Server.hs` 81-84, filled in
    `ouroboros-network/lib/Ouroboros/Network/TxSubmission/Inbound/V1.hs` 417-424 and `Inbound/V2.hs`
    268-278), so it never names an id twice. Scarab's own requests are drawn from one reply's offers,
    which must be distinct (`TxSubmissionPolicy.offers`), so a peer with the upstream rule never ends a
    connection to Scarab for one.
- **D2. The next peer-share request to a peer whose request failed.**
  - **What the reference does.** The next request to a peer is due 900 s after the request starts,
    whatever its result: `policyPeerShareRetryTime` is set as the request starts
    (`ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor/KnownPeers.hs` 193-195 at
    `c45735a5`; the 900 s, `ouroboros-network/lib/Ouroboros/Network/Diffusion/Policies.hs` 118), and the
    completions leave it ("TODO: also update with the failures", 318 and 361).
  - **What Scarab does.** The same after an answer; after a request that fails or times out, the peer
    is due 3600 s after the completion (`Policy.peerShareFailureRetryTime`, applied in
    `PeerShare.completed`).
  - **Decision.** Approved: it follows `policyPeerShareFailureRetryTime`, ouroboros-network pull request
    [#5439](https://github.com/IntersectMBO/ouroboros-network/pull/5439), in `2fb69829`.
- **D3. Warm-to-hot promotion.**
  - **What the reference does.** It draws uniformly: `policyPickWarmPeersToPromote` is
    `simplePromotionPolicy` (`ouroboros-network/lib/Ouroboros/Network/Diffusion/Policies.hs` 109 and
    174-182 at `c45735a5`). cardano-diffusion replaces only the hot-demotion pick
    (`cardano-diffusion/lib/Cardano/Network/Diffusion/Policies.hs` 28-31 at `c5117705`).
  - **What Scarab does.** `Picks.warmPromotion` quarters the random draw of each peer that is not tepid
    (`warmPromotionWords`), so it favours peers that were not just demoted.
  - **Decision.** Approved: it follows `warmPromotionPolicy` and `promoteWeight`, ouroboros-network pull
    request [#5437](https://github.com/IntersectMBO/ouroboros-network/pull/5437), in `2fb69829`.
- **D5. Scores in the hot-demotion traces.**
  - **What the reference does.** `TraceDemoteHotPeers` and `TraceDemoteHotBigLedgerPeers` carry the
    target, the count and the peers picked, and no scores
    (`ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor/Types.hs` 1771 and 1781 at
    `c45735a5`).
  - **What Scarab does.** `Trace.Demote` and `Trace.DemoteBigLedger` also carry the score of each peer
    that could be demoted, or none where the metrics have none, and the log writes them as `scores`
    (`PeerSelectionLines`).
  - **Decision.** Approved: it follows the scores `mkHotDemotionPolicy` returns, ouroboros-network pull
    request [#5437](https://github.com/IntersectMBO/ouroboros-network/pull/5437), in `2fb69829`. The
    ranking is the same at both revisions; only the traces differ.
- **D7. Local-root DNS answers across a reconfiguration.**
  - **What the reference does.** On a reconfiguration the local-root provider starts every domain with no
    addresses (`ouroboros-network/lib/Ouroboros/Network/PeerSelection/RootPeersDNS/LocalRootPeers.hs`
    124-130 at `c45735a5`, and the loop's restart at 168; the file is the same at `2fb69829`). The groups
    it publishes when a lookup next answers hold no peer of a domain whose own lookup has not answered
    yet.
  - **What Scarab does.** `LocalRootDnsCache.reconfigure` keeps the last answer of each name the new
    configuration still has, also when the name moves to another group, and looks it up again at once.
  - **Decision.** Approved: it follows ouroboros-network pull request
    [#5455](https://github.com/IntersectMBO/ouroboros-network/pull/5455), merged after `2fb69829`, so in
    neither revision.

Bugs of the linked packages, not reproduced:

- **D4. The objective and the salt of a peer-share request.**
  - **What the reference does.** The governor calls `jobPeerShare actions policy objective salt
    numPeersToReq`, whose parameters are `salt maxAmount requestAmount`
    (`ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor/KnownPeers.hs` 200-201 and 262-267
    at `c45735a5`). So the new peers are ordered by a hash salted with the objective, and cut to a random
    `Int`, not to the objective (`takeNPeers`, 275-278): read from the source, a draw that is not positive
    keeps none of them, and a positive one keeps at most that many, which for a random `Int` is nearly
    always all of them.
  - **What Scarab does.** `PeerShare.Batch` holds the objective as its `remaining` and a fresh random
    salt, and `PeerShare.completed` orders the new peers by their hash under the salt and keeps at most
    the objective.
  - **Decision.** Not reproduced. Fixed upstream by ouroboros-network pull request
    [#5434](https://github.com/IntersectMBO/ouroboros-network/pull/5434), in `2fb69829`.
- **D6. LocalTxMonitor `GetMeasures` on node-to-client versions 16 to 19.**
  - **What the reference does.** cardano-diffusion selects the codec `LocalTxMonitor_V1` below version 20
    (`cardano-diffusion/protocols/lib/Cardano/Network/Protocol/LocalTxMonitor/Codec.hs` 41-48 at
    `c5117705`). That codec decodes `MsgGetMeasures`, but calls `error` when it encodes the reply,
    `MsgReplyGetMeasures` (`ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxMonitor/Codec.hs`
    140-141 and 102-113 at `c45735a5`). That the connection then ends is read from the source, not
    observed.
  - **What Scarab does.** `LocalTxMonitorServer` answers `GetMeasures` on every version it negotiates,
    16 to 23.
  - **Decision.** Not reproduced. Upstream drops versions 16 to 22 and uses `LocalTxMonitor_V2` for
    every version left (ouroboros-network pull request
    [#5426](https://github.com/IntersectMBO/ouroboros-network/pull/5426), in `2fb69829`).
- **N1. Responders that start before the inbound governor records their connection.**
  - **What the reference does.** The inbound governor starts a new connection's responders
    (`ouroboros-network/framework/lib/Ouroboros/Network/InboundGovernor.hs` 321-345 at `c45735a5`) before
    it writes the state that records the connection (385-393, written at 563-564). A responder that
    starts in between is not found by its mux tracer, which then empties the connection's responder
    counters (613-651). From then on no start or clean end of that connection's responders reaches the
    inbound governor, so they are not restarted and its remote state is not updated.
  - **What Scarab does.** `NodeToNodeServer.Responders.create` makes the counters before the responders
    start (`NodeToNodeServer.start`), so a responder that starts before `NodeToNodeServer.remoteActivity`
    records the connection is counted, and what it signals waits for `remoteActivity`.
  - **Decision.** Not reproduced: a race, fixed upstream by ouroboros-network pull request
    [#5440](https://github.com/IntersectMBO/ouroboros-network/pull/5440), in `2fb69829`, which starts the
    responders in the transaction that records the connection. Scarab's responders are restarted on
    demand for the connection's life; it has no counterpart of the check that pull request also adds,
    which stops restarting them once the connection is no longer recorded (`InboundGovernor.hs` 441-447
    at `2fb69829`).
- **N2. A mux job that ends before its parent records it.**
  - **What the reference does.** A job of network-mux's job pool removes its entry when it ends, and its
    parent inserts the entry after forking it (`network-mux/src/Control/Concurrent/JobPool.hs` 85-95 at
    `c45735a5`), so when a job ends first, the entry its parent then inserts stays. The mux counts the
    mini-protocol jobs to choose between `Stopped` and `Failed` when the demuxer finds the bearer closed
    (`network-mux/src/Network/Mux.hs` 477-487).
  - **What Scarab does.** `MuxJobPool.fork` removes a job's entry only once the parent has inserted it.
  - **Decision.** Not reproduced: a race, fixed upstream by ouroboros-network pull request
    [#5417](https://github.com/IntersectMBO/ouroboros-network/pull/5417), in `2fb69829`.
- **D10. The IP roots of a reconfiguration.**
  - **What the reference does.** The local-root provider writes the groups it publishes at startup
    (`ouroboros-network/lib/Ouroboros/Network/PeerSelection/RootPeersDNS/LocalRootPeers.hs` 98 at
    `c45735a5`) and then only from a domain's monitor, when its lookup answers with a change (234-246). A
    reconfiguration is detected (153-162) and restarts the loop with the new groups (168) without
    publishing them, so the new topology's IP roots wait for the next DNS answer, and a topology with no
    DNS names is not applied: read from the source, not observed.
  - **What Scarab does.** A reload publishes the configured IP roots and the kept DNS answers before it
    starts new lookups (`PeerNetwork.retainedPublicRoots` and the reload in `PeerNetwork`).
  - **Decision.** Not reproduced: fixed upstream by ouroboros-network pull request
    [#5455](https://github.com/IntersectMBO/ouroboros-network/pull/5455), the change D7 follows. Found
    after the decision and recorded in #956 under the rule above.

`fixtures/peer-selection/policies` stays as exported at `2fb69829`: it records the warm promotion of D3
and the hot-demotion scores of D5, both approved. The mux lifecycle corpus ran the job pool of
`2fb69829`, whose order Scarab keeps (N2); whether its observations hold at `c45735a5` is still not
checked.

## Amendment (2026-11-14): requests for transaction bodies

A later approved deviation, indexed in [decisions.md](../decisions.md#networking-and-local-clients):

- **A body asked for again** (owner, 2026-11-14 (session): per-advertisement form). The side that serves
  transaction bodies refuses a `MsgRequestTxs` that names an id none of whose advertisements in the
  unacknowledged window is left unasked, and ends the connection
  (`TxSubmissionPolicy.Violation.AlreadyRequested`), after the specification's rule: "It is an error to ask
  for transaction identifiers that are not outstanding or that were already asked for" (ouroboros-network
  `c45735a5`, the network specification `network-spec/miniprotocols.tex` 1392-1393). An id offered more than
  once in the window is outstanding once per advertisement: a request takes the oldest advertisement not yet
  asked for, and acknowledgement drops the advertisements with their marks. The reference's serving side
  checks only that a requested id is in the window
  (`ouroboros-network/lib/Ouroboros/Network/TxSubmission/Outbound.hs` 177-193). Its receiving sides ask for an
  id again only after it was advertised again (`Inbound/V1.hs` 365-375 and 417-424; `Inbound/V2/State.hs`
  432-437, 1040-1043 and 1446-1453), which the rule allows. The approval holds while Scarab stays conformant:
  no request of an honest peer is refused. Trade-off: the bodies served between acknowledgements are at most
  the advertisements of the window.
