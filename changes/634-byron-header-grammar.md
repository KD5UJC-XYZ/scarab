### Fixed (ledger, #634)

- A Byron header is read as the reference reads it. Every list is definite and of its exact length. A main header's magic is a `Word32`, and a boundary header's is any `Int32`, a negative one included. Epochs, slot counts and difficulties are `Word64`. A main header's extra data is a protocol version, a software version, empty attributes and a byte string. A header the reference refuses is refused with a typed error.
- A boundary block's protocol magic is no longer compared with the genesis. The reference drops it as it reads the header; it compares only a main block's.
