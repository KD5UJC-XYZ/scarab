// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.mempool.{ConwayEntries, Entry}

/** A mempool entry for raw transaction bytes, a held Conway transaction read as the pool reads it
  * ([[ConwayEntries.held]]).
  */
object MempoolStateFixture:
  def entry(raw: Bytes): Entry = ConwayEntries.held(raw)
