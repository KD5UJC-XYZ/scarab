### Fixed (forging, #906)
- **SIGHUP with a credential file moved away or deleted now stops forging, as cardano-node 11.1.3 does.** Before,
  an absent file was a refused reload and the node went on forging with the credentials it had, so the
  community's failover runbook (move the files away, send SIGHUP, promote the standby) left two producers
  signing in the pool's slots. The node now reads the VRF key, the certificate and the KES key in the
  reference's order; when one does not exist it erases the KES key from memory, starts no further slot, writes
  nothing back to the key file and logs `blockForgingUpdate` `DisabledBlockForging`. A later SIGHUP that finds
  the files, and passes the checks of any reload, enables forging again (`EnabledBlockForging`). Files that are
  present but refused keep forging as it was, on or off. The `--key-lock` lock stays held while forging is off.

### Added (forging, #906)
- **`--start-as-non-producing-node`**, its hidden deprecated alias `--non-producing-node`, and the
  configuration key `StartAsNonProducingNode` (default false; the flag wins): the node reads and checks its
  credentials at start-up but forges only once a SIGHUP finds them.
- **`blockForgingUpdate`** (`Forge.BlockForgingUpdate`, field `enabled`) at start-up and at each reload that
  finds the files or finds one absent, as the reference's trace; `cardano_node_metrics_forging_enabled_int` follows it (0 while disabled, and on a node
  without `--forge`), and `/forging` reports `enabled`.
- The devnet scenario **`dynamic-forging`**: the credential files moved away and back with SIGHUP beside two
  Haskell pools; no block and no slot started in the leader slots scheduled while forging is off, and no key
  written back.
