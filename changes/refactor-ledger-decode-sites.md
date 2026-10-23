### Changed (ledger decoding)

- A ledger decode failure names the place it was reading with a closed `DecodeField` (and a
  `DecodeSite` where a place is narrowed, as the policy or asset name of a value) instead of free
  text. The rendered messages, in logs, NtC replies and error text, are unchanged.
