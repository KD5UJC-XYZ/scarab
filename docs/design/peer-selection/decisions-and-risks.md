<!-- SPDX-License-Identifier: Apache-2.0 -->
# Open questions, owner decisions and risks

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 8. Open questions for the owner

1. **D3, the meaning of `--peer`.** One local-root group with `hot = warm = n` keeps today's behaviour. Is that
   the intent, or should a bare `--peer` list be public-root-like (so only `targetNumberOfActivePeers` of them
   are followed)? A topology with `valency` below its access points will, from G1.1, follow fewer peers than it
   does now.
2. **The ledger-state judgement (resolved in G1.5).** Genesis hooks use the reference GSM `TooOld`/`YoungEnough`
   judgement; no wall-clock age replacement is used. See section 7g.
3. **Fetch mode (resolved).** Churn receives the consensus-selected mode and applies the matching sync or deadline
   targets; it does not infer the mode from tip age.
4. **D5, the default of 20 active peers.** If the measured cost per extra header-validating session on Preview
   is high, do we ship Scarab with a lower default active target (a recorded deviation) or accept the cost?
5. **D9, SRV and DNS (implemented in #142).** Topology and ledger SRV access points use the pinned RootPeersDNS
   selection and TTL policy; implementation details and evidence are in section 6.1.
6. **Duplex outbound (implemented in #142).** Outbound roots configured as `InitiatorAndResponder` serve over
   the same negotiated bearer, with lifecycle ownership recorded in section 7g.
7. **Reload (implemented in #142).** SIGHUP reloads topology through the acknowledged owner event, retaining the
   active configuration on invalid input; generation fences discard stale completions. See section 7g.
8. **Misbehaving peers.** The reference's overview says an adversarial peer is dropped from all sets
   immediately, and the classification lives in consensus's `RethrowPolicy`/`ExitPolicy` (not read). Scarab's
   `PeerError.InvalidHeader` ends the session today. Proposal: an `InvalidHeader`, protocol violation or
   oversize message maps to *forget* (and a short ban against re-learning from peer sharing: a gap the
   reference has too), network failures to the fail-count backoff. Needs the owner's yes before G1.1 because it
   decides the `PeerError` to `Status` mapping.
9. **ADR.** One ADR at G1.1 for D2 and the module split (D10), or two (state representation; module placement)?
10. **Evidence runs.** The two level-4 Preview runs (G1.1, G1.3) are 30-minute runs against the public relay. Is
    that acceptable to run from the usual box, or should they run on the long-run machine of the owner's
    choosing (not named here)?
11. **The trusted-state output.** Resolved by the owner: G1.1 publishes the value and G1.5 (#139) exposes the typed
    trusted-state hook; the consensus consumer remains with the faithful GSM and full Genesis work in G3 (#33).
12. **No escape hatch.** Replacing the fan-out in `Main` with the governor in G1.1 leaves no `--governor off`; the
    safety net is the layer-2 simulation, `relay` and `peercut`. A flag that kept the old path for a release would be
    a dual copy in the running node, which contradicts the rule that a migrated path replaces its predecessor.
    Confirm, or ask for the flag for exactly one release.

## 8a. Owner decisions on the open questions (2026-10-17)

Answers to section 8, taken before G1.1 started. They override the proposals above where they differ.

1. `--peer` is one local-root group with `hotValency = warmValency = n` (today's behaviour). A topology's
   `hotValency` and `warmValency` are honoured.
2. The ledger-state judgement is the reference's Genesis State Machine, ported faithfully
   (`Ouroboros.Consensus.Node.GSM`, ouroboros-consensus 4.2.1.0, the release cardano-node 11.1.3 pins), with
   citations. No ad-hoc tip-age rule.
3. The fetch mode (bulk sync or deadline) is derived from the GSM, as the reference does.
4. The reference default targets are kept (relay 60/150/30/20/15/10/5); the cost per extra hot session is
   measured in the G1.1 relay run and reported.
5. and 7. SRV records, TTL refresh, topology reload on SIGHUP, and duplex outbound connections are tracked together by #142; see section 6.1 for DNS behavior and the implementation stage's connection tests.
6. The former initiator-only decision is superseded by #142's duplex outbound stage; inbound and outbound connection ownership follows the negotiated bearer lifecycle.
8. **Decided by the owner (confirmed 2026-10-17, no longer pending):** a protocol violation, an invalid header or
   an oversize message means the peer is forgotten; a network failure means backoff. The classification is one
   typed function (`FailurePolicy.classify`), so a later change touches one place.
9. One ADR covers D2 (in-progress states in the peer status) and the module placement.
10. The G1.1 evidence run is on the development machine: a Preview follower from the official topology and
    config with no `--peer`, 30 minutes, read-only towards the public network.
11. `OutboundConnectionsState` is published only as a gauge and a log line.
12. No `--governor off` flag.

## 9. Risks, in one place

- The warm/hot split touches the `network` entry points that every connection uses (section 4.5): the existing
  suites are the net, and the split lands first inside G1.1 as its own commit with no behaviour change.
- 20 hot sessions multiply header validation and memory per candidate chain (D5): measured in G1.1.
- The reference's `adjustAvg` is stubbed, and a few behaviours (the exception classification, the fetch mode,
  the judgement) live outside the sources read; each is an open question above, not a guess in the code.
- Citation drift: the pin caveat in section 1.1; G1.1's first commit re-checks the cited lines against the
  reference image's release and corrects this document.
