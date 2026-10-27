### Fixed (Leios, #350)

- Write LeiosFetch typed values with the native encoder contract: keep collection and bitmap rejection at the decoder boundary, and remove the generic one-MiB encoding cap. Preserve the separate receive-state byte limits and original legacy raw items.
