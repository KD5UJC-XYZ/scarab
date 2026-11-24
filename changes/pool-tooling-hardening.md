### Fixed (pool tooling)
- **The KES rotation driver (`scripts/pool/rotate_kes.py`) no longer follows symbolic links where the node can
  write.** Erasing a generation overwrote, through a link planted in it, the file the link pointed at (the cold key,
  or any file the rotation user may write); the lock file `.lock` and the temporary file of `rotation.json`,
  `last-counter` and the metrics file were opened through links too. A link is now removed and never written
  through, only a regular file is overwritten, in full when a write is short, and temporary files are created new
  under names that cannot be foreseen and removed when a write fails. `rotation.json` and `current` may name only
  an entry of the key directory itself (`..`, `../cold` and absolute paths are refused with exit status 4), and a
  link in place of the new generation or of its staging directory is removed, not used.
- **Each `cardano-cli` container of the driver gets only the cold key file it reads**, read-only: the verification
  key for `node new-counter`, the signing key for `node issue-op-cert`; the cold key's directory is no longer
  mounted. The key files may have any name. A cold key file that is not there ends the run with exit status 4
  before any container starts.

### Changed (pool tooling)
- **The driver warns on a mainnet run that finds the cold signing key** (no `--testnet-magic`): the key is online,
  and `--cli-image` runs whatever the local image store holds under that name (`--pull=never`). The run goes on;
  the default image is unchanged.
- **The monitoring images are pinned by digest** in `ops/monitoring/agent/compose.yaml` and
  `ops/monitoring/server/compose.yaml` (`image: <name>:<tag>@sha256:...`). The agent's README and compose file now
  say that the agent can read every file on its host, key files included, and should not be deployed on a
  block-producer host that holds signing keys unless the operator accepts that; its mounts are unchanged.
