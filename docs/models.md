# Models generated from the upstream CDDL

Status: **spike**. The generator (`modelsGen`) and the generated models with their runtime (`models`) are done and
cross-checked (golden tests against the hand-written decoders, CDDL-sampled property tests, fuzzing, the cuddle
cross-check of section 13). They are used today by tests, and the runtime (`models.runtime.{Codec, Writer, DecodeError}`)
by the Haskell ledger-state codec in `storage`; **no node path decodes blocks, headers or protocol messages with
generated code**. Adopting the generated models in the node (sections 11 and 12) has not started and is not
scheduled; the known generator gaps (section 8) are tracked as [M13](roadmap.md) and the adoption as
[M14](roadmap.md).
Question answered: can the wire, ledger and disk models of the node be generated from the upstream CDDL instead of
being written by hand? Answer: yes, for the arrays, maps, choices, tags, sizes and ranges that make up the formats
checked so far, with byte-exact decoding of real chain data. What is left is a list of named gaps (below), not an
open question of approach.

Contents: [tooling](#1-tooling-survey-and-decision) | [architecture](#2-architecture) |
[naming](#3-naming) | [leaf types](#4-leaf-types-opaque-types-and-refinements) |
[original bytes and canonical encoding](#5-original-bytes-and-canonical-encoding) | [errors](#6-typed-errors) |
[eras](#7-modelling-eras) | [coverage](#8-cddl-feature-coverage) | [hand-written](#9-what-stays-hand-written) |
[results](#10-results-on-real-data) | [rollout effort (proposal)](#11-rollout-proposal-not-scheduled-effort) | [rollout order (proposal)](#12-rollout-proposal-not-scheduled-order-and-risks) |
[cuddle cross-check](#13-cross-check-against-cuddle)

## 1. Tooling survey and decision

Versions are what the registries showed on 2026-10-14.

| Tool | Version | What it is | Fit |
|---|---|---|---|
| `cuddle` (Haskell) | 1.8.1.0 (Hackage, uploaded 2026-07-15) | A library for *generating* CDDL from a Haskell EDSL, plus a CLI that formats and validates CDDL, generates random CBOR from it and checks CBOR against it. `cardano-ledger-binary` depends on `cuddle >=1.8`; the ledger's `*.cddl` files carry the header "auto-generated using generate-cddl … do not modify". | The source of the files we read, not a Scala code generator. Useful as an external oracle: `cuddle validate-cbor` can check our fixtures against the same CDDL in CI. Needs a Haskell toolchain, so it is not part of the build. |
| `cddl` (Rust crate) | 0.10.7 (crates.io, updated 2026-08-10) | A CDDL parser, AST and validator. | A parser we cannot call from the JVM build without a native toolchain. |
| `cddl-codegen` (dcSpark, Rust) | not checked | Generates Rust/WASM structs with CBOR serialisation from CDDL; used for Cardano multi-platform libraries. | Same idea as ours, Rust output, own annotation comments; no Scala target. The closest prior art for "preserve original bytes" annotations. |
| Ruby gems `cddlc`, `cddl`, `cbor-diag` (Carsten Bormann) | not checked | Converters and a reference toolchain for CDDL and CBOR diagnostic notation. | Test helpers, not build components. |
| JVM CDDL parser | none found | One web search found none for Java, Scala or Kotlin (only Rust, Node.js and Ruby implementations, and `cddl2java`, a Java generator for the WebDriver BiDi spec, not checked). Not an exhaustive search. | n/a |

**Decision: write our own parser with cats-parse 1.1.0** (Maven Central, MIT; 1.1.0 predates this work by more than two
weeks; compatible with Scala 3.3.8, and already in the local dependency cache). Reasons:

1. The build runs in a JDK-only container. Pulling a Haskell or Rust toolchain into `sbt compile` is a much larger
   dependency than 160 lines of parser.
2. The generator needs the AST in Scala, plus project-specific semantics no reusable parser provides: `;# import` module
   pragmas, per-module overrides, generic expansion, group splicing, sockets. The parser itself was the cheap part.
3. cats-parse is already the library the plan names for this ("Maybe later: cats-parse for the CDDL generator").
4. The risk of an own parser is silent mis-parsing. It is covered by parsing every pinned upstream file (979 rules) and
   by sampling random CBOR from our resolved AST against the generated codecs (section 10).

`cuddle` is the independent conformance oracle: section 13 runs it in CI, in both directions.

## 2. Architecture

```
upstream *.cddl ──► CddlParser ──► Ast ──► Schema (resolve) ──► RT ──► Emitter ──► Scala source ──► scalac
(pinned, vendored)  cats-parse     surface  modules, imports,   typed   one file     (-Werror)
 + overrides/*.cddl                syntax   generics, groups,   tree    per module
 + models.conf                              sockets, controls
                                    │                │
                                    └──► CddlSampler ┘  CBOR candidates (tests)

hand-written runtime (rt): Codec, Writer, DecodeError, Alternatives, Maps, Lit   ◄── generated code calls it
```

- `models-gen` (`scarab-models-gen`, package `xyz.kd5ujc.scarab.models.gen`): `Ast`, `CddlParser`, `Schema` (resolver;
  `RT` is the resolved type tree), `Emitter`, `Config`, `CddlSampler`, `Main`. No dependency on the node.
- `models` (`scarab-models`): `src/main/cddl/` holds the vendored upstream files (`upstream/`, with a
  `SOURCES` file recording repository and commit of each), our local refinements (`overrides/`), the schema we wrote to
  cover features the bounded targets do not use (`features.cddl`) and the configuration (`models.conf`).
  `Compile / sourceGenerators` runs `modelsGen` through sbt's `runner` on these inputs; the result goes to
  `src_managed` (not formatted, not committed) and is compiled with the same `-Werror` as everything else. The generator
  re-runs only when an input file or the generator's classes change. `src/main/scala/.../runtime` is the hand-written
  runtime the generated code calls (about 500 lines).
- `check` covers both: `modelsGen/Test/compile`, `models/Test/compile` and their tests are in the alias.

Pinned sources (the commit of each is in `SOURCES`): ouroboros-network `2fb6982`, ouroboros-consensus `8f56bc8`,
cardano-ledger `a51efc7`. (`Handshake.scala` cites network commit `c45735a`; the CDDL used here is newer.)
One file comes from a fourth repository: the Blueprint's `leios-fetch/messages.cddl` at `0460c5a6` (consensus `main` has its own
LeiosFetch CDDL since #2416, merged 2026-10-20, not pinned: it says `tx = #6.24(bytes)`); its `base` import is the consensus module
plus `word16` (`overrides/base-word16.cddl`) and its `dijkstra` include is the era module, so the generated reader decodes each
transaction as `dijkstra.block_transaction`. The hand-written codec carries the bytes inside the tag without reading them.

**Modules and pragmas.** The ouroboros specs use `;# import <module> as <alias>` and `;# include …` to bind the prefix
(`base.point`, `v14.nodeToNodeVersionData`, `conway.header`). `models.conf` names each CDDL *module*, its Scala
subpackage and its files; later files override earlier rules of the same name. A reference `alias.rule` resolves through
the pragmas of the file it appears in. Overrides are the mechanism for "local refinements" (below).

**Generation is demand-driven.** Only the transitive closure of `root` rules is emitted, so the whole of `conway.cddl`
is parsed and resolved, but only what a root reaches is generated. Rules used once as an alternative of a choice are
folded into the enum case (`msgRollForward` is a case, not a class).

### Configuration (`models.conf`)

| Directive | Meaning |
|---|---|
| `module <name> <subpackage> <files…>` | CDDL module, its Scala package under the base package, files in override order |
| `root <module>:<rule>` | generate this rule and everything it reaches |
| `preserve <module>:<rule>` | keep the original bytes (section 5) |
| `cases <module>:<rule> N0 N1 …` | Scala names of the alternatives of a choice, in order |
| `strip <module>:<rule> p1 p2 …` | prefixes removed from derived case names (`msgRollForward` becomes `RollForward`) |
| `field <module>:<rule> <key or index> <name>` | name of an integer-keyed map entry or an anonymous array member |
| `chunks <module>:<rule> <positive limit>` | byte-string runtime refinement: bound each original definite chunk; write long values as bounded indefinite chunks |
| `lenient-maps <module>` | the generated `{ + k => v }` and `{ n*m k => v }` maps of this module accept any entry count; every other module enforces the bounds (below) |
| `rename`, `external` | Scala type name; or "not generated, a raw `Node`" (the hand-written boundary) |

A directive naming a rule that no root reaches is an error, so a typo cannot silently stop a property being declared.

**Map entry bounds.** A map whose body is one repeated entry (`{ + k => v }`, `{ n*m k => v }`) is decoded by `Codec.pairs`
with the entry count bounds, as `Codec.vec` bounds `[+ x]`, and a count outside them is a `BadArity` refusal. The CDDL alone is
not the reference, so each place this refuses what the previous generator accepted was read against cardano-ledger at the
pinned commit of `upstream/SOURCES`: Conway `withdrawals`, `mint`, `voting_procedures` and the map form of `redeemers`, and
Dijkstra's `withdrawals`, `mint`, `multiasset`, `voting_procedures`, `redeemers`, `required_top_level_guards`,
`direct_deposits`, `account_balance_intervals` and `starting_account_balance_intervals`, are refused empty by the reference
(`Conway/TxBody.hs` field guards for keys 5, 9 and 19, `Dijkstra/TxBody.hs` `decoderByKey` for keys 5, 9, 19 and 24 to 27,
`Mary/Value.hs` `decodeMultiAsset` with `decodeConway` and `decodeDijkstra`, `Conway/Governance/Procedures.hs`
`DecCBOR VotingProcedures`, `Alonzo/TxWits.hs` `DecCBOR (Annotator (RedeemersRaw era))`). Mary, Alonzo and Babbage are the
exception: below decoder version 9 the reference reads `multiasset` and `mint` with a plain `decodeMap` and prunes the empty
inner maps (`decodeWithPrunning`), so it accepts `{ policy => {} }` where the CDDL says `{ + asset_name => a }`. Those three
modules are declared `lenient-maps` in `models.conf` and keep the previous, reference-conforming behaviour (their generated
sources are unchanged). `MapBoundsSuite` pins both sides.

**Local refinements of the CDDL** live in `overrides/` as real CDDL, never as edits to the pinned files:
`network-base.cddl` instantiates the `any` slots of ChainSync (`header`, `point`, `tip`) the way Cardano does;
`bounded-bytes.cddl` replaces `bytes .size (0 .. 64)` (see section 10, finding 1).

## 3. Naming

| From CDDL | To Scala |
|---|---|
| module | package `xyz.kd5ujc.scarab.models.<subpackage>` (`conway`, `handshake.v14`, `chain.hfc`), one file per module |
| rule `header_body` | `HeaderBody` (PascalCase of the rule; `$` stripped from sockets). A rule that would shadow a name the generated code uses (`big_int` is `BigInt`) gets the suffix `Type` |
| array/map member label `block_number` | field `blockNumber`; the CDDL name is kept as the error path segment |
| unlabeled member | camelCase of the referenced rule, else `value`, `value2`; `field` directives override |
| integer or text map key | `field` directive, else the label, else the text key, else the referenced rule, else `key<N>` |
| choice alternative | the referenced rule (`strip`ped), else `cases`, else a kind name (`Integer`, `Bytes`, `Items`, `Entries`, `TagN`), else `Alt<i>` |
| anonymous array/map/choice inside a member | a type named after where it occurs (`WitnessSetRedeemers`) |
| generic `set<a>` | expanded at each use site; no class of its own |
| enum case equal to a type of the module | suffixed `Case` (`CoinCase`), because the enum's companion refers to both |

Two rules mapping to one Scala name fail the build (`NameClash`).

## 4. Leaf types: opaque types and refinements

Every *named* constrained leaf becomes its own opaque type, so a `Slot` cannot be passed where a `BlockNumber` is
expected although both are `uint .size 8`.

| CDDL | Scala |
|---|---|
| `bytes .size n`, `.size (a..b)` | `opaque type X = Bytes`, size checked |
| `uint .size 1..4`, `a..b` within `Int` | opaque `Int` or `Long`, range checked |
| `uint .size 8`, `uint` | opaque `BigInt` (see the decision below) |
| `bool`, `tstr`, `bytes` | opaque `Boolean`, `String`, `Bytes` |
| `14 / 15`, sockets `$x /= 0`, `$x /= 1` | opaque `Int`, membership checked |
| `x / nil` | `Option[X]` (an *optional* entry of that type is `Option[Option[X]]`: absent differs from null) |
| `[* x]`, `[+ x]`, `[n*m x]` | `Vector[X]`, count checked |
| `{* k => v}` | `Vector[(K, V)]` in wire order (duplicates kept) |
| `#6.n(x)`, `bytes .cbor x` | the type of `x` (tag and embedding handled by the codec) |
| `#6.258(x) / x` | the type of `x`; tag optional on input, written on output |
| `any` | `Node`: the original bytes of the item |

Every opaque type has `from(value): Either[ConstraintViolation, X]` (the checked constructor), `unsafe`, `.value`.
**Decision for the reorg:** 64-bit unsigned values are `BigInt`-backed (exact, no sign bug, like today's 512 `BigInt`
sites). A `Long`-backed alternative is a one-line choice in `Emitter.intRepr` when profiling asks for it; sizes up to
32 bits are already `Int`/`Long`. Iron-style refinements are not used: the generated opaque types already carry the
CDDL constraint, and the generator is the single place that would target Iron later.

## 5. Original bytes and canonical encoding

Cardano hashes and signs *the bytes as received*: the block hash is of the header bytes, the KES signature covers the
`header_body` bytes, the transaction id is the hash of the body bytes, the script data hash covers the redeemers and
datums as sent. A decoded value that is re-encoded can differ (indefinite lengths, non-shortest integers, tag-258 or
not, map order), so these are declared per rule:

```
preserve conway:header
preserve conway:header_body
preserve conway:transaction_body
preserve conway:transaction_witness_set
```

A preserved type is `final case class Header(headerBody: HeaderBody, bodySignature: KesSignature)(val raw: rt.Bytes)`.
`raw` is in a second parameter list, so equality and `copy` are about the *value* and `raw` is the evidence. Then:

- `decode` stores `node.original`, the exact bytes of that item (zero-copy view of the input; inside `bytes .cbor` it
  is the embedded string).
- `encode` writes `raw` verbatim, so everything that contains a preserved value (a block, a ChainSync message)
  re-encodes to the received bytes there.
- `canonical(members…)` is the explicit way to build a *new* value (forging, tests): it encodes the members
  canonically and that encoding becomes `raw`.
- The hash of a Shelley-era header is `Blake2b-256(header.raw)`; the tests check this against the hand-written decoder. (A Byron header hashes the `[subtag, header]` pair as `NtNHeader` frames it, as `Header.byronFromRaw` does; the Byron golden test checks `raw` only, not that hash.)

For structured types without `preserve`, writers use shortest integers and lengths, definite-length containers and
map entries in schema order. Opaque `any` nodes retain original bytes; configured bounded byte strings use
indefinite chunks when their total exceeds the chunk limit. This is deterministic normalization, not a promise of RFC 8949 deterministic map-key
ordering. CDDL maps describe key/value membership, not wire order ([RFC 8610 section 3.5](https://www.rfc-editor.org/rfc/rfc8610.html#section-3.5)). A valid writer may reorder
map keys; semantic equality, an encoding fixed point and independent CDDL validation are required. `preserve`
keeps key order, widths and indefinite containers byte-for-byte, including when a containing value is normalized. Known gaps (M13, section 8): `preserve` is only on arrays and maps (not on choices such as `auxiliary_data`; workaround:
preserve the alternatives or decode the item as `any`); a per-rule "indefinite-length output" property is not
modelled (the hand-written ChainSync writes `FindIntersect` points as an indefinite list, like the Haskell node; the
generated writer writes a definite list; both decode, see the test "the other ChainSync messages").

## 6. Typed errors

No `Either[String, _]` in generated or runtime code. `Decoded[A] = Either[DecodeError, A]`, with

```scala
final case class DecodeError(kind: Kind, path: List[String], offset: Int, rooted: Boolean)
enum Kind: Syntax(cause: Cbor.Error), Domain[E <: DomainError](cause: E),
           TypeMismatch, BadSize, OutOfRange, LiteralMismatch, BadArity, BadTag,
           MissingKey, DuplicateKey, UnexpectedKey, NoAlternative(causes: Vector[DecodeError]),
           DuplicateElement, Violation, Unsupported, Internal(cause: Throwable)
```

- `path` lists CDDL member names outermost first (`transaction_witness_sets/[5]/5`); only built on failure.
- `offset` is the byte offset of the offending item from the start of the innermost document: the input, or the byte
  string of an enclosing `.cbor`. It is free, since `xyz.kd5ujc.scarab.codec` nodes are zero-copy windows.
- A choice reports **all** alternatives it tried (`NoAlternative`); `deepest` picks the cause that got furthest.
- `decodeBytes` catches anything a decoder throws and returns `Kind.Internal`; the fuzz tests assert it never appears.
- `Cbor.decode` returns `Either[Cbor.Error, _]`; `Syntax(cause)` retains its semantic CBOR failure.
  Syntax errors still have offset 0 because that API does not supply a location.
- `Kind.Domain[E <: DomainError](cause)` retains a downstream service's typed cause without a
  reverse module dependency. The marker is an intent contract, not proof of a closed error family.
- The build-time generator uses closed `GenError` cases and trampolined schema resolution. Its error transport is
  independent of generated-model node adoption (M14). See [the runtime error design](design/domain-errors.md).

## 7. Modelling eras

Separate packages per era, generated from each era's own file: `shelley`, `allegra`, `mary`, `alonzo`, `babbage`,
`conway`, `dijkstra`, `byron`. Eras are not shared types with a tag, for three reasons seen in the data:

1. The CDDL itself is per era and repeats names with different shapes (`header_body` is 15 flat members in Shelley-Alonzo,
   with group rules `operational_cert` and `protocol_version` spliced in, and 10 members with nested arrays from Babbage).
2. A field that exists only from one era on then cannot exist in earlier code, which is the plan's "era-indexed types".
3. The generator is trivially parallel: one module per file, no merging logic.

The cost is duplication (Shelley, Allegra and Mary are structurally identical: about 1,400 lines each). Remedy, not built
(M13): compare the resolved tree of a rule with the previous era's and emit `type X = prev.X` when equal, which
keeps identical types identical and shrinks the output. The hard-fork envelope is generated too:
`chain.hfc.Header` is an enum `Conway(conway.Header) | Babbage(…) | … | Byron(…)`, from the consensus
`ns8<…>` generic, and `chain.Point` is `Origin | Block(slot, hash)`.

## 8. CDDL feature coverage

Supported = parsed, resolved, emitted, compiled with `-Werror` and exercised by a test.

| Feature | Status | Evidence |
|---|---|---|
| rules, `=`, `/=`, `//=` (sockets `$name`, `$$name`) | supported | `features.cddl`, byron |
| arrays, `[* x]`, `[+ x]`, `[n*m x]`, optional members before or after required members | supported | headers, blocks, `ConformanceRegressionSuite` |
| maps with integer or text keys, `?` entries, duplicates and unknown keys rejected | supported | transaction body, `Person` |
| maps `{* k => v}` and several typed `* k => v` entries | supported | multiasset, `versionTable` |
| type choice `/` and group choice `//` | supported (ordered trial, all causes reported) | `msgAcceptVersion`, certificates |
| group rules `(…)` spliced into arrays | supported | Alonzo `header_body` |
| generics `name<a, b>`, nested instantiation | supported (expanded at use) | `ns8<…>`, `set<a>` |
| `.size n`, `.size (a..b)`, `a..b`, `a...b`, unsigned `.gt`, `.ge`, `.lt`, `.le`, `.ne`, named bounds | supported | Byron named `u8` controls; finite-domain regressions |
| `.cbor`, tags `#6.n(x)`, `#6.n` | supported | `serialisedShelleyHeader`, plutus data |
| `bool`, `nil`, `any`, literals (int, text, bool), `x / nil` | supported | |
| `;# import/include` namespaces, per-module overrides | supported | |
| spliced groups containing optional-before-required members | supported | Dijkstra `pool_params`; greedy optional matching and complete-suffix checks; array headers use the actual member count |
| `.eq .regexp .and .within .cborseq .bits`, explicit float models, `~` unwrap, `&` as a type | **not yet** (M13) | Not used by the pinned roots. Floats nested in generic `any` are accepted and retained losslessly; hand-written wire decoders retain their own float policy |
| the RFC 8610 prelude (`bigint`, `encoded-cbor`, `tdate`, `uri`, …) | supported | `features:prelude_use`; found missing by the cuddle cross-check (section 13) |
| `preserve` on a choice | **not yet** (M13) | `auxiliary_data` |
| `bytes` chunking limit (`bounded_bytes`) | runtime refinement | `chunks module:rule 64` in `models.conf`; original chunk heads checked separately from CDDL, section 10 |
| indefinite-length output as a per-rule property | **not yet** (M13) | the hand-written ChainSync writes `FindIntersect` points as an indefinite list (section 5) |
| map entries in RFC 8949 deterministic key order as an option | **not yet** (M13) | Schema-order output is a documented CDDL-valid contract; `preserve` remains byte-exact (section 13) |
| era dedup (`type X = prev.X` when the resolved tree equals the previous era's) | **not yet** (M13) | output size only, section 7 |
| recursive types | supported (lazy codecs) | `metadatum`, `plutus_data`, `person` |

Whole-specification coverage, measured by generating each non-generic rule as a root of its own (`GeneratorSuite`):
**1,030 of 1,030 non-generic rules** across 21 configured modules, including all 64 Byron and 169 Dijkstra
rules. This includes the existing upstream corpus and local feature schemas; it is source-generation coverage,
not proof of runtime correctness for every value. `GeneratorSuite` requires every rule to generate. The independent
cross-check executes the 25 original formerly unsupported rules, with group rules tested in an explicit enclosing
array because a CDDL group has no standalone CBOR representation.

The sampler conservatively includes optional members whenever a required suffix follows, propagating that suffix
through groups. This avoids proposing an absent overlapping member that greedy matching would consume as part
of the suffix. Trailing optionals still vary, and explicit decoder tests retain disjoint present/absent cases.
Original-schema Cuddle controls independently validate samples of `g = (? before: uint, after: uint); r = [g, tail: uint]`
and reject the ambiguous short array. This is not an arbitrary-constructor proof: public generated case classes
can still represent `None` for that overlapping field and write an invalid short array. Restricting or validating
such manually constructed states is an open [M13/M14 adoption obligation](roadmap.md); successful decoding and
bounded corpus conformance do not discharge it.

Independent Cuddle 1.8.1 controls also distinguish inline optionals from spliced groups:
`r = [? before: uint, after: uint]` admits CBOR `8101` (`[1]`) in that validator, while
`g = (? before: uint, after: uint); r = [g, tail: uint]` refuses `820102` (`[1, 2]`).
The current generated inline decoder refuses the first input. Reading the released validator's
inline suffix fallback alongside [RFC8610's normative greedy matching](https://www.rfc-editor.org/rfc/rfc8610.html#appendix-A)
supports retaining that Scala behavior; validator acceptance alone does not establish valid CDDL.
Separately, a preserved model
can retain its original raw bytes after its public fields are copied to different values;
the checked writer does not currently detect that disagreement. These boundary discrepancies
remain under [M13/#86](https://github.com/KD5UJC-XYZ/scarab/issues/86). A fix must retain greedy
matching and validate constructed fields, including preserved bytes; a successful
byte-only round trip does not establish that field values or optional presence are preserved.

## 9. What stays hand-written

| What | Why |
|---|---|
| `xyz.kd5ujc.scarab.codec` (the byte-preserving index of items, [ADR 0013](adr/0013-cbor-documents-are-an-index-over-the-input.md)) and `Bytes` | the foundation; the generated code is a typed view over `Node` |
| The runtime (`Codec`, `Writer`, `Alternatives`, …) | about 500 lines, written once, reviewed once |
| Anything CDDL cannot say | the 64-byte chunk limit of plutus byte strings, "the lengths of `transaction_bodies` and `transaction_witness_sets` are equal", "every `transaction_index` is below the number of bodies", cross-field rules in the block comment of `conway.cddl` |
| Semantic validation | hashing, signatures, VRF, KES, ledger rules, the body hash commitment |
| Protocol state machines, agency, limits, timeouts | CDDL is only the message syntax (mini-protocol CDDL has no state) |
| Mux framing, the handshake *policy* (version selection, magic agreement) | not message syntax |
| Types with no CDDL in the pinned repositories | `tip` for ChainSync is in an override file we wrote; node-to-client query results and ledger state snapshots are partly Haskell-defined |
| Scalus bridging, our own snapshot formats | not Cardano-specified |

## 10. Results on real data

All of the following run in `models/test` (`check` includes them).

**Golden tests against the hand-written decoders** (same bytes in, field-by-field comparison):

- `fixtures/preview/*.hex`: every block of nine fixture files (Alonzo first 20, Alonzo and Babbage transactions, the
  Babbage transition, Conway, Conway governance, deeply nested datum, duplicate redeemer, empty redeemers) decodes
  through the generated `alonzo|babbage|conway.Block`. Header fields, VRF certificates (two for TPraos, one for Praos),
  operational certificate and protocol version equal `xyz.kd5ujc.scarab.ledger.Header`; the transaction bodies and witness sets keep
  the exact original bytes of `xyz.kd5ujc.scarab.ledger.Block.Transaction`; the transaction ids (Blake2b-256 of the preserved body)
  equal; the auxiliary-data positions and invalid-transaction indexes equal.
- Header hash = Blake2b-256 of `header.raw` equals `Header.hash`; `headerBody.raw` equals `bodyRaw` (the KES message).
  `encode(decode(x)) == x`, and `canonical(members)` reproduces real headers (they are canonical).
- Handshake: `ntn-accept14-preview`, `scalus-ntn-propose14`, `scalus-ntn-accept16`, `scalus-ntn-refuse` and messages
  built by the hand-written writer (versions 14, 15, 16, query reply, decode error) decode to the same versions and
  data as `Handshake.decode` and re-encode to identical bytes. Hand-written `peerSharing: Boolean` is CDDL `0..1`;
  compared through `value == 1`.
- ChainSync: `RollForward` carrying each real Conway header (the NtN envelope `[6, #6.24(bytes .cbor header)]`) and a
  real Byron header, `RollBackward`, `IntersectFound`, `IntersectNotFound`, `FindIntersect`, `Done`, with points and tips
  including Origin. The decoded header's preserved bytes equal the announced bytes and the whole message re-encodes to the
  received bytes.
- Leios (`fixtures/leios/testnet`, N3, `docs/leios.md` section 11): the 1,100 + 6 headers of the public Leios testnet
  decode through the generated `dijkstra.Header` (12-element body) field by field as `ledger.LeiosHeader` does and
  re-encode to the received bytes (the generated Conway header refuses them: the testnet sends the 12-element header in
  Conway-tagged blocks); consensus' `leiosNotify.cddl` is a module (`leios-notify`, with `header.cddl` once more under the
  name `header`, which its `;# import` wants), and every message of the hand-written LeiosNotify codec reads back as the same
  case, with `[1*16 vote]` refusing a seventeenth vote as the codec does (`LeiosNotifyCddlSuite`). The newer file is the only
  one pinned from a commit other than `SOURCES`' (see there).

**Property and fuzz tests driven by the CDDL**, not by the generated code: `CddlSampler` generates random valid CBOR from
the resolved schema. 300 items per root rule (handshake, ChainSync, six era headers, the HFC header, tip, point) decode,
re-encode to the identical bytes and decode to an equal value; 400 items each of `features.cddl` roots decode to a value
whose canonical encoding is a fixed point. For every root, 1,200 mutated (bit flip, truncation, extension, deletion) and
random-byte inputs produce a value or a typed error, and never `Kind.Internal`; offsets stay inside the input.

The fuzz corpus loads the header schema's Byron dependency and the LeiosFetch/LeiosNotify modules,
including their existing `base.word16` and header imports and the LeiosFetch `dijkstra` include from `models.conf`.
Samples are members of this configured, composed schema. Every requested sample
must be generated; unresolved names, unsupported grammar and sampling cycles fail the seed gate
instead of supplying an empty batch. A deterministic guard requires all eight header-envelope,
five LeiosFetch and eight LeiosNotify constructors across 120 samples per rule. These are schema
sampling controls, not signed admissions or proof that arbitrary generated constructors are writable;
the optional-layout construction limitation in section 8 remains open.

**Findings the exercise produced** (each would have been a latent bug in a hand-written model):

1. The CDDL's own `bounded_bytes = bytes .size (0 .. 64)` rejects real Conway governance blocks: chain data uses
   indefinite-length strings of 64-byte chunks, which a joining CBOR reader sees as one long string. The upstream comment
   says as much. `overrides/bounded-bytes.cddl` permits the joined value in CDDL; the `chunks` configuration
   additionally enforces the released decoder's per-chunk limit using original bytes. It rejects a definite 65-byte
   string and an oversized indefinite chunk, accepts a 64+1-byte chunked string, and writes long values in chunks.
   This corresponds to `decodeBoundedBytes` and `decodeBoundedBytesIndef` in
   [Plutus 1.70.0.0 `PlutusCore/Data.hs`, pinned source](https://github.com/IntersectMBO/plutus/blob/507cab8cf6192e5143f12be5b2d3005a2928dc6d/plutus-core/plutus-core/src/PlutusCore/Data.hs#L248-L265).
   Cuddle checks the CDDL approximation and refinement independently; it cannot establish the runtime chunk bound.
2. A first version of the range emitter ignored the lower bound of `positive_coin = 1 .. 2^64-1`; the feature tests caught it.
3. Alonzo's `operational_cert` and `protocol_version` are *groups* (spliced, 15 header members) but Babbage's are
   arrays (10 members): the generator takes this from the CDDL, where hand-written code carries two layouts by hand.
4. `pool_params` in the Dijkstra CDDL has an optional member in the middle of a group. Its decoder now tracks the
   actual group size and checks the complete enclosing suffix. Optional matches are greedy, as RFC 8610 Appendix A
   requires; a later failure cannot reconsider a successful match. Tests cover disjoint present/absent members,
   ambiguous-short rejection and trailing values.

**Decode throughput** (`DecodeBench`, a timed loop; no JMH so as not to add an sbt plugin; the host was busy and
shared, so read these as orders of magnitude: two runs differed by up to 5x in absolute time, the ratios by less):

| Decode of | hand-written | generated | ratio |
|---|---|---|---|
| Conway header (857 bytes on average, 30 headers); the hand-written call also computes the hash | 2.0 µs | 0.85 µs | 0.3 to 0.4 |
| Handshake propose (versions 14 to 16) | 2.0 µs | 1.0 to 1.4 µs | 0.5 to 0.7 |
| ChainSync `RollForward` with a header | 93 µs | 1.6 µs (the generated decoder also decodes the header) | 0.02 |

Reference point: `Cbor.decode` alone is 0.75 µs for a header. So the generated codec adds about 0.1 to 2 µs on top of
parsing, and is not slower than the hand-written code, which hashes in the same call and (in the ChainSync path) copies
byte vectors. No performance risk in headers; blocks were not benchmarked (the decoders are the same combinators).

## 11. Rollout proposal (not scheduled): effort

A proposal and an estimate, not a plan of record: nothing below has started, and no part of it is scheduled (M14). The
estimates were made in 2026-10, when the spike was finished, in engineer-days of focused work including tests against
real data; the per-era and per-protocol rows are independent of each other. Row 1 is tracked as M13, the rest as M14.

| Work | Days | Notes |
|---|---|---|
| Remaining generator features: `.cborseq`, enum `preserve`, per-rule indefinite output, deterministic map-key order option | not re-estimated | The earlier estimate included the now-completed unsigned comparison controls and optional-group decoder |
| Era dedup (`type X = prev.X` when the resolved tree is equal) and per-era `field`/`cases` config | 3 to 4 | about 300 config lines |
| Conway/Babbage/Alonzo/Shelley/Allegra/Mary/Byron models replace hand decoders: blocks, headers, transactions, witnesses, certificates, governance | 8 to 12 | in the node, per module, behind the existing golden tests |
| All NtN mini-protocols from their CDDL (KeepAlive, BlockFetch, TxSubmission2, PeerSharing, handshake) | 3 to 4 | the policy and state machines stay |
| NtC protocols (LocalStateQuery, LocalTxSubmission, LocalTxMonitor) incl. the query/result tables of the consensus CDDL | 5 to 7 | query results are partly `any` upstream |
| Disk formats (consensus `disk/**`: ledger state, header state, praos/tpraos state, chunk files) | 5 to 8 | telescopes, indefinite lists; replace our own snapshot codecs only if worthwhile |
| Conformance in CI with cuddle, generating and validating (section 13) | 1 to 2 | done (K10) |
| `Cbor` typed errors (needed anyway by plan section 4) | 2 | removes `Syntax(message)` |
| Docs, review, performance pass on blocks | 3 | |

Rough total if all of it were done: 35 to 50 engineer-days (the one-person duration follows from that, shorter if the
era and protocol rows are done side by side). A smaller first cut would be the node-to-node protocols plus Conway
headers and blocks replacing the hand decoders, estimated at 12 to 16 days. These figures are unmeasured guesses from
the spike, to be redone when the work is taken up.

## 12. Rollout proposal (not scheduled): order and risks

Proposed order (each step would land with the existing tests unchanged and green, the generated model proved equal
first, as the spike does). Step 1 is done; steps 2 to 5 have not started. Issue #82 (M11) carried step 1 and states
the node-to-node protocols and Conway headers and blocks as its first adoption target, but its current work is the
ledger-state mirror, not this. Step 4 overlaps M12 (#85), which plans per-era `Tx[E]`, `TxOut[E]`, `TxCert[E]` and
`Block[E]`: the generated per-era models are a possible source for those types, to be decided when that step is
taken up (M14).

1. Rename and module split (the reorg; **done**, M11). `models-spike` became `models` (`scarab-models`): `runtime` plus generated
   code, the vendored `upstream/` CDDL and `models.conf`. `models-gen` is `scarab-models-gen`. Package roots are
   `xyz.kd5ujc.scarab.models.*`.
2. NtN handshake and ChainSync payloads: replace `protocol.Handshake.Term`/`decodeData`, `ChainSync.Point`/`Tip`,
   `NtNHeader` (keep the state machines and limits). Smallest, most testable surface.
3. Headers: replace `xyz.kd5ujc.scarab.ledger.Header.fromRaw`, `Header.Shelley`/`HeaderVrf`/`OpCert` with the era headers; the
   consensus code reads `headerBody` fields through the opaque types.
4. Blocks and transactions: replace `xyz.kd5ujc.scarab.ledger.Block` and the transaction decoding in `ledger/Tx*.scala`, then the
   witness, certificate and governance types in `ledger/`. Keep `Transaction.body: Bytes` semantics by reading `.raw`.
5. Remaining mini-protocols; NtC; disk formats last.

Types replaced by generated ones: `Handshake.Term`, `Handshake.Data`, `ChainSync.UInt64`/`Point`/`Tip`/`Message`,
`NtNHeader`, `Header.*`, `OpCert`, `VrfCert`, `Block.*`, `Block.Transaction`, and later the ledger certificate, governance
and witness ADTs. Our `Era` enum stays and maps to the generated HFC enum.

Risks and answers:

- **Performance on hot paths (blocks, UTxO).** Decode is comparable to hand-written in the header benchmark. Blocks and
  the UTxO set should keep the lazy, preserved-bytes style: model only what the ledger reads, keep `Node` (`any`) for the
  rest through `external`. Re-measure on a mainnet-sized block before step 4.
- **Generated code size** (14,000 lines for six eras). Compile time was +5 to 7 s incrementally here. The era dedup and
  keeping unused eras out of `root` reduce it; generated sources are not committed.
- **Upstream churn.** Each upgrade is "copy files, update `SOURCES`, build"; a changed shape fails to compile or fails a
  golden test, instead of drifting. The `field`/`cases` names are keyed by rule names that can change: the
  unreachable-rule check reports them.
- **Spec bugs and CDDL looseness** (finding 1). The CDDL is the *documented* format, not the reference
  implementation's behaviour; overrides are explicit and reviewed, and every one cites why.
- **Ergonomics.** Generated names are long and field names come from CDDL labels. The `field` and `cases` directives are
  the lever; extension methods can add views by hand where a type is read often.
- **`-Werror` and `-Xmax-inlines`.** Not an issue (no inline derivation is generated). The explicit `Codec` objects avoid
  the `Mirror` recursion the snapshot codecs of `node` need.
- **Original bytes retention.** A preserved value pins the input buffer it was cut from, as `xyz.kd5ujc.scarab.codec.Bytes` always
  has; call `raw.compact` when storing long-term.
- **Single point of failure.** A generator bug is a bug in every model. That is why the property tests sample from the
  CDDL with an independent sampler, and why the golden tests compare with the hand-written decoders for as long as both exist.

## 13. Cross-check against cuddle

Issue K10. cuddle is upstream's own Haskell CDDL tool (the ledger's `*.cddl` files are generated with it), a second
implementation of the same schemas that shares no code with ours. The generator is a single point of failure, so CI runs
it against cuddle on every change to the vendored CDDL, the generator or the runtime, and nightly
(`.github/workflows/cuddle-crosscheck.yml`).

**Tool and image.** cuddle 1.8.1.0 (Hackage, uploaded 2026-07-15; the version `cardano-ledger-binary` needs, `>=1.8`),
built by `docker/cuddle.Dockerfile` from the Hackage source with GHC 9.10.3 and cabal-install 3.14.1.1 (the official
`haskell` image, pinned by digest) against the Hackage index as of 2026-09-20 (`--index-state`), so the dependency plan
is reproducible; the runtime image is `debian:bookworm-slim` (pinned by digest) with the one executable, `libgmp` and
Python for the worker (195 MB). The build takes about 9 minutes on 4 CPUs; CI caches the image as a tarball keyed on
the Dockerfile. Both base images are rebuilt by their maintainers, so the pinned digests are from 2026-10-05/06, less
than two weeks before the check was written; the tool versions in them (GHC, cabal, cuddle, the Hackage index) are older.
No upstream binary or image of cuddle is published, hence the build. The commands used (cuddle 1.8.1.0, checked in
`bin/Main.hs`): `cuddle format FILE`, `cuddle validate FILE` (parse and resolve), `cuddle gen [-f hex] [--seed N]
[--size N] [--no-twiddle] RULE FILE` (one random term per call; by default with indefinite lengths and non-shortest
integers, "twiddled"), `cuddle validate-cbor -f binary RULE CBOR_FILE CDDL_FILE` (the `-f` is ignored for the input in
1.8.1.0, which is always read as raw bytes). cuddle reads one self-contained file and knows no `;# import`, so the
driver writes, per target, the transitive closure of its rules from the vendored files with module-qualified names
(`conway__block`); the validation rule text is upstream's, only identifiers are renamed, and the same module and override
semantics as `models.conf` apply (`overrides/bounded-bytes.cddl` is part of the closure).

**Driver.** `scripts/cddl_crosscheck.py run` uses standard-library Python to orchestrate the Scala tool and pinned
Cuddle container. `CuddleCrossCheck` exports sampler terms and parser rule lists, decodes every Cuddle-generated
term, and writes normalized output for a second independent Cuddle validation pass.

| Direction | What is asserted |
|---|---|
| Cuddle to us | Every generated term in both ordinary and twiddled modes decodes. Re-encoding decodes to an equal value and is an encoding fixed point. Every normalized output passes independent Cuddle validation. A configured `preserve` root must reproduce every input byte in both modes. |
| Us to Cuddle | Every sampled term passes `cuddle validate-cbor` for the same rule. A negative control (`false`) must fail for each target. |
| Rule sets | Each vendored file has the same (rule name, generic arity) set in our parser and `cuddle format`. Every configured module resolves in `cuddle validate`. |
| Coverage | Every non-generic configured rule generates; no historical Scarab failure is exempted. |
| Upstream correspondence | Both definite and 64+1-byte chunked strings expose the upstream `bounded_bytes` total-size approximation; both satisfy its CDDL refinement. Runtime tests separately enforce the actual per-chunk limit. |

Targets include every configured root, era headers and envelopes, all 25 original formerly unsupported Byron and
Dijkstra rules, and focused map-preservation, float and optional-group regressions. The original upstream schemas
remain pinned and unchanged. Cuddle 1.8.1.0's generator raises `Controller not yet implemented` for `.gt` and `.ne`.
For generation only, the driver replaces these controls with mathematically equivalent ranges intersected with the
named unsigned domain. It records every replacement in `report.json`; validation always uses the original schema.
Original control expressions additionally receive positive and negative boundary witnesses, so the validator must
accept the included point and reject the lower-bound/excluded point. The generator also needs literal labelled
integer enums expanded to their exact choices; the original enum validator must accept every member and reject a
non-member. These are explicitly changed generation profiles; the validation corpus remains unchanged.

The pinned native generator can also abort without returning a term when its random map has duplicate keys and
its canonicalizer cannot handle them. Only that exact abort is resampled, with a maximum of four attempts per
requested term. Every attempted seed, abort reason and successful seed is recorded in `report.json` under
`native_generator_non_results`. Exhausting the budget or encountering another generator failure fails the run.
A returned term is never discarded or retried: every one must pass the original validator and Scarab decoder.
These native-tool non-results are separate from executed conformance cases and are not Scarab exemptions. `pool_params` and `pool_registration_cert` are group rules, so the driver appends an
explicit array wrapper referencing the original group; it does not replace the group's definition.

The original cross-check recorded 32 exceptions: 25 unsupported rules, three float decoder rejections, one map-order
byte mismatch and three upstream `bounded_bytes` correspondences. They are no longer permitted Scarab failures.
`models/cuddle-allowlist.tsv` contains no exemptions. The map-order requirement now follows the documented CDDL
contract rather than treating every writer as byte-preserving; the original Person ordering case and a matching
preserved-map case remain executable regressions. Float payloads preserve their width, NaN payload and signed zero. Undefined and unassigned simple values remain
distinct from null and retain their bytes. Both extended forms require explicit syntax-decoder opt-in; ordinary
wire defaults remain unchanged.

`target/crosscheck/report.json` records the current run's per-target counts, independent validation results and
coverage. Generation alone and a finite sample do not prove all-value conformance. Any future exemption requires a
specific evidenced JVM constraint; historical implementation gaps are not justification.

Run it locally:

```
docker build -f docker/cuddle.Dockerfile -t scarab-cuddle:1.8.1.0 docker     # once
python3 scripts/cddl_crosscheck.py run --count 40                            # uses scripts/sbtw
```

(`--sbt "docker exec NAME /work/.cache/tools/sbtd --client"` runs the Scala half in an sbt container; `--count`, `--seed`
and `--size` widen the search; `target/crosscheck/` keeps the terms, `report.json` and `results.json`.)
