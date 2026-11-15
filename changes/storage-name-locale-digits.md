### Fixed (storage file names)

- The immutable store and the volatile log read the numbers of their file names from ASCII digits
  only, and refuse to open, before any of their files changes, when one of their names holds
  another decimal digit, as earlier builds wrote under a locale with other digits
  (`StorageError.Corrupt` with `FileNameProblem.LocaleDigits`). Such a volatile log had opened
  and then started a file of the same number beside the old one.
