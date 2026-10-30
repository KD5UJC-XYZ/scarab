### Added (issue triage, #605)
- The nightly triage workflow fixes the tracker's mechanical drift itself, through `python3 scripts/triage_fix.py
  --apply`, and also runs when an issue is opened: a closed issue loses `status:in-progress`, `status:blocked` and
  `status:needs-owner`; an issue form's area and priority answers become labels (and a missing status
  `status:backlog`); an open pull request whose merge into `main` changes nothing gets a comment; a shaped issue
  without a milestone gets the bucket its title names, or `reference-parity`; a quiet `status:in-progress` issue that
  no pull request or branch names is flagged. `triage:hold` stops every fix on an issue, a fix a person undid is not
  made again, and each issue gets at most one comment a run. Each fix is only reported until the repository variable
  `SCARAB_TRIAGE_FIXES` enables it.
