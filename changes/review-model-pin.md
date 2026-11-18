### Changed (the independent review runs at a pinned model and an effort by level)

- **An agent's independent review runs through `scripts/codex_review.py`**, with one pinned Codex model, read-only
  with the network, at an effort no lower than its change's floor: `medium` for level 0,
  `high` for a design document or ADR and for levels 1 and 2, `xhigh` for levels 3 and 4 (CONTRIBUTING.md,
  "Independent review"). The script refuses a run whose session file shows another model, effort or sandbox, or
  that changed the worktree, and stamps the review it writes. The pull request policy check reads the linked
  review and refuses a stamp below the floor or on another model; a person's review is a GitHub review submitted by
  someone other than the author.
