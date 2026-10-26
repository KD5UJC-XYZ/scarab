<!-- SPDX-License-Identifier: Apache-2.0 -->
# Pure injection fold observations

These 57 original cases call unmodified `foldInjectionData` at cardano-ledger f649f975,
`Shelley/Genesis.hs:874-937`. Every raw input, original request and original native output line
is retained. A recapture adds complete `resultEvents` directly from the returned native Value;
the observer preserves object order and Scientific coefficient/exponent without normalization.
The gzip has no timestamp. [Provenance](provenance.json) pins its identities and scope.

The pure Scala fold compares 50 completed inner outcomes: returned accumulators, parse/domain
refusal categories and expected/actual hashes. Read demand and returned chunk lengths are checked.
All six native controlled timeouts remain **non-results**; bounded Scala observations require
`NeedEof`, without introducing EOF refusal, termination or another file read. The native
`missing-file` refusal remains present but outside the pure implementation: Scala only exposes
its first demand. Neither a passing test for that pending boundary nor reproducing the native
observation establishes filesystem conformance.

Cases cover duplicate entries, ignored roots, short reads, chunk-sensitive large keys, trailing
bytes inside/beyond the demanded chunk, malformed values, and parsing versus hash precedence.
Typed test callbacks cover the original Integer examples; they do not implement or claim general
Aeson Integer/genesis decoding. Unit controls exercise key-before-value and fold-before-next-entry
ordering, immutable hash branching and 20,000-entry small-stack traversal.

The [portable oracle](../../../scripts/oracle/injection-fold/README.md) reproduces every complete
native output line, including retained diagnostics. Scala uses typed error causes and does not
claim matching native error text. File opening/closing, asynchronous cancellation, OS paths and
the five genesis sources remain unimplemented under [#499](https://github.com/KD5UJC-XYZ/scarab/issues/499).
