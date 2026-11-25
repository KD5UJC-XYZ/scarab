// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayPhase2
import xyz.kd5ujc.scarab.node.MirrorRules

/** Interpreter of the existing synchronous tooling callback. Observation and drain run in the
  * ordered validation owner, after joined workers land their values. One block's immutable
  * observations are retained; draining at afterBlock releases them before the next block. A block
  * of an era before Conway is observed by the flat rules (`Phase2.Observation`), a Conway block by
  * its era run (`ConwayPhase2.Observation`).
  */
final class ReplayObservations:
  private val pending =
    Vector.newBuilder[Either[Phase2.Observation, ConwayPhase2.Observation]]

  def observe(observation: Phase2.Observation): Unit =
    val _ = pending.addOne(Left(observation))

  def observeConway(observation: ConwayPhase2.Observation): Unit =
    val _ = pending.addOne(Right(observation))

  /** The node's phase-2 observer that tells this one of both. */
  def observer: MirrorRules.Phase2Observer = MirrorRules.Phase2Observer(observe, observeConway)

  private[preview] def drainRaw(): Vector[Either[Phase2.Observation, ConwayPhase2.Observation]] =
    val observations = pending.result()
    pending.clear()
    observations

  def drain(): Vector[ReplayTrace.Transaction] =
    drainRaw().map(_.fold(ReplayTrace.from, ReplayTrace.from))
