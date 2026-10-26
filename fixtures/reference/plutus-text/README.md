# Native scalar text and literal parsing

All 26 observations come from the unmodified pinned native libraries using
[`Main.hs`](../../../scripts/oracle/plutus/text/Main.hs); the [manifest](manifest.json) records
source/image/package identities, the exact harness hash and every output hash.

The 12 `pack` rows record input Unicode codepoints, normalized output codepoints, all UTF-8 bytes,
stored byte length and the actual `TextCostedByByteLength` measure. The five `decode` rows record strict
UTF-8 acceptance and complete decoded values. The nine `parse` rows record `conText` acceptance and
complete values, including supplementary escapes, separate surrogate escapes, empty escapes and
out-of-range/non-native syntax.

A Java string cannot distinguish two separately supplied surrogate codepoints from a valid UTF-16
pair after concatenation. The `pack` replay therefore supplies each scalar as its own numeric UPLC
escape, preserving the native input sequence until normalization. Separate constructor controls
cover raw JVM strings, including valid pairs and unpaired surrogates. Every native row is asserted;
there are no expected failures or field masks.

These observations cover the actual textual conformance ingress and immutable text representation.
Production `FlatScan` already rejects malformed UTF-8. They do not claim agreement for the dependency's
generic Flat decoder or exposed character-level parser helpers. Native CEK settlement and lazy costing
remain [#493](https://github.com/KD5UJC-XYZ/scarab/pull/493)'s separate requirements.
