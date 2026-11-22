### Fixed (pull request policy, #161)

- The pull request policy decides on every changed path of a pull request, read from git at the
  event's own commits: the merge base of its base and head and the head, fetched as commits and
  trees only, never checked out. GitHub's list of a pull request's files stops at 3,000, gave only
  the new name of a renamed file, and was read from the pull request as it is now rather than as
  the event saw it, so a large pull request, or one that moved the persisted-format register,
  could pass the path checks. Both sides of a rename now count, a path holding a newline stays one
  path, and a read that fails, or a list shorter than the pull request, fails the policy.
