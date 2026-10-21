// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.genesis

import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import UpstreamGenesisCases.*
import UpstreamGenesisReplay.{Downloaded, Result, Terminal}

/** The original ctProperty branches, plus Setup.hs279–296's exception constraint, at
  * consensus82ecba329. Snapshot rendering and cross-fiber finalizer order are not these properties.
  */
object UpstreamGenesisLaws:
  enum Law:
    case LoENoTimeout, LoETimeout, DensityDisconnect
    case WaitEnough, WaitTooLong, WaitForecast, ServeFast, ServeSlow, DelaySucceeds, DelayFails
    case CsjSharedHonest, CsjSeparateHonest, CsjSharedAdversaries, CsjSeparateAdversaries
    case BulkLeashing, Downtime, LeashingStalls, LeashingTimesOut, LoEStalls, AdversarialBranches
    case LongRangeAttack

  enum Violation:
    case UnexpectedException(terminal: Terminal)
    case ChainSyncExceptions(actual: Vector[End], alternatives: Set[Vector[End]])
    case TipEquality(actual: Point, trunk: Point, expected: Boolean)
    case TipMustBeAdversarial(actual: Point)
    case ImmutableMustBeHonest(actual: Point)
    case HonestDisconnected(peer: PeerId)
    case ImmutableTooOld(age: BigInt, maximum: BigInt)
    case InvalidHonestSchedules(peers: Vector[PeerId])
    case MissingHonestBlockSchedule
    case InvalidTrunkSuccessor(point: Point)
    case MissingTrunk
    case HonestDownloadBound(downloads: Vector[Downloaded], distinct: Int, jumpSize: BigInt)

  private val keys: Family => Vector[Law] =
    case Family.LoE               => Vector(Law.LoENoTimeout, Law.LoETimeout)
    case Family.DensityDisconnect => Vector(Law.DensityDisconnect)
    case Family.LoP =>
      Vector(
        Law.WaitEnough,
        Law.WaitTooLong,
        Law.WaitForecast,
        Law.ServeFast,
        Law.ServeSlow,
        Law.DelaySucceeds,
        Law.DelayFails
      )
    case Family.CSJ =>
      Vector(
        Law.CsjSharedHonest,
        Law.CsjSeparateHonest,
        Law.CsjSharedAdversaries,
        Law.CsjSeparateAdversaries
      )
    case Family.Uniform =>
      Vector(
        Law.BulkLeashing,
        Law.Downtime,
        Law.LeashingStalls,
        Law.LeashingTimesOut,
        Law.LoEStalls,
        Law.AdversarialBranches
      )
    case Family.LongRangeAttack => Vector(Law.LongRangeAttack)

  // The native observer encodes the original SmallKey ordinal in this identifier. Decode it once
  // at the fixture boundary into a closed case, rather than branching on descriptions in tests.
  def decode(label: String, family: Family, seed: Int): Option[Law] =
    val identity = raw"upstream-([A-Za-z]+)-([0-9]+)-seed-([0-9]+)".r
    label match
      case identity(name, ordinal, originalSeed)
          if name == family.toString && originalSeed.toIntOption.contains(seed) =>
        ordinal.toIntOption.flatMap(keys(family).lift)
      case _ => None

  private def honest(peer: PeerId): Boolean = peer match
    case _: PeerId.Honest      => true
    case _: PeerId.Adversarial => false

  private def slot(point: Point): BigInt = point match
    case Point.Origin          => 0
    case Point.Block(value, _) => value.value

  def evaluate(scenario: Scenario, actual: Result): Vector[Violation] =
    val unexpected = actual.terminals.collect {
      case terminal if terminal.end == End.Unexpected => Violation.UnexpectedException(terminal)
    }
    scenario.tree.headOption match
      case None => unexpected :+ Violation.MissingTrunk
      case Some(trunk) =>
        val csExceptions = actual.terminals.collect {
          case terminal
              if terminal.component == Component.ChainSyncClient && terminal.end != End.Success =>
            terminal.end
        }
        def exceptions(alternatives: Set[Vector[End]]): Vector[Violation] =
          Option
            .when(!alternatives(csExceptions))(
              Violation.ChainSyncExceptions(csExceptions, alternatives)
            )
            .toVector
        def exact(end: Option[End]): Vector[Violation] = exceptions(Set(end.toVector))
        def tipEquality(expected: Boolean): Vector[Violation] =
          Option
            .when((actual.selection.tip == trunk.tip) != expected)(
              Violation.TipEquality(actual.selection.tip, trunk.tip, expected)
            )
            .toVector
        def immutableHonest: Vector[Violation] =
          Option
            .when(!trunk.points.contains(actual.selection.anchor))(
              Violation.ImmutableMustBeHonest(actual.selection.anchor)
            )
            .toVector
        // Uniform.hs110–175; the implication's antecedent is Classifiers.hs97. Retain every input,
        // including short chains for which the original implication imposes no additional claim.
        def uniform: Vector[Violation] =
          if slot(trunk.tip) < scenario.genesisWindow then Vector.empty
          else
            val disconnected = actual.terminals
              .map(_.peer)
              .distinct
              .filter(honest)
              .map(Violation.HonestDisconnected(_))
            val honestSchedules = scenario.schedules.filter(s => honest(s.peer))
            // PartialAccessors.hs36–42 requires exactly one honest peer, with native id1.
            val lawfulHonest = honestSchedules match
              case Vector(schedule) if schedule.peer == PeerId.Honest(1) => Some(schedule)
              case _                                                     => None
            val lastHonestBlock = lawfulHonest.flatMap(
              _.events
                .collect { case Timed(_, Event.Block(point: Point.Block)) => point }
                .lastOption
            )
            val recent = lastHonestBlock match
              case None if lawfulHonest.isEmpty =>
                Vector(Violation.InvalidHonestSchedules(honestSchedules.map(_.peer)))
              case None => Vector(Violation.MissingHonestBlockSchedule)
              case Some(point) =>
                val age = (point.slot.value - slot(actual.selection.anchor)).mod(BigInt(1) << 64)
                val maximum = (scenario.genesisWindow + scenario.delay + 1).mod(BigInt(1) << 64)
                Option.when(age > maximum)(Violation.ImmutableTooOld(age, maximum)).toVector
            disconnected ++ immutableHonest ++ recent
        val property = scenario.law match
          // LoE.hs108–124.
          case Law.LoENoTimeout => tipEquality(false) ++ exact(None)
          case Law.LoETimeout   => tipEquality(true) ++ exact(Some(End.Timeout))
          // DensityDisconnect.hs580–591 permits either refusal but requires the exact trunk tip.
          case Law.DensityDisconnect =>
            val noAdversaries = !scenario.schedules.exists(s => !honest(s.peer))
            val alternatives = Set(Vector(End.DensityTooLow), Vector(End.CandidateTooSparse)) ++
              Option.when(noAdversaries)(Vector.empty[End])
            tipEquality(true) ++ exceptions(alternatives)
          // LoP.hs127–132,175–179,231–236,303–320.
          case Law.WaitEnough | Law.WaitForecast | Law.ServeFast => exact(None)
          case Law.WaitTooLong | Law.ServeSlow                   => exact(Some(End.PatienceExpired))
          case Law.DelaySucceeds => tipEquality(false) ++ exact(None)
          case Law.DelayFails    => tipEquality(true) ++ exact(Some(End.PatienceExpired))
          // CSJ.hs166–234. Normal received headers, before validation; drain handlers do not trace.
          case Law.CsjSharedHonest | Law.CsjSeparateHonest | Law.CsjSharedAdversaries |
              Law.CsjSeparateAdversaries =>
            val successor = trunk.tip match
              case Point.Origin => Some(BigInt(0))
              case Point.Block(value, _) if value.value < (BigInt(1) << 64) - 1 =>
                Some(value.value + 1)
              case _ => None
            successor match
              // Block/Abstract.hs330–331 uses partial Enum.succ at the Word64 maximum.
              case None => Vector(Violation.InvalidTrunkSuccessor(trunk.tip))
              case Some(boundary) =>
                val counted = actual.downloaded.filter(download =>
                  honest(download.peer) &&
                    (download.header.slot.value + scenario.jumpSize).mod(BigInt(1) << 64) < boundary
                )
                val distinct = counted.map(_.header).distinct.size
                Option
                  .when(BigInt(counted.size) > BigInt(distinct) + scenario.jumpSize)(
                    Violation.HonestDownloadBound(counted, distinct, scenario.jumpSize)
                  )
                  .toVector
          case Law.BulkLeashing | Law.Downtime | Law.LeashingStalls | Law.LeashingTimesOut |
              Law.AdversarialBranches =>
            uniform
          // Uniform.hs423–437 deliberately asserts only immutable-chain safety in this stalled case.
          case Law.LoEStalls => immutableHonest
          // LongRangeAttack.hs69–89 is an explicit negative control with Genesis disabled.
          case Law.LongRangeAttack =>
            Option
              .when(trunk.points.contains(actual.selection.tip))(
                Violation.TipMustBeAdversarial(actual.selection.tip)
              )
              .toVector
        unexpected ++ property
