<!-- SPDX-License-Identifier: Apache-2.0 -->
# Strict Aeson Value replay

`Main.hs` calls unmodified `eitherDecodeStrict` on original bytes. Its explicit-worklist observer
retains full Value events, native KeyMap traversal and Scientific coefficient/exponent without
sorting or normalization. Native refusal messages are captured verbatim. No native source is patched.

```sh
python3 scripts/oracle/native-json/run.py --image scarab-plutus176-context-reference:local
```

Run on an authorized compute host. The owned two-CPU Docker container has no network and uses
build tmpfs; the temporary input and container are removed automatically. Every output field is
compared exactly. LF-only record framing preserves valid literal U+0085/U+2028 inside JSON strings.
See the [corpus scope and provenance](../../../fixtures/reference/native-json/README.md).
