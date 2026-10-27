### Fixed (ChainSync re-intersection, #523)

- A Praos session whose candidate no longer joins our chain now drains its pipeline and finds a new intersection with our current chain, as the reference's `findIntersectionTop` does on `NoLongerIntersects`; the peer is disconnected only when no intersection exists, instead of at once as diverged.
