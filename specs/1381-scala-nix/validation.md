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

## Network apps

Level 1, validated on x86_64 Linux on 2026-11-25:

- All three app derivations build with ShellCheck; `nix flake check` and
  `nix fmt -- --fail-on-change` pass.
- All three help commands succeed without creating network state.
- Preview, preprod and mainnet ran concurrently for 60 seconds each, using
  freshly downloaded official configuration. All three HTTP query endpoints
  responded while their independent Unix sockets were live. The nodes reported
  network magic 2, 1 and 764824073 respectively, advanced their chain tips and
  exited successfully after writing shutdown snapshots.
- Bounded follow-up starts exercised cached configuration, an existing custom
  config directory, explicit source selection from another working directory,
  query flag overrides, and database/socket paths containing spaces. The cached
  configuration's timestamp and size were unchanged, with no repeat download.
- All temporary chain databases and configuration downloads from these checks
  were removed after the owner requested conservative disk use. No full-chain
  sync was attempted, and no public-network node remains running.
- The full Scala `check` and Python guards are running for this extension;
  their final outcome is not yet recorded. Docker remains prohibited, so the
  Docker-only preflight remains incomplete.

Raw run artifacts are not published.
