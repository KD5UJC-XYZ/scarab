### Fixed

- Share Byron integer decoding between parameter models and transport validation. Match the pinned
  native decoder's rejection of indefinite bignum magnitudes and long-form tag2/tag3 headers while
  preserving accepted definite noncanonical magnitudes and signed values.
