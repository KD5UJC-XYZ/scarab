<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native injection JSON trace replay

`Main.hs` calls the unmodified json-stream `runParser (objectItems value)` and supplies exact
recorded chunks. At exhaustion it feeds three empty chunks and records the resulting NeedData
state; it never turns a timeout into a native verdict. No native source is patched.

Run on Euler or another authorized compute host with the pinned installed packages:

```sh
python3 scripts/oracle/injection-json/run.py --image scarab-plutus176-context-reference:local
```

The runner uses an owned Docker container with two CPUs, no network, and disposable build tmpfs.
Its temporary input file and container are removed automatically. JSON numeric tokens retain
exact spelling and their distinct type, including exponents beyond Python Decimal's range.
The replay checks every native output field, including diagnostic text. Scala compares typed
refusal decisions instead of native diagnostic wording; see the [corpus scope](../../../fixtures/reference/injection-json/README.md).

Each trace retains serialized `pairs` and adds complete `pairEvents` from the native Values,
including KeyMap traversal order and Scientific coefficient/exponent. The Scala comparison uses
those direct events uniformly because JSON formatting can overflow an extreme exponent; the
portable replay still compares every serialized field without normalization.
