// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import xyz.kd5ujc.scarab.codec.Bytes

/** The blocks already fetched, as BlockFetch reads them: a membership test, not a set
  * (`readFetchedBlocks :: STM m (Point block -> Bool)`, ouroboros-network c45735a5
  * BlockFetch/ConsensusInterface.hs:100-101). The owner captures its state once for a decision, so
  * every candidate of that decision asks the same question of the same state, and nothing is copied
  * into a new set for each decision.
  */
opaque type Fetched = Bytes => Boolean

object Fetched:
  val none: Fetched = _ => false

  def apply(contains: Bytes => Boolean): Fetched = contains

  def of(hashes: Set[Bytes]): Fetched = hashes.contains

  extension (fetched: Fetched) def contains(hash: Bytes): Boolean = fetched(hash)
