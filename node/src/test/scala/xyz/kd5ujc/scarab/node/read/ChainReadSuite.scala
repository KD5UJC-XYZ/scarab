// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.consensus.{FollowerEvent, NodeState}
import xyz.kd5ujc.scarab.node.NodeRules
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** The two readings of a state agree: the node's flat state read through the mirror's vocabulary
  * ([[FlatEraRead]], converting the part asked for) and its mirror read as it is
  * ([[MirrorEraRead]]) give the same answer to every question of [[ChainRead]] and [[EraRead]], at
  * every sampled state of the era-walking capture, Byron to Conway.
  */
class ChainReadSuite extends FunSuite:
  private given ChainRead[NodeState] = ChainRead.nodeState(ErasStates.env)
  private val viaMirror: ChainRead[MirrorState] = summon[ChainRead[MirrorState]]

  /** The first, the last and every 20th state, so that every era and every boundary is in. */
  private lazy val chosen: Vector[ErasStates.Sample] =
    val all = ErasStates.samples
    all.zipWithIndex.collect {
      case (s, i)
          if i == 0 || i % 20 == 0 || i >= all.size - 3 || i + 1 < all.size && all(
            i + 1
          ).era != s.era =>
        s
    }

  /** Every fact of an era that the readers ask for, compared between the two readings. */
  private def sameFacts[E <: EraTag](a: EraRead[E], b: EraRead[E], slot: BigInt)(using
      ShelleyBasedEra[E]
  ): List[(String, Boolean)] =
    import EraInstances.given
    def same[A](x: Read[A], y: Read[A])(using eq: cats.Eq[A]): Boolean =
      (x, y).mapN(eq.eqv).getOrElse(false)
    val pa = a.params
    val pb = b.params
    def readsSame(x: ProtocolParams, y: ProtocolParams): Boolean =
      x.protocolMajor == y.protocolMajor && x.protocolMinor == y.protocolMinor &&
        x.effectiveD == y.effectiveD && x.extraEntropy == y.extraEntropy &&
        x.maxBlockHeaderSize == y.maxBlockHeaderSize && x.maxBlockBodySize == y.maxBlockBodySize
    List(
      "scalars" -> (a.epoch === b.epoch && (a.scalars, b.scalars).mapN { (x, y) =>
        x.epoch === y.epoch && x.bprev === y.bprev && x.bcur === y.bcur &&
        x.chainAccount === y.chainAccount && x.deposited === y.deposited && x.fees === y.fees &&
        x.donation === y.donation
      }.getOrElse(false)),
      "parameters the forecast reads" ->
        readsSame(pa, pb),
      "counts" -> (a.accountCount == b.accountCount && a.poolCount == b.poolCount),
      "pool distribution in force" -> (a.leaderPools(false) == b.leaderPools(false)),
      "pool distribution of the next epoch" -> (a.leaderPools(true) == b.leaderPools(true)),
      "genesis delegations" ->
        List(BigInt(0), slot, slot + 1000, slot + 10000000)
          .forall(s => a.genDelegsAt(s) == b.genDelegsAt(s)),
      "next epoch parameters" -> {
        val na = a.paramsNextEpoch(ErasStates.setup.globals)
        val nb = b.paramsNextEpoch(ErasStates.setup.globals)
        // the fields the era has no place for are zero in a mirror
        val ok = readsSame(na, nb)
        if !ok then println(s"next params differ: $na / $nb")
        ok
      },
      "certificate state" -> same(a.cert, b.cert),
      "governance state" -> same(a.gov, b.gov),
      "instant stake" -> same(a.instantStake, b.instantStake),
      "snapshots" -> same(a.snapshots, b.snapshots),
      "pool distribution (nesPd)" -> same(a.pd, b.pd),
      "reward update (deltas)" ->
        (a.rewardsDue, b.rewardsDue)
          .mapN { (x, y) =>
            x.map(r => (r.deltaT, r.deltaR, r.deltaF)) === y
              .map(r => (r.deltaT, r.deltaR, r.deltaF))
          }
          .getOrElse(false),
      "the whole new-epoch state" -> same(a.nes, b.nes)
    )

  private def facts(x: InEra, y: InEra, slot: BigInt): List[(String, Boolean)] =
    (x, y) match
      case (a: InEra.Of[ea], b: InEra.Of[eb]) => factsOf(a.era, a.read, b.era, b.read, slot)

  /** Matching the two witnesses refines both era parameters to the one era. */
  private def factsOf[A <: EraTag, B <: EraTag](
      ea: ShelleyBasedEra[A],
      x: EraRead[A],
      eb: ShelleyBasedEra[B],
      y: EraRead[B],
      slot: BigInt
  ): List[(String, Boolean)] =
    import ShelleyBasedEra.*
    (ea, eb) match
      case (SShelley, SShelley) => sameFacts(x, y, slot)(using ea)
      case (SAllegra, SAllegra) => sameFacts(x, y, slot)(using ea)
      case (SMary, SMary)       => sameFacts(x, y, slot)(using ea)
      case (SAlonzo, SAlonzo)   => sameFacts(x, y, slot)(using ea)
      case (SBabbage, SBabbage) => sameFacts(x, y, slot)(using ea)
      case (SConway, SConway)   => sameFacts(x, y, slot)(using ea)
      case _                    => List("the same era" -> false)

  test("the chain-level questions agree on the flat state and its mirror") {
    chosen.foreach { s =>
      val m = s.mirror
      val label = s"${s.era} block ${s.block.header.blockNo}"
      assertEquals(ChainRead[NodeState].era(s.state), viaMirror.era(m), label)
      assertEquals(ChainRead[NodeState].protocol(s.state), viaMirror.protocol(m), label)
      assertEquals(ChainRead[NodeState].consensus(s.state), viaMirror.consensus(m), label)
      assertEquals(ChainRead[NodeState].plutus(s.state), viaMirror.plutus(m), label)
    }
  }

  test("every fact of an era agrees between the two readings, in every era of the capture") {
    val results = chosen.map { s =>
      val flatRead = ChainRead[NodeState].ledger(s.state)(Some(s.anchor))
      val mirrorRead = viaMirror.ledger(s.mirror)(Some(s.anchor))
      val label = s"${s.era} block ${s.block.header.blockNo}"
      (flatRead, mirrorRead) match
        case (Right(None), Right(None)) => (s.era, label, List.empty[(String, Boolean)])
        case (Right(Some(x)), Right(Some(y))) =>
          (s.era, label, facts(x, y, s.anchor.slot).filterNot(_._2))
        case other => (s.era, label, List(s"read $other" -> false))
    }
    val bad = results.filter(_._3.nonEmpty)
    assert(
      bad.isEmpty,
      bad.take(5).map((_, l, f) => s"$l: ${f.map(_._1).mkString(", ")}").mkString("; ")
    )
    assertEquals(
      results.map(_._1).distinct,
      Vector(Era.Byron, Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage, Era.Conway)
    )
  }

  test("the follower's events are the same from the flat state and from its mirror") {
    val all = ErasStates.samples
    val geometry = ErasStates.setup.geometry
    val pairs = all.indices.dropRight(1).filter { i =>
      val (a, b) = (all(i), all(i + 1))
      a.state.ledger.epoch != b.state.ledger.epoch || a.era != b.era || i % 25 == 0
    }
    val epochLines = pairs.map { i =>
      val (a, b) = (all(i), all(i + 1))
      val viaFlat = NodeRules.observe[NodeState](geometry)(a.state, b.state, b.anchor)
      val viaMirror = NodeRules.observe[MirrorState](geometry)(a.mirror, b.mirror, b.anchor)
      assertEquals(viaMirror, viaFlat, s"${b.era} block ${b.block.header.blockNo}")
      viaFlat.count(_.isInstanceOf[FollowerEvent.LedgerEpoch])
    }
    // the epoch lines are among what was compared
    assert(epochLines.sum >= 7, epochLines.sum.toString)
  }
