### Added (issue triage, #605)
- The nightly triage workflow fixes the tracker's mechanical drift itself, through `python3 scripts/triage_fix.py
  --apply`, and also runs when an issue is opened: an issue form's area and priority answers become labels (and a
  missing status `status:backlog`); a `status:in-progress` issue that no open pull request names and that has had no
  activity for 7 days moves to `status:backlog` with a comment; a shaped issue without a milestone gets the bucket its
  title names, or `reference-parity`, with a comment; a closed issue loses `status:in-progress`, `status:blocked`
  and `status:needs-owner`; an open pull request whose merge into `main` changes nothing gets one comment. Without
  `--apply` the script prints its plan.
