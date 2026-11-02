### Added (build, #650)

- WartRemover refuses `var`, `while`, `throw`, `return`, `null`, casts, mutable collections and partial accessors in the main sources of every module, as compile errors. A site kept on a measured path or for a library contract carries a `@SuppressWarnings` that names its reason.
- `scripts/check_imperative.py`, run by preflight and CI, refuses new `try`/`catch`/`finally` in main Scala sources against a baseline that only shrinks.
