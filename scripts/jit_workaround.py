# SPDX-License-Identifier: Apache-2.0
"""The JVM options every JVM that runs Scarab's code starts with while #1521 is open: the one list.

On JDK 25 (Temurin 25.0.4.1, the JDK image's and the release image's), C2 miscompiled code reached from
`TxChecks.collateral`, so `Collateral.fee` came back null and the rules threw a NullPointerException on a
phase-2-invalid transaction, which stalled a mainnet sync. It depends on compile order, so it differs between JVMs
of one build. Turning off C2's reduction of allocation merges stopped it in the recorded runs: 0 failing runs of 8,
against 2 of 8 with the defaults (#1521). The option is diagnostic, so the unlock option comes first.

The Python launchers (`devnet/cluster.py`, `conformance/scenarios.py`) take the options from here. The launchers that
are not Python, or that ship alone in an image, carry the same options as literals, each marked with #1521:
`build.sbt` (`jitWorkaround`), `scripts/sbtw`, `scripts/scarab-jvm` and `antithesis/pool/scarab/scarab-node.sh`.
`scripts/check_jit_workaround.py` holds every copy to this list, and every place that starts a JVM to a recorded
classification. The workaround goes, everywhere at once, when a JDK without the fault is pinned and the source change
of #1521 has landed.
"""
from __future__ import annotations

OPTIONS: tuple[str, ...] = ("-XX:+UnlockDiagnosticVMOptions", "-XX:-ReduceAllocationMerges")
