# Checked native components for the audited early-epoch bundle

Implemented in a separate worktree from committed main0944c9e. This is an offline diagnostic derivation of checked components, **not full native seed admission, a runtime capability, authenticated acquisition, or protocol bootstrap**. No native build or live capture was performed. All originals remain immutable and are retained by the result. No captured payload or upstream source is committed; only source pins and bounded test evidence metadata are included.

## Entry point and supported profile

`NativeEpochComponents.decode(originals, expectedPins, anchor)` takes exactly seven independently pinned original byte arrays: native projection JSON, original debug epoch, original whole UTxO, derived full epoch seed, original capture JSON, request JSON and effective Shelley genesis. Each is bounded to512KiB. Returned `Checked` retains originals/pins/component bytes, source identity, point, existing ConwayStakeSeed.Prepared, decoded parameters, pots, both count roles, ConwayNonMyopic.State and a separately bound exact Absent value. There is no runtime attach/admission method on Checked; the reused supplied-state stake projection retains its existing false admission/readiness flags.

The supported state is deliberately narrow: epoch0; exact absent reward encoding80; empty set/go snapshots and go-pool domain; empty non-myopic map/pot0; coin-only supported base/enterprise UTxO; zero account rewards/deposits, pool/DRep deposits, treasury, stored deposits, fees, donations and snapshot fees. Pool/DRep delegation targets must exist and reverse indexes must match. Relays, metadata, DRep anchors, committee state, proposals, future parameter updates, pending pools, retirements, future genesis delegations and instantaneous reward maps/transfers must be empty. Existing current genesis delegations are preserved and shape-checked; they are not pending effects. Nonempty or unsupported cases fail closed rather than being approximated.

| Derivation | Independent check |
|---|---|
| Original/source identity | Every byte array matches its externally supplied digest; request/capture requested/acquired/final points and block numbers agree; captured original hex matches original files |
| Derived full seed | Decode complete bounded CBOR; restoring its omitted UTxO subtree yields the original debug epoch semantically; all other serialized state stays equal |
| Native components | All36 component names required; hash each raw component and compare35 against decoded full-seed subtrees; ordinary UTxO component matches original whole UTxO |
| MemPack bridge | Independently decode full-seed UTxO map into ordinary coin-only UTxO and compare with original whole UTxO, not an opaque round-trip report |
| Instantaneous stake | ConwayStake.recompute traverses original outputs and must equal the native stored credential map |
| Snapshots | Reconstruct active entries, pool amounts, owner amounts, fractions, delegation counts and pool parameters; recompute mark from current accounts/instantaneous stake; set/go must be actually empty |
| Leadership | Check serialized total, exact pool domain, amounts, fractions and VRF hashes against recomputed mark distribution |
| Monetary state | Derive UTxO coin sum, decode reserves/pots, check zero obligations in this profile and exact maximum-supply conservation from independently pinned effective genesis |
| Count roles | Decode previous and current maps separately; previous must be empty for this early profile; current values are preserved from source, never inferred from block number |
| Reward state | Require the original component and full-seed subtree both be exact80. Bind Absent to component SHA256, seed SHA256, point and full source identity. No historical freeze is constructed |
| Non-myopic | Require actual empty map/pot0 components and build the existing pure ConwayNonMyopic.State |

The opaque `invariants` report is never used for acceptance. A test replaces it with null while the derivation still succeeds. Source hashes are binding identifiers, not signatures: a coherently rewritten source with newly supplied pins has a different checked identity and is not thereby authenticated.

## Native encoding details

The main decoder uses the bounded, byte-preserving Cbor parser: full consumption; depth48;100000items;512KiB input/string caps. Semantic maps/sets are separately capped at4096 and duplicates reject. Native component comparisons use structural canonical re-encoding while original bytes and their original digests remain preserved. Noncanonical alternate spellings do not authorize different semantic state.

Native NewEpochState is `[epoch, previousBlocks, currentBlocks, epochState, rewardUpdate, poolDistr, unit]`. EpochState is `[chainAccountState, ledgerState, snapshots, nonMyopic]`; LedgerState is `[certificateState, utxoState]`. UTxOState serializes its map through MemPack, unlike ordinary UTxO EncCBOR. `NativeCoinUtxoMemPack` supports the audited little-endian native profile only: TxIn raw32-byte transaction id plus little-endianWord16 index; tag0 enterprise compact output and tag2 base compact output; Ada-only compact value and canonical MSB-first7-bit Word64 varint. Credential tags differ between MemPack and CBOR and are converted explicitly. Base address payment words and reserved header bits are checked. Other constructors, assets, datum/reference scripts, noncanonical varints, overflow, truncation and trailing bytes reject. There is no generic native-memory decoder or endian autodetection.

The PState VRF multiplicity map is preserved and shape-checked, but equality with the registered pools is not enforced: the audited PV9 state has an empty index despite two registrations, and that equality is not a PV9 admission rule. Native serialized snapshots omit cached total/distribution fields; totals and distribution are recomputed, not recovered from producer memory. Serialized governance can finish a DRep pulser; it remains opaque checked source material outside the narrow empty/deposit conditions here, not proof of the producer's live cursor.

Exact primary archive/member hashes are in `reference/native-components/source-pins.json`:8archives,25members and2original helper sources. The profile draws on pinned ledger-shelley1.19.0.1, ledger-core1.21.0.0, ledger-conway1.23.0.0, ledger-babbage1.14.0.0, ledger-alonzo1.16.0.0, ledger-mary1.11.0.0, crypto-class2.5.1.0 and mempack0.2.1.0. No dependency/provider changes.

## Audited result and tests

The independently pinned source bundle remains private outside Git. The offline Scala test copied only the seven needed originals into a private build directory, not the native executable or capture infrastructure. Derived facts match: six UTxOs total90000018000000; reserves10000002000000; maximum supply100000020000000; instantaneous/mark stake45000009000000; two pool fractions1/3 and2/3; previous counts empty; current count2 for poola302280f617116e3bce108854342a3d91e7a08ad98ecdbf2b12fd110 while point blockNo is1. Reward is exact80; non-myopic map is empty and pot0. The original effective genesis has500slots; the existing parameter decoder reports the required timing profile incompatible.

15targeted tests pass:8synthetic MemPack codec tests;6component tests including five explicitly enabled audited-bundle checks;1existing stake-seed guard test. Coherent in-memory mutations update seed, debug/capture/component encodings and pins together, ensuring semantic checks—not only hash mismatches—reject changed supply, instantaneous stake, mark, previous-count role, pool/DRep delegation, pending effects, deposits and fake completed rewards. A coherent current count3 is retained as3 under a new identity, proving no blockNo1 inference or hardcoded2 result. Originals are never overwritten.

Receipt: `reference/native-components/diagnostic-receipt.json`. Exact execution commands and logs remain in private evidence outside Git; their hashes are recorded in the receipt. Scala3.3.8/Java21 in existing immutable image, network none,2CPU/2GiB/no extra swap, private copied cache/build, Xmx1200m. Initial test compilation exposed incorrect test Point constructor usage; corrected with the actual existing case-class API before15/15 passed. No host install, new dependency, native build or live cluster.

Fresh1000-slot v2 capture is still required for original protocol state and usable timing. This result cannot mint a full ledger seed, reconstruct a missing reward freeze, authorize boundary publication, or replace that capture.

## Fees field correction

External review found the original B.Pots.fees argument selected UTxOState[5] (donations). It now projects UTxOState[2] (fees) through a package-private field-only helper. The checked decoder still requires both fees and donations to be zero, so accepted audited results and admission scope are unchanged. A distinct-sentinel regression uses fees7/donations11 to catch index confusion; coherent nonzero-fees and nonzero-donations fixtures both remain rejected. Focused rerun:16/16 passed, receipt test-3.log and command-3.json in the same private evidence directory. Independent source review confirmed the correction.

## Main integration verification

The corrected source passed 122 focused Scala tests (97 app, 25 ledger) and formatting
checks, including all five opt-in audited-bundle checks, the fees7/donations11 regression,
and adjacent stake/governance/role/default CLI safeguards. All five Scala files match
the independently approved corrected candidate. The seven original inputs were mounted
read-only and rehashed before and after execution. No retained native executable or
capture infrastructure was mounted. The run used 2 CPU/2 GiB, network disabled and a
private cache; owned container cleanup was verified.

Integration log SHA256: `69d19752efe2e2ccd196e18076d03b378775ff13665ba1acc118c7f776f67ddf`.
Eight archive hashes, 25 member hashes and two original helper hashes were rechecked
read-only. Public provenance metadata retains hashes and filenames, not private
absolute paths or raw corpus bytes. This is selected diagnostic derivation only;
protocol acquisition, usable timing, joint seed admission and runtime activation remain
separate requirements.
