// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{
  ConwayStake as S,
  ConwayEpochBoundary as B,
  ConwayRewardPulser as P,
  ConwayNonMyopic as NM
}
import ReferenceJson.Json as J
import RepeatedTerminalCbor.{Node, Value as V}
import RepeatedTerminalSupport.*
import scala.util.boundary

/** Exact observational projection of the pinned Shelley 1.19.0.1 RewardSnapShot/RSLP codec. No
  * completion, phase normalization, native admission, event publication or recovery authority.
  * RewardUpdate.hs SHA256: 606bd0b166e678d7cb6993adea6f66760aea4504dfbba09675d954f52db15317. Native
  * VMap decoding sorts semantic keys; credential traversal is Script before Key, then hash.
  */
private[lab] object RepeatedTerminalRewardPulser:
  final case class Snapshot(
      fees: BigInt,
      protocol: (BigInt, BigInt),
      nonMyopic: NM.State,
      deltaR1: BigInt,
      rewardPot: BigInt,
      deltaT1: BigInt,
      likelihoods: Map[Bytes, Vector[Int]],
      leaders: Map[S.Credential, Set[B.Reward]]
  )
  final case class PoolInfo(
      sigma: S.Ratio,
      pot: BigInt,
      snapshot: S.PoolSnapshot,
      blocks: BigInt,
      leader: B.Reward
  )
  final case class FreeVars(
      registered: Set[S.Credential],
      circulation: BigInt,
      protocol: (BigInt, BigInt),
      pools: Map[Bytes, PoolInfo]
  )
  final case class Projection(
      snapshot: Snapshot,
      pulseSize: Int,
      freeVars: FreeVars,
      remaining: Vector[(S.Credential, S.Active)],
      accumulated: Map[S.Credential, B.Reward],
      recent: Map[S.Credential, Set[B.Reward]]
  )
  private def ordered(c: S.Credential): (Int, String) = (if c.script then 0 else 1, c.hash.hex)
  private def protocol(n: Node)(using Scope): (BigInt, BigInt) =
    val a = arr(n, 2)
    val p = uint(a(0)) -> uint(a(1))
    equal(p, BigInt(9) -> BigInt(0), "active reward PV9.0")
    p
  private def member(n: Node)(using Scope): B.Reward =
    val r = RepeatedPlutusTerminal.reward(n)
    valid(r.kind == B.RewardKind.Member && r.amount > 0, "positive member reward")
    r
  private def rewardSets(n: Node, kind: B.RewardKind)(using
      Scope
  ): Map[S.Credential, Set[B.Reward]] = mapping(n)(
    credential,
    x =>
      val rs = set(x)(RepeatedPlutusTerminal.reward)
      valid(rs.nonEmpty && rs.forall(_.kind == kind), "nonempty homogeneous reward set")
      valid(rs.map(r => (r.kind, r.pool)).size == rs.size, "duplicate native reward key")
      if kind == B.RewardKind.Member then
        valid(rs.size == 1 && rs.head.amount > 0, "recent singleton positive member reward")
      rs
  )
  def decode(snapshot: Node, pulser: Node): Either[Failure, Projection] = boundary:
    val s = arr(snapshot, 8)
    val r = arr(pulser, 4)
    val f = arr(r(1), 4)
    val answer = arr(r(3), 2)
    val chunk = uint(r(0))
    valid(chunk >= 1 && chunk <= RepeatedTerminalCbor.MaxContainerEntries, "pulse size bound")
    val snap = Snapshot(
      uint(s(0)),
      protocol(s(1)),
      RepeatedPlutusTerminal.nonMyopic(s(2)),
      uint(s(3)),
      uint(s(4)),
      uint(s(5)),
      mapping(s(6))(
        bytes(_, 28),
        x =>
          val values =
            rows(x).map(v => cbor(RepeatedTerminalCbor.nativeFloat32(v), "active likelihood Float"))
          get(NM.likelihood(values), "100 finite raw32 weights").rawBits
      ),
      rewardSets(s(7), B.RewardKind.Leader)
    )
    val free = FreeVars(
      set(f(0))(credential),
      uint(f(1)),
      protocol(f(2)),
      mapping(f(3))(
        bytes(_, 28),
        x =>
          val fields = arr(x, 5)
          val leader = arr(fields(4), 2)
          PoolInfo(
            ratio(fields(0)),
            uint(fields(1)),
            RepeatedPlutusTerminal.poolSnapshot(fields(2)),
            uint(fields(3)),
            B.Reward(B.RewardKind.Leader, bytes(leader(0), 28), uint(leader(1)))
          )
      )
    )
    valid(free.circulation > 0, "positive circulation")
    free.pools.foreach { (pool, info) =>
      valid(
        info.leader.pool == pool && info.blocks > 0 && info.leader.amount <= info.pot,
        "producing pool reward binding"
      )
      valid(
        info.snapshot.coin <= free.circulation &&
          info.sigma.numerator * free.circulation == info.snapshot.coin * info.sigma.denominator,
        "pool relative stake binding"
      )
    }
    val remaining = mapping(r(2))(
      credential,
      x =>
        val fields = arr(x, 2)
        val coin = uint(fields(0)); valid(coin > 0, "positive remaining active stake")
        S.Active(coin, bytes(fields(1), 28))
    ).toVector.sortBy((c, _) => ordered(c))
    val accumulated = mapping(answer(0))(credential, member)
    val recent = rewardSets(answer(1), B.RewardKind.Member)
    valid(remaining.forall((c, _) => !accumulated.contains(c)), "remaining/accumulated disjoint")
    accumulated.foreach { (_, reward) =>
      valid(free.pools.contains(reward.pool), "accumulated producing pool")
    }
    recent.foreach { (c, rs) =>
      equal(accumulated.get(c), Some(rs.head), "recent accumulated reward")
    }
    val expectedLeaders = free.pools.values.foldLeft(Map.empty[S.Credential, Set[B.Reward]]) {
      (m, p) =>
        val c = p.snapshot.rewardAccount
        m.updated(c, m.getOrElse(c, Set.empty) + p.leader)
    }
    equal(snap.leaders, expectedLeaders, "snapshot producing leaders")
    Right(Projection(snap, chunk.toInt, free, remaining, accumulated, recent))

  def fromState(
      state: P.State,
      history: NM.State,
      likelihoods: Map[Bytes, NM.Likelihood]
  ): Either[Failure, Projection] = boundary:
    val view = get(P.observeActive(state), "checked active reward cursor")
    val f = view.frozen
    val allocation = view.allocation
    valid(
      history != null && likelihoods != null && likelihoods.values.forall(_ != null) &&
        likelihoods.keySet == f.go.pools.keySet,
      "frozen active likelihood domain"
    )
    val pools = view.pools.flatMap { (id, p) =>
      p.production.map { production =>
        id -> PoolInfo(
          S.Ratio(p.relativeStake.numerator, p.relativeStake.denominator),
          production.poolReward,
          p.snapshot,
          production.blocks,
          production.leaderReward
        )
      }
    }
    val leaders = pools.values.foldLeft(Map.empty[S.Credential, Set[B.Reward]]) { (m, p) =>
      val c = p.snapshot.rewardAccount
      m.updated(c, m.getOrElse(c, Set.empty) + p.leader)
    }
    val pv = BigInt(9) -> BigInt(0)
    Right(
      Projection(
        Snapshot(
          f.snapshotFees,
          pv,
          history,
          allocation.deltaR1,
          allocation.rewardPot,
          allocation.treasuryDelta,
          likelihoods.map((p, l) => p -> l.rawBits),
          leaders
        ),
        state.pulseSize,
        FreeVars(f.registeredAccounts.keySet, f.maxSupply - f.reserves, pv, pools),
        view.remaining,
        state.members,
        view.recent
      )
    )

  private def rewardJson(r: B.Reward): J =
    J.Arr(Vector(num(r.kind.ordinal), str(r.pool.hex), num(r.amount)))
  private def rewardsJson(m: Map[S.Credential, Set[B.Reward]]): J = mapJson(m)(
    cred,
    rs => J.Arr(rs.toVector.sortBy(r => (r.kind.ordinal, r.pool.hex)).map(rewardJson))
  )
  private def protocolJson(p: (BigInt, BigInt)): J = J.Arr(Vector(num(p._1), num(p._2)))
  def json(p: Projection): J = record(
    "snapshot" -> record(
      "fees" -> num(p.snapshot.fees),
      "protocol" -> protocolJson(p.snapshot.protocol),
      "nonMyopic" -> RepeatedPlutusTerminal.nmJson(p.snapshot.nonMyopic),
      "deltaR1" -> num(p.snapshot.deltaR1),
      "rewardPot" -> num(p.snapshot.rewardPot),
      "deltaT1" -> num(p.snapshot.deltaT1),
      "likelihoods" -> mapJson(p.snapshot.likelihoods)(
        _.hex,
        bs => J.Arr(bs.map(b => str(f"$b%08x")))
      ),
      "leaders" -> rewardsJson(p.snapshot.leaders)
    ),
    "pulseSize" -> num(p.pulseSize),
    "freeVars" -> record(
      "registered" -> J.Arr(p.freeVars.registered.toVector.sortBy(ordered).map(c => str(cred(c)))),
      "circulation" -> num(p.freeVars.circulation),
      "protocol" -> protocolJson(p.freeVars.protocol),
      "pools" -> mapJson(p.freeVars.pools)(
        _.hex,
        x =>
          record(
            "sigma" -> ratioJson(x.sigma),
            "pot" -> num(x.pot),
            "snapshot" -> RepeatedPlutusTerminal.poolSnapshotJson(x.snapshot),
            "blocks" -> num(x.blocks),
            "leader" -> rewardJson(x.leader)
          )
      )
    ),
    "remaining" -> J.Arr(
      p.remaining.map((c, a) => J.Arr(Vector(str(cred(c)), num(a.coin), str(a.pool.hex))))
    ),
    "accumulated" -> mapJson(p.accumulated)(cred, rewardJson),
    "recent" -> rewardsJson(p.recent)
  )

  /** Globals are not in native RSLP CBOR. Compare these separately with the independently checked
    * initial acquisition, rather than filling absent native fields from a terminal JSON claim.
    */
  def contextJson(g: GovernanceGlobals.Checked): J = record(
    "globalsId" -> str(g.id.hex),
    "sourceBindingId" -> str(g.sourceBindingId.hex),
    "genesisSHA256" -> str(g.genesisSHA256.hex),
    "rewardGlobalsId" -> str(g.rewardGlobals.id.hex),
    "randomnessWindow" -> num(g.randomnessStabilisationWindow),
    "securityParameter" -> num(g.securityParameter),
    "epochLength" -> num(g.geometry.epochLength),
    "activeSlotCoefficient" -> ratioJson(g.activeSlotCoefficient),
    "maxSupply" -> num(g.maxLovelaceSupply)
  )
