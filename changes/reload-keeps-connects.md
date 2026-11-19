### Changed (peer selection)
- **A topology reload leaves a connection attempt in progress to complete**, and the governor keeps the connection it
  opens, or counts its failure, as the reference's governor does; the reload no longer sets the peer cold while the
  attempt runs.
