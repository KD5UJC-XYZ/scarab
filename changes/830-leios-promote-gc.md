### Added (Leios endorser-block store, #830)

- Under the Leios build, the chain database drives the Leios store as the reference's ChainDB
  background drives its LeiosDB: each copy of blocks to the immutable store promotes the endorser
  block each copied ranking block certifies (the one its predecessor announced), and each collection
  of the received log collects the store at the same slot. Release builds open no Leios store and are
  unchanged.
