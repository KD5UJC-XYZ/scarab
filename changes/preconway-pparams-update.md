### Fixed (pre-Conway protocol-parameter updates)

- Read the parameter update of a Shelley to Babbage update proposal at the reference's update keys
  of its era, and refuse a repeated key, when the transaction is decoded, as the reference's sparse
  `PParamsUpdate` decoder does.
