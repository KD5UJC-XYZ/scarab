### Fixed (ledger decoding, #437)

- Shelley-based block headers and node-to-client query arguments read a byte string in the form the
  era's decoder version implies, as the reference does: a header body's fixed-size fields go through
  `decodeFixedSized`, which is `decodeBytes`, at the era's lowest protocol version, and consensus
  decodes query arguments with `fromEraCBOR @era` at the same version. Below version 12 that is a
  definite-length string, so a chunked one is now refused with a typed error instead of being joined
  and accepted; a stored block whose header carries one is expected to stop replaying; that path has no test, and #568 tracks it.
- Byron header byte strings and the future-era (Leios) readers are unchanged and still accept a
  chunked string: the shared helper keeps the policy it had before this change for the fields that go
  through it, and the Byron body proof is copied without any form check at all. Narrowing either is
  tracked in #574 (Byron) and #570 (Leios).
