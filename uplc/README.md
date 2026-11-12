# uplc: the core model of Scarab's own evaluator

Scarab's second Plutus evaluator, with no Scalus on its class path ([ADR 0027](../docs/adr/0027-own-uplc-evaluator-as-a-correctness-backend.md)).
This file records the core model the decoder, parser, machine, builtins and costing build on. Every type
transcribes plutus 1.70.0.0 (`507cab8c`); the Scaladoc of each cites the upstream file and line.

## The model

| Type | Reference | Notes |
|---|---|---|
| `DefaultUni` | `DefaultUni` at kind `*` (`Default/Universe.hs`) | `List`, `Pair`, `Array` are the applied operators; `size` is `defaultUniSize` |
| `Constant` | `Some (ValueOf DefaultUni)` | `typeOf` in constant time; lists and arrays carry their element type and are well typed by construction (`List.of`, `cons`, `tail`, `drop`); equal when types and values are |
| `Text` | `Text` | Unicode scalar values (surrogates become U+FFFD); `codePoints` and `utf8Length` cached for the costing |
| `Value` | `PlutusCore/Value.hs` | `buildWith` is `buildValueWith`, `decoded` the Flat/CBOR order, `pack` the normalising constructor; caches `totalSize`, `maxInnerSize`, `negativeAmounts` |
| `bls.G1Element`, `G2Element`, `MlResult` | BLS12-381 constants | opaque over bytes; the builtins own and may refine them |
| `DefaultFun` | `DefaultFun` (`Default/Builtins.hs`) | declaration order; `flatTag`, `name`, `signature` (`forces`, `arity`) |
| `Signature` | the `TypeScheme` of `makeBuiltinMeaning` | forces first; `Parameter.Opaque` (any value) or `Parameter.Constant` (monomorphic, or `SomeConstant` when it has variables) |
| `TermF[+R]`, `Term = Fix[TermF]`, `Program` | `UntypedPlutusCore/Core/Type.hs` | de Bruijn only; `Traverse` visits subterms in Flat order |
| `Word64`, `DeBruijn`, `ProgramVersion`, `ProtocolMajor` | `Word64`, `DeBruijn`, `Version`, `MajorProtocolVersion` | `ProgramVersion` components are unbounded naturals |
| `SemanticsVariant` | `BuiltinSemanticsVariant DefaultFun` | `of(language, protocol)` and `ofContext(language)` from the V1 to V3 evaluation contexts |
| `Availability` | `Common/Versions.hs`, `SerialisedScript.hs` | batches, program versions, `maxBounds`, and the decoder's refusals as `Refusal` |
| `Schemes` | | `cata`, `ana`, `hylo`, `cataM`, `anaM`, all from one `tailRecM` machine, `hyloM` |

## Design choices

- **Stack safety.** A type, a constant and a term are as deep as the script that holds them, so
  `DefaultUni`, `Constant` and `Fix` compare, hash and render through `codec.Tree`. `Fix` keeps its
  functor's `Traverse` for that. `hyloM` keeps the open layers on a heap list and steps through
  `tailRecM`, so it runs in constant stack for any lawful monad, `StateT` over `Either` included. Its
  effects come in the order of the plain recursion: the coalgebra in pre-order and the algebra in
  post-order, left to right in `Traverse` order. `SchemesSuite` checks a term two million levels deep
  on a 256 KiB stack. A Flat decoder cannot unfold `TermF` itself: the fields of `constr` and the
  branches of `case` are a Flat list whose length is known only once it is read, so it unfolds its own
  functor (a layer per list cell) and folds that into a `Term`.
- **Signatures at universe level.** The Haskell types that refine an argument for one variant
  (`Int`, `Word8`, `CInteger`, `TextCostedByByteLength`, `ValueTotalSize`, ...) bound unlifting or
  choose a costing measure. They are left to the builtins and the costing, since no variant of
  1.70.0.0 changes a scheme. Whether a builtin can fail is also per variant and is not recorded.
- **Two version gates.** The Flat decoder admits `constr` and `case` at `version >= 1.1.0`
  (`ProgramVersion.allowsConstrAndCase`). The ledger requires the version to be in a set
  (`Availability.programVersions`). Both are exposed.
- **Language of evaluation.** `Availability.builtins`, `programVersions` and `SemanticsVariant.of`
  take the script's language and use `PlutusLanguage.evaluatedAs`, because the node of 1.70.0.0
  decodes and evaluates V4 through plutus's V3 functions. Plutus's own V4 rows are transcribed and
  equal V3's from protocol 12. `languageIntroducedIn(V4)` is 12.
- **Variants F and G** exist for 1.71.0.0 (phase 2). At 1.70.0.0, `of` returns D or E at protocol 12
  and later. `DefaultFun` and its tags are the same in both releases.
- **Not here.** The universe's Flat tags and the kinded form of a type (the decoder), names and
  alpha-equality (the parser), the caser builtins (the machine), and unlifting bounds (the builtins).

## The contract between the machine, the builtins and the costing

Packages `cost` and `builtin` (the lead's, frozen with the model):

- `cost.SatInt` and `cost.ExBudget` are `Data.SatInt` and `ExBudget`: saturating 64-bit arithmetic, a budget
  combined component-wise, `times` as `stimes`. `CostStream` is `ExBudgetStream` (each element spent and
  checked in order), `StepKind` and `MachineCosts` the machine's step and start-up costs.
- `builtin.Builtins.prepare` is a saturated application in the reference's order (`toMonoF`,
  `Builtin/Meaning.hs`; `evalBuiltinApp`, `Cek/Internal.hs`): `Unlifting` checks every argument, first
  failure winning, and a failure costs nothing (`CostStream.free`); otherwise `Costing` computes the cost from
  the unlifted arguments, and the `Denotation` runs only when the machine has spent it (`Prepared.result`).
- Builtins are written once for any machine through `MachineValue[V]` (is this value a constant, make one).
  `Argument.Opaque` passes a machine value through (`ifThenElse`, `trace`, `chooseList`).
- Failures are typed: `UnliftingFailure` (structural or operational, with a cause) and `EvaluationFailure`,
  whose cases render the reference's log message. Builtins and unlifting add cases to these enums; they do
  not change existing ones.
- Owners: the unlifting (all builtins, all variants' bounds) and the core denotations are WP5's, the
  cryptographic, bitwise, `Value` and array denotations WP6's, `Costing` and `MachineCosts` from the cost
  model WP7's, the machine WP4's.

## Facts the tests pin

- The builtins with no evaluation case under `builtin/semantics` in the vendored corpus of either
  release are `serialiseData` and batch 7 (`multiIndexArray`, `policies`, `assetCount`,
  `keepPolicies`, `dropPolicies`).
- In 1.70.0.0 the cost models have no entries for `keepPolicies` and `dropPolicies`, which use
  `unimplementedCostingFun`.
- The tests read `plutus-reference` from `plutus`'s test resources without depending on `plutus`
  (`build.sbt`). `ReferenceCorpus` repeats the view rule of `PlutusReference`.
