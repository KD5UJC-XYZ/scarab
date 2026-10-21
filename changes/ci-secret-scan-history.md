### Fixed (CI)

- Scan the complete history reachable from the checked-out commit, including merge parents,
  without scanning unrelated fetched branches. Pinned-scanner controls retain detection of
  deleted historical secrets and reproduce the previous cross-branch failure.
