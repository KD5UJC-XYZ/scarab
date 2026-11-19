### Fixed (forging, #1101)
- **The KES write-back writes only a period the node has confirmed.** The key still evolves in memory at the
  first slot of each KES period and at a SIGHUP reload, but it is written back to the `--kes-skey` file only for a
  slot that passed the forecast check, and never at a later period than that slot's. A reading of the wall clock
  ahead of the true time, at a period's first slot past the forecast range or at a reload, no longer leaves in the
  file a key that cannot sign until the true time reaches its period; a restart reads the file as it was.
- **`kesKeyAhead` (Warning) at start-up** when the KES key in its file is at a later period than the current one:
  it cannot sign before then; restore the unevolved key, or issue a new KES key and operational certificate.
- **A SIGHUP recovers a KES key left past the present.** A reload keeps the key in use only while it is not past the
  period it must sign in; otherwise it installs the key read from the file and brings it to the present, as the
  reference's reload makes its hot key afresh from the file. A key in memory left ahead by a reload while the clock
  read ahead no longer gives every led slot up until a restart; a SIGHUP once the clock is right recovers it.
