### Fixed (storage file names)

- `import-haskell-db` and `import-mithril` find a reference database's chunk files under every
  locale: the chunk numbers they look up are written in ASCII digits, as the reference names its
  files. Under Persian, most Arabic locales and some others they had refused the database
  (`missing .chunk file`). The state digests of `--dump-state` are named in ASCII digits under
  every locale too.
