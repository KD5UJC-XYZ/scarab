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
