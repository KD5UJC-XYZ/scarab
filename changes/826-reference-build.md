### Added (the reference build, #826)

- `--reference-build <build>` on `follow`, `import-ledger-snapshot` and `export-ledger-snapshot`
  names the build of `cardano-node` the network runs, which the node mirrors: the layout of the
  database it reads and writes and, later, the rules it follows. The value has to be the build the
  network runs; another value is like running another binary. `11.1` (the default) is the stable
  11.1 release line, behaving as its pinned release 11.1.3. `leios-b004fe0c` is the unstable public
  Leios testnet build; it is refused at start until its ledger state layout is implemented.
- The start logs `referenceBuild` with the build and its pinned revisions, and warns
  (`unstableReferenceBuild`) when an unstable build runs without `ExperimentalHardForksEnabled`.

### Changed (storage, #826)

- Ledger snapshots are format V10 (`SCRBSNPA`): the V9 layout with the reference build of the
  Haskell state bytes recorded before the checkpoint binding. V9 snapshots, and older ones through
  V9, migrate on open as `11.1`, with the binding, the sidecar and the Haskell bytes unchanged. A
  snapshot recorded under a build of another layout is not read: the restore skips it, naming both
  builds, and tries an older one.
- `import-ledger-snapshot` refuses a `state` file that does not decode completely in the layout of
  the build that wrote it, and records that build with the unchanged bytes. `export-ledger-snapshot`
  writes the recorded bytes unchanged, reports their build, refuses a requested build of another
  layout, and still exports a V9 snapshot of a database not yet opened by this build, as
  `export-scls` and `import-scls` still read one.
