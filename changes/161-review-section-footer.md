### Fixed (pull request policy, #161)

- The pull request policy's review check counts only a link to a comment or a review of the pull
  request itself (`https://github.com/<repository>/pull/<number>#issuecomment-<id>` or
  `#pullrequestreview-<id>`) in its "Independent review" section. Until now any link counted,
  so a section that said only "Pending" passed when the body's closing attribution line, which
  falls in its last section, carried a link.
