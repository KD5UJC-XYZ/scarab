<!-- SPDX-License-Identifier: Apache-2.0 -->
# Streaming injection JSON reader

These 73 synthetic cases execute the unchanged `objectItems value` parser from json-stream
0.4.6.1, the dependency used by cardano-ledger f649f975 `Shelley/Genesis.hs:903-925`.
They retain every original input byte and requested chunk size. The corpus is gzip-compressed
without timestamps; decompression reveals ordinary JSON lines with `inputHex` and native traces.

The Scala gate compares all yielded keys and complete native Value events in encounter order, parse verdicts, demanded
chunk lengths and remaining bytes. Native diagnostic text is retained; Scala uses closed typed
errors, so this is not an identical-message claim. Native bounded traces that remain `NeedData`
after three empty feeds are non-results, not refusals or successful completed injections.
No input is removed for that behavior.

Nested objects preserve native ordered KeyMap traversal (UTF-8/Text ordering, including
supplementary characters), and every number retains its Scientific coefficient and signed-64
exponent without normalization. Native serialized `pairs` are also retained and replayed exactly.
They are not reparsed as a Scala Value oracle: native coefficient `120`, exponent
`9223372036854775806` renders as `1.2e-9223372036854775808` because formatter exponent arithmetic
overflows. Direct events observe the consumed Value for every case uniformly; this is not a
native JSON-rendering parity claim.

Cases include outer duplicates, nested first-wins duplicates, trailing bytes, ignored roots,
chunk-sensitive large-key skipping, malformed UTF-8/escapes, split strings/numbers, the C numeric
fast path, wrapped Scientific exponents, and the partial-number size check. The independent
ordering test stops at an entry before a later syntax failure; a small-stack test reads 20,000
nested arrays. `injection-json` fuzzing checks crashes and termination of individual pull steps;
it is not differential native fuzzing.

[The portable oracle](../../../scripts/oracle/injection-json/README.md) replays the complete native
traces. [Provenance](provenance.json) records source/archive/image identities. This reader is the
first isolated mechanism for [#499](https://github.com/KD5UJC-XYZ/scarab/issues/499). Hashing, effectful
file reads, domain-key/value decoding and the five genesis-source integration remain unimplemented.
It is not the strict Aeson raw-genesis JSON reader required by #497.
