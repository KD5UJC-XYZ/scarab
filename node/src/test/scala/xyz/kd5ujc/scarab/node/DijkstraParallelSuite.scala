// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicReference
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, ScriptLocation}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPlanned, DijkstraRecordedBlocks}
import xyz.kd5ujc.scarab.ledger.era.rules.DijkstraRuleDefect
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  EvalFailure,
  EvalRequest,
  EvalResult,
  ExUnits,
  ScarabEvaluator,
  ScriptEvaluator
}

/** Dijkstra's era run on a block through the node's bounded workers (`ParallelPlutus`, ADR 0018),
  * against its serial run (`DijkstraBlockRun.program`, #85): on every recorded block of the public
  * Leios testnet's script transactions (`DijkstraRecordedBlocks`), at one, two, four and eight
  * workers, the same state, proposals and counters, or the same refusal; and with the evaluator
  * giving no verdict at each script of every such block of two or more scripted batches, in turn,
  * the same naming, a sub-transaction's script's among them. The era run's prepared plan on the
  * reference's BBODY cases, the sub-transaction's and Alonzo's `:49` among them, and on the crafted
  * blocks whose scripts read what another batch changes, is `DijkstraBbodyImpSuite` 's (ledger), on
  * as many threads; the devnet chain through the node's rules is `MirrorDijkstraSuite` 's.
  */
class DijkstraParallelSuite extends FailsOnUncaught:
  /** The node's bounded workers, one, two, four and eight, each with its count. */
  private val services: IO[Vector[(Int, ParallelPlutus[IO])]] =
    Vector(1, 2, 4, 8).traverse(n =>
      ParallelPlutus.Workers
        .from(n)
        .fold(e => IO(fail(s"$e")), ParallelPlutus.create[IO](_))
        .map(n -> _)
    )

  test(
    "every recorded block through the node's bounded workers, one, two, four and eight: the serial run's result"
  ) {
    services
      .flatMap { services =>
        DijkstraRecordedBlocks.samples.traverse_ { sample =>
          // the serial run's results given again, so that a failing script's term, which
          // compares by identity, is the serial run's
          val shared = DijkstraPlanned.Shared(ScarabEvaluator.default)
          val serial = DijkstraRecordedBlocks.program(sample, shared).serial
          services.traverse_ { (n, service) =>
            service(DijkstraRecordedBlocks.program(sample, shared.replaying))
              .map(result => assertEquals(result, serial, s"block ${sample.block}, $n workers"))
          }
        }
      }
  }

  test(
    "a script with no verdict at each place of every recorded block of two or more scripted batches: named as the serial run names it, at every worker count"
  ) {
    val reason: EvalFailure.Unsupported =
      EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(ExUnits(0, 0)))
    val prepared = DijkstraRecordedBlocks.samples.filter(sample =>
      DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default).worthwhile
    )
    services.flatMap { services =>
      prepared
        .flatTraverse { sample =>
          // the requests the serial run asks, in its order
          val asked = AtomicReference(Vector.empty[EvalRequest])
          val recording: ScriptEvaluator[Id] = request =>
            val _ = asked.updateAndGet(_ :+ request)
            ScarabEvaluator.default.evaluate(request)
          val _ = DijkstraRecordedBlocks.program(sample, recording).serial
          asked.get.distinct.traverse { request =>
            val unjudged: ScriptEvaluator[Id] = r =>
              if r == request then EvalResult.Failure(reason, ExUnits(0, 0), Vector.empty)
              else ScarabEvaluator.default.evaluate(r)
            val shared = DijkstraPlanned.Shared(unjudged)
            val serial = DijkstraRecordedBlocks.program(sample, shared).serial
            services
              .traverse_ { (n, service) =>
                service(DijkstraRecordedBlocks.program(sample, shared.replaying))
                  .map(result => assertEquals(result, serial, s"block ${sample.block}, $n workers"))
              }
              .as(serial)
          }
        }
        .map { namings =>
          val located = namings.collect {
            case Left(
                  DijkstraRuleDefect.Ledger(LedgerFailure.Unavailable(_, location, `reason`))
                ) =>
              location
          }
          // every script so judged is named, none left unnamed
          assertEquals(located.size, namings.size)
          // what the sample exercises: the 41 distinct scripts of the 17 blocks, three of them a
          // sub-transaction's (`ScriptLocation.SubPhase2`)
          assertEquals(namings.size, 41)
          assertEquals(
            located.count {
              case _: ScriptLocation.SubPhase2                         => true
              case _: ScriptLocation.Phase2 | _: ScriptLocation.Phase1 => false
            },
            3
          )
        }
    }
  }
