// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Id
import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicReference
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, Phase2, ScriptLocation}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPlanned, DijkstraRecordedBlocks}
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraBlockRun, DijkstraPhase2, DijkstraRuleDefect}
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
  * workers, as the node runs them (serially where they declare less work than it prepares above,
  * #85) and prepared wherever they may be ([[AlwaysPrepared]]), the same state, proposals and
  * counters, or the same refusal, and the same observations told the node's phase-2 observer
  * (`--plutus-cases`); and with the evaluator giving no verdict at each script of every such block
  * of two or more scripted batches, in turn, the same naming, a sub-transaction's script's among
  * them, and the same observations, the script with no verdict the last. The era run's prepared
  * plan on the reference's BBODY cases, the sub-transaction's and Alonzo's `:49` among them, and on
  * the crafted blocks whose scripts read what another batch changes, is `DijkstraBbodyImpSuite` 's
  * (ledger), on as many threads; the devnet chain through the node's rules is `MirrorDijkstraSuite`
  * 's.
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
          val told = DijkstraPlanned.Observations()
          val serial = DijkstraRecordedBlocks.program(sample, shared, Some(told.observer)).serial
          // as the node runs it, serially below the declared work it prepares above, and prepared
          // wherever it may be
          val ways =
            Vector[(String, DijkstraBlockRun.Body => Phase2.Program[DijkstraBlockRun.Result])](
              "as the node runs it" -> identity,
              "prepared" -> (AlwaysPrepared(_))
            )
          (services, ways).tupled.traverse_ { case ((n, service), (way, of)) =>
            val again = DijkstraPlanned.Observations()
            service(
              of(DijkstraRecordedBlocks.program(sample, shared.replaying, Some(again.observer)))
            )
              .map { result =>
                assertEquals(result, serial, s"block ${sample.block}, $n workers, $way")
                assertEquals(
                  again.all,
                  told.all,
                  s"block ${sample.block}, $n workers, $way: observed"
                )
              }
          }
        }
      }
  }

  test(
    "a case recorder whose every case fails to be written: each recorded block's result as with no recorder, serially and at one, two, four and eight workers"
  ) {
    // the cases' directory a regular file: no case can be written into it
    IO.blocking(java.nio.file.Files.createTempFile("scarab-plutus-cases", ".file"))
      .bracket { notADirectory =>
        services.flatMap { services =>
          val cases = tools.PlutusCases(notADirectory, perKind = 1000, max = 100000)
          DijkstraRecordedBlocks.samples
            .traverse_ { sample =>
              val shared = DijkstraPlanned.Shared(ScarabEvaluator.default)
              val unobserved = DijkstraRecordedBlocks.program(sample, shared).serial
              assertEquals(
                DijkstraRecordedBlocks
                  .program(sample, shared.replaying, Some(cases.observeDijkstra))
                  .serial,
                unobserved,
                s"block ${sample.block}, serially"
              )
              // prepared wherever it may be, below the declared work the node prepares above too
              services.traverse_ { (n, service) =>
                service(
                  AlwaysPrepared(
                    DijkstraRecordedBlocks
                      .program(sample, shared.replaying, Some(cases.observeDijkstra))
                  )
                ).map(result =>
                  assertEquals(result, unobserved, s"block ${sample.block}, $n workers")
                )
              }
            }
            .map { _ =>
              // every batch observed, serially and at each worker count, kept unwritten
              val failed = cases.failed()
              assertEquals(failed.size, 171 * 5)
              assert(
                failed.forall {
                  case tools.PlutusCases.Failure.Write(_, _) => true
                  case tools.PlutusCases.Failure.Recorder(_) => false
                },
                s"${failed.headOption}"
              )
            }
        }
      }(notADirectory => IO.blocking(java.nio.file.Files.delete(notADirectory)))
  }

  test(
    "every admitted recorded block of two or more scripted batches, evaluated by the workers themselves at one, two, four and eight: the serial run's state, proposals and counters"
  ) {
    val admitted = DijkstraRecordedBlocks.samples.flatMap { sample =>
      val body = DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default)
      body.serial match
        case Right(Right(applied)) if body.preparable   => Vector(sample -> applied)
        case Right(Right(_)) | Right(Left(_)) | Left(_) => Vector.empty
    }
    services.flatMap { services =>
      admitted
        .traverse_ { (sample, applied) =>
          // a fresh evaluator, no result given again: the workers' evaluations overlap for real
          services.traverse_ { (n, service) =>
            service(AlwaysPrepared(DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default)))
              .map(result =>
                assertEquals(result, Right(Right(applied)), s"block ${sample.block}, $n workers")
              )
          }
        }
        .map(_ => assertEquals(admitted.size, 17))
    }
  }

  test(
    "a script with no verdict at each place of every recorded block of two or more scripted batches: named as the serial run names it, at every worker count"
  ) {
    val reason: EvalFailure.Unsupported =
      EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(ExUnits(0, 0)))
    val prepared = DijkstraRecordedBlocks.samples.filter(sample =>
      DijkstraRecordedBlocks.program(sample, ScarabEvaluator.default).preparable
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
            val told = DijkstraPlanned.Observations()
            val serial = DijkstraRecordedBlocks.program(sample, shared, Some(told.observer)).serial
            services
              .traverse_ { (n, service) =>
                val again = DijkstraPlanned.Observations()
                service(
                  AlwaysPrepared(
                    DijkstraRecordedBlocks.program(sample, shared.replaying, Some(again.observer))
                  )
                ).map { result =>
                  assertEquals(result, serial, s"block ${sample.block}, $n workers")
                  assertEquals(again.all, told.all, s"block ${sample.block}, $n workers: observed")
                }
              }
              .as(serial -> told.all.lastOption.map(_.outcome))
          }
        }
        .map { judged =>
          val namings = judged.map(_._1)
          val located = namings.collect {
            case Left(
                  DijkstraRuleDefect.Ledger(LedgerFailure.Unavailable(_, location, `reason`))
                ) =>
              location
          }
          // each observed last as the script with no verdict, where it is named
          assertEquals(
            judged.map(_._2.collect {
              case DijkstraPhase2.Outcome.Unavailable(_, location, _, `reason`) => location
            }),
            located.map(Some(_))
          )
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
