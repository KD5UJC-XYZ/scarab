### Fixed (storage file names)

- The numbers in storage file names are written in ASCII digits under every locale: the immutable
  store's chunk and index files, the volatile log's files, the ledger snapshots and their UTxO
  checkpoints, and the snapshot export's and the import point check's lookups of them. They had
  been written with the default locale's digits, which are not ASCII under Persian, most Arabic
  locales, Bengali, Marathi, Nepali, Burmese and some others, so a database written under one of
  them did not open under another locale, nor one written elsewhere under it. Names written under
  a locale with ASCII digits, `en_US` among them, are the same bytes as before.
