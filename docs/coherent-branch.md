# Atomic restricted branch candidates

`CoherentBranch` joins original-byte acquisition, operational-certificate/KES
checks, derived nonce state, supplied-stake VRF/leader eligibility and `ClusterTransition` in one
candidate. Its profile is `conway-pv9-header11-2-derived-nonce-one-block-v2`:
one original Conway block containing one transaction, fixed private testnet magic
1082026, ledger parameters PV9.0, observed header version 11.2, and one supplied
epoch. The two version numbers serve different purposes; equality is not assumed.

`BranchInput` owns nine bounded original files. `coherent-branch-input-v1` has
exact unique tab-separated format and SHA-256 fields; `BranchInput.sources`
defines the mapping. No post-state file is loaded. Hashes are checked before
parsing. The bundle retains body/witness spans and binds them exactly to the
single acquired block. Stable tip brackets, source slot/epoch, genesis timing,
parameter profile and the actual successor slot must agree. These separate
reference exports are neither authenticated nor an atomic snapshot.

The pre-state attribution hashes the seven pre-state sources, separately from
the candidate transaction/capture. CBOR supplies the UTxO projection; the JSON
UTxO export is retained attribution only, without a semantic reconciliation
claim. Nonce state is seeded once from all five required pre-protocol nonce fields
and the same certificate seed; absent previousEpochNonce remains unknown.
Eligibility uses the nonce receipt's epochNonceUsed and frozen supplied stake
fractions, epoch, active coefficient and registration context. Protocol
slot/counters/source digest must reconstruct the same certificate seed.

`prepare` produces a private pending candidate without publishing any stage.
The Cats Effect runtime owns one `Ref` containing acquisition, certificate state,
nonce state, optional eligibility and ledger state. `publish` atomically replaces the tuple
only after all checks succeed, with composite content/revision and ledger
checkpoint/environment/head fences. `rollback` restores every component and
preserves the increased revision returned by ledger undo. Old candidates and
undo receipts stay stale across rollback; a fresh candidate can reproduce the
same content at a new revision. Pure receipts are not globally single-use across
independent runtimes. There is no persistence or crash-recovery claim.

Outcomes distinguish `Unsupported`, rejection and explicit `ScopedSuccess`.
Neither a generic `Right`, checked input nor candidate means ledger validity.
Scoped success still reports full-ledger and consensus validation false.
Same-epoch nonce evolution is composed; epoch transitions, stake derivation and
full ledger rules remain outside admission.
Reference post-state may be compared externally after derivation, never used to
choose or construct the admitted state.

## Verification and retained evidence limitation

The unchanged earlier interval capture passes original inclusion, certificate
and supplied-state eligibility checks, but its whole pre-UTxO includes Byron
outputs. The new closed checkpoint profile explicitly returns `Unsupported`.
No output is filtered or rewritten by production code. Its historical receipt
keeps its original profile and is not relabelled as independent replay.

Opt-in `COHERENT_BRANCH_EVIDENCE` tests keep that negative result. Separate tests
explicitly construct a synthetic supplied state in memory by replacing unspent
Byron addresses with a dummy enterprise address and rebinding the manifest.
Those tests exercise competing publication, rollback/reapply, stale receipts,
cancellation before publication, changed source attribution and unchanged state
on eligibility/ledger rejection. They are unit tests, not reference acceptance
or full original post-state agreement. Raw private evidence stays outside Git.
A later [explicit fresh-genesis fixture](private-cluster-coherent.md) now supplies
one supported complete reference checkpoint and positive coordinator scenario;
the old capture and synthetic test attribution remain unchanged.

The applied comparison adapters now derive ledger state first and compare the
post-state only afterwards. Their narrower profiles are
`conway-pv9-cluster-derived-key-comparison-v1` and
`conway-pv9-cluster-derived-native-comparison-v1`. Default testnet captures with
Byron leftovers can therefore be Unsupported. Historical `RestrictedReplay`
is unchanged. Native diagnostics are distinct from credential-bound spending;
no new live native-spend claim is made by this integration.


Historical v1 verification: 883 public Scala tests, 25 serial public gates,
95 compiler-inclusive Python guards, and 26 separate opt-in suite checks (six
public reruns and 20 retained/synthetic checks). The isolated offline build used
2 CPUs/2 GiB and private caches. Initial failed retained-positive expectations
were preserved and corrected to explicit Unsupported; no live run was performed.


## Atomic nonce composition (v2)

Preparation orders the dependencies without an input cycle: original acquisition
and certificate checks, then the nonce transition and its original VRF proof,
eligibility under the receipt's nonce and frozen supplied stakes, then restricted
ledger preparation. Every result remains private until one Ref replacement.
Nonce transition does not require an eligibility result or post-state oracle.
The existing eligibility checker verifies the proof again initially; this is
deliberate composition using existing checked APIs.

The composite identity includes the nonce state identity. Eligibility attribution
is a domain-separated digest of its derived profile, original pre-protocol pin,
nonce context/before/after identities and admitted header hash. It is not a newly
supplied protocol export. The runtime's frozen context identity stays independent
of this candidate-specific eligibility identity. Publication fences the nonce
predecessor, certificate predecessor/successor and admitted header together.
The owned nonce receipt is undone alongside certificate and ledger receipts;
the existing ledger revision increases across rollback and stale operations reject.

The observation command parses its pinned post-protocol nonce export only after
publication, compares all five exported fields and lastSlot, and checks the full
nonce/certificate/acquisition/eligibility/ledger tuple on rollback/reapply.
Missing exported previousEpochNonce remains not compared. No post value repairs
or replaces admitted state.

This first composition checkpoint intentionally retains exactly one original
block with one transaction. It does not add a successor API, empty-block ledger
semantics, runtime-owned multi-step undo history, epoch crossing, Plutus expansion
or durable validated-state storage. A bounded sequence needs explicit rollback
capabilities: undo increases ledger revision, so a prior step's old receipt cannot
simply be reused. Rolling a successor back to its before-tip must also preserve
earlier acquisition and nonce history. Those changes require their own review.

The retained positive coherent capture has complete pre/post CBOR UTxO, nonce and
counter exports plus its actual transaction block and can exercise v2 offline.
The two-header freeze capture has no complete retained UTxO snapshot; it cannot
be combined with another run or described as real multi-block tuple agreement.
Synthetic rebound checkpoints remain explicitly synthetic. No new live cluster
is needed for this one-block composition checkpoint.


### Retained v2 composition result

Offline v2 replay of the unchanged positive capture at slots 1039→1129 in epoch 2
passed. Independent review verified all nine original inputs, four oracle pins,
exact transaction/header inclusion and recomputed all five nonce fields and
certificate counters. Candidate remained frozen; previousEpochNonce remained
unknown and not externally compared. The restricted ledger matched the complete
post UTxO and fee pot, while full tuple rollback/reapply preserved content at
revisions 0→1→2→3 and stale operations rejected.

Initial nonce ID:
`c89fe501642295caec860022bd48d6e2d5b72e6d170e141b988f9e543e93a3f2`.
Final nonce ID:
`5537202645949d4926f7b572a3b7d872668b1543e6679acebf478dd5f65f8d03`.
Fresh offline v2 receipt SHA-256:
`bbc05c165dd3b3aadd155160bb0cda2566e0236875707f24bb7c0ad4bdd3d19e`.
Unchanged historical live receipt SHA-256:
`844d506e1d17004bef5ae0a1ffe56dd66e86a8b76873b415dcf721d7d14fc89a`.

Private integration evidence is preserved at
`/home/<user>/cardano-nonce-composition-integration-20261009`. This result is real
retained one-block agreement. Synthetic altered checkpoints test failure and
atomicity mechanics separately, and are not counted as new live observations.


V2 final local validation passed 978 public Scala tests, 25 public gates and
123 compiler-inclusive Python guards. The separate opt-in suites passed 37 tests
(15 public reruns and 22 retained/synthetic cases), including successful nonce
preparation followed by eligibility rejection with no partial publication.
