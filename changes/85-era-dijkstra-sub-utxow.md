### Added (era types, #85)

- Dijkstra's sub-transaction rules for outputs and witnesses (SUBUTXO and SUBUTXOW) run on the era types, as the Leios testnet's build (cardano-ledger 9df9f98c) has them, with the reference's failure types and their injections, total on what each rule reaches. Each sub-transaction of the testnet chain's 28 recorded batches is admitted, its signatures and script integrity checked, threaded through the batch in order, and the top level is then admitted on the threaded state. The node still judges with the existing rules, so nothing changes in behaviour.
