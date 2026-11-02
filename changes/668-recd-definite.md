### Fixed (RecD records are definite lists, #668)

- The records the reference reads with `RecD` below decoder version 12 are definite lists only, as
  there: the update proposal of a Shelley-to-Babbage body, the execution units of a redeemer, and
  Conway's governance action id, voting procedure, proposal procedure, constitution and anchor. An
  indefinite list refuses the body or witness set with a typed `IndefiniteLength`; it was accepted
  before.
- A redeemer's execution units are each a `Word64` at most `maxBound :: Int64`, and the index of a
  governance action id a `Word16`, as the reference reads them; larger values were accepted before.
