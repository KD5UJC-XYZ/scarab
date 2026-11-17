// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.mempool.{Entry, MempoolState}
import xyz.kd5ujc.scarab.protocol.Era

/** A mempool entry for raw transaction bytes (parsed like the mempool does). */
object MempoolStateFixture:
  def entry(raw: Bytes): Entry =
    val parsed = MempoolState.parseParts(raw).toOption.get
    Entry(Era.Conway, parsed.parts, parsed.tx, 1L)
