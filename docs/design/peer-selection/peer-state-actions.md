<!-- SPDX-License-Identifier: Apache-2.0 -->
# PeerStateActions result handles and monitor

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7h. PeerStateActions result handles and monitor (#477 N-5)

`OutboundPeers` owns one monitor per established connection, supervised outside both its bearer
and hot-session resource. `ProtocolResults[F, A]` retains each application's running, returned,
errored, not-started or not-running state. The established, warm and hot bundles use the pinned
first-to-finish and last-to-finish reads. In particular, the first readable protocol is selected
before an inactive result makes that temperature retry; a successful hot return deactivates the
hot bundle, while an established or warm return closes the connection. Requested transitions and
monitor observations share a transition lock. A completed older activation cannot overwrite a
new run's result handles.

Establishment returns the actual retained connection handle or a closed error preserving its
original peer failure or foreign defect. Pre-ready defects notify the fatal sink and return a
failure, never a successful ready value. The governor retains a typed establishment diagnostic
before its existing retry-policy projection. A successful connect completion rechecks that same
identity and its current status; loss wakes consumed while Connecting cannot leave a dead handle
Warm or Hot when the completion is accepted. The retained read derives the original peer failure
from its terminal or application result, preserving the existing misbehaviour policy during this
reconciliation (PeerStateActions.hs:780–866).

Before readiness, acquisition owns the bearer, manager slot and partial installation. A failure
unwinds those resources and resets any installed handle to Cold before returning its original
cause; callback defects reach the fatal sink even if the monitor has not started. Cancellation
of the waiting caller cancels that exact acquisition. Once the ready wait returns its handle,
the independent monitor owns the connection and later caller cancellation cannot release it.

`ConnectionResources` preserves foreign teardown defects when cancellation interrupts partial
allocation, or when release follows the monitor's final Cooling transition. It guards individual
release effects in the [Cats Effect 3.7.1 Resource algebra](https://github.com/typelevel/cats-effect/blob/v3.7.1/kernel/shared/src/main/scala/cats/effect/kernel/Resource.scala),
preserving polling, cancellation and nested exit cases. Typed teardown outcomes live only for
that connection resource and are consumed once after cleanup. The resource owner sends each
original fatal cause directly; a completed protocol terminal is not reused for later teardown
failures. Owned work remains cancelable, while its outcome interpretation, fatal publication and
pre-ready failure delivery share an outer uncancelable boundary. A cancellation queued during
resource cleanup cannot discard an acquisition defect that has already occurred.

The retained connection read survives bearer release and keeps its Cold status and exit-policy
result when the same address reconnects. The governor rechecks that read and connection identity
before applying queued notifications. Requested actions capture this same handle before their
job is supervised, and completion carries its identity, so an old command cannot act on or update
a replacement at the same address. Application failures retain every protocol identifier and
typed cause through the action and governor boundaries. Only today's Mux abort API projects one
cause; every fatal cause still reaches the node's fatal sink. Deactivation timeout is a typed action
failure; close timeout returns normally with a typed timeout diagnostic, as in the reference.

The monitor/action owns graceful-drain deadlines and release. Close returns its current status
immediately for Cooling/Cold; the governor job owns the bounded wait for the retained handle to
reach Cold. Successful deactivation waits for the protocol results, without a second session join.
Protocol producers publish their
results and end the wire protocols without a competing timeout or connection release. Shared
control state and pending hot results are installed with the Hot transition before producers start;
starting a prepared bundle cannot overwrite a later Terminate. Replacing the queue adapter with the full native governor
`Monitor.connections`/`Monitor.jobs` loop remains [#477 N-6](https://github.com/KD5UJC-XYZ/scarab/issues/477).
The monitor uses today's Mux terminal cause and ConnectionManager resource-release boundary;
Mux job ownership and the complete manager state machine remain N-1 and N-2 of that plan.

Reference: ouroboros-network `2fb69829`,
`ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerStateActions.hs` 254–422 (result handles),
662–776 (monitor), 838–848 (monitor lifetime), 904–944 (retained read), 950–1104 (requested transitions),
and 1164–1174 (bundle replacement). The exit-policy fold combines delays by maximum. The node
retains its complete available `SessionEnd` result and applies ouroboros-consensus `82ecba329`,
`ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/Ouroboros/Consensus/Node/ExitPolicy.hs`
17–38: ordinary initiator completion and requested termination use 10 seconds, no intersection
uses 120, and rollback past the intersection uses 180. Logs render the retained typed reason.

The existing ChainSync producer lacks the native `ForkTooDeep` distinction and the local/remote
tip fields of `ChainSyncClientResult` (`ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/MiniProtocol/ChainSync/Client.hs`
2195–2223 at the same pin). Those producer types and their emission sites remain N-10; the monitor
neither manufactures missing context nor claims whole-client conformance. The deterministic
loss-report cancellation regression covers [#476](https://github.com/KD5UJC-XYZ/scarab/pull/476).
It does not establish the cause of [#371](https://github.com/KD5UJC-XYZ/scarab/issues/371), which stays open.

