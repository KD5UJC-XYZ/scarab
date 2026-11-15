### Added (Antithesis assertions, #1032)

- **`--assertions-file <path>`: the node records Antithesis assertions itself.** With the flag, `follow` appends
  the lines of the Antithesis fallback SDK to `<path>`: at start the declarations of its whole catalogue of
  properties (`Property`, closed: a property is a case of one enum per kind, `Sometimes` or `Unreachable`), then
  the first true and the first false evaluation of each property as the node meets it. An assertion is a call
  of a typed capability, `Assertions[F]` (`sometimes`, `always`, `unreachable`), with typed details; it never
  waits for the file, writes it or fails: a fiber of its own writes the lines and, at the node's end, the
  evaluations still queued before the file closes. A file that cannot be opened or written is said once, as an
  `error` line of kind `Unwritable`, and the node goes on without its assertions. Without the flag nothing is
  recorded.
