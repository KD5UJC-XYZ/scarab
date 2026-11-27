### Fixed (protocol-version updates before Conway, #1514)

- PPUP's `PVCannotFollowPPUP` check and UPEC's check of the future proposals now follow the reference's
  `pvCanFollow`. The minor version is a `Word32`, and its successor wraps, so `(major, 0)` follows
  `(major, 4294967295)`. The next major version is `succVersion`'s, and no major version follows 13.
  Scarab refused the wrapped version before. On a chain at minor 4294967295, a transaction carrying such
  an update was refused, and the epoch boundary dropped proposals that the reference keeps.
- The proposed version is read as the reference reads a `ProtVer`. A major version above 13 or a minor
  version above a `Word32` is unreadable, and is no longer compared as a number.
