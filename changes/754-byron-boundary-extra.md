### Fixed (Byron boundary headers, #754)

- A Byron epoch boundary header's extra data is read as the reference reads it: a definite list of one item holding attributes, a definite map of strictly ascending `Word8` keys to definite byte strings. A boundary header with any other extra data is refused, where it was accepted unread. The header corpus (`fixtures/reference/header-decoding`) compares 24 forms with the released decoder.
- The "Genesis" attribute is honoured: a boundary header of epoch 0 or with that tag names the genesis as its predecessor. A Byron chain starts with such a header, and a later boundary header that names the genesis is refused, as `updateChainBoundary` and consensus's `headerPrevHash` refuse it.
