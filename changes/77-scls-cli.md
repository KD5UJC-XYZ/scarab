### Added (offline SCLS interchange, #77)
- `export-scls` and `import-scls` expose the offline SCLS file services. Both require a complete native base, local network configuration/genesis inputs and an exact Conway block reference; import also requires an existing target database at that same immutable point. The default record and per-entry CBOR bound is 16 MiB and can be raised explicitly to 256 MiB with `--record-bytes`.
- The byte override changes CBOR input and string-byte limits only; the independent per-entry item ceiling remains 100,000.
