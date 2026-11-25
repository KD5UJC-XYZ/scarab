# Validation

Level 1: development tooling; production sources and persisted formats are unchanged.

Validated on x86_64 Linux, 2026-11-25:

- Scarab and the standalone template: `nix flake check` and Nix formatting pass.
- `JAVA_HOME` and the shell's Java executable identify the same JDK 25.
- Registry: `nix flake show` exposes `templates.scala`.
- A new project initialized using `nix flake init -t /path/to/nix-templates#scala`
  compiles Scala 3.9.0, passes its MUnit test, and prints both `Hello, Scala!`
  and `Hello, Nix!` through sbt. The documented `sbt test run` command also passes
  with default JVM options. The initialized project passes flake and formatting
  checks with its own Git metadata.
- All 19 existing Python guards pass via `nix develop -c python3 scripts/guards.py`.
- Full Scala `check`: the first run compiled all modules but failed before
  testing with a missing generated sbt definition class. A concurrent launcher
  diagnostic was the likely cause. A serial run exposed four MithrilClientSuite
  failures caused by hard-coded utility locations that do not exist in Nix.
  The owner approved a test-only portability fix. The fake-client scripts now
  export the test shell's quoted PATH and invoke utilities by name; all 15 tests
  in the affected suite pass, including output limits and child cleanup.
  The final `nix develop -c bash scripts/sbtw check` passes (exit 0): formatting,
  headers, every module's test compilation and tests, then scalafix. Existing
  external-data/benchmark skips and the ignored UPLC comparison remain as listed
  in `scripts/ci_test_skips.json`. The sleep guard also passes with no new
  sleeps or timers, and all 19 Python guards pass again after the fix.
- `SCARAB_PREFLIGHT_DOCKER=false bash scripts/preflight --check` reports
  `INCOMPLETE: Docker unavailable, gates not run` (exit 3), as expected with
  Docker prohibited. This is not a preflight pass; Docker-based secret scans,
  workflow lint and reference scenarios were not run.

The temporary sibling worktree was removed after the owner clarified the jail
boundary; implementation and subsequent validation use the original mounted
checkout. No containers were started. Raw run artifacts are not published.

The Scala template is committed in nix-templates as `5632f5c`.
