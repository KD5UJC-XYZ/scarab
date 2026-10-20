// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import xyz.kd5ujc.scarab.ledger.Phase2

/** Interpreter of the existing synchronous tooling callback. Observation and drain run in the
  * ordered validation owner, after joined workers land their values. One block's immutable
  * observations are retained; draining at afterBlock releases them before the next block.
  */
final class ReplayObservations:
  private val pending = Vector.newBuilder[Phase2.Observation]

  def observe(observation: Phase2.Observation): Unit =
    val _ = pending.addOne(observation)

  private[preview] def drainRaw(): Vector[Phase2.Observation] =
    val observations = pending.result()
    pending.clear()
    observations

  def drain(): Vector[ReplayTrace.Transaction] = drainRaw().map(ReplayTrace.from)
