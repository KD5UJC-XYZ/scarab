### Fixed (node, #953)

- The command line is read as UTF-8 whatever the locale. Under `LANG=C` the JVM used to decode each byte beyond ASCII of
  an argument as U+FFFD, so a socket path such as `/w/café.socket` named another file in the stale-socket removal and in
  the error line. Where the JVM's encoding is not UTF-8 and an argument holds a character beyond ASCII, Scarab now reads
  its arguments' bytes again from `/proc/self/cmdline`, and refuses to start where it cannot: an argument file before the
  main class, `JDK_JAVA_OPTIONS`, `--disable-@files` or `--source` in the launch, or no `/proc`. Other files are still
  named in the locale's encoding, so a configuration or database path beyond ASCII still needs a UTF-8 locale.
- Standard output and error are written as UTF-8 whatever the locale. Under `LANG=C` the JVM used to write each character
  beyond ASCII of a log line as `?`.
