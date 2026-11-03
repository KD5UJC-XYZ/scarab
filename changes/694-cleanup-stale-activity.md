### Fixed (scripts, #694)

- `scripts/cleanup.py --stale-days` could never find a worktree stale: its own `git status` rewrote the index whose time it then read as the worktree's last activity. Listing no longer refreshes the index, and a worktree without a HEAD reflog, whose commits and resets leave no trace of their time, counts as of unknown activity and is kept.
