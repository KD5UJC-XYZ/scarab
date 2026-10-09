// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}
import ReferenceJson.Json as J
import SyntheticRewardProjection.{obj, arr, str, num, encode, sha256}

/** Test-only finite synthetic completion. No production completion or native input admission. */
private[lab] object EmptyGovernanceProjection:
  val InputPin = "2f94d8b6d04ad338e41cff69961a39c121619305730b533b9626f4d6c2349b64"
  val ResultSchema = "synthetic-empty-governance-result-v1"
  final case class Case(
      id: String,
      dormant: BigInt,
      registered: Boolean,
      voteMode: String,
      committeeMode: String,
      currentFee: BigInt,
      previousFee: BigInt,
      future: String,
      reject: Option[String]
  )
  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
  private def fields(j: J, keys: String*): Map[String, J] = j match
    case J.Obj(fs) => require(fs.keySet == keys.toSet, "exact governance fields required"); fs
    case _         => throw new IllegalArgumentException("governance object required")
  private def text(j: J) = ReferenceJson.string(j)
  private def uint(j: J, max: BigInt): BigInt =
    val s = text(j)
    require(s.matches("0|[1-9][0-9]{0,19}"), "canonical decimal string required")
    val n = BigInt(s); require(n <= max, "governance integer bound"); n
  private def choice(j: J, choices: Set[String]): String =
    val s = text(j); require(choices(s), "unsupported finite governance variant"); s
  def decodeInput(raw: Bytes, expectedHash: String): Vector[Case] =
    require(
      raw != null && raw.size <= 65536 && expectedHash != null &&
        expectedHash.matches("[0-9a-f]{64}") && sha256(raw) == expectedHash,
      "input byte identity/bound"
    )
    val parsed = ReferenceJson.parse(raw)
    require(encode(parsed) == raw, "canonical input required")
    val root = fields(parsed, "schema", "profile", "cases")
    require(
      text(root("schema")) == "empty-governance-cases-v1" &&
        text(root("profile")) == "boundary-2200-empty-governance-v1",
      "input schema/profile"
    )
    val rows = ReferenceJson.array(root("cases"))
    require(rows.nonEmpty && rows.size <= 16, "case count bound")
    val cases = rows.map { row =>
      val f = fields(
        row,
        "id",
        "dormant",
        "registered",
        "voteMode",
        "committeeMode",
        "currentFee",
        "previousFee",
        "future",
        "reject"
      )
      val id = text(f("id")); require(id.matches("[a-z][a-z0-9-]{0,47}"), "case id")
      val registered = f("registered") match
        case J.Lit("true")  => true
        case J.Lit("false") => false
        case _              => throw new IllegalArgumentException("boolean required")
      val reject = f("reject") match
        case J.Lit("null") => None
        case other         => Some(choice(other, Set("proposals", "actions", "parameters")))
      Case(
        id,
        uint(f("dormant"), 100),
        registered,
        choice(f("voteMode"), Set("none", "credential", "abstain", "no-confidence")),
        choice(f("committeeMode"), Set("authorized", "resigned", "expired", "orphan")),
        uint(f("currentFee"), 1000),
        uint(f("previousFee"), 1000),
        choice(f("future"), Set("NoUpdate", "PotentialNone")),
        reject
      )
    }
    require(cases.map(_.id).distinct.size == cases.size, "duplicate case id")
    cases

  def output(raw: Bytes, expectedHash: String): J = obj(
    "schema" -> str(ResultSchema),
    "inputSha256" -> str(expectedHash),
    "producer" -> str("scala"),
    "cases" -> arr(decodeInput(raw, expectedHash).map(project))
  )

  /** Producer labels bind the agreed envelope; they are not proof of native execution. */
  def compare(input: Bytes, pin: String, result: Bytes, requiredProducer: String): Vector[String] =
    require(Set("native", "synthetic-expectation")(requiredProducer), "explicit result provenance")
    require(result != null && result.size <= 1048576, "result byte bound")
    val actual = ReferenceJson.parse(result)
    val root = fields(actual, "schema", "inputSha256", "producer", "cases")
    require(
      text(root("schema")) == ResultSchema && text(root("inputSha256")) == pin &&
        text(root("producer")) == requiredProducer,
      "result schema/input/producer mismatch"
    )
    val expected = output(input, pin).asInstanceOf[J.Obj]
    val normalized = J.Obj(expected.fields.updated("producer", str(requiredProducer)))
    val differences = Vector.newBuilder[String]
    var count = 0
    def diff(a: J, b: J, path: String): Unit = if a != b && count < 16 then
      (a, b) match
        case (J.Obj(x), J.Obj(y)) if x.keySet == y.keySet =>
          x.keys.toVector.sorted.foreach(k => diff(x(k), y(k), path + "." + k))
        case (J.Arr(x), J.Arr(y)) if x.size == y.size =>
          x.indices.foreach(i => diff(x(i), y(i), s"$path[$i]"))
        case _ => differences += path; count += 1
    diff(normalized, actual, "result")
    differences.result()

  private val nul = J.Lit("null")
  private def bytes(n: Int, size: Int = 28) = Bytes(Vector.fill(size)(n.toByte))
  private def key(n: Int) = S.Credential(false, bytes(n))
  private def credential(c: S.Credential): String =
    (if c.script then "script:" else "key:") + c.hash.hex
  private def vote(v: G.Vote): String = v match
    case G.Vote.Credential(c)      => credential(c)
    case G.Vote.AlwaysAbstain      => "always-abstain"
    case G.Vote.AlwaysNoConfidence => "always-no-confidence"
  private def credentialOrder(c: S.Credential) = (if c.script then 0 else 1, c.hash.hex)
  private def voteOrder(v: G.Vote): (Int, String) = v match
    case G.Vote.Credential(c)      => (if c.script then 1 else 0, c.hash.hex)
    case G.Vote.AlwaysAbstain      => (2, "")
    case G.Vote.AlwaysNoConfidence => (3, "")
  private def payload(n: BigInt) = get(G.payload(get(Cbor.encode(V.UInt(n)))))
  private val poolA = bytes(1); private val poolB = bytes(2)
  private val drep = key(64); private val cold = key(62)
  private val roots = G.Purpose.values.map(_ -> Option.empty[G.ActionId]).toMap
  private val constitution = G.Constitution(G.Anchor("", bytes(0, 32)), None)
  private val rows = Vector(
    (S.Credential(true, bytes(3)), BigInt(40), poolA),
    (key(3), BigInt(20), poolA),
    (key(4), BigInt(40), poolA),
    (key(5), BigInt(25), poolB),
    (key(6), BigInt(75), poolB),
    (key(7), BigInt(0), poolB)
  )

  private def fixture(c: Case): G.Input =
    val delegation = c.voteMode match
      case "none"          => None
      case "credential"    => Some(G.Vote.Credential(drep))
      case "abstain"       => Some(G.Vote.AlwaysAbstain)
      case "no-confidence" => Some(G.Vote.AlwaysNoConfidence)
      case _               => throw new IllegalArgumentException("finite vote mode required")
    val accounts =
      rows.map((cred, rewards, pool) => cred -> G.Account(rewards, 0, Some(pool), delegation)).toMap
    val dreps =
      if c.registered then
        Map(
          drep -> G.DRepState(
            3,
            None,
            5,
            if c.voteMode == "credential" then accounts.keySet else Set.empty[S.Credential]
          )
        )
      else Map.empty[S.Credential, G.DRepState]
    val committee = Some(
      G.Committee(Map(cold -> BigInt(if c.committeeMode == "expired" then 0 else 2)), S.Ratio(1, 2))
    )
    val authorization =
      if c.committeeMode == "resigned" then G.Authorization.Resigned(None)
      else G.Authorization.Hot(key(68))
    val committeeState = Map(cold -> authorization) ++
      (if c.committeeMode == "orphan" then Map(key(65) -> G.Authorization.Hot(key(69)))
       else Map.empty)
    val current = payload(c.currentFee); val previous = payload(c.previousFee)
    val future =
      if c.reject.contains("parameters") then G.FutureParameters.Pending(payload(99))
      else if c.future == "NoUpdate" then G.FutureParameters.NoUpdate
      else G.FutureParameters.PotentialNone
    val parameters = G.Parameters(current, previous, future)
    val enact = G.Enact(committee, constitution, current, previous, 0, Map.empty, roots)
    val action = G.ActionId(bytes(90, 32), 0)
    val old = G.OldDRep.Complete(
      G.CompletedSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty),
      G.Ratify(
        enact,
        if c.reject.contains("actions") then Vector(action) else Vector.empty,
        Set.empty,
        false
      )
    )
    val pools = Map(poolA -> G.Pool(payload(1), 0), poolB -> G.Pool(payload(2), 0))
    G.Input(
      0,
      c.dormant,
      dreps,
      committee,
      committeeState,
      constitution,
      parameters,
      roots,
      if c.reject.contains("proposals") then Map(action -> payload(1)) else Map.empty,
      old,
      accounts,
      Map.empty,
      G.PoolDistribution(
        200,
        Map(
          poolA -> G.PoolShare(100, S.Ratio(1, 2), bytes(8, 32)),
          poolB -> G.PoolShare(100, S.Ratio(1, 2), bytes(9, 32))
        )
      ),
      pools,
      Map.empty,
      Map.empty,
      Map.empty,
      0,
      17,
      G.Deposits(
        accounts.map((k, a) => k -> a.deposit),
        pools.map((k, p) => k -> p.deposit),
        dreps.map((k, d) => k -> d.deposit),
        Map.empty,
        if c.registered then 5 else 0
      ),
      G.Globals(1, payload(1))
    )

  /** Finite independent account fold for the empty-proposal case only, never production authority.
    */
  private def completeForComparison(fresh: G.FreshPulsing): (G.CompletedSnapshot, G.Enact) =
    require(
      fresh.index == 0 && fresh.drepDistribution.isEmpty && fresh.proposals.isEmpty &&
        fresh.proposalDeposits.isEmpty,
      "only fresh empty-proposal fixture completion supported"
    )
    val distribution = fresh.accounts.toVector
      .sortBy((c, _) => credentialOrder(c))
      .foldLeft(Map.empty[G.Vote, BigInt]) { case (done, (cred, account)) =>
        account.vote match
          case Some(v) if (v match
                case G.Vote.Credential(c) => fresh.dreps.contains(c)
                case _                    => true
              ) =>
            val amount = account.rewards + fresh.instantaneous.getOrElse(cred, BigInt(0))
            done.updated(v, done.getOrElse(v, BigInt(0)) + amount)
          case _ => done
      }
    val snapshot = G.CompletedSnapshot(
      Vector.empty,
      distribution,
      fresh.dreps,
      fresh.stakePoolDistribution.pools.map((p, s) => p -> s.stake)
    )
    // Native RATIFY's Empty signal resets only enact-state treasury. No actions are invented.
    require(fresh.enact.withdrawals.isEmpty, "empty RATIFY fixture withdrawals required")
    (snapshot, fresh.enact.copy(treasury = 0))

  private def drepRows(ds: Map[S.Credential, G.DRepState]): J = arr(
    ds.toVector
      .sortBy((c, _) => credentialOrder(c))
      .map { (c, d) =>
        require(d.anchor.isEmpty, "finite fixture anchor")
        obj(
          "credential" -> str(credential(c)),
          "expiry" -> num(d.expiry),
          "anchor" -> nul,
          "deposit" -> num(d.deposit),
          "delegators" -> arr(
            d.delegators.toVector.sortBy(credentialOrder).map(c => str(credential(c)))
          )
        )
      }
  )
  private def committee(c: Option[G.Committee]): J = c match
    case None => nul
    case Some(x) =>
      obj(
        "members" -> arr(
          x.members.toVector
            .sortBy((c, _) => credentialOrder(c))
            .map((c, e) => obj("credential" -> str(credential(c)), "expiry" -> num(e)))
        ),
        "threshold" -> obj("n" -> num(x.threshold.numerator), "d" -> num(x.threshold.denominator))
      )
  private def authorizations(as: Map[S.Credential, G.Authorization]): J = arr(
    as.toVector
      .sortBy((c, _) => credentialOrder(c))
      .map { (c, a) =>
        val (kind, hot) = a match
          case G.Authorization.Hot(h)         => ("hot", str(credential(h)))
          case G.Authorization.Resigned(None) => ("resigned", nul)
          case _ => throw new IllegalArgumentException("finite fixture resignation")
        obj("credential" -> str(credential(c)), "kind" -> str(kind), "hot" -> hot, "anchor" -> nul)
      }
  )
  private val constitutionJson =
    obj("url" -> str(""), "hash" -> str(bytes(0, 32).hex), "script" -> nul)
  private val rootsJson =
    obj("parameters" -> nul, "hardFork" -> nul, "committee" -> nul, "constitution" -> nul)
  private val pv = obj("major" -> num(9), "minor" -> num(0))
  private def parameters(current: BigInt, previous: BigInt): J = obj(
    "currentFee" -> num(current),
    "previousFee" -> num(previous),
    "currentPV" -> pv,
    "previousPV" -> pv
  )
  private def pools(ps: Map[Bytes, BigInt]): J = arr(
    ps.toVector.sortBy(_._1.hex).map((p, n) => obj("pool" -> str(p.hex), "stake" -> num(n)))
  )
  private def completed(snapshot: G.CompletedSnapshot, enact: G.Enact): J =
    obj(
      "snapshot" -> obj(
        "proposals" -> arr(Vector.empty),
        "drepDistribution" -> arr(
          snapshot.drepDistribution.toVector
            .sortBy((v, _) => voteOrder(v))
            .map((v, n) => obj("vote" -> str(vote(v)), "coin" -> num(n)))
        ),
        "dreps" -> drepRows(snapshot.dreps),
        "pools" -> pools(snapshot.poolDistribution)
      ),
      "ratify" -> obj(
        "enacted" -> arr(Vector.empty),
        "expired" -> arr(Vector.empty),
        "delayed" -> J.Lit("false"),
        "enact" -> obj(
          "committee" -> committee(enact.committee),
          "constitution" -> constitutionJson,
          "parameters" -> parameters(fee(enact.current), fee(enact.previous)),
          "treasury" -> num(enact.treasury),
          "withdrawals" -> arr(Vector.empty),
          "roots" -> rootsJson
        )
      )
    )
  private def fee(p: G.Payload): BigInt = get(Cbor.decode(p.original)).value match
    case V.UInt(n) => n
    case _         => throw new IllegalArgumentException("finite fee parameter payload required")
  private def state(c: Case, input: G.Input, applied: Option[G.Applied]): J =
    val parameterState = applied.fold(input.parameters)(_.parameters)
    val current = fee(parameterState.current)
    val previous = fee(parameterState.previous)
    val future = parameterState.future match
      case G.FutureParameters.NoUpdate      => "NoUpdate"
      case G.FutureParameters.PotentialNone => "PotentialNone"
      case _ => throw new IllegalArgumentException("unsupported future parameters")
    val ps = parameters(current, previous).asInstanceOf[J.Obj]
    val snapshotAndEnact = applied match
      case Some(a) => completeForComparison(a.fresh)
      case None =>
        input.oldDRep match
          case G.OldDRep.Complete(s, r) => (s, r.enact)
          case _ => throw new IllegalArgumentException("completed fixture required")
    obj(
      "epoch" -> num(applied.fold(input.epoch)(_.epoch)),
      "dormant" -> num(applied.fold(input.dormant)(_.dormant)),
      "dreps" -> drepRows(input.dreps),
      "accounts" -> arr(
        input.accounts.toVector
          .sortBy((c, _) => credentialOrder(c))
          .map((c, a) =>
            obj(
              "credential" -> str(credential(c)),
              "rewards" -> num(a.rewards),
              "deposit" -> num(a.deposit),
              "pool" -> a.pool.fold[J](nul)(p => str(p.hex)),
              "vote" -> a.vote.fold[J](nul)(v => str(vote(v)))
            )
          )
      ),
      "committee" -> committee(input.committee),
      "committeeState" -> authorizations(applied.fold(input.committeeState)(_.committeeState)),
      "constitution" -> constitutionJson,
      "parameters" -> J.Obj(ps.fields.updated("future", str(future))),
      "roots" -> rootsJson,
      "pots" -> obj(
        "treasury" -> num(input.treasury),
        "reserves" -> num(883 - input.deposits.total),
        "fees" -> num(1100),
        "deposits" -> num(input.deposits.total),
        "donations" -> num(0)
      ),
      "completed" -> completed(snapshotAndEnact._1, snapshotAndEnact._2)
    )

  def project(c: Case): J =
    val input = fixture(c)
    val result = G.applyBoundary(input, 1)
    c.reject match
      case Some(reason) =>
        require(result.isLeft, "declared profile rejection unexpectedly accepted")
        obj("id" -> str(c.id), "status" -> str("profile-rejected"), "reason" -> str(reason))
      case None =>
        val applied = get(result)
        require(applied.fresh.enact.treasury == input.treasury, "fresh treasury snapshot")
        obj(
          "id" -> str(c.id),
          "status" -> str("accepted"),
          "before" -> state(c, input, None),
          "after" -> state(c, input, Some(applied)),
          "diagnostic" -> obj(
            "freshPulseSize" -> num(applied.fresh.pulseSize),
            "freshSeedTreasury" -> num(applied.fresh.enact.treasury),
            "newMarkPools" -> pools(
              applied.fresh.stakePoolDistribution.pools.map((p, s) => p -> s.stake)
            )
          )
        )
