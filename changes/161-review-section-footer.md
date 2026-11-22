### Fixed (pull request policy, #161)

- The pull request policy's review check counts only a link to a comment or a review of the pull
  request itself (`https://github.com/<repository>/pull/<number>#issuecomment-<id>` or
  `#pullrequestreview-<id>`) in its "Independent review" section. Until now any link counted,
  so a section that said only "Pending" passed when the body's closing attribution line, which
  falls in its last section, carried a link.
- The check reads a link by its destination, never its visible text, so a link that shows this
  pull request's comment but leads elsewhere does not count, and an image is no link. A
  reference link in the section resolves against a definition anywhere in the body, such as one
  after a later heading; a definition that no link in the section uses still counts for nothing.
