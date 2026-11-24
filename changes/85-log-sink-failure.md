### Fixed (logging, #85)

- A log sink that cannot take a line (a full disk, a `--log-file` that cannot be turned over) no longer
  fails what logged it, such as block application, the follower, the mempool or the forger: the line is
  dropped, the first failure of a streak is reported on stderr with its cause and, when the sink takes a
  line again or the log closes, the number of lines dropped. The reports are written by a writer of their
  own, so a blocked stderr holds neither what logged nor the log's release. The log file is opened again for the next
  line, so a failed write or rotation no longer leaves the file sink writing to a closed stream.
