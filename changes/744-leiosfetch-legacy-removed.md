### Removed (LeiosFetch older block-transactions form, #744)

- `MsgLeiosBlockTxs` is read and written only as the build the public Leios testnet runs does
  (ouroboros-consensus `2416493b`): `[4, [* #6.24(bytes)]]`, written as a definite list and read as
  a definite or an indefinite one of at most `maxTxsPerEb` transactions. The older Blueprint form
  `[4, point, bitmaps, tx_list]` (`LeiosFetch.Message.LegacyBlockTxs`) is no longer read.
- A LeiosFetch message must be a definite list, and its length is checked with its key before any
  field is read, as that build's decoder does; an indefinite message is refused.
