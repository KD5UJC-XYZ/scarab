# Atomic restricted branch candidates

`CoherentBranch` joins original-byte acquisition, operational-certificate/KES
checks, supplied-state VRF/leader eligibility and `ClusterTransition` in one
candidate. Its profile is `conway-pv9-header11-2-supplied-epoch-one-block-v1`:
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
claim. Eligibility uses explicitly supplied nonce/stake state. Protocol
slot/counters/source digest must reconstruct the same certificate seed.

`prepare` produces a private pending candidate without publishing any stage.
The Cats Effect runtime owns one `Ref` containing acquisition, certificate state,
optional eligibility and ledger state. `publish` atomically replaces the tuple
only after all checks succeed, with composite content/revision and ledger
checkpoint/environment/head fences. `rollback` restores every component and
preserves the increased revision returned by ledger undo. Old candidates and
undo receipts stay stale across rollback; a fresh candidate can reproduce the
same content at a new revision. Pure receipts are not globally single-use across
independent runtimes. There is no persistence or crash-recovery claim.

Outcomes distinguish `Unsupported`, rejection and explicit `ScopedSuccess`.
Neither a generic `Right`, checked input nor candidate means ledger validity.
Scoped success still reports full-ledger and consensus validation false. Nonce
evolution, epoch transitions, stake derivation and full ledger rules are absent.
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


Verification for this checkpoint: 883 public Scala tests, 25 serial public gates,
95 compiler-inclusive Python guards, and 26 separate opt-in suite checks (six
public reruns and 20 retained/synthetic checks). The isolated offline build used
2 CPUs/2 GiB and private caches. Initial failed retained-positive expectations
were preserved and corrected to explicit Unsupported; no live run was performed.
