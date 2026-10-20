# The typed codec derivation, as built (#205)

**Status:** As built; representative measurement and restart evidence recorded below.

The persisted and Haskell-compatible codecs retain field and variant types through their
operations. This replaces the earlier ClassTag field-recovery prototype, whose source-cast
removal alone did not establish generic or phantom-role relationships.

## The implementation

`ProductFields.named[A, T]` generates an ordinary `a.field` accessor at compile time. The compiler
checks that the selected field's complete type is a subtype of T before the expression can become
`A => T`. `asExprOf[T]` validates a compiler expression; it does not emit an unchecked value cast.
A projection from `Vector[Int]` to `Vector[String]`, or between distinct `KeyHash` roles, fails
compilation. Positive controls compile with the matching types, so the rejection tests do not
pass merely because their local declarations are ill-formed.

The product chain is `Fields[A, Ts, Labels]`: its owner, field types and compiler-provided labels
remain connected. Each head pairs `A => T` with `Codec[T]`; decoding returns T in the typed tuple
consumed by the same product mirror. No product value is recovered from `productElement`/Any,
and encoding does not construct a transient product tuple. The accessors and chain are assembled
when the codec is built, not once per encoded record.

The sum chain retains `T <: A` for each variant and uses compiler-synthesized `TypeTest[A, T]`
relative to that known root. The case's codec returns T, which widens to A by the subtype bound;
there is no runtime ClassTag recovery of the decoded root. The input already has root type A,
so case selection retains the root's generic relationships. Generic sum/product tests compile
without `ClassTag[A]`; the field projection tests also cover phantom roles. Compiler-produced
lawful product/sum mirrors remain a trusted assumption, as for ordinary mirror derivation.

`Record.of[A]` retains the tuple of field codecs at `Tuple.Map[m.MirroredElemTypes, Codec]`.
Its generated accessors select those same field types directly from A. The typed last-two-fields
case decodes directly to a pair, avoiding an intermediate one-element tuple; larger records
prepend their typed heads in declaration order. First-error paths and constraint checks retain
the original labels and order. `Record.of`/`auto` and general automatic derivation require
`A <: Product`; all existing call sites compile. `withOrigin[A]` needs its value codec and no
longer requires a ClassTag for A.

## Preserved format and semantics

- No format version, field order, variant ordinal, shape/fingerprint or wire layout changes.
  V5 stays current, and no ADR0006 migration is introduced.
- Format/fingerprint goldens, generic migration tests, Haskell format comparisons and snapshot
  round trips remain intact. Maintained native fixtures and the representative legacy-derived
  corpus use V5; the original private V4 archives remain unchanged and retired readers stay retired.
- `CodecDerivationSuite` covers nested generic values and sums, phantom-role values, meaningful
  positive/negative compiler probes, and first/nested field error paths.
- Both representative snapshots produce identical complete encoded bytes, decoded states and
  truncated-input failures under the old and new implementations. This is bounded fixture evidence,
  not a claim about every possible snapshot or operational database.

`SnapshotV5.encoded` is package-visible for the test-side benchmark so it can measure the real
pure encoder on one thread; its body and format are unchanged. Both comparison conditions use
that same visibility-only measurement seam.

## Measurement and gates

[Performance section32](../performance.md#32-typed-snapshot-codec-derivation-205) records sources,
input/config/runtime hashes, commands, all run medians/ranges/allocations and limitations.
The primary current/legacy-derived roughly51 MB snapshot encode/decode operations pass the
issue's +10% limit: even the slowest candidate run median is within +2.63% of the fastest old
one. Complete bytes/state/refusal fingerprints match. No primary-workload waiver is needed.

The tiny real-record encode control still costs about6.1 ns/call (+17.72%) while allocating5.26%
less; its decode control improves14.05%. This remaining encode cost is explicitly tracked by
[#257](https://github.com/KD5UJC-XYZ/scarab/issues/257), not called neutral or hidden by the larger
workload. The measurements establish the combined implementation, not the causal cost of one
ClassTag, tuple or GC operation. Historical reports incorrectly said decode hashing had been
removed, called +25…+58% decode medians neutral, and described +6.25% allocation as within2%;
the current report corrects those statements and keeps the historical scope separate.

At `ab85c6a7`, storage `restart` passes with0.6/0.6 s stops, two V5 shutdown snapshots and a
successful restoration, zero errors. Focused format/migration/snapshot/type checks pass46/46;
corrected compiler controls/nested paths pass4/4. Extended decoder fuzz passes30.8 million cases
across77 targets. The final-head Docker check and exact-head CI are recorded on PR #247;
[compatibility evidence](../compatibility.md#evidence-log) carries date, commands, image identities
and the restart manifest. Readiness on dev does not complete #205 before main landing and green CI.
