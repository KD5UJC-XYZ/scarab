// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.{Eq, Show}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, EraTag, ShelleyBasedEra}

/** PLACEHOLDER for `xyz.kd5ujc.scarab.ledger.state.NewEpochState[E]` (stage S2, written in
  * parallel). Delete this file when S2 is merged and add `NewEpochState` to the `ledger.state`
  * import of `ShelleyLedgerState.scala` and of the test generators; nothing else mentions this
  * type. It has the one field the tests need and the shape of the instances the real one has (an
  * `Eq` and a `Show` by the era witness).
  */
final case class NewEpochState[E <: EraTag](epoch: EpochNo)

object NewEpochState:
  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[NewEpochState[E]] =
    Eq.by[NewEpochState[E], EpochNo](_.epoch)

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[NewEpochState[E]] =
    Show.show(n => s"NewEpochState(epoch = ${Show[EpochNo].show(n.epoch)})")
