### Fixed (node-to-client Byron update-interface state, #726)

- Byron's `GetUpdateInterfaceState` is answered while the acquired ledger is in Byron, with the
  reference's bytes: `toByronCBOR` of the update-interface state (`UPI.State`), eleven fields with
  `currentEpoch` 0. It used to be refused, which closed the connection. Past Byron it is answered
  with the era mismatch, as before.
- The Byron update state keeps each software proposal's installer metadata (`Map SystemTag
  InstallerHash`) on the registered proposal and on the application version of a confirmed one, as
  the reference does; the proposal decoder used to check it and drop it.

### Changed (storage, #726)

- Ledger snapshots are format V8 (`SCRBSNP8`): the V7 layout whose sidecar keeps that metadata.
  V7 snapshots, and V5 snapshots through V7, migrate on open with their binding and Haskell bytes
  unchanged. A V5 or V7 snapshot whose Byron update state already holds a software proposal or an
  application version lost their metadata and is refused before any store opens, with a typed
  `ResyncRequired` error that asks for a resync from genesis; nothing is invented for it. Snapshots
  taken after the Byron era hold no Byron update state and migrate.
