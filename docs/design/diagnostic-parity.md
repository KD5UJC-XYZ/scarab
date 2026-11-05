<!-- SPDX-License-Identifier: Apache-2.0 -->
# Complete diagnostic parity

**Status: Plan; remaining implementation and proof obligations.** Owner direction: 2026-11-04.
Tracking: [I4a #796](https://github.com/KD5UJC-XYZ/scarab/issues/796).
The authoritative policy is the [ADR 0001 amendment](../adr/0001-haskell-node-as-reference.md#amendment-2026-11-04-exact-diagnostic-and-refusal-parity).
This plan implements that decision; it does not establish complete conformance.

## Scope and inventory

Start with a tree-wide inventory of mirrored error presentation, rather than assuming the known
Plutus gaps are the only gaps. Cover ledger admission, local-client rejection, network/handshake,
consensus, import/storage and corresponding CLI/debug interfaces wherever a pinned native counterpart
exists. Record existing explicit owner-approved deviations by their decision links. Scarab-only
service defects have no fabricated native rejection counterpart. A source/API-only observation does
not establish node-wire exposure.

Use one machine-readable constructor and presentation inventory, referenced by suites and the coverage
register. Each row records pinned native source/line, constructor, Scala representation, public
boundary, era/language/protocol/configuration, original input identity, native observation and complete
output identity, test, status and any blocking issue. Distinguish not exposed (with proof), exposed and
verified, exposed but unimplemented, missing capture, and variable output requiring owner resolution.
No unsupported category disappears through a default branch or provider-message substitution.

For Plutus, expand all five native `EvaluationError` families: `CekError`, `DeBruijnError`, `CodecError`,
`CostModelParameterMismatch` and `InvalidReturnValue`. Expand every nested user/machine/builtin,
unlifting, force/application, case and budget error; inventory terms, constants and universe types
as well as outer predicate failures and context-collection errors. Reconcile structured-encoding
prerequisites under [#123](https://github.com/KD5UJC-XYZ/scarab/issues/123) against the code.

## Stage 1: land the shared foundation

PR [#782](https://github.com/KD5UJC-XYZ/scarab/pull/782) consolidates GHC string/character/byte escaping
into the codec module. JSON, injection, network and context presentation reuse it. Keep UPLC
`PrettyConst` quoting distinct where upstream specifies a different presentation.

Prepare potentially failing refusal encoders once, returning a closed typed presentation error before
a writer can run. Preserve unsupported-era `None` separately. On a real presentation defect,
`LocalSubmission` retains the typed cause and original exception, sends no fabricated `MsgRejectTx`
and closes that connection under ADR 0017; a fresh client must still work. This defect path does not
turn known unimplemented diagnostic kinds into supported output.

Retain the original native fixtures and compare whole outputs unconditionally. Native JSON/character
and byte controls cover all byte values, escape terminators, SO followed by H, quotes/backslashes,
Unicode supplementary characters and boundaries. The finite context/explicit-error corpus is a
foundation. General error formatting and the current document printer's restricted fitting remain
incomplete until later stages.

This source landing also synchronizes policy, roadmap and compatibility references. Preserve dated
historical receipts without upgrading their measured scope. A fresh independent review checks code
and policy claims together before readiness.

## Stage 2: prove exposure, repeatability and diagnostic capture

Before implementing a category, establish its actual native output and its prerequisites. In
particular, prove source-defined Pretty versus Show, `layoutPretty` versus `layoutSmart`, precedence,
binder/index naming, exact budgets at failure and machine environment/discharge semantics. Do not
infer the failing term by reading undocumented mutable VM state after an exception.

A concrete API counterexample needs an exposure proof first. At Plutus revision
`507cab8cf6192e5143f12be5b2d3005a2928dc6d`, Flat decoder state includes pointers in derived Show:
[`Types.hs`, lines 102–124](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-core/flat/src/PlutusCore/Flat/Decoder/Types.hs#L102-L124).
[`SerialiseViaFlat.hs`, lines 27–32](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-core/plutus-core/src/Codec/Extras/SerialiseViaFlat.hs#L27-L32)
passes `show` into a decoder failure. On 2026-11-04, the accepted GHC 9.10.3 costing API observer,
image `sha256:01961cacb7812313680b6dd5641ddf7ad77c2159d3492d5a7d3b3887274f092b`,
received four identical V1/PV5 requests containing serialized script `4100` in one process.
All four failed with `NotEnoughSpace`; their complete results differed in pointer values.
This demonstrates allocation-history-dependent `deserialiseScript` / `ScriptDecodeError` text
at decoding; it proves neither evaluation-time `CodecError` exposure nor official-node wire variability.
The harness SHA-256 was
`88b5200a4afc9f3fb8fc922e8974383e612d43d566c0f359b48a5e548016127a`;
the four-request input stream SHA-256 was
`5e55c5483a6e078396b236ba1772084518e45b20586c6ee47f920761b78fd928`.
To reproduce, read the first row of `fixtures/reference/plutus-costing/models.jsonl` and emit four
copies of a JSON object with fields in this order: `id` = `"same-request"`, `language` = `1`,
`protocol` = `5`, `costModel` = that row's `costModel`, `script` = `"4100"`. Encode each with Python's
`json.dumps(value, separators=(",", ":"))` followed by a newline. Pass the stream to
`docker run --rm -i --network none <recorded-image>` without arguments; the observer defaults both
CPU and memory budgets to `2^60`. Raw run artifacts are not published.

Submit the reproducer through official-node admission at each applicable historical profile; determine
whether an earlier rejection prevents exposure. Check peer block application and any supported debug
boundary separately. Repeat identical requests within a process with controlled preceding inputs and
across fresh processes; independent fresh processes alone can reproduce the same allocation sequence.
Record complete raw bytes and CLI text, compiler/package identities and Unicode controls. API rows
for variable strings are nondeterminism evidence, never goldens. If a required native surface exposes
variable text, #796 stays blocked on a precise owner resolution; do not mask or canonicalize it.
Other deterministic categories can proceed independently.

## Stage 3: establish the native oracle before each printer

Generate an independent observation for each inventory category before its Scala renderer. Bind
source revisions, frozen package plan, compiler, executable, immutable image, original inputs and
complete outputs. Verify source/binary identities. Keep actual signed native submissions and complete
rejection/CLI bytes distinct from API-derived internal, runtime-only and pure layout controls.
Original captures remain immutable; fixture absence is incomplete coverage.

Cover every registered legal language/protocol profile, historical era embeddings, both validity
flags, failure positions, multiple ordered failures, quiet ledger output and the separate verbose
API/debug presentation. The registry is authoritative for supported profiles. New languages,
protocols or native constructors force an inventory and pin review; they cannot inherit a finite
old corpus's pass. Repeated native observations establish stability where a golden is used.

## Stage 4: preserve typed causes and complete the printers

Retain lossless typed diagnostics through `EvalResult`, `Phase2.Failure` and `PlutusFailure`:
reference budget/slippage values, failing term and discharge environment, wide indices and typed
expected/actual universes. Keep the original provider cause for defect provenance. A diagnostic
presentation input must not offer generic provider text as a rendering alternative.

If Scalus needs a hook, use a minimal pinned patch with an immutable identity, checksums and member
controls. Prove equivalence to the default evaluator for verdicts, failure order, spent units and
serial/prepared behavior, including refused/held/NoVerdict routing. A real semantics or routing change
is separately classified and tested; it must not hide in presentation work.

Complete immutable document layout with native `layoutPretty` and `layoutSmart` selection semantics,
then typed UPLC terms, constants, universe/types and every derived error/context/outer failure and
CLI printer. Remove provider-message fallbacks as each category is completed. Use explicit stacks,
trampolines or library folds for input-controlled depth. Native controls cover width boundaries,
empty/multiline forms, negative numbers/budgets, Unicode, wide indices and deep/wide inputs.

## Stage 5: complete recordings and enforce coverage

Finish [#434](https://github.com/KD5UJC-XYZ/scarab/issues/434)'s missing complete raw reasons for every
named refused v1-context/plutus-boundaries case. Use original signed inputs/state where reproducible;
otherwise identify a new signed recording, provenance and case mapping. Never attach an API-generated
reason to an old capture as though it were original wire evidence.

Require every accumulated failure in reference order, complete bytes and intended CLI output, all
declared era/family coverage and usable offline/live runners under
[#401](https://github.com/KD5UJC-XYZ/scarab/issues/401). Finish distinct accumulation obligations under
[#412](https://github.com/KD5UJC-XYZ/scarab/issues/412); fixing prose cannot close an ordering gap.

Enforce exhaustive closed-ADT matches, pinned constructor inventory versus native goldens, generated
term/constant/property controls and scoped guards against generic provider-text fallback in native
refusal presentation. Numeric formatting itself is legitimate. No masks, expected failures,
cancellations or tolerances count as conformance. Each staged PR declares its categories; the general
coverage entry stays incomplete until every required row and recording passes.

## Closure and validation

| Item | Closure condition |
|---|---|
| #781 | Recorded explicit-error mismatch and all original PR782 comparisons pass on merged main, with original inputs preserved. |
| #491 | Its two original Babbage/Conway cases compare whole bytes/text on merged main, and all folded #208/general scope is transferred to #796 first. |
| #796 | Complete subsystem/constructor/boundary inventory, resolved exposure/repeatability blockers, lossless diagnostics, full printers, complete captures and strict comparisons; final review/gates and main CI pass. |
| #434 | Every named refused case has a complete provenance-bound raw reason and unconditional whole-item comparison. |
| #412 | Entire maintained corpus, including original cases, passes complete accumulated failure lists and ordering plus diagnostic parity. |
| #401 | Required supported era/family coverage and offline/live runners are strict and complete. |
| #208 | Historical closed record; its wording waiver is explicitly superseded and linked to #796. |
| #294 / #474 | Correct diagnostic-policy references without closing unrelated audit scope. |
| #123 / #386 | Keep independent structured-encoding/typed-state obligations; printer work alone cannot close them. |

Follow [CONTRIBUTING](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) for each stage's
level and gates. Presentation/cause plumbing runs full preflight and ntc; reassess the level if rules
or fault routing change. Rebase, recheck factual claims and obtain fresh independent Claude review of
the final head before readiness. Record source/input/command/version/result evidence, then clean exact
owned generated outputs and containers under AGENTS.md. Missing implementation or evidence keeps the
corresponding entry incomplete.
