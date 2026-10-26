<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native injection fold replay

`Main.hs` calls unmodified native `foldInjectionData` with an instrumented HasFS. It records
read/open/close observations and complete native Value events. The six five-second controlled
timeout observations are non-results; repeating their observations does not establish completion
or refusal. Original observations remain verbatim in the [corpus](../../../fixtures/reference/injection-fold/README.md).

```sh
python3 scripts/oracle/injection-fold/run.py --image scarab-plutus176-context-reference:local
```

Run only on an authorized compute host. The two-CPU Docker runner uses the pinned installed
packages, no network and disposable build tmpfs. Temporary raw inputs and the container are
removed on normal completion. Every native output line is compared exactly, including original
numeric spellings; no JSON re-rendering or field exclusion is used. This replay covers reference
observations, while the Scala implementation covers only the pure inner fold, not HasFS lifetime.
