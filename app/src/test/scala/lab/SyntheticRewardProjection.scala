// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.*
import lab.ledger.ConwayStake as S
import lab.ledger.ConwayEpochBoundary as B
import lab.ledger.ConwayRewardStart as R
import lab.ledger.ConwayMemberRewards as M
import lab.ledger.ConwayRewardPulser as P
import ReferenceJson.Json as J
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Test-only finite synthetic reward value projection. No native runner or live inputs. */
private[lab] object SyntheticRewardProjection:
  final case class Action(label: String, op: String, slot: BigInt)
  final case class Case(
      id: String,
      fees: BigInt,
      blocksA: BigInt,
      blocksB: BigInt,
      empty: Boolean,
      window: BigInt,
      omitScript: Boolean,
      applyRegistration: Boolean,
      actions: Vector[Action]
  )
  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def bytes(i: Int, size: Int = 32) = Bytes(Vector.fill(size)(i.toByte))
  private def n(v: V) = Node(v, Bytes.empty)
  private val pool = bytes(1, 28); private val other = bytes(2, 28)
  private val owner = S.Credential(false, bytes(3, 28))
  private val script = S.Credential(true, owner.hash)
  private val member = S.Credential(false, bytes(4, 28))
  private val ownerB = S.Credential(false, bytes(5, 28))
  private val memberB = S.Credential(false, bytes(6, 28))
  private val recipient = S.Credential(false, bytes(7, 28))
  private class Fixture(
      fees: BigInt = 1000,
      margin: S.Ratio = S.Ratio(1, 3),
      blocks: Map[Bytes, BigInt] = Map(pool -> BigInt(1), other -> BigInt(1)),
      leaderAccount: S.Credential = recipient,
      omitScriptAtFreeze: Boolean = false,
      empty: Boolean = false,
      k: Option[BigInt] = Some(BigInt(1)),
      observedSlot: BigInt = 110,
      window: BigInt = 100
  ):
    val bo = B.owner(); val so = S.owner()
    val accounts = Map(
      owner -> S.Account(0, 0, Some(pool)),
      script -> S.Account(0, 0, Some(pool)),
      member -> S.Account(0, 0, Some(pool)),
      ownerB -> S.Account(0, 0, Some(other)),
      memberB -> S.Account(0, 0, Some(other))
    )
    val pools = Map(
      pool -> S.Pool(
        bytes(8),
        0,
        7,
        margin,
        leaderAccount,
        Set(owner.hash, memberB.hash),
        Set(owner, script, member),
        0
      ),
      other -> S.Pool(
        bytes(9),
        0,
        0,
        S.Ratio(0, 1),
        leaderAccount,
        Set(ownerB.hash),
        Set(ownerB, memberB),
        0
      )
    )
    val context = get(S.context(bytes(10), 500, accounts, pools))
    val go =
      if empty then S.emptySnapshot
      else
        get(
          S.fromActive(
            context,
            Map(
              owner -> S.Active(20, pool),
              script -> S.Active(40, pool),
              member -> S.Active(40, pool),
              ownerB -> S.Active(25, other),
              memberB -> S.Active(75, other)
            )
          )
        )
    val raw = get(Cbor.encode(V.Map(Vector.empty)))
    val env = get(
      ClusterTransition.environment(bytes(10), bytes(10), 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(ClusterTransition.checkpoint(env, raw, 50, 50, bytes(10)))
    val state = get(
      S.seed(so, context, ledger, bytes(10), Map.empty, S.Snapshots(go, go, go, fees))
    )
    val pots = B.Pots(0, 1000, fees, 2000)
    def application(as: Map[S.Credential, S.Account]): S.Context =
      get(
        S.context(
          bytes(11),
          500,
          as,
          pools.map((id, p) =>
            id -> p.copy(delegators = as.collect {
              case (c, a) if a.delegation.contains(id) => c
            }.toSet)
          )
        )
      )
    val frozenAccounts = if omitScriptAtFreeze then accounts - script else accounts
    val start = get(
      B.contextAtApplication(
        bo,
        so,
        bytes(12),
        state,
        application(frozenAccounts),
        pots,
        blocks,
        Map.empty
      )
    )
    val params = get(
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
    val globalBytes = get(
      Cbor.encode(
        V.Arr(
          Vector(n(V.Text(if k.isDefined then R.PulserGlobalFormat else R.GlobalFormat))) ++
            (Vector[BigInt](500, 1, 20, 2000) ++ k.toVector).map(x => n(V.UInt(x)))
        )
      )
    )
    val globals = get(
      if k.isDefined then R.decodePulserGlobals(globalBytes) else R.decodeGlobals(globalBytes)
    )
    val frozen = get(B.freezeForAllocation(bo, start, observedSlot, window, params, globals))
    val allocation = get(R.calculate(frozen, frozen.id))
    val results = go.pools.keys
      .map(id =>
        id -> get(ConwayPoolReward.calculate(frozen, frozen.id, allocation, allocation.id, id))
      )
      .toMap
    def distribution(rs: Map[Bytes, ConwayPoolReward.Result] = results) =
      M.distribute(frozen, frozen.id, allocation, allocation.id, rs)

  def sha256(raw: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(raw.toArray).map(b => f"${b & 255}%02x").mkString
  def obj(fields: (String, J)*): J = J.Obj(fields.toMap)
  def arr(values: Iterable[J]): J = J.Arr(values.toVector)
  def str(s: String): J = J.Str(s)
  def num(n: BigInt): J = str(n.toString)
  private val nul: J = J.Lit("null")
  private def credential(c: S.Credential): String =
    (if c.script then "script:" else "key:") + c.hash.hex
  private def order(c: S.Credential) = (if c.script then 0 else 1, c.hash.hex)
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
  private def balances(bs: Map[S.Credential, BigInt]): J = arr(
    bs.toVector
      .sortBy(x => order(x._1))
      .map((c, a) => obj("credential" -> str(credential(c)), "amount" -> num(a)))
  )
  private def complete(c: ConwayRewardCompletion.Completed): J = obj(
    "rewards" -> rewards(c.rewards),
    "deltaT" -> num(c.deltas.treasury),
    "deltaR" -> num(c.deltas.reserves),
    "deltaF" -> num(c.deltas.fees)
  )
  private def step(label: String, s: Option[P.State]): J = s match
    case None =>
      obj(
        "label" -> str(label),
        "phase" -> str("Absent"),
        "remaining" -> nul,
        "members" -> nul,
        "complete" -> nul
      )
    case Some(state) if state.phase == P.Phase.Complete =>
      obj(
        "label" -> str(label),
        "phase" -> str("Complete"),
        "remaining" -> nul,
        "members" -> nul,
        "complete" -> complete(state.completion.get.completed)
      )
    case Some(state) =>
      // Source stake comes from the explicitly fixed synthetic case, joined below by the fixture.
      obj(
        "label" -> str(label),
        "phase" -> str("Pulsing"),
        "remaining" -> arr(state.traversal.drop(state.processed).map(c => str(credential(c)))),
        "members" -> rewards(state.members.map((c, r) => c -> Set(r))),
        "complete" -> nul
      )
  private def make(c: Case, slot: BigInt): Fixture = new Fixture(
    fees = c.fees,
    blocks = if c.empty then Map.empty else Map(pool -> c.blocksA, other -> c.blocksB),
    omitScriptAtFreeze = c.omitScript,
    empty = c.empty,
    observedSlot = slot,
    window = c.window
  )
  private def begin(f: Fixture): P.State = get(
    P.start(f.frozen, f.frozen.id, f.allocation, f.allocation.id, f.results)
  )
  def project(c: Case): J =
    val probe = make(c, c.window + 1); val initialState = begin(probe)
    val allocation = probe.allocation
    val initial = obj(
      "chunk" -> num(initialState.pulseSize),
      "allocation" -> obj(
        "fees" -> num(c.fees),
        "deltaR1" -> num(allocation.deltaR1),
        "deltaT1" -> num(allocation.treasuryDelta),
        "rewardPot" -> num(allocation.rewardPot),
        "circulation" -> num(1000)
      ),
      "pools" -> arr(probe.results.toVector.sortBy(_._1.hex).flatMap { (id, p) =>
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
              "margin" -> obj(
                "n" -> num(p.snapshot.margin.numerator),
                "d" -> num(p.snapshot.margin.denominator)
              ),
              "rewardAccount" -> str(credential(p.snapshot.rewardAccount))
            )
          )
        }
      })
    )
    var current: Option[P.State] = None
    val steps = c.actions.map { a =>
      a.op match
        case "start" =>
          require(current.isEmpty, "duplicate start")
          require(
            a.slot > c.window && a.slot <= 2 * c.window,
            "direct start must be inside pulse window"
          ); current = Some(begin(make(c, a.slot)))
        case "pulse" =>
          val s = current.getOrElse(throw new IllegalArgumentException("pulse without start"));
          current = Some(get(P.pulse(s, s.id, a.slot)))
        case "force" =>
          val s = current.getOrElse(throw new IllegalArgumentException("force without start"));
          current = Some(get(P.force(s, s.id, a.slot)))
        case "rupdFresh" =>
          current = if a.slot <= c.window then None else Some(begin(make(c, a.slot)))
        case _ => throw new IllegalArgumentException("unknown synthetic action")
      val value = step(a.label, current)
      current.filter(_.phase == P.Phase.Pulsing).fold(value) { s =>
        val J.Obj(fields) = value: @unchecked
        J.Obj(
          fields.updated(
            "remaining",
            arr(s.traversal.drop(s.processed).map { cred =>
              val stake = probe.go.active(cred)
              obj(
                "credential" -> str(credential(cred)),
                "pool" -> str(stake.pool.hex),
                "stake" -> num(stake.coin)
              )
            })
          )
        )
      }
    }
    val application =
      if !c.applyRegistration then nul
      else
        val done = current
          .flatMap(_.completion)
          .getOrElse(throw new IllegalArgumentException("application requires completed case"))
        val accounts = (probe.accounts - script - member).updated(recipient, S.Account(0, 0, None))
        val applied = get(
          ConwayRewardApplication.applyPv9(
            accounts,
            probe.pots,
            done.completed.deltas,
            done.completed.rewards
          )
        )
        obj(
          "registered" -> rewards(applied.registered),
          "unregistered" -> rewards(applied.unregistered),
          "credited" -> balances(applied.credited),
          "totalUnregistered" -> num(applied.totalUnregistered),
          "pots" -> obj(
            "treasury" -> num(applied.pots.treasury),
            "reserves" -> num(applied.pots.reserves),
            "fees" -> num(applied.pots.fees)
          ),
          "balances" -> balances(applied.balances)
        )
    obj(
      "id" -> str(c.id),
      "initial" -> initial,
      "steps" -> arr(steps),
      "application" -> application
    )

  def render(j: J): String = j match
    case J.Obj(fs) =>
      fs.toVector
        .sortBy(_._1)
        .map((k, v) => render(str(k)) + ":" + render(v))
        .mkString("{", ",", "}")
    case J.Arr(xs) => xs.map(render).mkString("[", ",", "]")
    case J.Str(s) =>
      "\"" + s.flatMap {
        case '\"' => "\\\""; case '\\' => "\\\\"; case c if c < ' ' => f"\\u${c.toInt}%04x";
        case c    => c.toString
      } + "\""
    case J.Num(n) => n
    case J.Lit(l) => l
  def encode(j: J): Bytes = Bytes.fromArray((render(j) + "\n").getBytes(UTF_8))
