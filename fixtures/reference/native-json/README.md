<!-- SPDX-License-Identifier: Apache-2.0 -->
# Strict native JSON Values

The 350 original-byte cases call unmodified Aeson 2.2.5.0 `eitherDecodeStrict` in the pinned
reference image: 142 accepted and 208 refused. Every one of the 302 JSONTestSuite parsing inputs
in that exact package archive is included. The `y_`, `n_` and `i_` filename categories do not decide
expected behavior: the captured native result does. The remaining 48 source-derived controls cover
duplicate aliases, invalid original UTF-8/scalars in overwritten values, strict grammar/EOF,
Scientific exponent wrap and coefficient representation, Unicode key order and 20,000-level depth.
Original upstream payload hashes and archive members are retained in [provenance](provenance.json).
JSONTestSuite's [MIT license](JSONTestSuite-LICENSE) is retained without alteration.

Every accepted Value is observed by a complete flat event stream, avoiding deep recursive printing
or equality. Events retain every primitive, array order/length and native KeyMap iteration order.
The installed Aeson `ordered-keymap` flag is true: `KeyMap` uses `Map Key`, with `Key` deriving `Ord`
from `Text`. Product objects therefore traverse unsigned UTF-8 key order, including supplementary
characters; the Scala assertion does not sort observations. Every original value is validated
before first-wins duplicate collapse. Numbers retain the original Scientific coefficient and
signed-64 exponent, including zero and trailing decimal zeros, without normalization.

`NativeJsonSuite` compares all 350 native admission results and complete accepted Value event
streams on a small-stack thread. Native errors are retained and reproduced exactly by the portable
oracle; Scala returns closed typed errors and does not claim diagnostic text equivalence. Temporary
last-wins, UTF-16 ordering and Scientific-normalizing variants fail 19 assertions; exact source
restoration passes all 350. No original input is excluded or relabeled.

This is an unused byte-to-Value primitive for [#497](https://github.com/KD5UJC-XYZ/scarab/issues/497),
not completed genesis parsing. Loader wiring, raw-file hash/parse ordering, required fields,
field-level decoders and multi-file error ordering remain separate work. It is also separate from
json-stream's permissive incremental `InjectionFromFile` mechanism. Fuzzing checks crashes and
termination, not differential native conformance. [Replay instructions](../../../scripts/oracle/native-json/README.md).
