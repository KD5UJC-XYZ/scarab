// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import lab.ledger.{ConwayStake as Stake, ClusterTransition}
import scala.util.control.NonFatal

/** Lossless source pins, checked stake projection. Not a complete epoch/bootstrap decoder. */
object ConwayStakeSeed:
  final class Prepared private[ConwayStakeSeed] (
      val context: Stake.Context,
      val utxo: Bytes,
      val instantaneous: Map[Stake.Credential, BigInt],
      val snapshots: Stake.Snapshots,
      val epoch: BigInt,
      val fees: BigInt,
      val sourceId: Bytes
  ):
    val rewardsDecoded = false
    val donationsKnown = false
    val epochTransitionReady = false
    def attach(owner: Stake.Owner, ledger: ClusterTransition.State): Either[String, Stake.State] =
      checked {
        require(
          ledger != null && ledger.environment.epoch == epoch && ledger.fees == fees,
          "stake ledger epoch/fees mismatch"
        )
        require(
          get(Stake.decodeUtxo(ledger.outputMap)) == get(Stake.decodeUtxo(utxo)),
          "stake complete ledger UTxO mismatch"
        )
        get(Stake.seed(owner, context, ledger, sourceId, instantaneous, snapshots))
      }
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def source(b: Bytes, pin: Bytes): Unit =
    require(
      b != null && b.value != null && b.size <= 4194304 && pin != null && pin.size == 32 &&
        ClusterHeaderObservation.sha256(b) == pin,
      "stake source pin/bound mismatch"
    )
  import ReferenceJson.{Json, field, uint, string, array}
  private def obj(j: Json): Map[String, Json] = j match
    case Json.Obj(m) => m
    case _           => throw new IllegalArgumentException("stake JSON object")
  private def hash(s: String, width: Int): Bytes =
    val b = get(Bytes.fromHex(s)); require(b.size == width && b.hex == s, "canonical stake hash"); b
  private def cred(s: String): Stake.Credential =
    if s.startsWith("keyHash-") then Stake.Credential(false, hash(s.drop(8), 28))
    else if s.startsWith("scriptHash-") then Stake.Credential(true, hash(s.drop(11), 28))
    else throw new IllegalArgumentException("credential prefix")
  private def credential(j: Json): Stake.Credential =
    val m = obj(j); require(m.size == 1, "credential object width")
    m.head match
      case ("keyHash", Json.Str(s))    => Stake.Credential(false, hash(s, 28))
      case ("scriptHash", Json.Str(s)) => Stake.Credential(true, hash(s, 28))
      case _                           => throw new IllegalArgumentException("credential object")
  private def optionHash(j: Json): Option[Bytes] = j match
    case Json.Lit("null") => None
    case Json.Str(s)      => Some(hash(s, 28))
    case _                => throw new IllegalArgumentException("pool delegation")
  private def fraction(j: Json): Stake.Ratio = j match
    case Json.Obj(_) =>
      get(Stake.fraction(uint(field(j, "numerator")), uint(field(j, "denominator"))))
    case Json.Num(text) =>
      val d = new java.math.BigDecimal(text);
      require(d.scale >= -64 && d.scale <= 64, "stake decimal bound")
      val a = BigInt(d.unscaledValue)
      val (n, b) =
        if d.scale >= 0 then (a, BigInt(10).pow(d.scale))
        else (a * BigInt(10).pow(-d.scale), BigInt(1))
      require(n >= 0 && n <= b, "stake decimal range"); val g = n.gcd(b)
      get(Stake.fraction(n / g, b / g))
    case _ => throw new IllegalArgumentException("stake rational")
  private def unique[A](xs: Vector[A]): Set[A] =
    require(xs.distinct.size == xs.size, "duplicate stake set entry"); xs.toSet
  private def pool(j: Json): Stake.Pool = Stake.Pool(
    hash(string(field(j, "spsVrf")), 32),
    uint(field(j, "spsPledge")),
    uint(field(j, "spsCost")),
    fraction(field(j, "spsMargin")),
    credential(field(j, "spsAccountId")),
    unique(array(field(j, "spsOwners")).map(x => hash(string(x), 28))),
    unique(array(field(j, "spsDelegators")).map(credential)),
    uint(field(j, "spsDeposit"))
  )
  private def snapshot(context: Stake.Context, j: Json): Stake.Snapshot =
    val active = obj(field(j, "activeStake")).map { (c, a) =>
      cred(c) -> Stake.Active(
        uint(field(a, "swdStake")),
        hash(string(field(a, "swdDelegation")), 28)
      )
    }
    val pools = obj(field(j, "stakePoolsSnapShot"))
    if active.isEmpty && pools.isEmpty then Stake.emptySnapshot
    else
      val expected = get(Stake.fromActive(context, active))
      require(pools.keySet == expected.pools.keySet.map(_.hex), "snapshot pool set mismatch")
      pools.foreach { (id, p) =>
        val e = expected.pools(hash(id, 28))
        require(
          uint(field(p, "stake")) == e.coin && fraction(field(p, "stakeRatio")) == e.ratio &&
            unique(
              array(field(p, "selfDelegatedOwners")).map(x => hash(string(x), 28))
            ) == e.owners &&
            uint(field(p, "selfDelegatedOwnersStake")) == e.ownerCoin && hash(
              string(field(p, "vrf")),
              32
            ) == e.vrf &&
            uint(field(p, "pledge")) == e.pledge && uint(field(p, "cost")) == e.cost && fraction(
              field(p, "margin")
            ) == e.margin &&
            uint(field(p, "numDelegators")) == e.delegators && credential(
              field(p, "accountId")
            ) == e.rewardAccount,
          "snapshot derived pool mismatch"
        )
      }
      expected

  /** Complete UTxO means the separately acquired whole-UTxO source, not the omitted ledger JSON
    * map. Source authenticity/atomicity and omitted donations/reward state are not inferred.
    */
  def decode(
      ledgerBytes: Bytes,
      ledgerPin: Bytes,
      utxoCbor: Bytes,
      utxoPin: Bytes,
      epochLength: BigInt
  ): Either[String, Prepared] = checked {
    source(ledgerBytes, ledgerPin); source(utxoCbor, utxoPin)
    val j = ReferenceJson.parse(ledgerBytes)
    val ds = field(j, "stateBefore", "esLState", "delegationState")
    val accounts = obj(field(ds, "dstate", "accounts")).map { (c, a) =>
      val balance = uint(field(a, "balance"))
      require(uint(field(a, "reward")) == balance, "deprecated reward/balance alias mismatch")
      cred(c) -> Stake.Account(balance, uint(field(a, "deposit")), optionHash(field(a, "spool")))
    }
    val pools = obj(field(ds, "pstate", "stakePools")).map((id, p) => hash(id, 28) -> pool(p))
    require(
      obj(field(ds, "pstate", "futureStakePoolParams")).isEmpty && obj(
        field(ds, "pstate", "retiring")
      ).isEmpty,
      "fixed pool fixture has pending changes"
    )
    val sourceId = ClusterHeaderObservation.sha256(Bytes(ledgerPin.value ++ utxoPin.value))
    val context = get(Stake.context(sourceId, epochLength, accounts, pools))
    val exported =
      obj(field(j, "stateBefore", "esLState", "utxoState", "stake", "credentials")).map((c, n) =>
        cred(c) -> uint(n)
      )
    val instant = get(Stake.recompute(utxoCbor))
    require(instant == exported, "whole UTxO/instantaneous export mismatch")
    val ss = field(j, "stateBefore", "esSnapshots")
    val snaps = Stake.Snapshots(
      snapshot(context, field(ss, "pstakeMark")),
      snapshot(context, field(ss, "pstakeSet")),
      snapshot(context, field(ss, "pstakeGo")),
      uint(field(ss, "feeSS"))
    )
    val max = (BigInt(1) << 64) - 1
    require(
      uint(field(j, "lastEpoch")) <= max && snaps.fees <= max &&
        uint(field(j, "stateBefore", "esLState", "utxoState", "fees")) <= max,
      "stake epoch/fee bounds"
    )
    new Prepared(
      context,
      utxoCbor,
      instant,
      snaps,
      uint(field(j, "lastEpoch")),
      uint(field(j, "stateBefore", "esLState", "utxoState", "fees")),
      sourceId
    )
  }
