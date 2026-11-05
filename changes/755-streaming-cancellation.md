### Fixed (ChainSync, #755)

- Preserve the original local protocol failure when cancelling a sibling streamed receive. The cancelled channel remains unusable, but its cleanup no longer publishes a competing mux-close event that could hide an incoherent state-acquisition fatal (#755).
