### Added (build, #650)

- WartRemover checks the main sources of every module for `var`, `while`, `throw`, `return`, `null`, casts, mutable collections and partial accessors. In the modules that are clean (`wartFree` in `build.sbt`, `consensus` first) they are compile errors; elsewhere they are reported as information while each module is cleaned.
- `scripts/check_imperative.py`, run by preflight and CI, refuses new `try`/`catch`/`finally` in main Scala sources against a baseline that only shrinks.
