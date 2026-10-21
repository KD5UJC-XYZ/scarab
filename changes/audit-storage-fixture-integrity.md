### Fixed (audit storage fixture integrity)

- Harden storage comparison tests against empty, disjoint, undecodable or incomplete fixtures. Pin the seven original native state snapshots and their 21 companion files; require a complete same-snapshot checkpoint for optional MemPack acceptance while preserving explicit historical constructor and original-CBOR canonicalization contracts.
