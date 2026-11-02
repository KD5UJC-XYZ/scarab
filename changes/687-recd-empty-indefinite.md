### Fixed (detached RecD records, #687)

- A `RecD` record written as an empty indefinite list with its fields after the break is read as
  the reference reads it below decoder version 12: in a block, a transaction body, a witness set,
  auxiliary data, a submitted transaction and a stored output, the update proposal, a parameter
  update's prices and execution units, an output's value, a redeemer's execution units, Conway's
  governance records and the list form of auxiliary data. Such a block or transaction was refused
  before; it is now accepted, with the transaction ids and block body hash over the bytes the
  reference reads, and refused where the reference refuses it.
