# Exact active monetary reward comparison

The repeated terminal diagnostic can observe a monetary reward update while it is still
`Pulsing`. This is a read-only comparison surface, not native state import, reward admission,
event publication, restart support, or proof of native execution. Operational capture and soak
guards remain in place. No native endpoint agreement is established by source-shaped tests.

## Pinned wire shape

The source is `cardano-ledger-shelley-1.19.0.1`, whose pins are retained in
`reference/non-myopic-dynamic/source-pins.json`:

- `Shelley/RewardUpdate.hs`: SHA-256
  `606bd0b166e678d7cb6993adea6f66760aea4504dfbba09675d954f52db15317`
- `Shelley/LedgerState/PulsingReward.hs`: SHA-256
  `d351328c783e33552f73ee6cb7435fb5b08e0df9eaab28415990786e00cb3f4e`

`StrictMaybe` contains `[0, RewardSnapShot, RSLP]` for an active update. All eight snapshot
fields are compared: fees, protocol version, frozen non-myopic history, reserve allocation,
reward pot, treasury allocation, generated likelihoods, and leader rewards. All four RSLP
fields are compared: pulse size, free variables, remaining active stake, and reward answer.
Free variables include the frozen registration set, circulation, protocol version, and every
producing pool's five-field reward information, including its complete ten-field pool snapshot.
The reward answer includes both accumulated members and the most recent pulse's singleton
member reward sets. Nonproducing pools are absent from reward information but remain represented
in all-pool generated likelihoods. Zero-valued leader rewards are retained.

The bounded decoder retains original byte spans. Typed map/set duplicate detection occurs after
key conversion; alternate integer widths or byte-string chunks cannot hide duplicates. Native
VMap meaning is sorted by credential constructor (Script before Key), then hash, irrespective of
encoded map order. Key and script credentials with the same hash remain distinct. Native Float
fields accept binary16/binary32, reject binary64 and nonfinite values, and compare exact binary32
words including the sign of zero. Neither likelihood offsets nor active phases are normalized.

## Closed runtime projection

`ConwayRewardPulser.observeActive` accepts only an opaque active state. It checks the expected
traversal, pulse size, cursor bounds, chunk/revision relationship, signal timing, and recomputed
bounded accumulated prefix. It does not call completion or advance/replace the supplied state.
For a nonempty processed prefix, the most recent chunk starts at
`((processed - 1) / pulseSize) * pulseSize`; this also handles a short final chunk. Initial recent
rewards are empty. Owners, nonproducers and zero-reward credentials consume cursor positions even
when absent from reward maps. An exhausted active cursor and an initially empty active cursor
remain `Pulsing`; the next native pulse may change phase, but observation does not.

## Independent source context

Native RSLP does not serialize the security parameter, active-slot coefficient, epoch length,
maximum supply or randomness stabilization window. These are not invented as native fields.
The runtime first binds frozen inputs to its checked source globals. Terminal metadata includes
`repeatedEpoch.rewardContext`, containing those values and the checked globals, reward-globals,
genesis and source-binding identities. The production comparator requires a
`GovernanceGlobals.Checked` from the independently checked initial acquisition, compares this
metadata exactly, and checks the initial epoch and both parameter-original digests. Generation
evidence uses the checked context's epoch geometry and coefficient.

The unreleased `plutus-repeated-service-terminal-observation-v1` schema now requires this closed
metadata field. Earlier v1 artifacts lacking it are rejected. Retained observations must not be
rewritten or supplemented to manufacture new comparison evidence. Existing source/acquisition,
original component, full-point, protocol, output-map and supply-accounting checks remain.

## Local tests and limits

Generated tests cover independent native-shaped monetary values, initial/partial/exhausted
active cursors, short final chunks, empty input, same-hash credential kinds, shared recipients,
zero leaders, nonproducers, frozen registration, recent-map replacement, every native record
field, malformed lengths, duplicate keys/reward ordering keys, and raw Float semantics. A full
terminal comparison test covers an empty-but-active native-shaped endpoint, phase differences,
foreign checked globals and every source-context field.

These are synthetic source-conformance checks with fixed local bounds, not an independently
captured native differential, native/live acceptance, or an authorization to remove launch guards.
