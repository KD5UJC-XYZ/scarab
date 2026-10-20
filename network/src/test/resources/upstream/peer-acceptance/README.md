# PeerSharing finite conversations and protocol byte limits

Pinned `ouroboros-network`: `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`.
The export-only observer uses the original `Fun Word8 Int` and request-list generators, the
original `direct`, `connect`, `channel ST` and `channel IO` properties, and the original
`prop_byteLimits` functions in `PeerSharing/Test.hs` and `KeepAlive/Test.hs`.
The manifest pins all source hashes, observer patch, target dependency plan, image and commands.
No independent full-project dependency-plan equivalence is claimed by this capture.

The 24 finite cases retain all 217 requests, the generated function's exact result for each
request, and the 985 collected Int items in original reverse-request order. One case has no
requests; 94 function evaluations are negative (Haskell `replicate` returns an empty list), and
three responses contain more peers than requested. Nothing is clamped or turned into fake IP
addresses. All four original upstream properties pass for every retained case.

`UpstreamPeerSharingBehaviorSuite` runs the same generic conversation functions called by the
production Address client and server over a real in-memory mux pair. Request and Done messages
use the production codec. A test Int-response interpreter supplies the upstream generic payload;
this does not claim Cardano Address wire compatibility or equivalent scheduler traces. The
production Address wrappers keep their response-count refusal, amount/byte fitting, timeout,
flush, terminal-state and mutex policies; the ordinary PeerSharing suite verifies those policies.

The two byte-limit files retain all 24 messages each, their original encoded bytes, source state,
actual encoded length, actual upstream state limit and original property result. Scarab's limits
are 5760 bytes for PeerSharing Idle/Busy and 65535 for KeepAlive Client/Server. The suite checks
those exact payload-independent ceilings and byte lengths, round-trips compatible production
messages, and retains generic Int replies through their separate test interpreter. The peer
payload distinction does not change the state limit. All 48 original byte-limit properties pass.

These are bounded samples. Mux byte/timer enforcement and adversarial fragmentation have their
own suites; this fixture adds no scheduling, full network or unlimited message-size claim.
Upstream licensing is retained in `fixtures/witness/licenses/`.
