### Fixed (transactions of an earlier era, and the mempool before Conway, #934)

- The mempool holds transactions in every era from Shelley to Conway: those of the era of its ledger,
  the ledger state at the tip ticked to the slot after it, which may already be the next era. Each era
  validates them by its own rule, LEDGER from Shelley to Babbage and MEMPOOL in Conway, with its own
  measures, and a refusal is sent in that era's predicate-failure encoding. Before, the mempool took
  Conway transactions only and refused every transaction while the chain was before Conway.
- LocalTxSubmission takes a transaction of an earlier era as the selected reference build does: under
  `11.1` it is translated forward through each era, as cardano-node 11.1.3 does; under `11.2` and the
  Leios build it is decoded in the ledger's era, as cardano-node 11.2.1 does, which also takes a
  later-era transaction whose bytes that era reads. A Byron transaction, one that does not reach the
  ledger's era, and under `11.1` one of a later era, get the hard-fork combinator's era mismatch with
  the ledger's era, where it used to name Conway whatever the ledger. When the ledger enters a new era,
  the held transactions follow it in the same way, and one that does not reach it leaves the pool.
- LocalTxMonitor `NextTx` lists each transaction under the era it is held in, where it used to name
  Conway always.
- Node-to-node transaction submission carries the transactions and ids of every Shelley-based era under
  the era's index, as the reference's hard-fork combinator does: the node offers its peers each
  transaction it holds under the era it holds it in, and reads a peer's with the decoders of the era it
  names. A peer's transaction of another era is brought into the pool's era by the same conversion as
  a local client's (translated forward under `11.1`, decoded in the pool's era under `11.2` and the Leios
  build); from Alonzo on a peer's transaction is then applied with `is_valid` true and, when only that
  flag is what a failing script refuses, held with `is_valid` false (`DoNotIntervene`), where a local
  client's is judged as it came. It used to read and write Conway's alone, and a peer's transaction of
  another era ended the connection. A Byron transaction from a peer still ends it.
- The mempool applies every transaction at transaction index 0, as the reference's does, so a
  registration in a transaction held behind others gets the pointer the reference gives it.
