### Fixed (block headers and their KES signature, #698)

- A Babbage or Conway header and its body are read as the reference's `RecD` records: written as
  a non-empty indefinite list they are refused, and written as an empty indefinite list with the
  fields after it they are read as the record, alone and in the block that holds them; a Leios
  header and its announcement likewise. A VRF certificate written as an indefinite list is refused
  in every era, and so are a body size or minor version over a `Word32` and a major version over 13.
- The KES signature of a header is checked over the header body encoded again, as the reference
  checks it, not over the body's original bytes, which differ when they hold a non-minimal head,
  an indefinite list or a detached record. The header hash stays over the original bytes, and the
  forger signs the same encoding.
