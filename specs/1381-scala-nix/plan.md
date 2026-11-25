# Plan

Use flake-parts, pinned nixpkgs, and treefmt-nix, with explicit imports from
flake/ and perSystem/. Target x86_64-linux like the Rust template. Select JDK 25
to match Scarab's required JVM generation, and override sbt to use that same JDK.
Keep Scala and sbt versions in each project's existing build files.

Supply a development shell, Nix formatter and formatting check. Adopt the shell
in Scarab without changing application sources. Export the shared environment
with a minimal standalone sbt application and adoption instructions. Scala
dependencies are resolved by sbt; this template does not promise sandboxed Nix
application packaging or a dependency lock for Maven artifacts.

Validation: flake check; formatter check; JDK identity; Scarab check through its
existing sbtw launcher inside Nix; clean template initialization, compilation and
execution. Existing preflight invokes Docker, which the owner prohibited; record
that limitation and run feasible non-Docker checks directly.

The owner approved a test-only portability fix after validation exposed four
MithrilClientSuite failures: resolve head, touch, mv and sleep through PATH
instead of assuming their FHS locations. Preserve the subprocess lifecycle
assertions, then rerun this suite and the full check serially.
The production verifier intentionally clears its child environment, so the fake
client script explicitly exports the test shell's quoted PATH. Production
environment handling is unchanged.

## Network apps

Add `perSystem/apps.nix` with three `writeShellApplication` apps sharing
`scripts/nix-network`. Use the pinned JDK, the existing sbtw launcher and
`node/runtimeClasspathFile`, then exec the node directly so signals reach it.
Require a writable Scarab checkout (current Git root, or `SCARAB_SOURCE_DIR`).
Serialize compilation with a checkout-local flock; release it before the node
starts, allowing different networks to run concurrently.

Fetch the official flat network configuration, topology, genesis files and
referenced checkpoints/peer snapshot over HTTPS. Download into a temporary
directory and publish the complete config directory atomically under a
per-network lock. Reuse this cache on subsequent launches; allow an explicit
`SCARAB_CONFIG_DIR` instead. Scarab performs its existing genesis/hash checks.

Default database and socket paths are network-specific. Query ports are 7071
(Preview), 7072 (preprod) and 7073 (mainnet). Explicit node flags can override
wrapper defaults without duplicate flags. Use the documented native-access JVM
flags and malloc setting, a default 3 GiB heap, and expose heap/processor settings.
Help must not build, download or create run data.

Validate all three apps with Nix checks and real, bounded network starts,
including concurrent execution and cached configs. Also run the required level 1
Scala check and direct Python guards. Docker remains prohibited and its preflight
is reported as incomplete. Raw run artifacts are not published.
