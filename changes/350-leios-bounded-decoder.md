### Fixed (Leios, #350)

- Follow the native LeiosFetch decoder when checking record fields, bounded collections and bitmap entries. Reject chunked byte strings in hashes and tag-24 transactions, preserve original opaque transaction bytes, and enforce the reference block-response byte limit at the client. Retain the explicitly supported legacy response form.
