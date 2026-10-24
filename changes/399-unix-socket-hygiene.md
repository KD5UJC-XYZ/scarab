### Changed (node-to-client socket, #399)

- The Unix socket given by `--socket-path` is restricted to owner and group (`rw-rw----`) once bound. A stale socket at the path is still replaced, as the reference node does, but anything else there (a file, a directory, a link) is refused with a typed configuration error and left untouched.
