# Offline PV9 translation differential tests

All translator/evaluation code is in `src/test`, depending on core/vm Compile products. No application or runtime project depends on translator. Run `translator/test` explicitly; this project is intentionally outside the production aggregate. It adds no external dependencies beyond the existing test framework.

This independently derives a restricted V3 ScriptContext from original transaction, full pinned parameters and pre-state CBOR. It does not import reference Data. Only after complete ordered Data equality and exact CBOR equality do differential tests evaluate the unchanged reviewed script. This is admitted-profile translation evidence, not full ledger validation.

## Closed grammar

The wrapper is exactly `[body,witnesses,true,null]`. False/nonboolean validity markers, auxiliary data and wrong arity reject. True is a marker, never a validity conclusion. CBOR definite/indefinite container forms supported by the bounded core decoder are accepted only when their semantic shapes match below; no general ledger-decoder compatibility is claimed. Original body encoding is retained for hashing.

Body map required keys: 0 ordinary inputs, 1 outputs, 2 fee, 11 script-integrity bytes32, 13 collateral inputs. Optional keys: 3 upper validity slot, 8 lower validity slot. All other keys reject, including explicit empty unsupported fields. Duplicate semantic keys reject irrespective of integer encoding. Script integrity is shape-checked, not recomputed/validated by Scala.

Witness map required and only keys: 5 redeemer map, 7 V3 scripts. V3 scripts must be tag258 array of exactly one byte string containing the pinned369-byte ledger script. Redeemers must be exactly one map pair `[0,index] -> [signedInt64,[memory,cpu]]`; index selects the script input in ledger-sorted ordinary inputs. Memory exactly100000, CPU exactly30000000. Lower or higher declared budgets reject; evaluation uses these same fixed limits. No native/V1/V2/key/bootstrap witnesses or witness datums.

Input sets must be tag258 arrays with unique `[bytes32,index0..65535]` references. One script input and at most one key input; exactly one disjoint key collateral input. Resolve exactly all ordinary/collateral entries, rejecting duplicates, missing or unused entries. Collateral must have no datum and does not enter context inputs/outputs. No collateral return, total collateral, sufficiency checks or invalid-transaction collateral path.

Outputs accept only `[address,coin]` or maps with required0 address/1 coin and optional2 inline datum. Maps reject all other/duplicate keys. Address is bytes29 starting60(key enterprise testnet) or70(script enterprise testnet). Coin is nonnegative signedInt64. Datum must be `[1,tag24(bytes containing tag121([beneficiaryBytes28,minimumNonnegativeInt64]))]`. No datum hashes, reference scripts or multi-assets. Only the resolved script input has inline datum; key inputs/collateral and one or two new outputs must be key-address outputs with no datum.

Parameters are bounded and checked against the exact approved full-parameter SHA before interpretation: PV9.0, frozen251-entry V3 cost model. Transaction<=65536 bytes; parameters<=65536; <=3 pre-state entries; each input<=128/output<=4096 bytes; aggregate<=16384. CBOR decoder depth32, items4096, string4096; per-member input cap. Datum nesting is separately decoded under4096 bytes with the same depth/item bounds. Trailing bytes, malformed CBOR and unsupported tags/types reject. Immutable `Bytes` owns its buffers.

## Context and identity

Compute tx identity by BLAKE2b-256 over original body bytes; script credential by BLAKE2b-224 over03 plus pinned script payload. Both use existing explicit Bouncy Castle helpers. Do not call Scalus global ledger hashing/decoder routes. Native runtime artifacts remain excluded.

Sort ordinary references by unsigned hash byte lexicographic order then numeric index; pre-state order is irrelevant. Preserve output order. Populate all16 V3 fields in schema order. Empty fields correspond to rejected unsupported transaction features. Inline datum appears in resolved input and SpendingScript datum, not witness datum map. Redeemer map key is Spending(actual resolved reference); current redeemer comes from transaction witnesses.

Time is synthetic:1577836800000 +1000*slot milliseconds, slots0..1000000. Lower finite inclusive, upper finite exclusive (Conway), unbounded endpoint tags/closures follow pinned reference. Equal endpoints translate to an empty interval; inverted bounds reject as a narrower profile restriction. No historical schedule or time-dependent effects.

## Translation versus evaluation

The reviewed script requires exactly one ordinary input AND exactly one output, redeemer7, matching purpose/datum/beneficiary and sufficient payment. Extra-input/output and redeemer-change cases may have exact translation parity while intentionally failing evaluation. They are never successful-spend claims. Variant generators rebuild transaction script-integrity commitments before final serialization; no context golden is patched. Reference CEK failures remain unclassified and have no inferred consumed budget. No signatures, phase1, value conservation, integrity validation or full ledger acceptance is claimed.

## Pinned translation sources and validation

Exact upstream archive and revised Cabal hashes/URLs are in `fixtures/plutus-pv9-translator/provenance.json`. Inspected APIs: Conway1.23.0.0 `Conway/TxInfo.hs`486-540 (all fields, ordering, identity),745-771 (ScriptContext/SpendingScript),790-817 (Conway intervals); `Conway/TxBody.hs`662-679 (spending pointer inverse); Alonzo1.16.0.0 `Alonzo/TxBody.hs`550-558 (set index); Babbage1.14.0.0 `Babbage/TxInfo.hs`191-227 (redeemer translation); PlutusLedgerApi1.70.0.0 `V3/Contexts.hs`434-458 and715-748 (field/constructor schema). Scalus commit31531c14d4e556fb38c984d702ee60dd82b6453f is pinned in base provenance; Transaction.id/Script.scriptHash/TransactionWitnessSet are deliberately avoided because they call the global platform.

Validation:27 translator tests (18 complete differential cases, inventory,8 baseline/grammar tests) passed under offline Docker2CPUs/2GiB with no extra swap;47 existing VM tests and formatting checks also passed. All11 successful vectors independently matched CPU19269788/memory47600;7 failures remained Haskell CEK-unclassified and Scala explicit-error, with no category equivalence inferred. The resolved test classpath had28 JARs, no blst-java/secp256k1-jni artifacts and no .so/.dll/.dylib/.jnilib members. Translator Compile/sources is empty; all4 Scala files are Test-only. Native reference machinery remains external to this JVM classpath.

Commands: `translator/test`, `vm/test`, `show translator/Test/fullClasspath`, `show translator/Compile/sources`, `scalafmtCheckAll`. Use existing cached sbt1.10.7/Java21 image, `--network=none --read-only --cpus=2 --memory=2g --memory-swap=2g`, private cache/output, JVM `-XX:ActiveProcessorCount=2 -Xmx1200m`. The test project intentionally requires explicit invocation and is not part of root/app runtime aggregation.

The public GitHub Actions workflow explicitly runs `translator/test` as a separate step. The root production aggregate and app/runtime dependency graph remain unchanged.
