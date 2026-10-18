### Fixed (HTTP queries, #170)

- Reject partial, malformed or ambiguous `/state` point parameters with HTTP 400, including hashes other than 32 bytes. Well-formed unavailable points return 404; `/state` without point parameters still reads the tip. Slots accept the full unsigned 64-bit range.
