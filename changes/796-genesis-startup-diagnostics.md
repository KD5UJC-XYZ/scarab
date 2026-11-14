### Fixed (genesis, #796)

- Refuse a Shelley, Alonzo or Conway genesis file with the reference's own error: the parsers are
  transcribed onto aeson's combinators, so a refusal carries aeson's `Error in $.path: message`, with
  the reference's paths, type names and number, ratio, hash, credential, address, time and cost
  model wording. Every refusal of the genesis-field and cost-model corpora matches whole.
- Read the genesis files in the reference's order, each read, hashed and decoded before the next, and
  write a failed start as its top-level handler does: the `show` and `prettyError` of the
  `ProtocolInstantiationError`, under the name the program was started with, exit code 1. 42 of 43
  recorded `cardano-node` 11.1.3 start-ups match whole; Byron syntax errors keep their own text.
- The genesis check now refuses, as the reference does, values the earlier check accepted or left to
  the node's own readers: Shelley fees, deposits and minimums outside `Word64`, a protocol minor
  version above `Word32`, a slot length whose exponent is above 1024, a `systemStart` that
  text-iso8601 refuses, and malformed staking, delegation, DRep and injection entries.
