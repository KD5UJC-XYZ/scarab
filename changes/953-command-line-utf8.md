### Fixed (node, #953)

- The node no longer acts on a path the JVM decoded into other characters. Under `LANG=C` the JVM decodes each byte
  beyond ASCII of an argument as U+FFFD, so `--local-socket /w/café.socket` used to name another file in the
  stale-socket removal and in the error line. Now the node refuses to start, with exit code 2 and a line that says to
  rename the path or run under a UTF-8 locale, on an argument beyond ASCII while the locale is not UTF-8, or one that
  holds U+FFFD (what the JVM makes of bytes that are not valid UTF-8), before it touches any file. The file names in the
  configuration (the genesis files, `SocketPath`, `DatabasePath`) are checked the same way before any file is removed or
  made by them. Any
  valid UTF-8 path without U+FFFD works under a UTF-8 locale, as in the Docker image; a local socket path stays ASCII
  only.
- Standard output and error are written as UTF-8 whatever the locale. Under `LANG=C` the JVM used to write each character
  beyond ASCII of a log line as `?`.
