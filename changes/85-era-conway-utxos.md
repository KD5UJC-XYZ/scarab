### Added (era types, #85)

- Conway's script-evaluation rule (UTXOS) and the change of the UTxO state that follows it run on the era types, with the reference's failures and events.
  - The Conway transactions given to the reference node are run under both validity flags and both validation policies. Their verdicts equal the existing rule's.
  - Run as submitted, each transaction is accepted or refused as the reference did.
  - On the reference's multi-script results, the scripts that pass and fail are the reference's, in its order.
  - Where the scripts pass as the flag says, the state after equals the existing rule's.
  - The node still judges with the existing rule, so nothing changes in behaviour.
