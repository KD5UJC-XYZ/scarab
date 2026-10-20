### Fixed (local-root DNS rotation, #240)

- Apply refreshed local-root addresses and peer provenance in one governor decision, so a removed connected address is demoted without leaving the governor's configured and effective root sets inconsistent.
