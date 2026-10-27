<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native genesis start (#488)

Part of the [compatibility matrix](../compatibility.md); the register entry is `native-genesis-start` in [compatibility.json](../data/compatibility.json).

## Status as recorded

`NodeSetup` constructs native state through `ByronMirror` or `GenesisStart.ledger` and `GenesisMirror`. A Shelley-based start translates the Byron genesis UTxO to its first era and applies that era's genesis injection. A Byron start applies no injection; its `FromByron` hand-over context uses `ProtocolParams.translatedFromGenesis`. Supplied Alonzo `extraConfig.costModels` are still parsed, so ignoring their application does not bypass existing validation. Base genesis models remain available to the later era translations. Alonzo's injection overrides only current parameters for a Shelley-based start. Initial-fund parsing retains ordered address/Coin pairs. An opaque `FundAddress` admits only the strict JSON address decoder and canonical native serialization, so public injection callers cannot supply an unchecked raw address envelope. Parsed legacy and extra sources remain distinct, including absent versus embedded-empty sources; every supplied source is parsed even for a Byron start. The guarded injection constructs each output with the reference's compact value bound, hashes the decoded address's serialization for its pseudo input, and subtracts the full signed Coin total from reserves. Repeated addresses replace the output but contribute every amount to that total.

Reference: ouroboros-consensus `82ecba32`, `Cardano/Node.hs:933-974` (`mkInitExtLedgerStateCardano`, `perEraInjections`, Byron identity); cardano-ledger `f649f975`, `Alonzo/Genesis.hs:222-242` (parse the optional extra configuration) and `Alonzo/Transition.hs:35-66` (`injectIntoTestState`, `alonzoInjectCostModels`, current parameters only). The `legacy` module and its flat-state conversion are removed.

## Evidence

`GenesisStartSuite` checks native genesis funds, accounts, stake snapshots, governance state, current-only cost-model injection and typed refusals. Its Byron regression constructs the real fixture's native Byron state, performs `Handover.toShelley` and the translations through Alonzo, and requires the base PlutusV1 model to survive without the injected PlutusV2 model. A separate malformed-model case requires the unused injection's existing typed validation to remain active. Output-construction tests cover zero and Word64 maximum, reject negative and over-Word64 amounts, and exercise a negative enterprise-address fund through `NodeSetup`. Byron controls refuse malformed unused Addr/Coin values while accepting an unused negative Coin, valid file metadata and conflicting sources without constructing outputs or selecting sources. Mainnet checks precede output compaction in the public injection operation. Two individually valid maximum outputs can debit reserves below zero without wrapping. Repeated-address and existing-UTxO collision controls exercise the ordered-list and map boundaries. Twelve [native API cases](../../fixtures/reference/genesis-initial-funds/README.md) compare JSON acceptance, canonical address bytes, pseudo-input CBOR and compact conversion without field exclusions; they include reserved header bits, pointer normalization and a non-shortest Byron envelope tag.

Source-derived regressions cover `InjectionData` JSON forms and required file metadata (`Shelley/Genesis.hs:833-848`), parsing invalid unselected entries, and source selection separately from consumption. Conway resolves DRep/delegation conflicts before the Shelley guard (`Conway/Transition.hs:166-171`). Shelley checks Mainnet before resolving pools/staking, then registers funds (`Shelley/Transition.hs:250-265`); the funds operation checks Mainnet before resolving its own source (`647-655`). Negative controls that retain a noncanonical Byron envelope or move funds conflict resolution before Mainnet fail the intended assertions; restored code passes the focused suite. Valid selected files retain a typed unsupported-file error until the approved reader is implemented. The `genesis-injections` fuzz target mutates complete injection documents, including signed Coin and a file/legacy conflict, and checks canonical address re-parsing for every accepted embedded fund.

The [Byron-origin cost-model oracle](../../fixtures/ntc-golden/byron-cost-models/README.md) executes the unchanged native parameter upgrades on the exact era-capture genesis. It establishes V1-only Alonzo/Babbage models and V1/V3 Conway models. The associated 265-row query-golden migration changes only 68 cost-model subtrees in 25 answers; an inverse byte comparison restores all original answers. It does not claim native equivalence of the complete synthetic query states.

`DuplicateWithdrawalsLedgerSuite`, carried from [#496](https://github.com/KD5UJC-XYZ/scarab/pull/496), keeps the value-conservation regression on native ledger state when the legacy test module is deleted. These are scoped unit/source checks; full scenario evidence belongs in a dated evidence-log entry.

The isolated `InjectionJson` pull reader follows json-stream 0.4.6.1's lexer and `objectItems value`,
used by pinned `Shelley/Genesis.hs:903-925`, and yields each entry's value as `JsonValue` (aeson's
`Value`, as json-stream builds it). Its [123 actual native traces](../../fixtures/reference/injection-json/README.md)
retain raw bytes and chunk sizes, all yielded pairs, remaining bytes and completion/refusal decisions.
Each refusal is a closed typed error for one reachable json-stream message, and its rendered text
equals the native diagnostic, including the Haskell `show` of the element the message prints.
Six traces still request data after three empty feeds; those are reference non-results, not completed
injections or native refusals. The Scala reader exposes the same state. Separate ordering and
20,000-layer small-stack tests exercise incremental delivery and explicit frames. These checks do
not implement hash checking, effectful file reads or genesis source integration, and the permissive
json-stream mechanism is separate from strict raw-genesis JSON admission (#497).

The isolated reader's focused Docker checks pass 125 tests (123 native traces, entry ordering,
and 20,000-level small-stack parsing). The portable native replay compares all 123 recorded
outputs, including diagnostic text. The existing deterministic resource guards pass, and
20,000 mutations with seed 1 pass for the `injection-json` target. An earlier fuzz attempt
was terminated during failure shrinking without a completed result; the subsequent source
change avoids parsing large numeric coefficients twice while preserving the native exponent
range. These are bounded reader checks, not complete file-injection or whole-genesis gates.

Native Value comparisons include ordered object traversal and unnormalized Scientific coefficient/exponent events. Four ordering assertions fail on the earlier insertion-order reader and pass after matching the installed ordered KeyMap. Serialized native pairs remain unchanged observations in the portable replay; reparsing the formatter's overflowed exponent is not a valid oracle for its in-memory Value. The earlier full fuzz run was stopped when this ordering defect was found; its partial progress is not a completed gate.

## Explicitly unchecked or unconfirmed

Genesis pool relays/metadata and hash-checked `InjectionFromFile` support remain [#499](https://github.com/KD5UJC-XYZ/scarab/issues/499), approved for separate reference-alignment changes. Existing required-field/default and malformed-input parser differences remain [#497](https://github.com/KD5UJC-XYZ/scarab/issues/497). Preserving validation of the tested malformed extra cost model does not establish complete genesis-parser conformance. #496 is a required predecessor of the legacy deletion. Signed reserve arithmetic depends on [#492](https://github.com/KD5UJC-XYZ/scarab/issues/492). Focused boundary tests pass on the integrated arithmetic implementation; final predecessor integration, independent review and complete stage gates remain required. The twelve-case native API corpus covers Addr/Coin serialization and compaction; source-selection ordering is checked against the cited source with Scala regressions, not a claimed whole-transition native oracle. File loading is still unimplemented.

## Strict raw JSON prerequisite (#497)

The unused `NativeJson` primitive mirrors installed Aeson 2.2.5.0's strict byte-to-Value boundary:
`bsToTokens` (`Decoding/ByteString.hs:32-126,132-166,182-273`), read into a Value as
`Decoding/Conversion.hs:48-94` does, then `eitherDecodeStrict`'s trailing-input check
(`Decoding.hs:48-53`). `parse` returns the value and where it ends, and `onlySpace` is the separate
`bsSpace` check, so a later typed decoder can run between them as the reference's `ifromJSON` does.
Every original token is validated before first-wins duplicate collapse. Refusals are closed typed
cases that render the reference's message text, including its spelling and its quoting of the
bytes from the comma when an object key is missing after one.

It returns `JsonValue`, aeson's `Value` (`Data/Aeson/Types/Internal.hs:366-372`): objects are the
ordered KeyMap in UTF-8/Text key order. Keys and strings are `JsonKey` and `JsonText`, built as
text 2.1.3's `pack` builds `Text`: an unpaired surrogate in a hand-built string becomes U+FFFD,
and the reader refuses such input before it builds one. Numbers are `Scientific` with the parsed
coefficient and signed-64 exponent, not normalized. Equality, ordering and hashing normalize them, as scientific 0.3.8.1's `Eq`, `Ord` and
`Hashable` do; `JsonValue` ordering is the derived `Ord Value` and `toString` is `Show Value`, with
`Show Scientific`'s text. Explicit frames admit outside-data nesting without a configured depth
cutoff, and equality, ordering, hashing and showing a value walk an explicit stack.

[All 356 actual native cases](../../fixtures/reference/native-json/README.md) retain the 302
original upstream JSONTestSuite parsing inputs and 54 source-derived controls, with no filename
category used as an expected-failure exemption. Full flat Value events make every accepted
primitive/order observable without deep recursive equality, and every refused case's rendered
message equals the native error text. All 356 small-stack Scala checks pass; a combined temporary
last-wins/UTF-16-order/parse-normalization fault failed 22 of them on 2026-10-27, as the corpus
README details, and exact restoration passes.
`JsonValueSuite` compares `show` and `compare` against goldens that GHC 9.10.3 printed in the
pinned reference image. JVM hash values are consistent with equality but are not hashable's
values, an owner decision recorded in [#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6452723958)
(2026-10-27).

This parser is not wired into genesis loaders. Original-file hash/parse ordering, required fields,
field decoders and multi-file failure order remain incomplete under #497. The separate permissive
json-stream mechanism for injection files must not reuse this strict grammar.
