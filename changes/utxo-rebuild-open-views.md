### Fixed (UTxO store, local state queries)

- The RocksDB UTxO store no longer closes its database under a local state query that holds a
  pinned view. A query acquired in a Shelley-based era pins a snapshot of the store; the block that
  enters Shelley, applied again after a switch to a fork from a Byron block, reset the store by
  closing and reopening its database, and the session's next query that read the store, or its
  release, then used the closed database, which could end the node with a native crash. The reset
  now empties the database in place while a view or an enumeration is held, so each keeps reading
  what it pinned, as the reference's acquired state keeps its tables' handle. A restore or the
  store's release waits for the calls already running, frees the snapshots and iterators still
  held, and leaves their later reads of the store failing with `UtxoStoreClosed`; a local query
  that needs one ends that client's connection only.
