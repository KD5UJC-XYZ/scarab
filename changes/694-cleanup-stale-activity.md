### Fixed (scripts, #694)

- `scripts/cleanup.py --stale-days` measures a worktree's age from its last checkout, commit or reset (HEAD and its reflog). It read the index too, which every `git status` rewrites, including the one the command itself runs, so no worktree ever looked stale.
