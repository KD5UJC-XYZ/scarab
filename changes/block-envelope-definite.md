### Fixed (block envelope)

- A block whose `[era, block]` envelope, or a Byron block's `[header, body, extra]` list, is written as an
  indefinite-length list is refused, as the reference refuses it: it reads both lists with `enforceSize`, which
  takes a definite list only. The node accepted such a block, whose header and hash are those of the definite
  one, stored the bytes as received and served them to peers that refuse them. The block of a later era inside
  the envelope is still read in either form, as the reference reads that record.
