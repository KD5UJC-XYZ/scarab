// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.mempool.{Entry, MempoolState}

/** A mempool entry for raw transaction bytes (parsed like the mempool does). */
object MempoolStateFixture:
  def entry(raw: Bytes): Entry =
    val (id, tx) = MempoolState.parse(raw).toOption.get
    Entry(id, raw, tx, 1L)
