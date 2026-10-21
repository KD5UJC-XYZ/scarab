### Added (Complete database identity, #159)

- Global format V6 records protocol magic and the existing Byron/Shelley identity bindings together with BLAKE2b-256 of the exact Alonzo and Conway genesis-file bytes consumed by node setup. Native snapshots remain V5; unchanged file kinds are not rewritten for the global version.
- V1/V2 identity files are read as incomplete legacy data. Opening a populated legacy or identity-less database requires `--adopt-legacy-network-identity`; the node checks all fields the old identity provides, checks the first immutable predecessor for identity-less stores, completes snapshot preflight before writing, preserves original legacy bytes without overwriting a conflicting backup, and records operator confirmation without claiming historical proof.
- Fresh databases record a complete identity. Mismatch or unconfirmed legacy admission refuses before mutable storage is opened.
