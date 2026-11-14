### Added (correspondence map, #1026)

- A correspondence map under `docs/data/correspondence/` pairs each definition of the five mirrored areas (network,
  consensus, ledger, plutus and uplc, storage) with its reference counterpart at a pinned revision, with its class
  and whether its structure is the reference's, or lists it as Scarab-only glue with a reason.
  `scripts/check_correspondence.py`, run with the other guards, refuses a new definition that is neither, and a
  mapped definition whose code changed while its entry did not; the definitions that existed before are in a
  baseline that only shrinks.
