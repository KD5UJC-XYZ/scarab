### Added (Leios devnet follower, #73)
- **`devnet.py leios-traffic --follow` runs the Scala node as a follower of the private reference Leios cluster.** The
  node runs `follow --reference-build leios-b004fe0c --db --local-socket` against a reference pool from the cluster's
  start, and the run judges it: its selected chain is the reference nodes' immutable chain's prefix, every certifying
  block up to its tip is on it with the certified endorser block's closure in its own store, its log holds no error
  line, and the same-point ledger state of both nodes is compared byte for byte.
- **`LeiosStoreCheckMain`** (`tools`) reports, for endorser-block hashes, whether a stopped node's store holds the body
  and the complete closure.
