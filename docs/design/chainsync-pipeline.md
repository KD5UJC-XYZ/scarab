# Rolling ChainSync pipeline

**Status: implemented; independent review and combined acceptance passed (#310). Merge depends on
[Genesis PR #309](https://github.com/KD5UJC-XYZ/scarab/pull/309).**

## Reference contract

This replaces fixed send-and-collect rounds with the pinned node's rolling pipeline. The network
pin is `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`; see
`ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs:175–231`,
`ClientPipelined.hs:237–265`, and `cardano-diffusion/lib/Cardano/Network/NodeToNode.hs:210–211`.
The consensus pin is `82ecba329d7d054340bf707d44fe6e9ac27cec40`; see
`MiniProtocol/ChainSync/Client.hs:1255–1291,1389–1462` and its `drainThePipe` implementation.

The production defaults are low/high marks 200/300. Zero outstanding requests sends an ordinary
request when candidate and server heights are equal, otherwise a pipelined request. In the low
phase, positive outstanding work collects when the candidate height plus outstanding requests
reaches the server, collects and switches phase at the high mark, otherwise collects a completed
reply if ready or submits one more request. The high phase collects until at most the low mark
remains, then returns to optional collection. Origin addition starts at one; additions preserve
native Word64 wrapping. The policy continuation is retained across decisions, including zero reset.

## Runtime and scope ownership

A hot connection owns one bounded receiver and an ordered complete-response queue. Outstanding
credit is released only when the sender collects a final RollForward or RollBackward. AwaitReply
is intermediate and never releases request credit or makes a complete response ready.

Each submission captures an immutable request observer. Its receiver hook is a service whose
method takes the receiver's fresh `Raise[F, NetworkError]`; the receiver creates and closes that
scope and returns typed failures to the connection owner. It does not retain the sender's error
capability. In production, Genesis callbacks own total disconnect/fatal value channels and capture
no caller error capability. Header history is captured at submission, while GSM phase and current
time remain live at callback execution.

The receiver invokes AwaitReply immediately, even while the sender validates an earlier header or
waits for CSJ. It finishes that hook before starting the must-reply read/deadline. This matters:
notification can change CSJ roles and pause LoP while earlier final replies remain queued. An
ordered sender-only callback would delay that notification and is not the production contract.
Ordinary final-reply hooks stop idling and resume the relevant patience state before header work,
matching native `handleNext`; validation/publication does not clear a later receiver's Await state.

Cancellation after submission aborts the connection before reuse. A typed wire/receiver failure
retains its actual cause; later calls observe the same failure. Resource release guarantees receiver
cancellation and joining even if abort itself fails. FindIntersect and Done require zero outstanding
requests. Normal draining consumes final replies without header work or ordinary final-reply hooks;
receiver AwaitReply hooks still run. Genesis jump, restart and termination use that boundary.

FindIntersect outcomes retain the remote tip on both acceptance and rejection. A rejected CSJ jump
can resume ordinary processing when an asynchronous AwaitReply has disengaged its peer during
drain; its announced tip must therefore update the policy input even though its intersection was
rejected. An empty accepted checkpoint likewise retains that tip and obtains its local height from
the captured anchor rather than requiring a last header.

## Candidate metadata and publication

Policy heights come from the candidate header chain, including its retained anchor. They do not
come from the body application tip, a slot number, or the server's height after rollback. Offered
intersection points and their heights come from one captured fragment before FindIntersect, so a
concurrent local tip change cannot erase or substitute their metadata. Missing required metadata is
a typed local invariant failure, not a peer refusal.

Header-first-disabled sessions use an explicitly captured selected-fragment height service. The
production connection factory provides the rolling pipeline; legacy facade methods remain for
existing callers and mock profiles. The node does not silently substitute a fixed batch for a
production rolling session.

Header replies are validated and published individually. The existing bounded header journal and
body publication/fetch credits continue to control downstream work independently of ChainSync's
wire window. The read-ahead publication chunk and rolling low/high marks are separate settings.
No signed bytes, wire codecs or persisted formats change.

The existing ChainSync header counter increments once for each collected final reply, including
discarded replies during drain. AwaitReply does not increment it. Round counters, histograms and
`chainSyncRounds` log aggregates remain specific to the legacy batch facade; rolling replies are
not reported as artificial rounds.

## Acceptance evidence

- All 798 explicit observations of the unchanged native low/high function agree, including its
  opaque phase continuation, Origin, near-tip behavior and Word64 boundaries. These are explicit
  decision inputs, not an original upstream generator corpus.
- All 24 retained original upstream low/high generated cases pass through the actual observed
  rolling runtime with their original low/high inputs, complete expected intersection and producer
  chains, and bounded outstanding work. Original inputs and expected results remain unchanged.
- All 84 original Genesis runtime cases pass through rolling connections with the native simulator's
  10/20 marks, retaining every original property branch and expected fragment.
- Controlled regressions cover asynchronous AwaitReply while an earlier response is blocked, fresh receiver error scopes,
  FIFO rollback/refill, typed failures, capacity, cancellation, drain and same-connection reuse.
- Docker `check`, private relay300 and independent runtime/node review pass. Environmental
  missing-input skips remain separate from these comparisons; all retained native cases execute.

The accepted runtime is `ce3e1dd16` on main `7b1aa7001`, measured on 2026-10-21 UTC. Docker
`check` passed 8,422 tests across 14 modules, with 16 existing external-input/benchmark skips and
zero failures/errors; formatting, warning-clean compilation, standard decoder fuzzing and Scalafix
passed. All 447 Python script tests pass with the hash-pinned release dependencies. The inventory
guard verifies 93 corpora, 4,900 pinned files and 28 comparison contracts.

The private gate was:

```sh
python3 scripts/devnet.py --name scarab-310-reference-relay relay \
  --seconds 300 --every 10 --seed 310021 --consensus-mode GenesisMode \
  --no-peer-sharing --keep-data
```

All 30 samples were within one block; final tips agreed at block 284, with zero node errors or
reconnects. Inbound headers advanced from 1 to 161. The run retained four rollbacks, 253 mempool-added
and 252 adopted events, and ten load-build refusals. All scenario assertions passed. A live scrape
also verified that the rolling header counter increases while the legacy round counter stays zero.
These are bounded functional observations rather than a controlled throughput comparison.

The 13,497 frozen runtime files matched the compiled source exactly; their tree SHA-256 is
`763a62c032a435cf0c3c5aa4b46067ff9cd847201471749e211209f3648c91d6`. The actual images were
JDK `c90140def52d` and reference 11.1.3 `74e849e1b68e`. Build metadata reports the exact workspace
commit and development version. Compact receipts, public generated genesis inputs and compressed
logs are retained under `/tmp/scarab/310-rolling-integration-evidence`; disposable databases,
containers and build outputs are removed after acceptance.

One initial invocation omitted the documented private Genesis `--no-peer-sharing` flag. It remained
in PreSyncing at the native honest-availability limit and is retained as a failed setup, not an
accepted relay. Pinned `Cardano/Network/PeerSelection/Governor/Types.hs:111–117,184–203` requires
big-ledger availability for that unrestricted profile; this private fixture disables ledger discovery.
The corrected invocation uses the existing trusted-local-root contract and unchanged scenario
assertions. Fixture corrections preserve all 16 adversarial seeds, native 200/300 windows and
existing convergence/attribution assertions, supplying current selected-fragment metadata and
answering actual outstanding AwaitReply requests before reactivation.

Exact thread interleavings and identical wall-clock timing are not the contract. Observable policy
outputs, callback ordering, resource bounds and complete retained native outcomes are. Remaining
peer-metric attribution (#150), churn policy (#311) and older Praos header-first coverage are
separate from this rolling implementation and are not claimed solved here.
