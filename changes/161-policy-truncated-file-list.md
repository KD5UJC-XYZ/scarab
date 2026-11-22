### Fixed (pull request policy, #161)

- The pull request policy decides on every changed path of a pull request. GitHub lists at most
  3,000 files of one, so a larger pull request's list could leave out the persisted-format
  register, the storage format manifest or its guard, and the policy passed it. When the list is
  short, the policy job now reads the paths from git (the head and the merge base, fetched as
  commits and trees only, never checked out), naming both sides of a rename; when that read
  fails, the policy fails with a diagnostic instead of deciding on the short list.
