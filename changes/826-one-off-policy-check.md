### Changed (pull request policy, #826)

- A pull request that changes `scripts/check_storage_format.py` fails the pull request policy
  while its `ONE_OFF_PATHS` still names a pull request that merged: a follow-up pull request
  removes an exception once its pull request merges (FMT-1). The policy reads the pull request's version of
  the guard as data, with Python's `ast`, and the states of the pull requests it names from the
  API. A pull request that leaves the guard alone, and a promotion of `dev`, are not checked for
  this.
