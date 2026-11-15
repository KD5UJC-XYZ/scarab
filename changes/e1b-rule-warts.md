### Changed (build)

- Three warts of the design rules' own, in a `warts` project that every module's compiler loads, refuse in the
  main sources of every module but `tools` and `warts`, generated code excluded: a reading of the system clock (EFF-6); an `unsafeRun*` (EFF-5);
  `sys.error`, `require`, `assume`, `assert`, and a `raiseError` (or `fromEither` of a `Left`) of a
  `Throwable` made to be raised or of a domain error, which goes through cats-mtl `Raise` (ERR-4); floating point in the ledger and consensus
  packages (ARI-1); and a decision on a String's contents (STR-3), outside the parsers listed in
  `project/StringParsers.scala`. Each message names its rule, and `DisallowedWartsSuite` shows that each fires.
- The sites they found are fixed or kept with `@SuppressWarnings` and a reason: the reference's own floating
  point, with its pinned citation; the two `Dispatcher` bridges; the timed regions of the probes; preconditions
  that are defects, not domain errors; and the application-name lookups of the Byron update state, whose key
  the persisted sidecar holds as text. Fixed: the state dump and the measured scheduler steps take their time
  from `Clock[F]`; a topology's `diffusionMode` is parsed into `DiffusionMode` when it is read; the genesis
  answer's network id is decided when the genesis is read; an SRV target is compared with the typed root
  name; the native crypto library's digest is a typed `LibraryDigest`; and the Scalus builtins are mapped to
  ours by a table keyed by the builtin. Results, the log and the answers are unchanged; the timings of a state
  dump and of a measured scheduler step come from `Clock[F]`, so a scheduler step's time can include the
  runtime's scheduling between the two clock reads.
