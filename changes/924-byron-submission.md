### Fixed (node-to-client, #924)

- LocalTxSubmission reads a submitted Byron transaction, delegation certificate, update proposal or vote whole, with
  the decoder of its kind, as the reference does: one that decoder refuses (a transaction of three items, a
  verification key whose length is not written in its shortest form) now ends the connection with no reply, as on
  the reference, instead of being answered with the era mismatch. One it reads is still answered with the era
  mismatch.
