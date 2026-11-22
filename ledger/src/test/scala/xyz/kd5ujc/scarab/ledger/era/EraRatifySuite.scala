// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedSet
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Committee as StoredCommittee,
  Credential as FlatCredential,
  DecodeField,
  DecoderVersion,
  GovActionId,
  PreprodDeepTx,
  RewardAccount,
  Unsigned,
  Vote
}
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W16
import xyz.kd5ujc.scarab.ledger.era.conway.{DRepVotingThresholds, PoolVotingThresholds}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraEraPParams}
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayEnact, ConwayRatify, EnactSignal}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  ColdCommittee,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  ConwayAccounts,
  ConwayInstantStake,
  Credential,
  DRep,
  DRepRole,
  DRepState,
  Dijkstra,
  EpochNo,
  HotCommittee,
  KeyHash,
  KeyRole,
  NonZero,
  Sorted,
  StakePool
}

/** RATIFY and ENACT on the era types (`Conway/Rules/Ratify.hs`, `Conway/Rules/Enact.hs`,
  * `Governance/Internal.hs:311-544`, cardano-ledger f649f975; Dijkstra's the same at 9df9f98c), at
  * Dijkstra, on crafted states:
  *   - the pools' and the DReps' thresholds of each update of the Leios build's parameter corpus
  *     (`fixtures/reference/dijkstra-pparams`), each body's threshold distinct, against the groups
  *     the build recorded for that update: every key alone, Dijkstra's 34 to 56 among them;
  *   - one parameter change of each group Dijkstra's keys fall in (Network/Security 47,
  *     Technical/NoStakePool 38, Economic/NoStakePool 39, Economic/Security 55 and 56) ratified
  *     when every body votes yes, refused when the DReps vote no, refused when the pools vote no
  *     only where the pools vote on it, and enacted as `applyPPUpdates` gives;
  *   - the committee's, DReps' and pools' shares of several voters at, above and below each
  *     threshold, an abstaining, expired or always-abstain voter not counted and a silent one no;
  *   - ENACT's withdrawals by credential as `Map.mapKeys` keeps them, its committee update, its
  *     previous parameters kept where they differ from the current ones, and the order of
  *     `reorderActions`;
  *   - properties of ENACT's treasury and of the fold (TST-6), and RATIFY over a long signal on a
  *     small stack (STK-3).
  */
class EraRatifySuite extends ScalaCheckSuite:
  private given dijkstraPP: DijkstraEraPParams[Dijkstra] = EraPParams.dijkstra
  private given ConwayEraAccounts[Dijkstra] = EraAccounts.dijkstra

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private def bytes(n: Int, size: Int): Bytes = Bytes(Vector.fill(size)(n.toByte))

  private def keyHash[R <: KeyRole](n: Int): KeyHash[R] = ok(KeyHash.fromBytes[R](bytes(n, 28)))

  private def unit(n: Int, d: Int): UnitInterval =
    UnitInterval.boundRational(Rational(n, d)).getOrElse(fail(s"$n/$d"))

  // ---- CBOR of crafted proposals, as the reference's encoders write them

  private def head(major: Int, n: BigInt): String =
    val m = major << 5
    if n < 24 then f"${m | n.toInt}%02x"
    else
      Vector(24 -> 1, 25 -> 2, 26 -> 4, 27 -> 8)
        .collectFirst {
          case (code, width) if n < (BigInt(1) << (8 * width)) =>
            f"${m | code}%02x" + n.toString(16).reverse.padTo(2 * width, '0').reverse
        }
        .getOrElse(fail(s"$n"))

  private def uint(n: BigInt): String = head(0, n)
  private def bstr(b: Bytes): String = head(2, b.size) + b.hex
  private def arr(items: String*): String = head(4, items.size) + items.mkString

  private val txId = bytes(6, 32)
  private def actionId(ix: Int): GovActionId = GovActionId(txId, BigInt(ix))
  private val anchor = arr(
    head(3, 19) + "https://example.com".getBytes.map(b => f"$b%02x").mkString,
    bstr(bytes(0, 32))
  )
  private val returnAccount = bstr(DijkstraCorpus.hex("e0" + "01" * 28))

  /** A proposal of `action` with the network's deposit, as `[deposit, account, action, anchor]`. */
  private def procedure(action: String): ProposalProcedure[Dijkstra] =
    DijkstraCorpus
      .eraProposals(
        DijkstraCorpus.hex("d90102" + arr(arr(uint(100), returnAccount, action, anchor)))
      )
      .toVector
      .headOption
      .getOrElse(fail("no proposal"))

  /** `[0, parent, update, null]`: a parameter change of the update's bytes. */
  private def parameterChange(update: String, parent: Option[Int] = None): String =
    arr(uint(0), parent.fold("f6")(ix => arr(bstr(txId), uint(ix))), update, "f6")

  private val info = arr(uint(6))

  // ---- a state with one committee member, one DRep and one pool, each with stake

  private val cold: Credential[ColdCommittee] = Credential.KeyHashObj(keyHash[ColdCommittee](3))
  private val hot: Credential[HotCommittee] = Credential.KeyHashObj(keyHash[HotCommittee](4))
  private val drep: Credential[DRepRole] = Credential.KeyHashObj(keyHash[DRepRole](2))
  private val pool: KeyHash[StakePool] = keyHash[StakePool](8)
  private val epoch = EpochNo.fromUnsigned(10)

  private val drepThresholds = DRepVotingThresholds(
    unit(1, 13),
    unit(2, 13),
    unit(3, 13),
    unit(4, 13),
    unit(5, 13),
    unit(6, 13),
    unit(7, 13),
    unit(8, 13),
    unit(9, 13),
    unit(10, 13)
  )
  private val poolThresholds =
    PoolVotingThresholds(unit(1, 17), unit(2, 17), unit(3, 17), unit(4, 17), unit(5, 17))

  /** The testnet's parameters, with the thresholds above, a committee of at least one member and
    * terms of at most 100 epochs.
    */
  private val pp: PParams[Dijkstra] =
    val p = ConwayEraPParams
      .ppDRepVotingThresholdsL[Dijkstra]
      .replace(drepThresholds)
      .andThen(ConwayEraPParams.ppPoolVotingThresholdsL[Dijkstra].replace(poolThresholds))
      .andThen(ConwayEraPParams.ppCommitteeMinSizeL[Dijkstra].replace(Unsigned.lit[W16](1)))(
        DijkstraCorpus.testnetPParams
      )
    assertEquals(
      xyz.kd5ujc.scarab.ledger.era.rules.ConwayEra
        .hardforkConwayBootstrapPhase(dijkstraPP.ppProtocolVersionL.get(p)),
      false
    )
    p

  private val committee =
    StoredCommittee(Map(FlatCredential.Key(bytes(3, 28)) -> BigInt(50)), Rational(1, 2))

  private def enactState(treasury: BigInt = 1000): EnactState[Dijkstra] =
    EnactState[Dijkstra](
      Some(committee),
      xyz.kd5ujc.scarab.ledger.ConwayGenesis.noConstitution,
      pp,
      pp,
      Coin(treasury),
      Sorted.empty,
      GovRelation[Option](None, None, None, None)
    )

  private def ratifyState(enact: EnactState[Dijkstra] = enactState()): RatifyState[Dijkstra] =
    RatifyState[Dijkstra](enact, Vector.empty, SortedSet.empty, false)

  private val env: RatifyEnv[Dijkstra] = RatifyEnv[Dijkstra](
    ConwayInstantStake(Sorted.empty),
    VotingStakePoolDistr(
      Sorted.map(Vector(pool -> CompactCoin.fromUnsigned(700))),
      ok(NonZero.from(Coin(700)))
    ),
    Map(DRep.DRepKeyHash(keyHash[DRepRole](2)) -> CompactCoin.fromUnsigned(500)),
    Map(drep -> DRepState(EpochNo.fromUnsigned(100), None, CompactCoin.zero, Sorted.emptySet)),
    epoch,
    CommitteeState(Sorted.map(Vector(cold -> CommitteeAuthorization.CommitteeHotCredential(hot)))),
    ConwayAccounts(Sorted.empty),
    Sorted.empty
  )

  private def gas(
      ix: Int,
      action: String,
      committeeVote: Vote = Vote.Yes,
      drepVote: Vote = Vote.Yes,
      poolVote: Vote = Vote.Yes,
      expiresAfter: Long = 20
  ): GovActionState[Dijkstra] =
    GovActionState[Dijkstra](
      actionId(ix),
      Sorted.map(Vector(hot -> committeeVote)),
      Sorted.map(Vector(drep -> drepVote)),
      Sorted.map(Vector(pool -> poolVote)),
      procedure(action),
      EpochNo.fromUnsigned(9),
      EpochNo.fromUnsigned(expiresAfter)
    )

  private def ratify(signal: GovActionState[Dijkstra]*): RatifyState[Dijkstra] =
    ok(ConwayRatify.ratifyTransition(env, ratifyState(), RatifySignal(signal.toVector)))

  // ---- the corpus of the Leios build's updates

  private final case class Row(id: String, update: String, groups: List[List[String]])

  private val rows: Vector[Row] =
    Files
      .readAllLines(Path.of("fixtures/reference/dijkstra-pparams/cases.jsonl"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => parse(l).fold(e => fail(e.message), identity).hcursor)
      .filterNot(_.downField("reference").downField("refused").succeeded)
      .map(c =>
        Row(
          c.get[String]("id").fold(e => fail(e.message), identity),
          c.get[String]("update").fold(e => fail(e.message), identity),
          c.downField("reference")
            .get[List[List[String]]]("groups")
            .fold(e => fail(e.message), identity)
        )
      )

  private def node(hex: String): xyz.kd5ujc.scarab.codec.Node =
    Cbor.decode(DijkstraCorpus.hex(hex)).fold(e => fail(s"$e"), identity)

  private def update(hex: String): PParamsUpdate[Dijkstra] =
    given DecodeAt = DecodeAt(DecoderVersion.Dijkstra, DecodeField.ParameterUpdate)
    ok(PParamsUpdate.decodePParamsUpdate[Dijkstra](node(hex)))

  /** The keys an update's bytes set. */
  private def keys(hex: String): Set[Int] = node(hex).value match
    case Value.Map(entries) =>
      entries.map(_._1.value).collect { case Value.UInt(k) => k.toInt }.toSet
    case other => fail(s"not a map: $other")

  private def drepThreshold(group: String): UnitInterval = group match
    case "NetworkGroup"   => drepThresholds.dvtPPNetworkGroup
    case "EconomicGroup"  => drepThresholds.dvtPPEconomicGroup
    case "TechnicalGroup" => drepThresholds.dvtPPTechnicalGroup
    case "GovGroup"       => drepThresholds.dvtPPGovGroup
    case other            => fail(s"group $other")

  private def greatest(us: Iterable[UnitInterval]): Rational =
    us.foldLeft(Rational.zero)((a, u) => a.max(u.rational))

  test("each update's pool and DRep thresholds follow the groups the Leios build recorded") {
    assert(rows.size > 100, s"${rows.size} rows")
    rows.foreach { row =>
      val action = GovAction.ParameterChange[Dijkstra](None, update(row.update), None)
      val rs = ratifyState()
      val security = row.groups.exists(_(1) == "SecurityGroup")
      assertEquals(
        VotingThreshold.votingDRepThreshold(rs, action).map(_.rational),
        Some(greatest(row.groups.map(g => drepThreshold(g.head)))),
        row.id
      )
      assertEquals(
        VotingThreshold.votingStakePoolThresholdInternal(pp, true, action),
        if security then VotingThreshold.Threshold(poolThresholds.pvtPPSecurityGroup)
        else VotingThreshold.NoVotingAllowed,
        row.id
      )
      assertEquals(VotingThreshold.isStakePoolVotingAllowed(action), security, row.id)
      assertEquals(VotingThreshold.isDRepVotingAllowed(action), true, row.id)
      assertEquals(
        VotingThreshold.isCommitteeVotingAllowed(epoch, env.reCommitteeState, action),
        true,
        row.id
      )
    }
  }

  test("Dijkstra's keys 34 to 56 alone, in the groups the Leios build gives them") {
    val alone = rows.filter(r => keys(r.update).size == 1).groupBy(r => keys(r.update).head)
    val expected = (34 to 56).map { k =>
      k -> (k match
        case 38      => List("TechnicalGroup", "NoStakePoolGroup")
        case 39      => List("EconomicGroup", "NoStakePoolGroup")
        case 55 | 56 => List("EconomicGroup", "SecurityGroup")
        case _       => List("NetworkGroup", "SecurityGroup"))
    }.toMap
    assertEquals(alone.keySet.filter(_ >= 34), expected.keySet)
    expected.foreach { (k, group) =>
      alone(k).foreach(r => assertEquals(r.groups, List(group), r.id))
    }
  }

  // ---- one parameter change of each group, through RATIFY and ENACT

  private type Record = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraPParams[Identity]
  private type UpdateRecord = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraPParams[StrictMaybe]

  /** What ENACT must change of the parameters for one update: each field the update sets is the
    * update's value, and the parameters with those fields put back are the ones before.
    */
  private final case class Fields(
      set: (Record, UpdateRecord) => Boolean,
      restored: (Record, Record) => Record
  )

  private def corpus(id: String): String =
    rows.find(_.id == id).map(_.update).getOrElse(fail(s"no row $id"))

  /** Keys 55 and 56 in one update, as a definite map. */
  private val refInputsCosts = "a2183705183807"

  /** One update of each group, from the corpus, and keys 55 and 56 together. */
  private val cases: Vector[(String, String, Boolean, Fields)] = Vector(
    (
      "Network/Security (47)",
      corpus("maxEndorserBlockExUnits non-zero"),
      true,
      Fields(
        (a, u) => u.maxEndorserBlockExUnits.unTHKD == Some(a.maxEndorserBlockExUnits.unTHKD),
        (a, b) => a.copy(maxEndorserBlockExUnits = b.maxEndorserBlockExUnits)
      )
    ),
    (
      "Technical/NoStakePool (38)",
      corpus("maxPledgeLeverage non-zero"),
      false,
      Fields(
        (a, u) => u.maxPledgeLeverage.unTHKD == Some(a.maxPledgeLeverage.unTHKD),
        (a, b) => a.copy(maxPledgeLeverage = b.maxPledgeLeverage)
      )
    ),
    (
      "Economic/NoStakePool (39)",
      corpus("minPoolMargin non-zero"),
      false,
      Fields(
        (a, u) =>
          u.minPoolMargin.unTHKD == Some(a.minPoolMargin.unTHKD) &&
            a.minPoolMargin.unTHKD.rational == Rational(1, 2),
        (a, b) => a.copy(minPoolMargin = b.minPoolMargin)
      )
    ),
    (
      "Economic/Security (55 and 56)",
      refInputsCosts,
      true,
      Fields(
        (a, u) =>
          u.refInputsCostPerMultiAssetPolicy.unTHKD ==
            Some(a.refInputsCostPerMultiAssetPolicy.unTHKD) &&
            a.refInputsCostPerMultiAssetPolicy.unTHKD == Coin(5) &&
            u.refInputsCostPerDatumByte.unTHKD == Some(a.refInputsCostPerDatumByte.unTHKD),
        (a, b) =>
          a.copy(
            refInputsCostPerMultiAssetPolicy = b.refInputsCostPerMultiAssetPolicy,
            refInputsCostPerDatumByte = b.refInputsCostPerDatumByte
          )
      )
    )
  )

  cases.foreach { (group, hex, security, fields) =>
    test(s"the $group parameter change: its thresholds, the bodies it needs and what ENACT makes") {
      val ppu = update(hex)
      val action = GovAction.ParameterChange[Dijkstra](None, ppu, None)
      val rs = ratifyState()
      val drepExpected = group.takeWhile(_ != '/') match
        case "Network"   => drepThresholds.dvtPPNetworkGroup
        case "Technical" => drepThresholds.dvtPPTechnicalGroup
        case "Economic"  => drepThresholds.dvtPPEconomicGroup
        case other       => fail(other)
      assertEquals(VotingThreshold.votingDRepThreshold(rs, action), Some(drepExpected))
      assertEquals(
        VotingThreshold.votingStakePoolThreshold(rs, action),
        Some(if security then poolThresholds.pvtPPSecurityGroup else UnitInterval.zero)
      )
      assertEquals(
        ok(VotingThreshold.votingCommitteeThreshold(epoch, rs, env.reCommitteeState, action)),
        Some(unit(1, 2))
      )
      val proposal = parameterChange(hex)
      val enacted = ratify(gas(0, proposal))
      assertEquals(enacted.rsEnacted.map(_.gasId), Vector(actionId(0)))
      val after: Record = enacted.rsEnactState.ensCurPParams.hkd
      assert(fields.set(after, ppu.hkd), "each field the update sets is the update's value")
      assertEquals(fields.restored(after, pp.hkd), pp.hkd: Record, "and no other field changed")
      assertNotEquals(after, pp.hkd: Record)
      assertEquals(
        enacted.rsEnactState.ensPrevGovActionIds.grPParamUpdate,
        Some(GovPurposeId[GovActionPurpose.PParamUpdate](actionId(0)))
      )
      assertEquals(enacted.rsEnactState.ensTreasury, Coin.zero)
      assertEquals(enacted.rsDelayed, false)
      assertEquals(ratify(gas(0, proposal, drepVote = Vote.No)).rsEnacted, Vector.empty)
      assertEquals(ratify(gas(0, proposal, committeeVote = Vote.No)).rsEnacted, Vector.empty)
      assertEquals(
        ratify(gas(0, proposal, poolVote = Vote.No)).rsEnacted.nonEmpty,
        !security,
        "the pools' no counts only where they vote"
      )
    }
  }

  // ---- several voters in each body: the vote shares at, above and below each threshold

  private val colds: Vector[Credential[ColdCommittee]] =
    Vector(3, 11, 12).map(n => Credential.KeyHashObj(keyHash[ColdCommittee](n)))
  private val hots: Vector[Credential[HotCommittee]] =
    Vector(4, 13, 14).map(n => Credential.KeyHashObj(keyHash[HotCommittee](n)))

  /** Five DReps of stakes 6, 7, 100, 1 and 50, the last expired, and the always-abstain DRep. */
  private val drepKeys: Vector[(Int, Long, Long)] =
    Vector((2, 6L, 100L), (21, 7L, 100L), (22, 100L, 100L), (23, 1L, 100L), (24, 50L, 9L))
  private val dreps: Vector[Credential[DRepRole]] =
    drepKeys.map((n, _, _) => Credential.KeyHashObj(keyHash[DRepRole](n)))

  /** Three pools of stakes 5, 12 and 100, the whole voting stake. */
  private val pools: Vector[(KeyHash[StakePool], Long)] =
    Vector((8, 5L), (31, 12L), (32, 100L)).map((n, s) => keyHash[StakePool](n) -> s)

  /** The committee of the three members, each to epoch 50, its quorum one half. */
  private val committeeOfThree =
    StoredCommittee(
      Vector(3, 11, 12).map(n => FlatCredential.Key(bytes(n, 28)) -> BigInt(50)).toMap,
      Rational(1, 2)
    )

  private val envOfMany: RatifyEnv[Dijkstra] = RatifyEnv[Dijkstra](
    ConwayInstantStake(Sorted.empty),
    VotingStakePoolDistr(
      Sorted.map(pools.map((k, s) => k -> CompactCoin.fromUnsigned(s))),
      ok(NonZero.from(Coin(pools.map(_._2).sum)))
    ),
    drepKeys
      .map((n, s, _) =>
        (DRep.DRepKeyHash(keyHash[DRepRole](n)): DRep) -> CompactCoin.fromUnsigned(s)
      )
      .toMap + (DRep.DRepAlwaysAbstain -> CompactCoin.fromUnsigned(1000)),
    drepKeys
      .zip(dreps)
      .map { case ((_, _, expiry), d) =>
        d -> DRepState(EpochNo.fromUnsigned(expiry), None, CompactCoin.zero, Sorted.emptySet)
      }
      .toMap,
    epoch,
    CommitteeState(
      Sorted.map(
        colds.zip(hots).map((c, h) => c -> CommitteeAuthorization.CommitteeHotCredential(h))
      )
    ),
    ConwayAccounts(Sorted.empty),
    Sorted.empty
  )

  private def cast[K: cats.Order](keys: Vector[K], votes: Vector[Option[Vote]]) =
    Sorted.map(keys.zip(votes).collect { case (k, Some(v)) => k -> v })

  private val Y = Some(Vote.Yes)
  private val N = Some(Vote.No)
  private val A = Some(Vote.Abstain)
  private val silent = None

  /** A change of key 47 (Network/Security: every body votes) with these votes. */
  private def manyVotes(
      committeeVotes: Vector[Option[Vote]],
      drepVotes: Vector[Option[Vote]],
      poolVotes: Vector[Option[Vote]]
  ): GovActionState[Dijkstra] =
    GovActionState[Dijkstra](
      actionId(0),
      cast(hots, committeeVotes),
      cast(dreps, drepVotes),
      cast(pools.map(_._1), poolVotes),
      procedure(parameterChange(corpus("maxEndorserBlockExUnits non-zero"))),
      EpochNo.fromUnsigned(9),
      EpochNo.fromUnsigned(20)
    )

  private def enactedOfMany(gas: GovActionState[Dijkstra]): Boolean =
    ok(
      ConwayRatify.ratifyTransition(
        envOfMany,
        ratifyState(enactState().copy(ensCommittee = Some(committeeOfThree))),
        RatifySignal(Vector(gas))
      )
    ).rsEnacted.nonEmpty

  private val allYes3 = Vector(Y, Y, Y)
  private val drepsYes = Vector(Y, Y, Y, Y, Y)

  test("the committee's share of several members: yes over those not abstaining, a silent one no") {
    val members = Sorted.map(colds.map(_ -> EpochNo.fromUnsigned(50)))
    Vector(
      (Vector(Y, Y, N), Rational(2, 3), true),
      (Vector(Y, N, A), Rational(1, 2), true),
      (Vector(Y, N, silent), Rational(1, 3), false),
      (Vector(Y, N, N), Rational(1, 3), false)
    ).foreach { (votes, share, enacted) =>
      val gas = manyVotes(votes, drepsYes, allYes3)
      assertEquals(
        ConwayRatify.committeeAcceptedRatio(
          members,
          gas.gasCommitteeVotes,
          envOfMany.reCommitteeState,
          epoch
        ),
        share,
        s"$votes"
      )
      assertEquals(enactedOfMany(gas), enacted, s"$votes against 1/2")
    }
  }

  test(
    "the DReps' share of several stakes: an abstaining, expired or always-abstain one not counted"
  ) {
    assertEquals(drepThresholds.dvtPPNetworkGroup, unit(6, 13))
    Vector(
      (Vector(Y, N, A, A, N), Rational(6, 13), true),
      (Vector(Y, N, A, silent, N), Rational(6, 14), false),
      (Vector(N, Y, A, A, N), Rational(7, 13), true),
      (Vector(Y, N, N, A, N), Rational(6, 113), false)
    ).foreach { (votes, share, enacted) =>
      val gas = manyVotes(allYes3, votes, allYes3)
      assertEquals(
        ConwayRatify.dRepAcceptedRatio(envOfMany, gas.gasDRepVotes, gas.gasAction),
        share,
        s"$votes"
      )
      assertEquals(enactedOfMany(gas), enacted, s"$votes against 6/13")
    }
  }

  test(
    "the pools' share of several stakes: yes over the total less the abstaining, a silent one no"
  ) {
    assertEquals(poolThresholds.pvtPPSecurityGroup, unit(5, 17))
    Vector(
      (Vector(Y, N, A), Rational(5, 17), true),
      (Vector(Y, N, silent), Rational(5, 117), false),
      (Vector(N, Y, A), Rational(12, 17), true),
      (Vector(Y, N, N), Rational(5, 117), false)
    ).foreach { (votes, share, enacted) =>
      val gas = manyVotes(allYes3, drepsYes, votes)
      assertEquals(
        ConwayRatify.spoAcceptedRatio(envOfMany, gas, dijkstraPP.ppProtocolVersionL.get(pp)),
        share,
        s"$votes"
      )
      assertEquals(enactedOfMany(gas), enacted, s"$votes against 5/17")
    }
  }

  test("ENACT keeps the previous parameters where they differ from the current ones before it") {
    val previous = ConwayEraPParams.ppCommitteeMinSizeL[Dijkstra].replace(Unsigned.lit[W16](7))(pp)
    val before = enactState().copy(ensPrevPParams = previous)
    assertNotEquals(before.ensCurPParams, before.ensPrevPParams)
    val ppu = update(corpus("maxPledgeLeverage non-zero"))
    val signals = Vector(
      GovAction.ParameterChange[Dijkstra](None, ppu, None),
      GovAction.HardForkInitiation[Dijkstra](
        None,
        ProtVer(Version.lit(13), Unsigned.lit[Unsigned.Width.W32](0))
      ),
      GovAction.NoConfidence[Dijkstra](None),
      GovAction.InfoAction[Dijkstra]()
    )
    signals.zipWithIndex.foreach { (action, ix) =>
      val after =
        ok(ConwayEnact.enactmentTransition(before, EnactSignal[Dijkstra](actionId(ix), action)))
      assertEquals(after.ensPrevPParams, previous, s"$action")
    }
    val changed = ok(
      ConwayEnact.enactmentTransition(
        before,
        EnactSignal[Dijkstra](actionId(0), GovAction.ParameterChange[Dijkstra](None, ppu, None))
      )
    )
    assertEquals(changed.ensCurPParams, dijkstraPP.applyPPUpdates(pp, ppu))
    assertEquals(
      Vector(pp, previous, changed.ensCurPParams).distinct.size,
      3,
      "the current, previous and new parameters pairwise distinct"
    )
  }

  test("ENACT of a hard fork, a committee update and a motion of no confidence") {
    val st = enactState()
    val fork = ok(
      ConwayEnact.enactmentTransition(
        st,
        EnactSignal[Dijkstra](
          actionId(1),
          GovAction.HardForkInitiation[Dijkstra](
            None,
            ProtVer(Version.lit(13), Unsigned.lit[Unsigned.Width.W32](0))
          )
        )
      )
    )
    assertEquals(dijkstraPP.ppProtocolVersionL.get(fork.ensCurPParams).pvMajor, Version.lit(13))
    assertEquals(
      fork.ensPrevGovActionIds.grHardFork,
      Some(GovPurposeId[GovActionPurpose.HardFork](actionId(1)))
    )
    val added: Credential[ColdCommittee] = Credential.KeyHashObj(keyHash[ColdCommittee](9))
    val update = ok(
      ConwayEnact.enactmentTransition(
        st,
        EnactSignal[Dijkstra](
          actionId(2),
          GovAction.UpdateCommittee[Dijkstra](
            None,
            Set(cold),
            Map(added -> EpochNo.fromUnsigned(40), cold -> EpochNo.fromUnsigned(41)),
            unit(2, 3)
          )
        )
      )
    )
    // `Map.union membersToAdd (current withoutKeys toRemove)`: a member removed and added stays
    assertEquals(
      update.ensCommittee,
      Some(
        StoredCommittee(
          Map(
            FlatCredential.Key(bytes(9, 28)) -> BigInt(40),
            FlatCredential.Key(bytes(3, 28)) -> BigInt(41)
          ),
          Rational(2, 3)
        )
      )
    )
    val noConfidence = ok(
      ConwayEnact.enactmentTransition(
        st,
        EnactSignal[Dijkstra](actionId(3), GovAction.NoConfidence[Dijkstra](None))
      )
    )
    assertEquals(noConfidence.ensCommittee, None)
    assertEquals(
      noConfidence.ensPrevGovActionIds.grCommittee,
      Some(GovPurposeId[GovActionPurpose.Committee](actionId(3)))
    )
  }

  test("ENACT keeps, of two accounts of one credential, the greater one's withdrawal") {
    val credential = FlatCredential.Key(bytes(5, 28))
    val st = ok(
      ConwayEnact.enactmentTransition(
        enactState(),
        EnactSignal[Dijkstra](
          actionId(4),
          GovAction.TreasuryWithdrawals[Dijkstra](
            Map(RewardAccount(1, credential) -> Coin(7), RewardAccount(0, credential) -> Coin(3)),
            None
          )
        )
      )
    )
    // `mapKeys` keeps the value of the greatest key, the mainnet account (network 1), and the
    // treasury pays the sum of both
    assertEquals(st.ensWithdrawals.toVector, Vector(Credential.KeyHashObj(keyHash(5)) -> Coin(7)))
    assertEquals(st.ensTreasury, Coin(990))
  }

  property("ENACT of withdrawals to distinct accounts moves their sum out of the treasury") {
    forAll(Gen.listOf(Gen.zip(Gen.choose(1, 60), Gen.choose(0L, 1000L)))) {
      (entries: List[(Int, Long)]) =>
        val wdrls = entries.toMap.map((n: Int, v: Long) =>
          RewardAccount(0, FlatCredential.Key(bytes(n, 28))) -> Coin(v)
        )
        val before = enactState(100000).copy(
          ensWithdrawals = Sorted.map(Vector(Credential.KeyHashObj(keyHash(1)) -> Coin(5)))
        )
        val after = ok(
          ConwayEnact.enactmentTransition(
            before,
            EnactSignal[Dijkstra](actionId(5), GovAction.TreasuryWithdrawals[Dijkstra](wdrls, None))
          )
        )
        val moved = after.ensWithdrawals.valuesIterator.map(_.value).sum -
          before.ensWithdrawals.valuesIterator.map(_.value).sum
        assertEquals(moved, wdrls.valuesIterator.map(_.value).sum)
        assertEquals(after.ensTreasury.value, before.ensTreasury.value - moved)
    }
  }

  property("an action no body accepts is never enacted, and expires only past its last epoch") {
    forAll(Gen.listOf(Gen.choose(0L, 20L))) { expiries =>
      val signal = expiries.zipWithIndex.map((e, ix) =>
        gas(ix, info, committeeVote = Vote.No, expiresAfter = e)
      )
      val end = ratify(signal*)
      assertEquals(end.rsEnacted, Vector.empty)
      assertEquals(
        end.rsExpired,
        SortedSet.from(signal.filter(_.gasExpiresAfter.toBigInt < epoch.toBigInt).map(_.gasId))
      )
      assertEquals(end.rsEnactState, enactState(0))
    }
  }

  test("reorderActions sorts by priority, keeping the order within a priority") {
    val signal = Vector(
      gas(0, info),
      gas(1, parameterChange(refInputsCosts)),
      gas(2, arr(uint(3), "f6")),
      gas(3, info),
      gas(4, parameterChange(refInputsCosts))
    )
    assertEquals(
      RatifySignal.reorderActions(signal).map(_.gasId.index.toInt),
      Vector(2, 1, 4, 0, 3)
    )
  }

  test("RATIFY over a long signal, enacting a chain of changes, on a small stack") {
    // 20,000 changes of key 38, each the child of the one before, which no body but the committee
    // and the DReps vote on: each is enacted on the enact state the one before left
    val leverage = corpus("maxPledgeLeverage non-zero")
    val n = 20000
    val signal = (0 until n).map(ix =>
      gas(ix, parameterChange(leverage, Option.when(ix > 0)(ix - 1)), poolVote = Vote.No)
    )
    val end = PreprodDeepTx.onSmallStack(ratify(signal*))
    assertEquals(end.rsEnacted.size, n)
    assertEquals(
      end.rsEnactState.ensPrevGovActionIds.grPParamUpdate,
      Some(GovPurposeId[GovActionPurpose.PParamUpdate](actionId(n - 1)))
    )
  }
