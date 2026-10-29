### Added (issue triage, #400)

- `python3 scripts/status.py --check` lists, grouped by rule, each open issue missing an `area:`, `priority:`
  or `status:` label, without a milestone while neither `status:idea` nor `status:parked`, with two `status:`
  labels, or stale in `status:in-progress` (7 days) or `status:needs-owner` (14 days), and exits non-zero when
  any is found. A nightly workflow writes its report into the one issue titled "Triage report", and
  `scripts/preflight` shows it without failing.
- `python3 scripts/file_issue.py` files an issue through `gh issue create` only with an area, a priority, a
  status and, unless the status is `idea` or `parked`, a milestone. The issue forms default to
  `status:backlog` and ask for the area and the priority.
