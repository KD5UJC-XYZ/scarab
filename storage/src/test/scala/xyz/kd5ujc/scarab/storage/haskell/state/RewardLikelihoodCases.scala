// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, RewardPulserFixture}
import xyz.kd5ujc.scarab.ledger.state.*

/** Additional full-state oracle inputs for local likelihood creation, separate from the original
  * pulser corpus so its original imported states and timing schedules remain unchanged.
  */
object RewardLikelihoodCases:
  import RewardPulserFixture.*
  import RewardOracleCases.{Input, Operation}
  private val steps = Vector(Operation.Pulse, Operation.Complete)
  private val base = rewarding(conway(5, 7, 3))
  private val history = NonMyopic(
    Sorted.map(
      Vector
        .range(0, 4)
        .map(p =>
          pool(p) -> Likelihood(
            Vector.tabulate(100)(i => LogWeight((i * (p + 1) - 40).toFloat / 8f))
          )
        )
    ),
    coin(12345)
  )
  private def input(name: String, state: NewEpochState[Conway]): Input =
    Input(name, Some(EraNes.InEra(ShelleyBasedEra.SConway, state)), None, steps)
  private val noBlocks = base.copy(bprev = BlocksMade.empty)
  private val onePool =
    base.copy(bprev = BlocksMade(Sorted.map(Vector(pool(0) -> Word64.fromUnsigned(7)))))

  val inputs: Vector[Input] = Vector(
    input("likelihood-no-pools", conway(5, 0, 0)),
    input("likelihood-no-blocks", noBlocks),
    input("likelihood-one-producing-pool", onePool),
    input(
      "likelihood-zero-production-history",
      noBlocks.copy(es = noBlocks.es.copy(nonMyopic = history))
    ),
    input("likelihood-produced-history", base.copy(es = base.es.copy(nonMyopic = history)))
  ) ++ Vector(Rational.zero, Rational(1, 2), Rational.one).map { d =>
    val nes = RewardOracleCases.shelley(2)
    val gov = nes.es.ls.utxo.gov.copy(prevPParams = nes.es.ls.utxo.gov.prevPParams.copy(d = d))
    val changed =
      nes.copy(es =
        nes.es.copy(ls = nes.es.ls.copy[Shelley](utxo = nes.es.ls.utxo.copy[Shelley](gov = gov)))
      )
    Input(
      s"likelihood-shelley-d-$d",
      Some(EraNes.InEra(ShelleyBasedEra.SShelley, changed)),
      None,
      steps
    )
  }

  def main(args: Array[String]): Unit =
    Files.writeString(Path.of(args(0)), Json.arr(inputs.map(RewardOracleCases.json)*).spaces2)
