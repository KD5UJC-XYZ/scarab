// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Eq, Order}
import monocle.Lens
import monocle.law.discipline.LensTests
import munit.DisciplineSuite
import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational, Value}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  Credential as FlatCredential,
  GovAction as FlatGovAction,
  GovActionId,
  GovActionState as StoredGovActionState,
  Proposal,
  RewardAccount,
  Unsigned,
  Vote
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayProcedures, ConwayProposals}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraProposals, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayGov
import xyz.kd5ujc.scarab.ledger.rules.{Convert, DijkstraConway}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  Credential,
  DRepRole,
  Dijkstra,
  EpochInterval,
  EpochNo,
  EraTag,
  HotCommittee,
  KeyHash,
  KeyRole,
  StakePool,
  Staking
}

/** The governance state of the era types (`ConwayGovState era`, `EraGov`, `ConwayEraGov`;
  * cardano-ledger f649f975, Dijkstra's at 9df9f98c):
  *   - the lens laws of each lens of `EraGov` and `ConwayEraGov`, at Conway and Dijkstra (TST-6);
  *   - the typed vote keys order as the node's flat ones (`Credential.CredentialOrder`, the flat
  *     credential's and the bytes' orders), so that a vote map is the same sequence either way;
  *   - Dijkstra's proposals through the flat record: every proposal of the reference's GOV cases
  *     read by the stored reader, written to the flat proposal as the flat path writes it
  *     (`DijkstraConway.proposal`) and read back unchanged, and the chain's two proposals, of keys
  *     45 and 46, and 47 and 48, refused with their keys, alone and in a governance state;
  *   - a stored vote of a pool key of another length refused with its action's id.
  */
class EraGovStateSuite extends DisciplineSuite:
  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private def bytes(n: Int, size: Int): Bytes = Bytes(Vector.fill(size)(n.toByte))

  private def id(n: Int): GovActionId = GovActionId(bytes(n, 32), BigInt(0))

  private val account = RewardAccount(0, FlatCredential.Key(bytes(1, 28)))

  private val info = Proposal(BigInt(100), account, FlatGovAction.Info)

  // ---- the lens laws

  private def lawsOf[S, A](name: String, lens: Lens[S, A], s: Gen[S], a: Gen[A]): Unit =
    given Arbitrary[S] = Arbitrary(s)
    given Arbitrary[A] = Arbitrary(a)
    given Cogen[A] = Cogen[Int].contramap(_.hashCode)
    given Eq[S] = Eq.fromUniversalEquals
    given Eq[A] = Eq.fromUniversalEquals
    checkAll(name, LensTests(lens))

  private def laws[E <: EraTag](era: String, proposal: ProposalProcedure[E])(using
      g: ConwayEraGov[E],
      ev: ConwayGovState[E] =:= GovState[E]
  ): Unit =
    val p = g.pparamsOf
    val pparams: Gen[PParams[E]] =
      Gen
        .choose(9, 12)
        .flatMap(major =>
          Gen
            .choose(0, 2)
            .map(minor =>
              p.ppProtocolVersionL.replace(
                ProtVer(
                  Version.mkVersion(BigInt(major)).getOrElse(fail("no version")),
                  Unsigned.from[Unsigned.Width.W32](BigInt(minor)).getOrElse(fail("no minor"))
                )
              )(p.emptyPParams)
            )
        )
    val committee: Gen[Option[Committee]] =
      Gen.option(
        Gen
          .choose(1, 3)
          .map(n =>
            Committee(
              Map(FlatCredential.Key(bytes(n, 28)) -> BigInt(n)),
              Rational(BigInt(1), BigInt(n + 1))
            )
          )
      )
    val constitution: Gen[Constitution] =
      Gen
        .choose(0, 3)
        .map(n =>
          Constitution(Anchor(s"https://c/$n", bytes(n, 32)), Option.when(n % 2 == 0)(bytes(n, 28)))
        )
    val future: Gen[FuturePParams[E]] = Gen.oneOf(
      Gen.const(FuturePParams.NoPParamsUpdate[E]()),
      pparams.map(FuturePParams.DefinitePParamsUpdate(_)),
      Gen.option(pparams).map(FuturePParams.PotentialPParamsUpdate(_))
    )
    val none = GovRelation[Option](None, None, None, None)
    val proposals: Gen[Proposals[E]] = Gen.oneOf(
      Proposals.empty[E](none),
      Proposals.empty[E](none.copy(grHardFork = Some(GovPurposeId(id(9))))),
      Proposals
        .proposalsAddAction(
          ConwayGov.mkGovActionState(id(1), proposal, EpochInterval.zero, EpochNo.fromUnsigned(3)),
          Proposals.empty[E](none)
        )
        .getOrElse(fail("not added"))
    )
    val pulser: Gen[DRepPulsingState[E]] =
      Gen
        .oneOf(true, false)
        .map(delayed =>
          DRepPulsingState.DRComplete(
            PulsingSnapshot.empty[E],
            RatifyState.empty[E](using p).copy(rsDelayed = delayed)
          )
        )
    val state: Gen[GovState[E]] =
      for
        ps <- proposals
        c <- committee
        k <- constitution
        cur <- pparams
        prev <- pparams
        f <- future
        d <- pulser
      yield ev(ConwayGovState[E](ps, c, k, cur, prev, f, d))
    lawsOf(s"$era curPParamsGovStateL", g.curPParamsGovStateL, state, pparams)
    lawsOf(s"$era prevPParamsGovStateL", g.prevPParamsGovStateL, state, pparams)
    lawsOf(s"$era futurePParamsGovStateL", g.futurePParamsGovStateL, state, future)
    lawsOf(s"$era constitutionGovStateL", g.constitutionGovStateL, state, constitution)
    lawsOf(s"$era proposalsGovStateL", g.proposalsGovStateL, state, proposals)
    lawsOf(s"$era drepPulsingStateGovStateL", g.drepPulsingStateGovStateL, state, pulser)
    lawsOf(s"$era committeeGovStateL", g.committeeGovStateL, state, committee)

  laws[Conway]("Conway", ok(ConwayProcedures.proposal(info)))
  laws[Dijkstra]("Dijkstra", ok(DijkstraProposals.fromFlat(info)))

  test("the empty governance state of each era is the reference's default, its deposits none") {
    val conway = EraGov.conway
    val dijkstra = EraGov.dijkstra
    assertEquals(conway.emptyGovState, ConwayGovState.empty[Conway])
    assertEquals(dijkstra.emptyGovState, ConwayGovState.empty[Dijkstra])
    assertEquals(conway.obligationGovState(conway.emptyGovState), cats.Monoid[Obligations].empty)
    val one = conway.proposalsGovStateL.replace(
      Proposals
        .proposalsAddAction(
          ConwayGov.mkGovActionState(
            id(1),
            ok(ConwayProcedures.proposal(info)),
            EpochInterval.zero,
            EpochNo.fromUnsigned(0)
          ),
          Proposals.empty[Conway](GovRelation[Option](None, None, None, None))
        )
        .getOrElse(fail("not added"))
    )(conway.emptyGovState)
    assertEquals(conway.obligationGovState(one).oblProposal.value, BigInt(100))
  }

  // ---- the orders of the vote keys

  private val hashes: Gen[Bytes] =
    Gen.listOfN(28, Gen.oneOf(0, 1, 127, 128, 255)).map(xs => Bytes(xs.map(_.toByte).toVector))

  private val flatCredentials: Gen[FlatCredential] =
    Gen
      .zip(Gen.oneOf(true, false), hashes)
      .map((key, h) => if key then FlatCredential.Key(h) else FlatCredential.Script(h))

  private def agrees[R <: KeyRole](role: String)(using Order[Credential[R]]): Unit =
    property(s"the order of the votes keyed by $role is the flat credential's") {
      forAll(flatCredentials, flatCredentials) { (a, b) =>
        val typed = (ok(Convert.credential[R](a)), ok(Convert.credential[R](b)))
        Integer.signum(Order[Credential[R]].compare(typed._1, typed._2)) ==
          Integer.signum(summon[Ordering[FlatCredential]].compare(a, b))
      }
    }

  agrees[HotCommittee]("committee member")
  agrees[DRepRole]("DRep")
  agrees[Staking]("stake credential (the enact state's withdrawals)")

  property("the order of the votes keyed by pool is the bytes'") {
    forAll(hashes, hashes) { (a, b) =>
      val typed = (ok(KeyHash.fromBytes[StakePool](a)), ok(KeyHash.fromBytes[StakePool](b)))
      Integer.signum(Order[KeyHash[StakePool]].compare(typed._1, typed._2)) ==
        Integer.signum(a.compareTo(b))
    }
  }

  // ---- Dijkstra's proposals through the flat record

  private def setItems(n: Node): Vector[Node] = n.value match
    case Value.Tag(_, inner) => setItems(inner)
    case Value.Arr(xs)       => xs
    case other               => fail(s"not a set: $other")

  test(
    "every proposal of the reference's Dijkstra GOV cases goes through the flat record unchanged"
  ) {
    val items = jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
      .filter(j => str(j, "kind") == "gov")
      .flatMap(_.hcursor.downField("proposals").as[String].toOption)
      .flatMap(p => setItems(node(hex(p))))
    val kinds = items.zipWithIndex.map { (n, i) =>
      val era = ok(DijkstraProposals.proposal(n))
      val flat = ok(DijkstraProposals.toFlat(id(i), era))
      // the flat proposal the flat path writes of the one read, which holds no parameter change
      assertEquals(Right(flat), DijkstraConway.proposal(era.read), s"#$i")
      val back = ok(DijkstraProposals.fromFlat(flat))
      assertEquals(back, era, s"#$i")
      assertEquals(back.read, era.read, s"#$i")
      flat.action.ordinal
    }
    assertEquals(items.size, 15)
    // every kind of action but a parameter change, which no GOV case of the corpus proposes
    assertEquals(SortedSet.from(kinds), SortedSet(1, 2, 3, 4, 5, 6))
  }

  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** The proposals of the testnet chain's transactions, each with its id. */
  private lazy val chainProposals: Vector[(GovActionId, ProposalProcedure[Dijkstra])] =
    val b = EraTxBody.dijkstra
    chainContextCases.flatMap { j =>
      val tx = ok(DijkstraTx.inBlock(node(hex(str(j, "bytes")))))
      b.proposalProcedures[TxLevel.Top](tx.body)
        .toVector
        .zipWithIndex
        .map((p, i) => GovActionId(b.id(tx.body), BigInt(i)) -> p)
    }

  test("the stored reader reads each of the chain's proposal items as the body's proposal") {
    val items = chainContextCases.flatMap { j =>
      val body = setItems(node(hex(str(j, "bytes"))))(0)
      body.value match
        case Value.Map(pairs) =>
          pairs
            .collectFirst { case (k, v) if k.value == Value.UInt(20) => setItems(v) }
            .toVector
            .flatten
        case other => fail(s"not a body: $other")
    }
    assertEquals(items.map(n => ok(DijkstraProposals.proposal(n))), chainProposals.map(_._2))
    assertEquals(items.size, 2)
  }

  test("the chain's proposals of Dijkstra's keys are refused by the flat record, with their keys") {
    assertEquals(
      chainProposals.map((gaid, p) =>
        DijkstraProposals.toFlat(gaid, p).left.map {
          case ProposalsWriteError.UnstorableUpdate(at, keys) => (at, keys.toSortedSet.toVector)
          case other                                          => fail(s"$other")
        }
      ),
      chainProposals
        .map(_._1)
        .zip(Vector(Vector(45, 46), Vector(47, 48)))
        .map((gaid, keys) => Left((gaid, keys.map(BigInt(_)))))
    )
    // in a governance state, among its enacted actions, the whole record is refused
    val (gaid, first) = chainProposals.headOption.getOrElse(fail("no proposal"))
    val state = ConwayGovState.empty[Dijkstra]
    val holding = state.copy(cgsDRepPulsingState =
      DRepPulsingState.DRComplete(
        PulsingSnapshot.empty[Dijkstra],
        RatifyState
          .empty[Dijkstra]
          .copy(rsEnacted =
            Vector(
              ConwayGov.mkGovActionState(gaid, first, EpochInterval.zero, EpochNo.fromUnsigned(0))
            )
          )
      )
    )
    assert(GovStateRecord.toStored[Dijkstra](state, GovStateBases.uniform(testnetStored)).isRight)
    assertEquals(
      GovStateRecord.toStored[Dijkstra](holding, GovStateBases.uniform(testnetStored)).left.map {
        case ProposalsWriteError.UnstorableUpdate(at, _) => at
        case other                                       => fail(s"$other")
      },
      Left(gaid)
    )
  }

  test("a stored vote of a pool key hash of 27 bytes is refused with its action") {
    val stored = StoredGovActionState(
      id(4),
      Map.empty,
      Map.empty,
      Map(bytes(4, 27) -> Vote.Yes),
      info,
      BigInt(0),
      BigInt(6)
    )
    assert(
      ConwayProposals.action(stored) match
        case Left(ProposalsReadError.StoredVoter(at, _)) => at == id(4)
        case _                                           => false
    )
  }
