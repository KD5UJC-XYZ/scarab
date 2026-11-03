### Fixed (scripts, #694)

- `scripts/cleanup.py --stale-days` could never find a worktree stale: its own `git status` rewrote the index whose time it then read as the worktree's last activity. Listing no longer refreshes the index; activity is the newest of HEAD, its reflog, the index and the tracked files, so an edit undone in the working tree still counts; and a worktree without a HEAD reflog, whose commits and resets leave no trace of their time, counts as of unknown activity and is kept.
