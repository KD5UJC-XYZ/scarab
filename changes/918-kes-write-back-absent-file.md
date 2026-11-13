### Fixed (forging, #918)
- **The KES write-back never makes a key file that is not there.** A period change between moving the key file
  away and the SIGHUP no longer puts the key back at its path (which let forging go on after the SIGHUP); the
  node logs `kesFileAbsent` and signs on with the key in memory until the SIGHUP disables forging. On Linux the
  evolved key replaces the file by an atomic exchange (`renameat2` with `RENAME_EXCHANGE`), so no window
  remains; elsewhere the write-back checks that the file is there before it erases and renames.
