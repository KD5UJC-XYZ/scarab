// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import munit.Assertions.fail
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraBlock, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

/** The era's Dijkstra transactions of recorded blocks and endorser blocks, which the forger's
  * Dijkstra path takes, read by the era's decoders from the recorded bytes.
  */
object DijkstraEraTxs:
  /** The transactions of the block `raw` (`[8, [header, body]]`), read by the era's block decoder.
    */
  def ofBlock(raw: Bytes)(using munit.Location): Vector[Tx[TxLevel.Top, Dijkstra]] =
    DijkstraBlock
      .decode(raw)
      .fold(e => fail(s"the era's decoder refuses the block: $e"), _.blockBody.txs)

  /** A transaction in its block form, `[body, witnesses, auxiliary data / null, is_valid]`, as a
    * block body or an endorser block's closure holds it.
    */
  def inBlock(raw: Bytes)(using munit.Location): Tx[TxLevel.Top, Dijkstra] =
    Cbor
      .decode(raw)
      .fold(
        e => fail(s"not CBOR: $e"),
        n => DijkstraTx.inBlock(n).fold(e => fail(s"the era's decoder refuses it: $e"), identity)
      )
