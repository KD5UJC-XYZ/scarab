// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.*
import lab.ledger.ConwayStake as S
import lab.ledger.ConwayEpochBoundary as B
import lab.ledger.ConwayRewardStart as R
import lab.ledger.ConwayRewardPulser as P
import ReferenceJson.Json as J
import SyntheticRewardProjection.{obj, arr, str, num}

/** Finite, test-only boundary algebra. No native oracle, block validation or runtime admission. */
private[lab] object SyntheticBoundaryProjection:
  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def bytes(i: Int, size: Int = 32) = Bytes(Vector.fill(size)(i.toByte))
  private def n(v: V) = Node(v, Bytes.empty)
  private val nul: J = J.Lit("null")
  private val poolA = bytes(1, 28)
  private val poolB = bytes(2, 28)
  private val owner = S.Credential(false, bytes(3, 28))
  private val script = S.Credential(true, bytes(3, 28))
  private val member = S.Credential(false, bytes(4, 28))
  private val ownerB = S.Credential(false, bytes(5, 28))
  private val memberB = S.Credential(false, bytes(6, 28))
  private val recipient = S.Credential(false, bytes(7, 28))
  private val rows = Map(
    owner -> S.Active(20, poolA),
    script -> S.Active(40, poolA),
    member -> S.Active(40, poolA),
    ownerB -> S.Active(25, poolB),
    memberB -> S.Active(75, poolB)
  )
  private val accounts = rows
    .map((c, a) => c -> S.Account(a.coin, 0, Some(a.pool)))
    .updated(recipient, S.Account(0, 0, Some(poolB)))
  private val pools = Map(
    poolA -> S.Pool(
      bytes(8),
      0,
      7,
      S.Ratio(1, 3),
      recipient,
      Set(owner.hash, memberB.hash),
      Set(owner, script, member),
      0
    ),
    poolB -> S.Pool(
      bytes(9),
      0,
      0,
      S.Ratio(0, 1),
      recipient,
      Set(ownerB.hash),
      Set(ownerB, memberB, recipient),
      0
    )
  )
  private val context = get(S.context(bytes(10), 500, accounts, pools))
  private def historical(which: String): S.Snapshot = get(
    S.fromActive(
      context,
      rows.map((c, a) =>
        c -> a.copy(coin =
          a.coin +
            (if which == "mark" && a.pool == poolA then 10
             else if which == "set" && a.pool == poolB then 20
             else 0)
        )
      )
    )
  )
  private val snapshots = S.Snapshots(historical("mark"), historical("set"), historical("go"), 1000)
  private val pots = B.Pots(0, 900, 1100, 2200)
  private val previous = Map(poolA -> BigInt(1), poolB -> BigInt(1))
  private val current = Map(poolA -> BigInt(3), poolB -> BigInt(1))
  private val params = get(
    R.decodePoolParameters(
      get(
        Cbor.encode(
          V.Arr(
            Vector(n(V.Text(R.PoolParameterFormat))) ++
              Vector[BigInt](9, 0, 0, 1, 0, 1, 0, 1, 1).map(x => n(V.UInt(x)))
          )
        )
      )
    )
  )
  private val globals = get(
    R.decodePulserGlobals(
      get(
        Cbor.encode(
          V.Arr(
            Vector(n(V.Text(R.PulserGlobalFormat))) ++
              Vector[BigInt](500, 1, 20, 2200, 1).map(x => n(V.UInt(x)))
          )
        )
      )
    )
  )

  private class Seed(
      val application: S.Context,
      val snaps: S.Snapshots,
      val money: B.Pots,
      val counts: Map[Bytes, BigInt],
      epoch: BigInt,
      at: BigInt,
      currentCounts: Map[Bytes, BigInt] = Map.empty
  ):
    val bo = B.owner()
    val so = S.owner()
    val env = get(
      ClusterTransition.environment(
        bytes(10),
        bytes(10),
        1082026,
        epoch,
        9,
        0,
        44,
        155381,
        16384,
        4310
      )
    )
    val ledger = get(
      ClusterTransition.checkpoint(
        env,
        get(Cbor.encode(V.Map(Vector.empty))),
        money.fees,
        at,
        bytes(10)
      )
    )
    val stake = get(S.seed(so, application, ledger, bytes(10), Map.empty, snaps))
    val boundary = get(B.context(bo, so, bytes(12), stake, money, counts, currentCounts))
    def start(slot: BigInt): Start = new Start(
      get(B.freezeForAllocation(bo, boundary, slot, 100, params, globals))
    )

  private class Start(val frozen: B.Frozen):
    val allocation = get(R.calculate(frozen, frozen.id))
    val results = frozen.go.pools.keys
      .map(id =>
        id -> get(ConwayPoolReward.calculate(frozen, frozen.id, allocation, allocation.id, id))
      )
      .toMap
    val state = get(P.start(frozen, frozen.id, allocation, allocation.id, results))

  private def credential(c: S.Credential) =
    (if c.script then "script:" else "key:") + c.hash.hex
  private def order(c: S.Credential) = (if c.script then 0 else 1, c.hash.hex)
  private def ratio(r: S.Ratio): J = obj("n" -> num(r.numerator), "d" -> num(r.denominator))
  private def active(c: S.Credential, a: S.Active): J =
    obj("credential" -> str(credential(c)), "pool" -> str(a.pool.hex), "stake" -> num(a.coin))
  private def distribution(s: S.Snapshot): J = obj(
    "total" -> num(s.total),
    "pools" -> arr(s.distribution.toVector.sortBy(_._1.hex).map { (p, a) =>
      obj("pool" -> str(p.hex), "stake" -> num(a.coin), "fraction" -> ratio(a.ratio))
    })
  )
  private def snapshot(s: S.Snapshot): J = obj(
    "total" -> num(s.total),
    "active" -> arr(s.active.toVector.sortBy(x => order(x._1)).map(active)),
    "distribution" -> distribution(s)
  )
  private def snapshotSet(s: S.Snapshots): J = obj(
    "mark" -> snapshot(s.mark),
    "set" -> snapshot(s.set),
    "go" -> snapshot(s.go),
    "fees" -> num(s.fees)
  )
  private def counts(cs: Map[Bytes, BigInt]): J = arr(cs.toVector.sortBy(_._1.hex).map { (p, c) =>
    obj("pool" -> str(p.hex), "blocks" -> num(c))
  })
  private def reward(c: S.Credential, r: B.Reward): J = obj(
    "credential" -> str(credential(c)),
    "kind" -> str(if r.kind == B.RewardKind.Member then "member" else "leader"),
    "pool" -> str(r.pool.hex),
    "amount" -> num(r.amount)
  )
  private def rewards(rs: Map[S.Credential, Set[B.Reward]]): J = arr(
    rs.toVector
      .sortBy(x => order(x._1))
      .flatMap((c, set) =>
        set.toVector
          .sortBy(r => (if r.kind == B.RewardKind.Member then 0 else 1, r.pool.hex))
          .map(reward(c, _))
      )
  )
  private def rewardRow(state: Option[P.State], source: S.Snapshot): J =
    val fields = state match
      case None =>
        Vector("phase" -> str("Absent"), "remaining" -> nul, "members" -> nul, "complete" -> nul)
      case Some(s) if s.phase == P.Phase.Complete =>
        val c = s.completion.get.completed
        Vector(
          "phase" -> str("Complete"),
          "remaining" -> nul,
          "members" -> nul,
          "complete" -> obj(
            "rewards" -> rewards(c.rewards),
            "deltaT" -> num(c.deltas.treasury),
            "deltaR" -> num(c.deltas.reserves),
            "deltaF" -> num(c.deltas.fees)
          )
        )
      case Some(s) =>
        Vector(
          "phase" -> str("Pulsing"),
          "remaining" -> arr(s.traversal.drop(s.processed).map(c => active(c, source.active(c)))),
          "members" -> rewards(s.members.map((c, r) => c -> Set(r))),
          "complete" -> nul
        )
    obj((Vector("label" -> str("rupd")) ++ fields)*)
  private def initial(s: Start): J = obj(
    "chunk" -> num(s.state.pulseSize),
    "allocation" -> obj(
      "fees" -> num(s.frozen.snapshotFees),
      "deltaR1" -> num(s.allocation.deltaR1),
      "deltaT1" -> num(s.allocation.treasuryDelta),
      "rewardPot" -> num(s.allocation.rewardPot),
      "circulation" -> num(s.frozen.maxSupply - s.frozen.reserves)
    ),
    "pools" -> arr(s.results.toVector.sortBy(_._1.hex).flatMap { (id, p) =>
      p.production.map { produced =>
        obj(
          "pool" -> str(id.hex),
          "sigma" -> obj(
            "n" -> num(p.relativeStake.numerator),
            "d" -> num(p.relativeStake.denominator)
          ),
          "poolPot" -> num(produced.poolReward),
          "blocks" -> num(produced.blocks),
          "leader" -> reward(p.snapshot.rewardAccount, produced.leaderReward),
          "snapshot" -> obj(
            "stake" -> num(p.snapshot.coin),
            "ownerStake" -> num(p.snapshot.ownerCoin),
            "owners" -> arr(p.snapshot.owners.toVector.map(_.hex).sorted.map(str)),
            "pledge" -> num(p.snapshot.pledge),
            "cost" -> num(p.snapshot.cost),
            "margin" -> ratio(p.snapshot.margin),
            "rewardAccount" -> str(credential(p.snapshot.rewardAccount))
          )
        )
      }
    })
  )
  private def state(
      epoch: BigInt,
      prev: Map[Bytes, BigInt],
      curr: Map[Bytes, BigInt],
      leadership: S.Snapshot,
      snaps: S.Snapshots,
      as: Map[S.Credential, S.Account],
      money: B.Pots,
      ru: J
  ): J = obj(
    "epoch" -> num(epoch),
    "previousCounts" -> counts(prev),
    "currentCounts" -> counts(curr),
    "leadership" -> distribution(leadership),
    "snapshots" -> snapshotSet(snaps),
    "accounts" -> arr(as.toVector.sortBy(x => order(x._1)).map { (c, a) =>
      obj(
        "credential" -> str(credential(c)),
        "balance" -> num(a.balance),
        "deposit" -> num(a.deposit),
        "pool" -> a.delegation.fold(nul)(p => str(p.hex))
      )
    }),
    "pots" -> obj(
      "treasury" -> num(money.treasury),
      "reserves" -> num(money.reserves),
      "fees" -> num(money.fees),
      "deposits" -> num(as.values.map(_.deposit).sum + pools.values.map(_.deposit).sum),
      "donations" -> num(0)
    ),
    "reward" -> ru
  )

  def project(id: String, slot: BigInt, oldRewardPhase: String): J =
    require(slot >= 500 && slot < 1000, "exact synthetic successor epoch")
    require(Set("Complete", "Pulsing", "Absent").contains(oldRewardPhase), "old reward phase")
    val predecessor = new Seed(context, snapshots, pots, previous, 0, 499, current)
    // An earlier synthetic freeze differs only in historical snapshot fees. It shares the
    // account/pool context and earlier revision with the application state; no native result is read.
    val oldStart = new Seed(context, snapshots.copy(fees = 200), pots, previous, 0, 50).start(101)
    val old = oldRewardPhase match
      case "Absent"  => None
      case "Pulsing" => Some(get(P.pulse(oldStart.state, oldStart.state.id, 102)))
      case _         => Some(get(P.force(oldStart.state, oldStart.state.id, 201)))
    val phase = old match
      case None =>
        B.RewardPhase.Absent(get(B.suppliedAbsent(predecessor.bo, predecessor.boundary, bytes(13))))
      case Some(s) =>
        val completed = get(P.completeAtBoundary(s, s.id, slot)).completion.get.completed
        // The freeze must belong to the same boundary owner, so reconstruct it through the
        // predecessor owner using an earlier stake context with the declared historical fees.
        val frozenContext = get(
          B.context(
            predecessor.bo,
            predecessor.so,
            bytes(12),
            get(
              S.seed(
                predecessor.so,
                context,
                get(
                  ClusterTransition.checkpoint(
                    predecessor.env,
                    get(Cbor.encode(V.Map(Vector.empty))),
                    pots.fees,
                    50,
                    bytes(10)
                  )
                ),
                bytes(10),
                Map.empty,
                snapshots.copy(fees = 200)
              )
            ),
            pots,
            previous,
            Map.empty
          )
        )
        val owned = new Start(
          get(B.freezeForAllocation(predecessor.bo, frozenContext, 101, 100, params, globals))
        )
        val ownedDone = get(
          P.completeAtBoundary(owned.state, owned.state.id, slot)
        ).completion.get.completed
        require(
          ownedDone.deltas == completed.deltas && ownedDone.rewards == completed.rewards,
          "old phase completion differs"
        )
        B.RewardPhase.Completed(
          get(B.completeFromFrozen(predecessor.bo, predecessor.boundary, ownedDone))
        )
    val boundary = get(
      B.preview(
        predecessor.bo,
        predecessor.boundary,
        get(B.signal(predecessor.bo, predecessor.boundary, bytes(14), slot)),
        phase
      )
    )
    val postAccounts = accounts.map((c, a) => c -> a.copy(balance = boundary.balances(c)))
    val postContext = get(S.context(bytes(15), 500, postAccounts, pools))
    // Pure RUPD diagnostic environments: epoch-1 timing over immutable old inputs versus
    // rotated inputs. These seeds do not claim full-block transition or coordinator ownership.
    def fresh(ctx: S.Context, ss: S.Snapshots, ps: B.Pots, cs: Map[Bytes, BigInt]): J =
      val when = get(B.rewardTiming(500, 100, slot))
      val s =
        if when == B.Timing.TooEarly then None
        else Some(new Seed(ctx, ss, ps, cs, 1, 500).start(slot).state)
      rewardRow(s, ss.go)
    val pre = fresh(context, snapshots, pots, previous)
    val post =
      fresh(postContext, boundary.rotation.snapshots, boundary.pots, boundary.previousBlocks)
    val absent = rewardRow(None, snapshots.go)
    def after(ru: J): J = state(
      boundary.epoch,
      boundary.previousBlocks,
      boundary.currentBlocks,
      boundary.rotation.leadership,
      boundary.rotation.snapshots,
      postAccounts,
      boundary.pots,
      ru
    )
    obj(
      "id" -> str(id),
      "slot" -> num(slot),
      "before" -> state(
        0,
        previous,
        current,
        snapshots.go,
        snapshots,
        accounts,
        pots,
        rewardRow(old, snapshots.go)
      ),
      "preTickInitial" -> initial(new Seed(context, snapshots, pots, previous, 0, 50).start(101)),
      "boundary" -> after(absent),
      "tick" -> after(pre),
      "freshPreTickRupd" -> pre,
      "postBoundaryDiagnostic" -> post
    )
