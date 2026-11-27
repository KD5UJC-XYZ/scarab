### Changed (correspondence map, #1515)

- The correspondence map under `docs/data/correspondence/` is stored as one map file per mapped Scala source,
  holding that source's entries, glue and baseline keys in one sorted form, instead of one file per area and a
  separate baseline, so that pull requests conflict on it only where their mapped sources do. The data and the
  checks of `scripts/check_correspondence.py` are unchanged; its new `--tidy` moves keys to their source's file.
