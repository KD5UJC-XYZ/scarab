### Fixed (KES signing key and key files in memory, #1418)

- The KES signing key no longer leaves copies of its seeds on the heap. It hashed the seed halves with
  Bouncy Castle's Blake2b and derived and signed with its leaves through Bouncy Castle's Ed25519, whose
  working arrays are not cleared, so the seeds and the expanded leaf keys of periods the key later left
  stayed in memory until the collector reused it. Generation, loading, evolution and signing now
  compute every value derived from the seeds in working storage that is zeroed when the operation ends,
  on failure too, with a Blake2b-256 of the node's own and the constant-time curve arithmetic of VRF
  proving; a payload is checked before it is copied into a key. Signing, evolving and forgetting
  are serialized on the key: a forgotten key refuses to sign or evolve. Signatures, verification keys
  and the `kes.skey` layout are unchanged, byte for byte.
- The VRF and KES key files are read and written as bytes, with no text, JSON value or byte value
  holding the key: a flat envelope (as cardano-cli and the node write it) is read in place and its
  key decoded into storage that is zeroed, and the bytes pass between the file and the heap through
  native memory zeroed before it is freed, not the JDK's per-thread buffer, which kept the last key
  file read or written until the thread did other I/O. A staged key is compared with the key in
  memory in constant time, with no copy kept for it. An envelope that is not flat is read as text, as
  before; every key file reads, and every failure is reported, as before, and the files the node
  writes are unchanged, byte for byte. The JVM may still have copied an array before it is zeroed, and
  the curve arithmetic's own intermediates are not erased.

### Added (KES secret path guard, #1418)

- `scripts/check_crypto_guard.py` holds the KES signing key's secret package, and the shared working
  storage, to the rules of the VRF secret path, and refuses Bouncy Castle in every secret package.
