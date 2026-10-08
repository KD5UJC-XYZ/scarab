# Conway PV9 ADA native payment spending

This is a separate followup to immutable evaluator commit
`e81af0cbb456e8df464e0f837856e7ccbc1db6d2`. The branch starts at that commit
(itself based on main `b825b31c78c516fc7aa8c0b623a86c57825772a5`). Integrate the
evaluator first, then this packet. No historical API, source pin, evaluator file,
shared launcher, or application source is changed by this packet.

## Explicit profile and owned files

`NativeSpending.scala` adds profile `conway-pv9-ada-native-spending-v1` and checked
payment-credential binding. `ClusterNativeTransfer.scala` adds
`conway-pv9-cluster-ada-native-transition-v1`, composing it with the existing
original-block inclusion/interval binding and ADA state comparison.
`NativeSpendingSuite.scala` owns the positive and negative regressions. This
document and `native-spending-integration.patch` complete the packet.

Consumed outputs must be testnet network-ID 0 scalar-ADA outputs with exactly
address and coin fields. Payment addresses may be base key/key (kind 0, 57 bytes),
enterprise key (kind 6, 29 bytes), or enterprise script (kind 7, 29 bytes). At least
one script input is required. Created outputs/change are restricted to key kinds
0/6. No minting, Plutus, collateral, datum, attached reference script, reference
input, certificate, withdrawal, explicit signer field, governance, or auxiliary
data is supported. These are local exclusions, not assertions of reference
ledger invalidity. Array and map output forms are accepted only without extra
fields; even a null datum/reference-script field rejects. Encoded multiasset
values reject even if their maps are empty.

The body admits only required fields 0/1/2 and optional interval fields 3/8.
The original witness map admits vkeys 0 and native scripts 1 through the existing
native diagnostic codec. Absent collections are allowed; present ones must be
nonempty. At most 128 input references, 128 outputs, 128 witness entries and 32
script entries are allowed. A snapshot is at most 4,096 references and 1 MiB;
the transaction is at most 1 MiB with CBOR depth 16 and 65,536 items. The evaluator's
per-script limits also apply. These limits bound pure computation independently
of ledger protocol parameters. No runtime or new dependency is introduced.

## Exact pinned source contract

All references use cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`. This is the established source research
pin; it is not a claim of a reproducible node binary or a new live differential
run. The evaluator's source contract remains in `native-script.md`.

[Address serialization and credentials](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs#L261)
establish the header kinds and payment credential location: the 28 bytes after
the header. Base staking credentials do not authorize spending. Restriction to
these three consumed-address forms and two created-address forms is local.

[Shelley key requirements](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/UTxO.hs#L192)
and [Conway composition](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/UTxO.hs#L162)
reduce, within this closed scope, to payment keys of consumed resolved key inputs.
Unspent entries, recipients, and staking keys create no requirement here.

[Spending-script derivation](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs#L349)
and [payment script extraction](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/State/UTxO.hs#L206)
derive script hashes from resolved consumed script-address credentials. This
implementation rejects unresolved inputs first, rather than allowing a missing
resolution to omit a required credential. Repeated credentials require one
script hash, while the receipt retains the input-to-script mapping.

[Babbage UTXOW](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxow.hs#L173)
requires equality of needed and received script hashes when reference scripts
are absent. Needed native scripts must evaluate successfully; missing and extra
scripts reject. An unrelated failing script is an extra witness, not a required
predicate failure. The new binding filters diagnostic evaluations by the actual
required set. Different original encodings with equivalent meaning have different
hashes and cannot substitute for a credential's exact hash.

The evaluator's PV9 duplicate rules remain in force: identical native witness
scripts collapse by hash; duplicate identical vkey witnesses collapse as set
elements; different signatures for the same key all undergo verification. The
complete supplied vkey set is verified before credential checks; an invalid
unneeded signature cannot disappear. The checked key set is bound to the original
transaction body, and script timelocks consume that body's validity interval.

[Alonzo memo sizing](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs#L403)
and [memoized witnesses](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs#L474)
give `ledgerSize = 1 + originalBody.size + originalWholeWitnessMap.size + 1` for
this no-auxiliary-data scope. Native scripts, set tags, duplicate entries and
nonminimal encodings all contribute their original bytes. The validity flag does
not. Minimum fee is `feePerByte * ledgerSize + feeFixed` under this profile.

[Conway reference-script sizing](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/UTxO.hs#L141)
examines regular as well as reference inputs. Rejecting attached reference scripts
on consumed outputs is therefore necessary for the zero-reference-fee assumption.
There are no redeemers/execution units and hence no execution-unit fee; native
evaluation itself has no additional CPU fee. [Execution-unit calculation](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/ExUnits.hs#L173)

## Checked boundary and original bytes

`NativeSpending.check(original, preUTxO)` returns a constructor-restricted
`Admission` only after resolving all inputs, verifying all supplied signatures,
binding exact required script hashes, evaluating required native scripts, and
covering ordinary payment keys. `credentialBound=true` means those credential
requirements are covered. This standalone binding does not establish value
conservation, fee sufficiency, inclusion, or full transaction validity.

Historical Coverage is reused only as a semantic projection of original input,
output, and fee fields. That temporary envelope omits interval and witness fields;
it is never used for authentication, IDs, or fee size. Original output spans are
copied verbatim for minimum-output sizing. Signature verification, body hashes,
script hashes, created output IDs and total memo size always use unmodified
original bytes. No witness or body reserialization serves as authentication
evidence.

`ClusterNativeTransfer.compare` binds original body AND whole witness map to the
actual containing block, checks interval inclusion at that block's slot, performs
credential binding, checks minimum outputs/value balance/fee/size, then checks
the observed UTxO identity delta, created address/value pairs, untouched original
bytes and fee-pot delta. It reuses the existing 1–8 block original-body commitment
and endpoint continuity checks. It preserves `fullLedgerValidated=false` and
`referenceSnapshotAtomic=false`; neither header consensus validity nor atomic
reference snapshots is established. Created snapshot output encodings may differ
while address and value must match; untouched output encodings must remain exact.

The evaluator's detached results remain `credentialBound=false`. Only this new
checked payment binding/transition receipt asserts coverage. No full UTXOW failure
ordering is claimed: signature verification precedes script diagnostics here,
and the restricted minimum-output/fee order follows the local composition.

## Typed failures and integration proposal

`Error` distinguishes local unsupported profile/input, CBOR decode rejection,
malformed structure, resource limit, unresolved inputs, invalid signature, missing
key, missing script, wrong script hashes (both missing and extra sets), extraneous
script, failed required script, interval failure, fee/size/minimum/balance failure,
and observed state mismatch. `WitnessProfileRejected` conservatively retains an
unclassified legacy diagnostic; it must not be displayed as proof of reference
ledger invalidity. The same applies to all local parser/resource exclusions.

`native-spending-integration.patch` is an additive, opt-in application adapter:
`ClusterTransferCommand.compareNative(in, originalBlocks)` returns the new typed
receipt/error using the existing loaded context and captured bytes. It leaves
CLI dispatch and existing key-transfer comparison unchanged. The owner can call
it after the existing capture stage when the explicit native-spending scenario
is selected. No default flag/profile widening or live launcher is included.

## Verification scope

Tests cover mixed script/key spending, shared script credentials, empty-key native
scripts, missing/wrong/extra scripts, missing ordinary and script keys, invalid
extra signatures, timelock boundaries, exact memo fee/size boundaries, duplicate
witnesses, original script/body mutations, captured witness mismatch, unsupported
input forms, unresolved/duplicate snapshot references and state/value changes.
All signatures use disposable test-only in-memory keys; blocks have synthetic
header crypto and real original-body commitments. No live node acceptance is
claimed. Offline Docker is capped at two CPUs and 2 GiB with a private cache and
network disabled. Funded script-address reference tests remain the integration
owner's next step after source review and resource-slot authorization.

Final offline result: `scalafmtAll scalafmtCheckAll ledger/test` passed with
148 ledger tests, including 18 new spending tests and all evaluator/historical
replay regressions. With the proposed application adapter temporarily applied in
this isolated worktree, `app/test` passed 167 tests and compiled the adapter.
The adapter was then restored out of source and its formatted diff retained
**unapplied** for the integration owner. `git diff --check` and patch applicability
checks passed. Independent read-only source review found no blocker; requested
boundary tests were added and passed. Logs remain ignored and local under
`.cache/native-spend-final-tests.log` and
`.cache/native-spend-integration-tests.log`.
