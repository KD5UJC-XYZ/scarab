// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.IO
import cats.mtl.Raise
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import munit.Assertions.{assert, assertEquals, fail}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{MirrorClock, MirrorState, MirrorStep, Placement}
import xyz.kd5ujc.scarab.ledger.{Block, PlutusStats, StorageError}
import xyz.kd5ujc.scarab.ledger.era.PParams
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayBlock, ConwayPParams}
import xyz.kd5ujc.scarab.ledger.era.rules.{BbodyEnv, ConwayBlockRun, ShelleyBbodyState}
import xyz.kd5ujc.scarab.ledger.rules.{EraBlock, EraNes, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  LedgerState,
  NewEpochState,
  ShelleyBasedEra,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.{NodeRules, NodeSetup, ParallelPlutus}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}
import xyz.kd5ujc.scarab.storage.UtxoStore

/** Conway's BBODY on a recorded block four ways, from the one ticked state the node's mirror ticks
  * it to (`MirrorRules`): the flat rules' body (`EraBlock.Body`) and the era run's
  * (`ConwayBlockRun.Body`), each serial and through the node's bounded workers (ADR 0018), in an
  * order rotated by the block and the round. Each is timed alone; the block's transactions and the
  * era block are decoded, and the inputs read from the store, outside the timings. The era run's
  * result through the workers is checked against its serial result, the flat rules' through the
  * workers against theirs, and the era run's against the flat rules' (the same ledger state and
  * counters), on the first round, as reading the whole state costs; the last is reported, not
  * asserted: the node does not apply Conway blocks with the era run yet (#85).
  */
object EraBodies:
  /** The bound of the node's workers the two parallel ways run at, and the rounds of each block. */
  final case class Timing(workers: ParallelPlutus.Workers, rounds: Int)

  /** The four ways, as the timings name them. */
  enum Way:
    case FlatSerial, FlatParallel, EraSerial, EraParallel

  /** What a body came to, as a reader of the timings sees it: its counters, or its refusal. */
  private def described(result: Either[EraBlock.Outcome, ConwayBlockRun.Result]): String =
    result match
      case Left(EraBlock.Outcome.Applied(_, plutus)) => s"applied $plutus"
      case Left(other)                               => s"$other".take(2000)
      case Right(Right(Right(applied)))              => s"applied ${applied.plutus}"
      case Right(Right(Left(ConwayBlockRun.Refusal.Failed(failures)))) =>
        s"refused $failures".take(2000)
      case Right(Right(Left(ConwayBlockRun.Refusal.Stopped(failures, defect)))) =>
        s"refused $failures, stopped by defect $defect".take(2000)
      case Right(Left(defect)) => s"defect $defect".take(2000)

  private def timed[A](fa: IO[A]): IO[(A, Long)] =
    for
      began <- IO.monotonic
      a <- fa
      ended <- IO.monotonic
    yield (a, (ended - began).toNanos)

  /** The timings of `block`, from `state`, if it is a Conway block: one row a round. */
  def measure(
      setup: NodeSetup,
      layout: StateLayout,
      store: UtxoStore[IO],
      state: MirrorState,
      block: Block.Shelley,
      raw: Bytes,
      parallel: ParallelPlutus[IO],
      rounds: Int,
      index: Int
  )(using Raise[IO, StorageError]): IO[Vector[Json]] =
    val header = block.header
    if block.era != Era.Conway then IO.pure(Vector.empty)
    else
      val prepared = NodeRules.prepare(header, raw, setup.praos.verdicts)
      val inputs = prepared.fold(Set.empty[xyz.kd5ujc.scarab.ledger.TxIn])(_.inputs.toSet)
      store.read(state.utxo.missing(inputs)).flatMap { fetched =>
        IO.defer {
          val resident = MirrorStep.mapUtxo(state)(_.withResident(fetched))
          // the configuration and constants of the chain within Shelley, its geometry resolved
          // from the first Shelley epoch, as `MirrorRules` takes them (`NodeRules.entry`)
          val entry = NodeRules
            .entry(resident.consensus.byron, header, setup.praos, setup.globals, setup.byronConfig)
            .fold(e => fail(s"entry: $e"), identity)
          val (config, constants) = (entry.configuration, entry.constants)
          val geometry = config.geometry
          val placement = Placement(geometry, setup.byronHistory, config.forks)
          val clock =
            MirrorClock.of(constants.systemStart, resident, placement, Era.Conway, header.slot)
          val backend = PlutusBackend.default
          val globals = Globals(constants, geometry)
          val ctx = EraBlock.Context(
            globals,
            config.forks.transition(geometry.k),
            MirrorStep.afterVotingOf(resident),
            verdicts = config.verdicts,
            evaluator = backend.evaluator,
            decoder = backend.decoder,
            clock = Some(clock)
          )
          val nes = MirrorStep.nesOf(resident).getOrElse(fail("no Shelley-based state"))
          val stamped = EraNes.mapUtxo(nes)(_.at(header.blockNo.toLong))
          val ticked: NewEpochState[Conway] =
            EraBlock
              .ticked(ctx)(stamped, header, block)
              .fold(e => fail(s"tick: $e"), identity) match
              case i: EraNes.InEra[e] =>
                i.era match
                  case ShelleyBasedEra.SConway => i.nes
                  case other                   => fail(s"ticked into $other")
          val txs = prepared.fold(EraBlock.decode(block))(_.txs)
          def flat: EraBlock.Body =
            EraBlock.program(ctx)(
              EraNes.InEra(ShelleyBasedEra.SConway, ticked),
              header,
              block,
              txs,
              PlutusStats.zero
            )
          val eraBlock = ConwayBlock.decode(raw, layout).fold(e => fail(s"era block: $e"), identity)
          val params = ticked.es.ls.utxo.gov.curPParams
          val pp =
            PParams[Conway](ConwayPParams.fromStored(params).fold(e => fail(s"$e"), identity))
          val ls = ticked.es.ls
          val bbody = ShelleyBbodyState[Conway](
            ls.copy[Conway](utxo =
              ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo.at(header.blockNo.toLong)))
            ),
            ticked.bcur
          )
          def era: ConwayBlockRun.Body =
            ConwayBlockRun.program(
              globals,
              BbodyEnv[Conway](pp, ticked.es.chainAccount),
              eraBlock,
              globals.systemStart,
              MirrorClock.horizon(resident, placement, Era.Conway, header.slot),
              bbody,
              backend.evaluator,
              config.verdicts,
              backend.decoder,
              clock.posix
            )
          def run(way: Way): IO[(Either[EraBlock.Outcome, ConwayBlockRun.Result], Long)] =
            way match
              case Way.FlatSerial   => timed(IO.blocking(flat.serial)).map(_.leftMap(Left(_)))
              case Way.FlatParallel => timed(parallel(flat)).map(_.leftMap(Left(_)))
              case Way.EraSerial    => timed(IO.blocking(era.serial)).map(_.leftMap(Right(_)))
              case Way.EraParallel  => timed(parallel(era)).map(_.leftMap(Right(_)))
          val ways = Way.values.toVector
          val redeeming = txs.count(_.exists(_.witnesses.redeemers.nonEmpty))
          (0 until rounds).toVector.traverse { round =>
            val rotation = (index + round) % ways.size
            (ways.drop(rotation) ++ ways.take(rotation))
              .traverse(way => run(way).map(way -> _))
              .map { results =>
                val by = results.toMap
                // the results compared on the first round only: a comparison reads the whole state
                val sameAsFlat = Option.when(round == 0) {
                  val flatSerial = by(Way.FlatSerial)._1
                  val eraSerial = by(Way.EraSerial)._1
                  assertEquals(by(Way.FlatParallel)._1, flatSerial, s"flat: slot ${header.slot}")
                  val agrees = (eraSerial, by(Way.EraParallel)._1) match
                    case (Right(Right(Right(s))), Right(Right(Right(p)))) =>
                      cats.Eq[LedgerState[Conway]].eqv(s.state.ls, p.state.ls) &&
                      s.state.blocksMade == p.state.blocksMade && s.plutus == p.plutus
                    case (s, p) => s == p
                  assert(agrees, s"era: slot ${header.slot}")
                  (flatSerial, eraSerial) match
                    case (Left(EraBlock.Outcome.Applied(after, plutus)), Right(Right(Right(e)))) =>
                      after match
                        case i: EraNes.InEra[x] =>
                          i.era match
                            case ShelleyBasedEra.SConway =>
                              cats.Eq[LedgerState[Conway]].eqv(i.nes.es.ls, e.state.ls) &&
                              i.nes.bcur == e.state.blocksMade && plutus == e.plutus
                            case _ => false
                    case _ => false
                }
                // where the two sides differ, the row says what each came to, for the reader
                Json.obj(
                  "slot" -> header.slot.asJson,
                  "block" -> header.blockNo.asJson,
                  "round" -> round.asJson,
                  "transactions" -> txs.size.asJson,
                  "redeeming" -> redeeming.asJson,
                  "eraAgreesWithFlat" -> sameAsFlat.asJson,
                  "differs" -> Option
                    .when(sameAsFlat.contains(false))(
                      Json.obj(
                        "flat" -> described(by(Way.FlatSerial)._1).asJson,
                        "era" -> described(by(Way.EraSerial)._1).asJson
                      )
                    )
                    .asJson,
                  "nanos" -> Json.obj(ways.map(w => w.toString -> by(w)._2.asJson)*)
                )
              }
          }
        }
      }
