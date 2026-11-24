### Changed (Leios forging, #1380)

- A Dijkstra block that certifies nothing takes the pool's own revalidated transactions when the pool is at its parent and the slot changes nothing they were judged by: the slot after the parent, or a later one in the same epoch inside every held transaction's validity interval. This is the reference's `getSnapshotFor` cache. Before, the forger revalidated the whole pool at every led slot. A certifying block's snapshot is still revalidated, as the reference's is.
