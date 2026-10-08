# Conway PV9 native-script predicate packet

This packet adds an explicit pure evaluator and standalone witness diagnostics.
It does not admit script-locked spending or minting. Historical Coverage,
RestrictedReplay, and the existing transfer admission APIs remain unchanged.
The base is `b825b31c78c516fc7aa8c0b623a86c57825772a5`.

## Source contract

All rule references below are pinned to cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, matching the existing Conway PV9 source
research profile. This is source-level conformance, not an assertion that a
complete resolved build plan reproduces the private node binary. No new reference
node run was performed for this packet.

The [Conway CDDL](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/cddl/data/conway.cddl#L368)
defines native constructor arrays: 0/keyhash, 1/children, 2/children,
3/threshold/children, 4/start slot, 5/expiry slot. Key hashes are 28 bytes;
thresholds are signed int64, including negative values; slots are uint64.

The [Allegra evaluator and decoder](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs#L432)
use a witness-key set and transaction validity interval. A signature leaf tests
key membership. All/any over an empty sequence return true/false. Thresholds
at or below zero succeed; larger thresholds count successful children, retaining
duplicate children. A start lock requires a present lower bound at least as large
as the lock. An expiry lock requires a present upper bound no larger than the
lock. Both endpoint comparisons include equality. Missing corresponding bounds
fail. No wall clock or captured current slot enters this predicate. Interval
inclusion is a separate UTXO check; even reversed intervals can satisfy native
predicates without any slot being admissible.

[Conway Script instances](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs#L68)
select Timelock native scripts and Alonzo's prefix scheme.
[Core.hashScript](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs#L561),
[Alonzo original bytes/prefix](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs#L479),
and [ScriptHash's hash type](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Hashes.hs#L117)
establish `Blake2b-224(0x00 || original native-script CBOR)`. The witness array,
set tag, and general script wrapper are not part of these bytes. Semantic
equivalence does not imply hash equality: nonminimal encodings retain their
distinct original-byte identity.

### Duplicate and set behavior

At PV9, [Alonzo's custom witness decoders](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs#L575)
allow an optional set tag 258 and require collections to be nonempty when
present. Duplicate vkey elements collapse through a set, and native scripts
through a map keyed by script hash. These custom paths begin duplicate rejection
at PV12. [WitVKey ordering](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Keys/WitVKey.hs#L53)
includes both key and signature hashes: different signatures on the same key
survive and must all verify. This implementation deduplicates byte-identical
key/signature pairs before crypto, never by key alone.

Do not generalize this rule to all CBOR sets: [the generic set decoder](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs#L887)
rejects duplicates at PV9. [Sparse map decoding](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs#L538)
rejects repeated field keys independently of collection duplicate behavior.

## Owned APIs and bounds

- `NativeScript.scala`: profile `conway-pv9-native-script-predicate-v1`; checked
  script decoding/hash, verified key construction, exact-body-bound evaluation.
- `NativeScriptWitnesses.scala`: profile
  `conway-pv9-native-witness-diagnostic-v1`; original envelope diagnostics with
  witness fields 0/1 only, true validity flag, null auxiliary data, and the
  existing interval body's 0/1/2 plus optional 3/8 syntax.
- `NativeScriptSuite.scala`: semantic, byte/hash, crypto-binding, duplicate,
  malformed-input, and resource regressions.
- This document and `native-script-integration.patch`.

Each script is limited to 65,536 bytes, 1,024 decoded CBOR items and AST depth 16.
AST depth counts the root as zero. CBOR depth is independently bounded at 34.
Witness diagnostics accept at most 32 script entries and 128 vkey entries before
deduplication, within a 1 MiB envelope. The existing interval decoder additionally
limits envelope CBOR depth to 16, making nested diagnostics intentionally narrower
than standalone script decoding. These are local resource limits, not consensus
parameters. Malformed children reject even under a zero threshold or an already
satisfied disjunction. Unsupported constructors, tags, arities, tagged bignums,
indefinite key/signature byte strings, and out-of-range integers reject explicitly.
Definite/nonminimal and indefinite array framing retain their original bytes.

`VerifiedKeys` has no public constructor or copy operation. Its factory verifies
every distinct supplied witness against the exact original transaction body using
the existing Cardano Ed25519 predicate. Evaluation rejects a key set from another
body. Empty keys are legitimate for timelock-only or empty-conjunction predicates.
An evaluation's `satisfied` is solely a native predicate result;
`credentialBound=false` and `fullLedgerValidated=false` remain explicit.

The witness diagnostic reports every supplied native script, deduplicated by its
original-byte hash. It does not declare an aggregate transaction-validity result.
It deliberately evaluates supplied scripts for inspection, rather than pretending
to know the reference's required-script set. Hash collisions with differing
original bytes are rejected rather than silently selecting a map entry.

## Integration plan and remaining admission work

`native-script-integration.patch` adds an opt-in
`ClusterIntervalTransfer.compareWithVerifiedKeys` method. It composes the existing
captured transfer comparison with diagnostic key verification and returns its
receipt plus a verified key set. Existing entry points are unchanged. Detached
scripts can then be evaluated with the receipt's `bound.interval.interval` and
those keys. Signature work is repeated to obtain checked proof without changing
the existing receipt or bypassing its constructor boundaries. A later dedicated
refactor can share that proof within the successful signed-transfer check.

The patch does not admit witness key 1 into the existing transfer decoder or
script addresses into Coverage. A native witness diagnostic can independently
inspect such a witness map, but does not establish input ownership, required
script hashes, reference scripts, minting policies, or state validity. There are
no shared application changes, no new runtime dependencies, and no Plutus code.

Before admitting script spending, derive required hashes from resolved input
credentials and compare them with supplied script hashes. For minting, add an
explicit policy/asset binding profile and corresponding value rules.
[Babbage UTXOW](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxow.hs#L173),
used by [Conway](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxow.hs#L187),
subtracts reference-script hashes from needed hashes before checking missing and
extra witness scripts. Without references, needed and received must match exactly.
Only needed native scripts are evaluated. Full UTXOW checks native predicates
before script-set checks and later signature/required-key checks. This packet
verifies signatures before producing native diagnostics and does not claim full
reference failure ordering. Keep captured inclusion interval checking, original
body/witness commitments, fee sizing, and state transition checks in that future
composition; successful scripts alone cannot replace any of them.

## Verification

Offline Docker uses the existing `cardano-public-v023-check:local` image,
`--network=none --cpus=2 --memory=2g --pids-limit=256`, a 1200 MiB JVM heap, and
this worktree's private cache. No live cluster, public data fetch by test code,
host installation, production key, or push is authorized for this packet.
Test-only Ed25519 keys are generated in memory. Two fixed script hash vectors
were cross-checked independently with Python's `hashlib.blake2b(digest_size=28)`.

Final checks: `scalafmtAll scalafmtCheckAll ledger/test` passed with 130 tests,
including 12 new native-script tests and the unchanged historical replay pins.
The integration patch was then temporarily applied in this isolated worktree:
`scalafmtAll scalafmtCheckAll ledger/test app/test` passed with 130 ledger and
167 app tests. Its signed captured-transfer test asserts exact-body equality and
successful detached key/start/expiry evaluation using the returned verified keys.
The two patched source/test files were subsequently restored byte-for-byte to
HEAD; the formatted, tested diff is delivered **unapplied** for owner integration.
No existing source file is modified in the packet itself.

Independent read-only review found no blocking issue. Its integration-cap concern
was resolved by making the wrapper additive and opt-in; its codec test suggestions
are included. `git diff --check` and patch applicability checks passed. Logs are
ignored worktree-local `.cache/native-tests.log` and
`.cache/native-integration-tests.log`. Live reference acceptance remains pending
resource-slot authorization and actual credential/witness-set integration.


## Main diagnostic integration

The evaluator packet was cherry-picked as `a8732da`. Its diagnostic adapter patch
remains unapplied. The existing transfer, interval, inclusion and fee paths and
legacy Coverage/Balance/RestrictedReplay sources are unchanged. Evaluator results
retain `credentialBound=false`; no script-spending or minting admission follows.
Required consumed-credential binding is a separate, not-yet-integrated packet.

Combined main validation passed 829 public Scala tests and 25 public gates. The
full native-inclusive launcher suite passed 95 tests using the existing Linux C
compiler, with a Docker-call sentinel proving zero cluster/container calls from
those tests. The isolated Java image separately ran its available Python guards
successfully, explicitly skipping native-helper setup because it has no compiler.
These are offline integration checks, not a live native-script spending result.
