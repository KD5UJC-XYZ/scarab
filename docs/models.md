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
                                    └──► CddlSampler ┘  random valid CBOR (tests)

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
One file comes from a fourth repository: the Blueprint's `leios-fetch/messages.cddl` at `fe4cceb9` (LeiosFetch has no CDDL in the
consensus or network repositories); its `base` import is the consensus module plus `word16` (`overrides/base-word16.cddl`) and its
`tx` import is a module of ours, `overrides/leios-fetch-tx.cddl` (`tx = any`: the codec keeps each transaction as its bytes).

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
| `rename`, `external` | Scala type name; or "not generated, a raw `Node`" (the hand-written boundary) |

A directive naming a rule that no root reaches is an error, so a typo cannot silently stop a property being declared.

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

Everything not marked is encoded canonically: shortest integers and lengths, definite-length containers, map entries in
rule order. Known gaps (M13, section 8): `preserve` is only on arrays and maps (not on choices such as `auxiliary_data`; workaround:
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
- The build-time generator's `GenError`/`GenException` cleanup remains M11-E; it is independent of
  generated-model node adoption (M14). See [the runtime error design](design/domain-errors.md).

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
| arrays, `[* x]`, `[+ x]`, `[n*m x]`, optional trailing `?` | supported | headers, blocks |
| maps with integer or text keys, `?` entries, duplicates and unknown keys rejected | supported | transaction body, `Person` |
| maps `{* k => v}` and several typed `* k => v` entries | supported | multiasset, `versionTable` |
| type choice `/` and group choice `//` | supported (ordered trial, all causes reported) | `msgAcceptVersion`, certificates |
| group rules `(…)` spliced into arrays | supported | Alonzo `header_body` |
| generics `name<a, b>`, nested instantiation | supported (expanded at use) | `ns8<…>`, `set<a>` |
| `.size n`, `.size (a..b)`, `a..b`, `a...b`, `.lt`, `.le`, named bounds (`max_word64`) | supported | |
| `.cbor`, tags `#6.n(x)`, `#6.n` | supported | `serialisedShelleyHeader`, plutus data |
| `bool`, `nil`, `any`, literals (int, text, bool), `x / nil` | supported | |
| `;# import/include` namespaces, per-module overrides | supported | |
| required member after an optional one (`[a, ? b, c]`) | **not yet** (M13) | Dijkstra `pool_params`; needs a backtracking array decoder (12 rules) |
| `.gt .ge .eq .ne .regexp .and .within .cborseq .bits`, floats, `~` unwrap, `&` as a type | **not yet** (M13) | 8 rules use `.gt` and 5 `.ne` (all Byron, on a named `u8`); none of the rest in the pinned files except as noted |
| the RFC 8610 prelude (`bigint`, `encoded-cbor`, `tdate`, `uri`, …) | supported | `features:prelude_use`; found missing by the cuddle cross-check (section 13) |
| `preserve` on a choice | **not yet** (M13) | `auxiliary_data` |
| `bytes` chunking limit (`bounded_bytes`) | cannot be expressed in CDDL | overridden, section 10 |
| indefinite-length output as a per-rule property | **not yet** (M13) | the hand-written ChainSync writes `FindIntersect` points as an indefinite list (section 5) |
| map entries in canonical key order as an option | **not yet** (M13) | `features:person` in the cuddle allowlist (section 13) |
| era dedup (`type X = prev.X` when the resolved tree equals the previous era's) | **not yet** (M13) | output size only, section 7 |
| recursive types | supported (lazy codecs) | `metadatum`, `plutus_data`, `person` |

Whole-specification coverage, measured by generating each non-generic rule as a root of its own (`GeneratorSuite`):
**955 of 980 rules** (97%) across the 16 modules; Shelley, Allegra, Mary, Alonzo, Babbage and Conway generate
completely, Dijkstra 157/169, Byron 51/64. The 25 failures are the 12 "required after optional" cases (Dijkstra), 8 `.gt`
and 5 `.ne` bounds (Byron); they are listed one by one in `models/cuddle-allowlist.tsv` and the cross-check fails
when the set changes.
Six whole eras' blocks compile (about 14,000 lines of generated code for headers, blocks and transactions of
Shelley to Conway, the Dijkstra header, Byron headers and the network protocols).

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

**Findings the exercise produced** (each would have been a latent bug in a hand-written model):

1. The CDDL's own `bounded_bytes = bytes .size (0 .. 64)` rejects real Conway governance blocks: chain data uses
   indefinite-length strings of 64-byte chunks, which a joining CBOR reader sees as one long string. The upstream comment
   says as much. Handled by `overrides/bounded-bytes.cddl`.
2. A first version of the range emitter ignored the lower bound of `positive_coin = 1 .. 2^64-1`; the feature tests caught it.
3. Alonzo's `operational_cert` and `protocol_version` are *groups* (spliced, 15 header members) but Babbage's are
   arrays (10 members): the generator takes this from the CDDL, where hand-written code carries two layouts by hand.
4. `pool_params` in the Dijkstra CDDL has an optional member in the middle of a group, which the current array decoder
   cannot express (listed in section 8).

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
| Generator gaps: optional-in-the-middle (backtracking array decode), `.gt/.ge/.cborseq`, enum `preserve`, per-rule indefinite output, canonical map-key order option | 5 to 7 | the only research-ish item is the array decode |
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
(`conway__block`); the rule text is upstream's, only identifiers are renamed, and the same module and override
semantics as `models.conf` apply (`overrides/bounded-bytes.cddl` is part of the closure).

**Driver.** `scripts/cddl_crosscheck.py run` (standard library, like `devnet.py`) orchestrates, because neither tool can
host the other: cuddle only exists in Docker and the generated codecs only on the JVM. It calls the Scala half
(`CuddleCrossCheck` in `models/src/test`, run through `sbt models/Test/runMain`) to export our sampler's
terms and our parser's rule lists, runs cuddle in one container with a parallel worker (the same script, `worker`
subcommand), calls the Scala half again to decode and re-encode what cuddle produced, and compares everything with
`models/cuddle-allowlist.tsv`. The whole run takes about 25 seconds warm.

| Direction | What is asserted |
|---|---|
| a. cuddle to us | N terms per target from `cuddle gen --no-twiddle` decode with the generated codec and re-encode byte for byte; N terms with indefinite lengths and non-shortest integers decode, and their re-encoding is a stable value (decode of it is equal, its encoding a fixed point) |
| b. us to cuddle | N terms per target from `CddlSampler` pass `cuddle validate-cbor` for the same rule; a control (`false` against every rule) must fail, so the validator is known to discriminate |
| c. rule sets | for every vendored file, the set of (rule name, generic arity) of `cuddle format` equals the one of our parser; every module (all rules, not only the roots) resolves in `cuddle validate` |
| d. coverage | the set of rules our generator cannot produce on their own equals the allowlist |

Targets: every `root` of `models.conf` (14, `models.conf` and the target list must agree or the export fails) plus the
generated types the sampled tests exercise (headers of Shelley to Dijkstra, the hard-fork envelope, point and tip), 23
in all.

**Result** (N = 40, seed 1, cuddle size 12; the same on a re-run, since every seed is fixed):

| Target | cuddle to us (byte-exact) | twiddled (non-canonical inputs) | us to cuddle |
|---|---|---|---|
| handshake v14, ChainSync messages | 40/40 (40), 40/40 (40) | 40/40 (37), 40/40 (32) | 40/40, 40/40 |
| `conway` header, block, transaction | 40/40 (40) each | 40/40 (0 for the preserved header, 38, 29) | 40/40 each |
| `shelley`, `allegra`, `mary`, `alonzo`, `babbage` blocks | 40/40 (40) each | 40/40 (38) each | 40/40 each |
| `shelley`..`babbage`, `dijkstra` headers | 40/40 (40) each | 40/40 (0: preserved, rewritten verbatim) | 40/40 each |
| `features` transaction, key_descriptor, prelude_use | 40/40 (40) each | 40/40 (40, 29, 37) | 40/40 each |
| `features:person` | 40/40 (0, listed below) | 40/40 (40) | 40/40 |
| hard-fork header (`chainsync-header`) | 37/40 (37, 3 hold floats, listed below) | 37/40 (32) | 40/40 |
| `leios-notify` messages (the announcement holds that header) | 39/40 (39, 1 holds a float, listed below) | 39/40 (29) | 40/40 |
| `leios-fetch` messages (the Blueprint's file; transactions are `any`) | 33/40 (33, 7 hold a float, listed below) | 33/40 (26) | 40/40 |
| `network.base:tip`, `base:point` | 40/40 (40) each | 40/40 (29, 24) | 40/40 each |

The rule sets agree on all 27 files (1,108 rule name and arity pairs); cuddle resolves all 16 modules completely.

**Allowlist** (`models/cuddle-allowlist.tsv`; each entry is asserted, an entry that no longer holds fails the run
as stale, a divergence that is not listed fails it as new):

| Entry | Why |
|---|---|
| `override` alonzo, babbage, conway `bounded_bytes` | Upstream `bytes .size (0 .. 64)` is stricter than the chain (64-byte chunks of an indefinite string, finding 1). The driver sends a 65-byte string through cuddle with the upstream rule (must be rejected) and with the override (must be accepted) |
| `decode-float` `chainsync-header:header` | cuddle fills `any` (Byron `attributes = {* any => any}`) with floats; `xyz.kd5ujc.scarab.codec` reads floats only when `Cbor.Limits.allowFloats` is set (off for every wire decoder; the ledger-state codec of `storage` sets it), so such a term is rejected here. Cardano data contains none |
| `decode-float` `leios-notify:leiosNotifyMessage` | The same floats, reached through the header an announcement carries (`leios-notify` imports `header.cddl`) |
| `decode-float` `leios-fetch:leiosBlockFetchMessage` | `tx = any` (`overrides/leios-fetch-tx.cddl`): cuddle fills `any` with floats |
| `not-byte-exact` `features:person` | cuddle writes map entries sorted by key, the generated writer in rule order (the canonical-order option of section 5 is a listed gap); the value is equal |
| `unsupported` x25 | 12 Dijkstra rules with a required member after an optional one, 8 Byron `.gt` and 5 `.ne` bounds on a named `u8` (section 8) |

**Found and fixed.** The cross-check found one generator bug: the RFC 8610 prelude (appendix D) was missing, so Byron's
`bigint` and `encoded-cbor` (which `byron.cddl` uses without defining) did not resolve although cuddle's postlude
defines them. `Schema` now knows `biguint`, `bignint`, `bigint`, `integer`, `unsigned`, `tdate`, `encoded-cbor`, `uri`,
`b64url`, `b64legacy`, `regexp`, `mime-message`, `eb64url`, `eb64legacy`, `eb16` and `cbor-any`. Regression tests: the
`GeneratorSuite` prelude test and `features:prelude_use` (a root of `features.cddl`, sampled and cross-checked). The
other divergences are properties of the tools, listed above; none was an error in the generated decoders or in the
parser.

Run it locally:

```
docker build -f docker/cuddle.Dockerfile -t scarab-cuddle:1.8.1.0 docker     # once
python3 scripts/cddl_crosscheck.py run --count 40                            # uses scripts/sbtw
```

(`--sbt "docker exec NAME /work/.cache/tools/sbtd --client"` runs the Scala half in an sbt container; `--count`, `--seed`
and `--size` widen the search; `target/crosscheck/` keeps the terms, `report.json` and `results.json`.)
