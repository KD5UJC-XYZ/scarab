// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.testByteStrings
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import cats.data.NonEmptyList
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node as CborNode, Rational, Value}
import xyz.kd5ujc.scarab.node.ntc.ConwayFailureCbor
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  CommitteeAuth,
  Certificate,
  Proposal,
  Proposals,
  VotingProcedure,
  VotingProcedures,
  Voter,
  Vote,
  Credential,
  DRep,
  DRepState,
  GovActionState,
  Ratify,
  RatifyState,
  state as S
}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Node}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Wire}
import xyz.kd5ujc.scarab.storage.haskell.state.NodeOrders.given
import xyz.kd5ujc.scarab.storage.haskell.state.Params
import xyz.kd5ujc.scarab.ledger.era.{EraGovRun, Mismatch as EraMismatch, PParams}
import xyz.kd5ujc.scarab.ledger.era.Relation.RelEQ
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGovEvent,
  ConwayGovPredFailure,
  GovEnv as EraGovEnv,
  GovSignal as EraGovSignal
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ScriptHash}

import HaskellStateCodecs.given

/** Lawful nonempty GOV inputs and independently observed successful RATIFY integrations. */
class UpstreamGovernanceFunctionalSuite extends FunSuite:
  private val base = Path.of("node/src/test/resources/upstream/conway-governance-functional")
  private given S.ProtVer = S.ProtVer(S.Word64.fromUnsigned(9), S.Word64.zero)

  private def node(id: String, name: String): Node =
    Cbor
      .decode(
        Bytes.fromArray(Files.readAllBytes(base.resolve(s"cases/$id/$name.cbor"))),
        Cbor.Limits(allowFloats = true)
      )
      .fold(e => fail(e.message), identity)

  private def read[A](n: Node, codec: Codec[A]): A =
    codec.decode(n).fold(e => fail(e.render), identity)

  private def environment(id: String): Ratify.Env =
    val fields = node(id, "environment").value match
      case Value.Arr(xs) if xs.size == 8 => xs
      case other => fail(s"expected all eight native RATIFY environment fields, got $other")
    // Instant stake is retained and decoded, though the voting predicates read the already
    // computed DRep/pool distributions. These are the same typed conversions as ConwayPulser.
    val _ = read(fields(0), summon[Codec[S.ConwayInstantStake]])
    val pools = read(fields(1), summon[Codec[S.PoolDistr]])
    val dreps = read(fields(2), Wire.sortedMapOf(summon[Codec[DRep]], summon[Codec[S.CompactCoin]]))
    val drepState =
      read(fields(3), Wire.sortedMapOf(summon[Codec[Credential]], summon[Codec[DRepState]]))
    val epoch = read(fields(4), summon[Codec[S.EpochNo]])
    val committee = read(fields(5), summon[Codec[S.CommitteeState]])
    val accounts = read(fields(6), summon[Codec[S.ConwayAccounts]])
    val stakePools = read(
      fields(7),
      Wire.mapOf(summon[Codec[S.KeyHash[S.StakePool]]], summon[Codec[S.StakePoolState]])
    )
    Ratify.Env(
      dreps.view.mapValues(_.toBigInt).toMap,
      drepState,
      pools.distribution.iterator
        .map((key, value) => key.bytes -> value.totalPoolStake.toBigInt)
        .toMap,
      pools.totalActiveStake.value.value,
      epoch.toBigInt,
      S.FlatView.stakeMap(committee.creds) {
        case S.CommitteeAuthorization.CommitteeHotCredential(hot) =>
          CommitteeAuth.Hot(S.FlatView.credBack(hot))
        case S.CommitteeAuthorization.CommitteeMemberResigned(anchor) =>
          CommitteeAuth.Resigned(anchor)
      },
      S.FlatView.stakeMap(accounts.states)(S.FlatView.conwayAccount),
      S.FlatView.pools(0, stakePools)
    )

  private def signal(id: String): Vector[GovActionState] =
    read(node(id, "signal"), Wire.seqOf(summon[Codec[GovActionState]]))

  private def state(id: String, name: String): RatifyState =
    read(node(id, name), summon[Codec[RatifyState]])

  private val strictCertificateRefusals = Set(510102, 510103, 510109, 510113, 510114)

  /** The parameters of a case's GOV environment, as the node stores them. */
  private def environmentParams(id: String) = node(id, "environment").value match
    case Value.Arr(xs) if xs.size == 6 => read(xs(2), Params.conwayCodec)
    case other => fail(s"expected all six GOV environment fields, got $other")

  /** The certificate state of an original environment with its pools' VRF key hashes left out
    * (`pState.vrfKeyHashes`): five seeds hold a zero registration count there, which the node's
    * decoder refuses, and GOV reads only the pools' registrations, the accounts, the DReps and the
    * committee's authorisations (`Conway/Rules/Gov.hs:467-474`, cardano-ledger f649f975).
    */
  private def certStateForGov(id: String, cert: Node): S.ConwayCertState = cert.value match
    case Value.Arr(Vector(voting, pools, delegation)) =>
      pools.value match
        case Value.Arr(Vector(_, stakePools, future, retiring)) =>
          val noVrfs = CborNode(Value.Map(Vector.empty), Bytes.empty)
          val withoutVrfs =
            CborNode(Value.Arr(Vector(noVrfs, stakePools, future, retiring)), Bytes.empty)
          read(
            CborNode(Value.Arr(Vector(voting, withoutVrfs, delegation)), Bytes.empty),
            summon[Codec[S.ConwayCertState]]
          )
        case other => fail(s"$id: expected four pool-state fields, got $other")
    case other => fail(s"$id: expected three certificate-state fields, got $other")

  /** The era-typed GOV's environment of a case: the original environment's fields, its parameters
    * Conway's; its certificate state read whole, or without the pools' VRF key hashes
    * ([[certStateForGov]]).
    */
  private def eraEnvironment(id: String, withoutVrfs: Boolean = false): EraGovEnv[Conway] =
    val fields = node(id, "environment").value match
      case Value.Arr(xs) if xs.size == 6 => xs
      case other => fail(s"expected all six GOV environment fields, got $other")
    EraGovEnv[Conway](
      S.Hash32
        .fromBytes(read(fields(0), Codec.bytesSized(32, 32)))
        .fold(e => fail(s"$id id: $e"), identity),
      read(fields(1), summon[Codec[S.EpochNo]]),
      PParams[Conway](
        ConwayPParams
          .fromStored(read(fields(2), Params.conwayCodec))
          .fold(e => fail(s"$id parameters: $e"), identity)
      ),
      read(fields(3), Wire.strictMaybe(Codec.bytesSized(28, 28))).map(b =>
        ScriptHash.fromBytes(b).fold(e => fail(s"$id guardrails: $e"), identity)
      ),
      if withoutVrfs then certStateForGov(id, fields(4))
      else read(fields(4), summon[Codec[S.ConwayCertState]]),
      read(fields(5), Wire.strictMaybe(summon[Codec[Committee]]))
    )

  /** The era-typed GOV's signal of a case: the votes with their anchors, the proposals and the
    * certificates as the flat decoders read them, made Conway's.
    */
  private def eraSignal(id: String): EraGovSignal[Conway] = node(id, "signal").value match
    case Value.Arr(Vector(votes, proposals, certificates)) =>
      val voting = VotingProcedures
        .decode(
          votes,
          xyz.kd5ujc.scarab.ledger.DecoderVersion.of(xyz.kd5ujc.scarab.protocol.Era.Conway)
        )
        .fold(e => fail(e.message), identity)
      val proposalNodes = proposals.value match
        case Value.Tag(258, inner) => inner
        case _                     => proposals
      val certs = certificates.value match
        case Value.Arr(xs) =>
          xs.map(n => Certificate.decode(n, Era.Conway).fold(e => fail(e.message), identity))
        case other => fail(s"expected original GOV certificates, got $other")
      EraGovRun
        .signalFromFlat(voting, read(proposalNodes, Wire.seqOf(summon[Codec[Proposal]])), certs)
        .fold(e => fail(s"$id era signal: $e"), identity)
    case other => fail(s"expected complete three-field GOV signal, got $other")

  /** The era-typed GOV, every failure accumulated: its failures in the reference's order, or the
    * proposals after as the node records them.
    */
  private def eraRule(
      env: EraGovEnv[Conway],
      state: Proposals,
      signal: EraGovSignal[Conway]
  ): Either[Vector[ConwayGovPredFailure[Conway]], Proposals] =
    val before = EraGovRun.proposals(state).fold(e => fail(s"stored proposals: $e"), identity)
    EraGovRun.run(env, signal, S.Network.Testnet, before) match
      case Left(defect)             => fail(s"era GOV defect: $defect")
      case Right(Left(fs))          => Left(fs.toChain.toVector)
      case Right(Right((after, _))) => Right(EraGovRun.toStored(after))

  /** A GOV failure as the reference writes it (`ConwayGovPredFailure`'s `EncCBOR`,
    * `Conway/Rules/Gov.hs:258-299`, cardano-ledger f649f975).
    */
  private def encoded(f: ConwayGovPredFailure[Conway]): Bytes =
    ConwayFailureCbor.gov(f).fold(e => fail(s"no Conway encoding of $f: $e"), identity)

  (510101 to 510115).foreach { seed =>
    test(s"the era-typed GOV gives the reference's proposals at seed $seed") {
      val id = s"gov-sized5-$seed"
      val signal = eraSignal(id)
      assert(
        signal.gsVotingProcedures.unVotingProcedures.nonEmpty ||
          signal.gsProposalProcedures.toVector.nonEmpty || signal.gsCertificates.nonEmpty
      )
      val before = read(node(id, "state"), summon[Codec[Proposals]])
      val expected = read(node(id, "implementation-state"), summon[Codec[Proposals]])
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
      val strict = strictCertificateRefusals(seed)
      assertEquals(
        eraRule(eraEnvironment(id, withoutVrfs = strict), before, signal),
        Right(expected),
        "complete proposal roots, ordered actions, votes and expirations"
      )
    }
  }

  test("the era-typed GOV refuses the changed required deposit with the reference's failure") {
    val id = "gov-invalid-deposit"
    val env = eraEnvironment(id)
    val signal = eraSignal(id)
    val proposal =
      signal.gsProposalProcedures.toVector.headOption
        .getOrElse(fail("the case requires a proposal"))
    val before = read(node(id, "state"), summon[Codec[Proposals]])
    val required = environmentParams(id).govActionDeposit
    // the deposit supplied and the parameters' (`Conway/Rules/Gov.hs:520-530`, cardano-ledger
    // f649f975)
    val failure = ConwayGovPredFailure.ProposalDepositIncorrect[Conway](
      EraMismatch[RelEQ, S.Coin](proposal.pProcDeposit, S.Coin(required))
    )
    assertEquals(eraRule(env, before, signal), Left(Vector(failure)))
    assertEquals(
      Files.readString(base.resolve(s"cases/$id/result.txt")),
      "implementation-rejected\nagda-rejected\n"
    )
    def coin(n: Node): BigInt = n.value match
      case Value.UInt(value) => value
      case other             => fail(s"expected native failure coin, got $other")
    node(id, "native-failures").value match
      case Value.Arr(Vector(nativeFailure)) =>
        nativeFailure.value match
          case Value.Arr(Vector(tag, supplied, expected)) =>
            assertEquals(tag.value, Value.UInt(4), "native GOV.hs:241 ProposalDepositIncorrect tag")
            assertEquals(
              (coin(supplied), coin(expected)),
              (proposal.pProcDeposit.value, required),
              "the native failure's deposits"
            )
          case other => fail(s"expected native GOV deposit mismatch, got $other")
        assertEquals(encoded(failure).hex, nativeFailure.original.hex, "the reference's bytes")
      case other => fail(s"expected exactly one native predicate failure, got $other")
  }

  test("the era-typed GOV keeps named committee voters with empty vote maps known") {
    val id = "gov-sized5-510104"
    val before = read(node(id, "state"), summon[Codec[Proposals]])
    val env = eraEnvironment(id)
    val signal = eraSignal(id)
    val votes = signal.gsVotingProcedures.unVotingProcedures
    assert(votes.nonEmpty)
    assert(votes.values.forall(_.isEmpty))
    assert(votes.keys.exists {
      case Voter.CommitteeHot(_) => true
      case _                     => false
    })
    assertEquals(
      eraRule(env, before, signal),
      Right(read(node(id, "implementation-state"), summon[Codec[Proposals]]))
    )
    val authorisations = S.ConwayCertState.vState
      .andThen(S.VState.committeeState)
      .andThen(S.CommitteeState.creds)
    val unknown = NonEmptyList
      .fromList(
        votes.keys.toList
          .collect { case voter @ Voter.CommitteeHot(_) => voter }
          .sorted(using Voter.order.toOrdering)
      )
      .getOrElse(fail("the case has a committee voter"))
    // the voters GOV does not know, in `Ord Voter` order (`Conway/Rules/Gov.hs:597-604`,
    // cardano-ledger f649f975)
    assertEquals(
      eraRule(
        env.copy(geCertState = authorisations.modify(_.empty)(env.geCertState)),
        before,
        signal
      ),
      Left(Vector(ConwayGovPredFailure.VotersDoNotExist[Conway](unknown)))
    )
  }

  test("the era-typed GOV casts the registered DRep's vote, and tells the vote it replaces") {
    val id = "gov-drep-cast-vote"
    val before = read(node(id, "state"), summon[Codec[Proposals]])
    val expected = read(node(id, "implementation-state"), summon[Codec[Proposals]])
    val env = eraEnvironment(id)
    val signal = eraSignal(id)
    assert(signal.gsProposalProcedures.toVector.isEmpty)
    assert(signal.gsCertificates.isEmpty)
    signal.gsVotingProcedures.unVotingProcedures.toVector.map((v, ps) => v -> ps.toVector) match
      case Vector((Voter.DRepVoter(credential), Vector((actionId, procedure))))
          if procedure.vote == Vote.Yes =>
        assertEquals(before.byId.get(actionId).flatMap(_.drepVotes.get(credential)), None)
        assertEquals(
          expected.byId.get(actionId).flatMap(_.drepVotes.get(credential)),
          Some(Vote.Yes)
        )
      case other => fail(s"expected the original actual DRep yes vote, got $other")
    assertEquals(read(node(id, "observed-ledger-proposals"), summon[Codec[Proposals]]), expected)
    assertEquals(
      Files.readString(base.resolve(s"cases/$id/result.txt")),
      "implementation-accepted\nagda-accepted\noutputs-equal\nledger-proposals-equal\n"
    )
    assertEquals(eraRule(env, before, signal), Right(expected))
    // the same vote again, on the proposals after: it replaces the one cast, which the event names
    val after = EraGovRun.proposals(expected).fold(e => fail(s"stored proposals: $e"), identity)
    EraGovRun.run(env, signal, S.Network.Testnet, after) match
      case Right(Right((again, events))) =>
        assertEquals(EraGovRun.toStored(again), expected)
        val voted = signal.gsVotingProcedures.unVotingProcedures.toVector.flatMap((voter, votes) =>
          votes.keys.map(voter -> _)
        )
        assertEquals(
          events.toList.collect { case ConwayGovEvent.GovRemovedVotes(_, replaced, _) =>
            replaced.toVector
          },
          List(voted)
        )
      case other => fail(s"the vote cast again: $other")
  }

  test("the era-typed GOV refuses registered DRep and committee voters once they are not") {
    Vector("gov-drep-cast-vote", "gov-sized5-510104").foreach { id =>
      val env = eraEnvironment(id)
      val signal = eraSignal(id)
      val before = read(node(id, "state"), summon[Codec[Proposals]])
      val dreps = S.ConwayCertState.vState.andThen(S.VState.dreps)
      val authorisations = S.ConwayCertState.vState
        .andThen(S.VState.committeeState)
        .andThen(S.CommitteeState.creds)
      val absent = env.copy(geCertState =
        dreps.modify(_.empty).andThen(authorisations.modify(_.empty))(env.geCertState)
      )
      val unknown = NonEmptyList
        .fromList(
          signal.gsVotingProcedures.unVotingProcedures.keys.toList
            .collect {
              case voter @ Voter.DRepVoter(_)    => voter
              case voter @ Voter.CommitteeHot(_) => voter
            }
            // the reference's `Ord Voter`, whatever order the signal's map keeps
            .sorted(using Voter.order.toOrdering)
        )
        .getOrElse(fail("negative control must name a removed registration"))
      // `Conway/Rules/Gov.hs:597-604` (cardano-ledger f649f975)
      assertEquals(
        eraRule(absent, before, signal),
        Left(Vector(ConwayGovPredFailure.VotersDoNotExist[Conway](unknown))),
        id
      )
    }
  }

  test("GOV reports the voters that do not exist once each, in the reference's Ord Voter order") {
    // Conway/Rules/Gov.hs:597-604 (f649f975) builds the failure from Set.fromList, so a
    // transaction's map order and a repeated voter do not reach the failure.
    val id = "gov-drep-cast-vote"
    val env = eraEnvironment(id)
    val before = read(node(id, "state"), summon[Codec[Proposals]])
    val actionId = eraSignal(id).gsVotingProcedures.unVotingProcedures.values
      .flatMap(_.keys)
      .headOption
      .getOrElse(fail("the case votes on an action"))
    def pool(byte: Int): Voter = Voter.Pool(Bytes.fromArray(Array.fill(28)(byte.toByte)))
    // 0x80 is a high byte: a signed comparison would put it first.
    val (low, middle, high) = (pool(0x01), pool(0x7f), pool(0x80))
    val ballot = Vector((actionId, VotingProcedure(Vote.Yes, None)))
    val signal = EraGovRun
      .signalFromFlat(Vector(high, middle, low, high).map(_ -> ballot), Vector.empty, Vector.empty)
      .fold(e => fail(s"era signal: $e"), identity)
    val pools = S.ConwayCertState.pState.andThen(S.PState.stakePools)
    val absent = env.copy(geCertState = pools.modify(_.empty)(env.geCertState))
    val failure = ConwayGovPredFailure.VotersDoNotExist[Conway](NonEmptyList.of(low, middle, high))
    assertEquals(eraRule(absent, before, signal), Left(Vector(failure)))
    // [14 VotersDoNotExist, [voters]] as the plain list of [4 Pool, hash] the encoder writes,
    // in the order of the failure.
    def entry(v: Voter): String = v match
      case Voter.Pool(h) => "82" + "04" + "581c" + h.hex
      case other         => fail(s"expected a pool voter, got $other")
    assertEquals(encoded(failure).hex, "820e83" + Vector(low, middle, high).map(entry).mkString)
  }

  strictCertificateRefusals.toVector.sorted.foreach { seed =>
    test(s"strict certificate decoding refuses original zero registration count at seed $seed") {
      val id = s"gov-sized5-$seed"
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
      val cert = node(id, "environment").value match
        case Value.Arr(fields) if fields.size == 6 => fields(4)
        case other => fail(s"expected complete GOV environment, got $other")
      summon[Codec[S.ConwayCertState]].decode(cert) match
        case Left(error) =>
          assertEquals(
            error.kind,
            DecodeError.Kind.Domain(S.VocabError.Zero(S.VocabError.Kind.NonZero))
          )
          assertEquals(error.path.take(2), List("pState", "vrfKeyHashes"))
        case Right(_) => fail("full certificate decoding must retain its NonZero invariant")
    }
  }

  Vector(
    "ratify-native-positive",
    "ratify-both-positive",
    "ratify-forward",
    "ratify-reverse",
    "ratify-missing-votes"
  ).foreach { id =>
    test(s"native full RATIFY outcome: $id") {
      val before = state(id, "state")
      val expected = state(id, "implementation-state")
      val actions = signal(id)
      val env = environment(id)
      val approvals = read(node(id, "approvals"), Wire.seqOf(Codec.bool))
      assert(
        !before.delayed,
        "successful controls must reach voting rather than the delayed fast path"
      )
      if id != "ratify-native-positive" then
        assertEquals(before.enact.params.drepVotingThresholds.motionNoConfidence, Rational(1, 2))
        assertEquals(before.enact.params.poolVotingThresholds.motionNoConfidence, Rational(1, 2))
      assertEquals(
        actions.flatMap(action =>
          Vector(
            Ratify.drepAccepted(env, before.enact, action),
            Ratify.spoAccepted(env, before.enact, action)
          )
        ),
        approvals
      )
      assertEquals(approvals, Vector.fill(actions.size * 2)(id != "ratify-missing-votes"))
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\noutputs-equal\n"
      )
      assertEquals(Ratify.transition(env, before, actions), expected)
      if id == "ratify-missing-votes" then assertEquals(expected.enacted, before.enacted)
      else
        assertEquals(expected.enacted.size, before.enacted.size + 1)
        assertEquals(expected.enacted.lastOption, actions.headOption)
    }
  }

  test("competing actions expose native supplied-order behavior") {
    val forward = signal("ratify-forward")
    val reverse = signal("ratify-reverse")
    assertEquals(
      reverse,
      forward.reverse,
      "same lawful actions, independently run in opposite orders"
    )
    assertEquals(state("ratify-forward", "state"), state("ratify-reverse", "state"))
    assertEquals(environment("ratify-forward"), environment("ratify-reverse"))
    assertNotEquals(
      state("ratify-forward", "implementation-state"),
      state("ratify-reverse", "implementation-state")
    )
  }

  test("every original input and observed outcome remains pinned") {
    val manifest =
      parse(Files.readString(base.resolve("manifest.json"))).fold(e => fail(e.message), identity)
    val rows = manifest.hcursor.get[Vector[Json]]("cases").fold(e => fail(e.message), identity)
    val expectedIds = (510101 to 510115).map(seed => s"gov-sized5-$seed").toSet ++ Set(
      "gov-invalid-deposit",
      "gov-drep-cast-vote",
      "ratify-native-positive",
      "ratify-both-positive",
      "ratify-forward",
      "ratify-reverse",
      "ratify-missing-votes"
    )
    assertEquals(rows.size, expectedIds.size)
    assertEquals(rows.map(_.hcursor.get[String]("id").toOption).toSet, expectedIds.map(Option(_)))
    rows.foreach { row =>
      val cursor = row.hcursor
      val id = cursor.get[String]("id").fold(e => fail(e.message), identity)
      assertEquals(cursor.get[Int]("upstreamExitCode").toOption, Some(0))
      val files = cursor.get[Map[String, Json]]("files").fold(e => fail(e.message), identity)
      val common =
        Set("context.cbor", "environment.cbor", "state.cbor", "signal.cbor", "result.txt")
      val expectedFiles =
        if id == "gov-invalid-deposit" then common + "native-failures.cbor"
        else if id == "gov-drep-cast-vote" then
          common ++ Set("implementation-state.cbor", "observed-ledger-proposals.cbor")
        else if id.startsWith("ratify-") then
          common ++ Set("implementation-state.cbor", "approvals.cbor")
        else common + "implementation-state.cbor"
      assertEquals(files.keySet, expectedFiles)
      files.foreach { (name, descriptor) =>
        val bytes = Files.readAllBytes(base.resolve(s"cases/$id/$name"))
        val sha =
          MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
        assertEquals(descriptor.hcursor.get[String]("sha256").toOption, Some(sha), s"$id/$name")
        assertEquals(
          descriptor.hcursor.get[Int]("bytes").toOption,
          Some(bytes.length),
          s"$id/$name"
        )
      }
    }
  }
