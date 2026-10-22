### Fixed (consensus and networking, #318)

- Validate Byron headers, the first Shelley header after Byron, and epoch-boundary
  envelopes before fetching their bodies in Praos mode. Every enabled header-first
  session now uses the same canonical all-era ledger forecast, timing and typed
  validation state; missing forecasts wait for ledger progress.
- Remove the legacy unchecked node-header entrypoint. Praos retains its existing
  consensus policies and explicit `--header-first off` option.
