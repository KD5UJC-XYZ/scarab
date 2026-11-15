### Fixed (PeerSharing decoding, #1029)

- The PeerSharing decoder reads the declared length of a message's array and of a peer address's and
  ignores it, as the reference's codec does: the fields read are those the message key or address tag
  names, and an item declared beyond them is left to the next message. A message or address whose
  declared length is wrong was refused before; a length of 2^63 or more is still refused, as cborg's
  `decodeListLen` refuses it.

### Fixed (Handshake decoding, #1029)

- The Handshake decoder bounds a message only as the reference does, at 5760 bytes: the local caps on
  nesting depth (24), terms (512), string bytes (4096) and refusal text bytes (1024) are gone, in the
  decoder and the encoder. A version map of a few hundred versions, a refusal text of 2000 bytes and a
  term nested thousands deep within 5760 bytes are now decoded; a term is parsed and written on a stack
  on the heap, so its depth does not reach the thread's stack.
- A refusal's reason is read as the reference's `decodeRefuseReason` reads it: its declared length is
  read and ignored, and its tag names the items read (the list of versions, or a version and a text).

### Changed (PeerSharing and Handshake encoding, #1029)

- The PeerSharing and Handshake encoders write a message of any size, as the reference's do: its driver
  sends what the codec encodes and bounds a message on receipt only. They refused one over 5760 bytes
  before. The receive bound is unchanged, and the PeerSharing server still cuts its answer to what fits
  5760 bytes. `PeerSharing.encode` and `PeerSharingEngine.encode` no longer take `Limits`.
- A handshake term keeps the chunks of an indefinite text or byte string (`Term.TextChunks`,
  `Term.ByteChunks`), and the encoder writes them back as cborg's does: chunk by chunk, an empty chunk
  dropped, so an empty indefinite text is `7f ff` and no longer `7f 60 ff`. A message that decodes is
  not written longer than it was read.
