# Reference refusals

Transactions a pinned reference node refused, each with its **complete** refusal: the hard-fork-combinator `ApplyTxErr` it sent over node-to-client LocalTxSubmission, holding
every predicate failure it accumulated, in its order (#401). `ReferenceRefusalSuite` (node module, part
of `check`) replays every case through Scarab and compares the bytes. The inputs and expected refusals come from runs of `scripts/devnet.py refusal-corpus`;
repacking the case records preserves every JSON field and all original CBOR bytes.
The data is from throwaway local devnets (their genesis and transactions signed with devnet keys);
no signing key is kept, and it carries the repository's licence.

## Layout

| path | content |
|---|---|
| `manifest.json` | `coverage`: the predicate-failure families each era must have a case for; `corpora`: each corpus directory with the sha256 of its `corpus.json` |
| `<corpus>/corpus.json` | era, reference build, protocol version, provenance (reference node version and revision, image and image id, the command, the Scarab commit of the tooling, the time), the sha256 of each genesis file and of each case file |
| `genesis/<sha256>-<name>` | the clusters' genesis files and node configurations, as `devnet.py` copied them; each file is stored once under the sha256 its corpus pins, so corpora recorded on identically configured clusters share it |
| `<corpus>/states/<sha256>.cbor` | the reference's `GetCBOR DebugNewEpochState` answer at the tip a case was judged at |
| `<corpus>/utxo/<sha256>.cbor` | the reference's `GetCBOR GetUTxOWhole` answer at that tip (UTxO-HD keeps the UTxO out of the state) |
| `<corpus>/cases/cases.jsonl` | format 2: one complete case record per line (below) |
| `<corpus>/cases/<label>.json` | format 1: one case per file; the reader retains support |

A case holds `item` (the exact LocalTxSubmission payload `[era index, #6.24(tx)]` that was sent), `tip`
(the ledger tip; `mempoolSlot` is the reference mempool's snapshot slot, the tip's successor), the state
and UTxO files it names, `reason` (the reference's reason item) with its sha256, `families` (the rule of
each failure, read from the reference's constructor tags), `expect` (the constructor names the case was
built to provoke) and `rendered` (what `cardano-cli transaction submit` printed for a second submission
of the same transaction; a diagnostic only, never compared).

The families are UTXO, UTXOW, UTXOS, PPUP, LEDGER (the ledger rule's own failures), CERTS, DELEGS, DELEG, POOL,
GOVCERT, GOV and MEMPOOL. `scripts/tests/test_refusalcorpus.py` checks that each recorded family is the
one the reference's rendering names.

## Reference builds

A corpus is recorded from one reference build, the `--reference-build` value whose node answered
(`ReferenceBuild`, #826), and `corpus.json` names it in `referenceBuild`. A corpus without the field
is the 11.1 build's: every corpus recorded before corpora named their build was recorded from
`cardano-node` 11.1.3. The tooling checks the node against the build when it records (the 11.1 line
by its version, an unstable build by its commit), and the suite and `scripts/tests/test_refusalcorpus.py`
check the recorded provenance against it again. Each corpus is replayed under its build's rules,
through `NodeSetup.fromFiles` with that build, and its states are read in that build's layout.
`manifest.json`'s coverage is what the 11.1 corpora must hold; a case of another build covers none of it.

`conway-bootstrap-attributes-leios-b004fe0c` (2026-11-10, #826) was recorded from the Leios testnet
build `leios-b004fe0c` (release rc-2026w44, cardano-node `b004fe0c`, the pinned image digest), on a
cluster of that build held in Conway (`up-dijkstra --conway-only`, protocol version 12 as on its
devnet). It holds the bootstrap-attribute transaction of the `<era>-bootstrap-attributes` corpora (below), which that build
refuses with Conway's `OutputBootAddrAttrsTooBig` (UTXO tag 10), where 11.1 refuses it with
`OutputTooBigUTxO` (tag 11). The suite also replays this case under the 11.1 rules, and each 11.1
bootstrap-attribute case from Allegra on under the Leios build's, and requires a different refusal:
the replay follows the corpus's build. The suite replays this corpus offline, not through a running
node: `follow --reference-build leios-b004fe0c` still refuses to start (#826). No Dijkstra refusal is
recorded: Scarab does not read a Dijkstra ledger state yet.

## What is compared

For a **Conway** case the suite decodes the state and UTxO with Scarab's Haskell-state codecs (a state
that does not read and write back byte for byte fails its own test), ticks it to the slot after the tip
(`MempoolLedger.tick`), and puts `item` through Scarab's own node-to-client path:
`LocalSubmission.unwrap`, `MempoolState.add` on an empty mempool, `LocalSubmission.reasonItem`. The
result must equal `reason` byte for byte: every failure, their number and their order.

For a **Shelley, Allegra, Mary, Alonzo or Babbage** case the same state is ticked and the transaction
is judged by that era's LEDGER rule (`ShelleyLedger.sts[E]`) and written by `PreConwayFailureCbor`. Scarab's mempool and
LocalTxSubmission serve Conway only, so this compares the rule and its encoding, not a node-to-client
path.

The 2026-10-26 corpus contains 105 transactions: 7 each in Shelley, Allegra and Mary,
8 in Alonzo, 28 in Babbage and 48 in Conway. Of these, 103 compare the complete refusal bytes. Two `utxos-script-fails-flag-true` cases (one in Babbage and one in Conway)
have an explicitly incomplete comparison under [#491](https://github.com/KD5UJC-XYZ/scarab/issues/491):
the suite removes only the `PlutusFailure` text to compare the remaining structure, checks that the
original refusal bytes still differ, and cancels the case. Neither is counted as a conformance pass;
the recorded original bytes are unchanged. Every other case whose bytes differ fails. A family
that `manifest.json` requires for an era and no case of that era covers fails the era's coverage test:
missing coverage is an incomplete gate, never a pass. Batches were recorded only while the tip and the
mempool slot held still, with an empty mempool, so the validity-interval slot in a refusal is the one
the replay judges at; the slot is compared unchanged.

The `<era>-bootstrap-attributes` corpora (2026-11-09, #832) hold one transaction in each of the six eras,
paying a Byron address whose attributes are 65 bytes, one over the limit. Shelley refuses it with its
own `OutputBootAddrAttrsTooBig`. From Allegra the reference reports `OutputTooBigUTxO`, which it injects
through `shelleyToAllegraUtxoPredFailure`, with sizes 0 and 0 from Alonzo.

The certificate-order corpora add three failing certificates in both eras. The Babbage corpus also
records MIR certificates with successive amounts `[-1, +1, -1]`, once against each pot. The native
rule retains the signed intermediate update after reporting a negative reward: the complete refusal
therefore contains two `MIRProducesNegativeUpdate` failures. Other failures in those submissions are
retained and compared too. Reinstating the old implementation that discarded the negative update
makes both native MIR cases fail; the corrected rule matches their complete original refusals.

The `conway-governance` corpus (2026-11-13) holds seven Conway transactions, each built to break one
check: `MalformedProposal`, `ProposalCantFollow`, `InvalidPrevGovActionId`, `DisallowedVoters`,
`VotingOnExpiredGovAction` and `TreasuryWithdrawalsNetworkIdMismatch` (GOV), and `WrongNetworkInTxBody`
(UTXO). The reference refused each with exactly that one failure. `refusalcases.conway_governance` cites
the reference's check for each case. The two vote cases name actions that setup transactions had already
proposed on the cluster. The first is a DRep's vote on an info action, cast in the one epoch in which the
action is expired but not yet removed. The second is a stake pool's vote on a live treasury withdrawal,
which no pool may vote on. Neither vote breaks the other's check.
The `refusalcoverage` corpora (2026-11-13) hold predicate failures that no earlier corpus provoked, each
case built to break exactly one check, and `scripts/tests/test_refusalcoverage.py` requires each
recorded refusal to be that one failure, read from the reference's constructor tags. `scripts/devnet/refusalcoverage.py`
cites the reference's check for each case at cardano-ledger f649f975.

| corpus | protocol | cases |
|---|---|---|
| `conway-deleg-collateral` | 10.0 | DELEG `IncorrectDepositDELEG` (a registration's deposit, an unregistration's refund), `StakeKeyHasNonZeroAccountBalanceDELEG`, `DelegateeDRepNotRegisteredDELEG`; GOVCERT `ConwayDRepIncorrectRefund`; UTXO `ScriptsNotPaidUTxO`, `CollateralContainsNonADA` |
| `conway-gov-proposals` | 10.0 | GOV `ConflictingCommitteeUpdate`, `ExpirationEpochTooSmall`, `TreasuryWithdrawalReturnAccountsDoNotExist`, `InvalidGuardrailsScriptHash` (a parameter change and a treasury withdrawal), and `MalformedProposal` for each parameter `ppuWellFormed` refuses at zero and for an empty update |
| `conway-v11-coverage` | 11.0 | DELEG `DepositIncorrectDELEG`, `RefundIncorrectDELEG`; GOV `MalformedProposal` (`nOpt` 0), `UnelectedCommitteeVoters` |
| `alonzo-collateral` | 6.0 | UTXO `ScriptsNotPaidUTxO`, `CollateralContainsNonADA`, `WrongNetworkInTxBody`, `ExUnitsTooBigUTxO` |
| `babbage-collateral` | 8.0 | UTXO `ScriptsNotPaidUTxO`, `CollateralContainsNonADA`, `WrongNetworkInTxBody` |

The three Conway corpora come from one cluster, so they share its genesis files. At protocol version 10
a wrong deposit or refund is `IncorrectDepositDELEG`; version 11 reports `DepositIncorrectDELEG` and
`RefundIncorrectDELEG` instead, refuses `nOpt` 0 and refuses a vote by a committee member no committee
elected. The `conway-v11-coverage` builder takes the cluster to version 11 first: it elects a committee of
one member, then proposes the hard fork, which that member, the three DReps and the three pools vote for.
The unelected member is one that a pending UpdateCommittee proposal names, which is what allows its hot key
to be authorised. Disabling Scarab's reward-balance, DRep-refund, script-locked-collateral and
conflicting-committee checks fails exactly the cases that break them, the Alonzo and Babbage
script-locked cases among them, since those eras run the same check.

The Alonzo and Babbage corpora each come from an era-walking cluster stopped in that era. A transaction
whose execution units exceed `maxTxExecutionUnits` is refused by the node's mempool before the ledger
judges it (ouroboros-consensus 82ecba32, `Mempool/Update.hs:207-224`, `Shelley/Ledger/Mempool.hs:601-641`).
The Alonzo case's refusal is that check's, which nests the failure as the Alonzo rule does. Babbage has no
such case: its mempool nests the failure under `AlonzoInBabbageUtxowPredFailure`, where the Babbage rule,
which this suite compares for a pre-Conway era, reports it under UTXOW's `UtxoFailure`. No era has an
`OutsideForecast` case. The rule extends the epoch information linearly from the current slot before it
translates the validity interval's end (`Alonzo/Rules/Utxo.hs:377-382`; cardano-base 060819b5,
`cardano-slotting/src/Cardano/Slotting/EpochInfo/Extend.hs:54-66`), so that translation cannot fail; the
`plutus-forecast-horizon` corpora record what the reference does with such a bound instead. Disabling
Scarab's Alonzo non-ada-collateral and body-network checks, its Babbage non-ada-collateral check (which
Conway also runs), and its two protocol-11 switches (the deposit and refund failures, the unelected voters)
fails exactly the cases that break them. All five corpora were recorded from a clean tree, each at the
commit its `corpus.json` names. The Alonzo corpus's setup sent its change to the funder key's enterprise
address, the committed builder to the funder's base address.

These expectations come from the released node's submission path. Separately, the offline
`UpstreamFreshLedgerConformanceSuite` and `UpstreamMultiLedgerConformanceSuite` use the same production
transaction rules under accumulation, with a native-shaped LEDGERS adapter, and compare all original
native refusal lists as complete bytes. Their original inputs and native/formal provenance remain
separate from these devnet captures. The recorded devnet corpus covers the six Shelley-based eras.
PPUP is identified at its era-specific embedding: directly under UTXO through Mary and under UTXOS
in Alonzo and Babbage. The early-era cases include three failing certificates and complete accumulated
failures; Alonzo also includes an invalid validity flag with an empty script set, requiring no
PlutusFailure-text exception. These STS/LEDGERS comparisons do not establish Byron transaction-error
coverage or exhaust every constructor within each family.

## Refresh

```sh
python3 scripts/devnet.py --name <yours> up
python3 scripts/devnet.py --name <yours> refusal-corpus                 # writes conway-devnet/
python3 scripts/devnet.py --name <yours> down
python3 scripts/devnet.py --name <yours> up-eras --final-era babbage   # walks Byron..Babbage and stays there
python3 scripts/devnet.py --name <yours> refusal-corpus --era babbage  # writes babbage-devnet/
python3 scripts/devnet.py --name <yours> down
python3 scripts/devnet.py --name <yours> up-dijkstra --conway-only --dijkstra-epoch 1000   # the Leios build, in Conway
python3 scripts/devnet.py --name <yours> refusal-corpus --reference-build leios-b004fe0c \
    --corpus conway-bootstrap-attributes-leios-b004fe0c --cases refusalcases:leios_bootstrap_attributes
python3 scripts/devnet.py --name <yours> down
python3 scripts/devnet.py --name <yours> up
python3 scripts/devnet.py --name <yours> refusal-corpus --corpus conway-governance \
    --cases refusalcases:conway_governance   # waits about two epochs for its vote cases
python3 scripts/devnet.py --name <yours> refusal-corpus --corpus conway-deleg-collateral \
    --cases refusalcoverage:conway_deleg_collateral
python3 scripts/devnet.py --name <yours> refusal-corpus --corpus conway-gov-proposals \
    --cases refusalcoverage:conway_gov_proposals
python3 scripts/devnet.py --name <yours> refusal-corpus --corpus conway-v11-coverage \
    --cases refusalcoverage:conway_v11     # takes the cluster to protocol version 11, about five epochs
python3 scripts/devnet.py --name <yours> down
python3 scripts/devnet.py --name <yours> up-eras --final-era alonzo
python3 scripts/devnet.py --name <yours> refusal-corpus --era alonzo --corpus alonzo-collateral \
    --cases refusalcoverage:alonzo_collateral
python3 scripts/devnet.py --name <yours> down
python3 scripts/devnet.py --name <yours> up-eras --final-era babbage
python3 scripts/devnet.py --name <yours> refusal-corpus --era babbage --corpus babbage-collateral \
    --cases refusalcoverage:babbage_collateral
python3 scripts/devnet.py --name <yours> down
python3 scripts/check_test_coverage.py --refresh-fixtures
```

A corpus directory is never overwritten: remove it to record it again. The command fails when a case is
refused for another reason than the one it was built for (a constructor of `expect` that the rendering
does not name), or when the reference accepts one.

## Adding cases

Cases are built in `scripts/devnet/refusalcases.py`. A branch may instead keep a builder module of its
own in `scripts/devnet` and record a corpus directory of its own with
`refusal-corpus --era <era> --corpus <name> --cases <module>:<function>` (the function takes the devnet
name and returns `refusalcases.Case`s). The command adds the directory to `manifest.json`; the suite and
the tests read every corpus the manifest lists, with no change to the runner.
