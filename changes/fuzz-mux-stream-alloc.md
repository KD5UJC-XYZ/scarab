### Fixed (mux)

- The streaming mux decoder (`Mux.Decoder.feed`) keeps the parts of a frame that spans feeds and joins them once when the frame completes, as the reference's socket bearer does, instead of copying the payload received so far on every feed. A 4,096-byte frame fed a byte at a time allocated about 2,400 bytes per input byte and now about 400; decoded frames are unchanged.
