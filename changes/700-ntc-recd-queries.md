### Fixed (node-to-client RecD records, #700)

- A node-to-client query that carries a `RecD` record reads it as the reference does: in
  `GetProposals`, a `GovActionId` written as an empty indefinite list with its fields after it is
  read (in a set of any form, under `GetCBOR` too), and the LocalStateQuery codec ends the message
  where the reference's decoder of queries does, waiting for the rest of a record that has not all
  arrived. One written as a non-empty indefinite list, with an index over 16 bits or with a
  transaction id of other than 32 bytes is refused, as the reference refuses it.
