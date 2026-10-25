// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.Instant
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value}
import xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  CommitteeAuth,
  Certificate,
  DecoderVersion,
  GovFailure,
  LedgerFailure,
  Mismatch,
  PredicateFailure,
  EpochGeometry,
  LedgerGlobals,
  Proposal,
  Proposals,
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
import xyz.kd5ujc.scarab.ledger.rules.{
  Globals,
  Gov,
  GovEnv,
  GovSignal,
  GovCertificateView,
  GovViewEnv,
  GovKernel,
  Sts
}

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

  private val globals = Globals(
    LedgerGlobals(BigInt(0), BigInt(1), Rational.zero, network = 0),
    EpochGeometry(Instant.EPOCH, 1000L, 1L, 1, BigDecimal("0.1"))
  )

  private def govEnvironment(id: String): GovEnv =
    val fields = node(id, "environment").value match
      case Value.Arr(xs) if xs.size == 6 => xs
      case other => fail(s"expected all six GOV environment fields, got $other")
    GovEnv(
      read(fields(0), Codec.bytesSized(32, 32)),
      read(fields(1), summon[Codec[S.EpochNo]]).toBigInt,
      read(fields(2), Params.conwayCodec),
      read(fields(3), Wire.strictMaybe(Codec.bytesSized(28, 28))),
      read(fields(4), summon[Codec[S.ConwayCertState]]),
      read(fields(5), Wire.strictMaybe(summon[Codec[Committee]]))
    )

  private def govViewEnvironment(id: String): GovViewEnv =
    def fields(n: Node, size: Int): Vector[Node] = n.value match
      case Value.Arr(xs) if xs.size == size => xs
      case other => fail(s"expected $size original environment fields, got $other")
    val env = fields(node(id, "environment"), 6)
    val cert = fields(env(4), 3)
    val voting = fields(cert(0), 3)
    val pools = fields(cert(1), 4)
    val delegation = fields(cert(2), 4)
    // GOV observes only these memberships and authorisations, not registration counts,
    // account balances or delegation state. Original environment bytes remain immutable.
    val accounts = read(
      delegation(0),
      Wire.mapOf(summon[Codec[S.Credential[S.Staking]]], HaskellStateCodecs.rawItem)
    )
    val dreps = read(
      voting(0),
      Wire.mapOf(summon[Codec[S.Credential[S.DRepRole]]], HaskellStateCodecs.rawItem)
    )
    val registeredPools =
      read(pools(1), Wire.mapOf(summon[Codec[S.KeyHash[S.StakePool]]], HaskellStateCodecs.rawItem))
    val committee = read(voting(1), summon[Codec[S.CommitteeState]])
    GovViewEnv(
      read(env(0), Codec.bytesSized(32, 32)),
      read(env(1), summon[Codec[S.EpochNo]]).toBigInt,
      read(env(2), Params.conwayCodec),
      read(env(3), Wire.strictMaybe(Codec.bytesSized(28, 28))),
      GovCertificateView(
        accounts.contains,
        dreps.contains,
        registeredPools.contains,
        committee.creds
      ),
      read(env(5), Wire.strictMaybe(summon[Codec[Committee]]))
    )

  private def govSignal(id: String): GovSignal = node(id, "signal").value match
    case Value.Arr(Vector(votes, proposals, certificates)) =>
      val voting = VotingProcedures.decode(votes).fold(e => fail(e.message), identity)
      val proposalNodes = proposals.value match
        case Value.Tag(258, inner) => inner
        case _                     => proposals
      val props = read(proposalNodes, Wire.seqOf(summon[Codec[Proposal]]))
      val certs = certificates.value match
        case Value.Arr(xs) =>
          xs.map(n =>
            Certificate
              .decode(n, DecoderVersion.of(Era.Conway))
              .fold(e => fail(e.message), identity)
          )
        case other => fail(s"expected original GOV certificates, got $other")
      GovSignal(
        voting.map((voter, entries) => voter -> entries.map((id, p) => id -> p.vote)),
        props,
        certs
      )
    case other => fail(s"expected complete three-field GOV signal, got $other")

  private val strictCertificateRefusals = Set(510102, 510103, 510109, 510113, 510114)

  (510101 to 510115).foreach { seed =>
    test(s"lawful nonempty GOV full outcome at seed $seed") {
      val id = s"gov-sized5-$seed"
      val inputs = govSignal(id)
      assert(inputs.votes.nonEmpty || inputs.proposals.nonEmpty || inputs.certificates.nonEmpty)
      val before = read(node(id, "state"), summon[Codec[Proposals]])
      val expected = read(node(id, "implementation-state"), summon[Codec[Proposals]])
      assertEquals(
        Files.readString(base.resolve(s"cases/$id/result.txt")),
        "implementation-accepted\nagda-accepted\n"
      )
      val actual = Sts
        .apply(GovKernel)(globals, govViewEnvironment(id), before, inputs)
        .fold(e => fail(s"Scarab refused upstream-accepted nonempty GOV: $e"), identity)
      if !strictCertificateRefusals(seed) then
        assertEquals(
          Sts.apply(Gov)(globals, govEnvironment(id), before, inputs),
          Right(actual),
          "full validated certificate facade and minimal production kernel agree"
        )
      assertEquals(
        actual,
        expected,
        "complete proposal roots, ordered actions, votes and expirations"
      )

    }
  }

  test("named authorised committee voters remain known with empty inner vote maps") {
    val id = "gov-sized5-510104"
    val inputs = govSignal(id)
    assert(inputs.votes.nonEmpty)
    assert(inputs.votes.forall(_._2.isEmpty))
    assert(inputs.votes.exists {
      case (Voter.CommitteeHot(_), _) => true
      case _                          => false
    })
    assertEquals(
      Sts.apply(Gov)(
        globals,
        govEnvironment(id),
        read(node(id, "state"), summon[Codec[Proposals]]),
        inputs
      ),
      Right(read(node(id, "implementation-state"), summon[Codec[Proposals]]))
    )
    val env = govEnvironment(id)
    val authorisations = S.ConwayCertState.vState
      .andThen(S.VState.committeeState)
      .andThen(S.CommitteeState.creds)
    val unregistered = env.copy(cert = authorisations.modify(_.empty)(env.cert))
    val unknown = inputs.votes.collect { case (voter @ Voter.CommitteeHot(_), _) => voter }
    assertEquals(
      Sts.apply(Gov)(
        globals,
        unregistered,
        read(node(id, "state"), summon[Codec[Proposals]]),
        inputs
      ),
      Left(
        LedgerFailure.Rejected(env.id, PredicateFailure.Gov(GovFailure.VotersDoNotExist(unknown)))
      )
    )
  }

  test("lawful GOV applies the actual registered DRep yes vote") {
    val id = "gov-drep-cast-vote"
    val before = read(node(id, "state"), summon[Codec[Proposals]])
    val expected = read(node(id, "implementation-state"), summon[Codec[Proposals]])
    val inputs = govSignal(id)
    assert(inputs.proposals.isEmpty)
    assert(inputs.certificates.isEmpty)
    inputs.votes match
      case Vector((Voter.DRepVoter(credential), Vector((actionId, Vote.Yes)))) =>
        assertEquals(before.byId.get(actionId).flatMap(_.drepVotes.get(credential)), None)
        assertEquals(
          expected.byId.get(actionId).flatMap(_.drepVotes.get(credential)),
          Some(Vote.Yes)
        )
      case other => fail(s"expected the original actual DRep yes vote, got $other")
    assertEquals(read(node(id, "observed-ledger-proposals"), summon[Codec[Proposals]]), expected)
    assertEquals(
      Sts.apply(GovKernel)(globals, govViewEnvironment(id), before, inputs),
      Right(expected)
    )
    assertEquals(
      Files.readString(base.resolve(s"cases/$id/result.txt")),
      "implementation-accepted\nagda-accepted\noutputs-equal\nledger-proposals-equal\n"
    )
  }

  test("minimal GOV view retains registered DRep and committee voter refusals") {
    Vector("gov-drep-cast-vote", "gov-sized5-510104").foreach { id =>
      val env = govViewEnvironment(id)
      val inputs = govSignal(id)
      val before = read(node(id, "state"), summon[Codec[Proposals]])
      val absent = env.copy(cert =
        env.cert.copy(
          drepRegistered = _ => false,
          committee = env.cert.committee.empty
        )
      )
      val unknown = inputs.votes.collect {
        case (voter @ Voter.DRepVoter(_), _)    => voter
        case (voter @ Voter.CommitteeHot(_), _) => voter
      }
      assert(unknown.nonEmpty, "negative control must name a removed registration")
      assertEquals(
        Sts.apply(GovKernel)(globals, absent, before, inputs),
        Left(
          LedgerFailure.Rejected(env.id, PredicateFailure.Gov(GovFailure.VotersDoNotExist(unknown)))
        )
      )
    }
  }

  test("GOV reports the voters that do not exist once each, in the reference's Ord Voter order") {
    // Conway/Rules/Gov.hs:597-604 (f649f975) builds the failure from Set.fromList, so a
    // transaction's map order and a repeated voter do not reach the failure.
    val id = "gov-drep-cast-vote"
    val env = govViewEnvironment(id)
    val before = read(node(id, "state"), summon[Codec[Proposals]])
    val actionId = govSignal(id).votes.flatMap(_._2).map(_._1).head
    def pool(byte: Int): Voter = Voter.Pool(Bytes.fromArray(Array.fill(28)(byte.toByte)))
    // 0x80 is a high byte: a signed comparison would put it first.
    val (low, middle, high) = (pool(0x01), pool(0x7f), pool(0x80))
    val ballot = Vector((actionId, Vote.Yes))
    val inputs = GovSignal(
      Vector(high, middle, low, high).map(_ -> ballot),
      Vector.empty,
      Vector.empty
    )
    val absent = env.copy(cert = env.cert.copy(poolRegistered = _ => false))
    val expected = Vector(low, middle, high)
    val failure = PredicateFailure.Gov(GovFailure.VotersDoNotExist(expected))
    assertEquals(
      Sts.apply(GovKernel)(globals, absent, before, inputs),
      Left(LedgerFailure.Rejected(env.id, failure))
    )
    // [14 VotersDoNotExist, [voters]] as the plain list of [4 Pool, hash] the encoder writes,
    // in the order of the failure.
    def entry(v: Voter): String = v match
      case Voter.Pool(h) => "82" + "04" + "581c" + h.hex
      case other         => fail(s"expected a pool voter, got $other")
    assertEquals(
      PredicateFailureCbor.applyTxError(Vector(failure)).hex.takeRight(6 + 3 * 64),
      "820e83" + expected.map(entry).mkString
    )
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

  test("native and formal refuse the changed required deposit, retaining the typed Scarab cause") {
    val id = "gov-invalid-deposit"
    val env = govEnvironment(id)
    val inputs = govSignal(id)
    val proposal =
      inputs.proposals.headOption.getOrElse(fail("refusal control requires a proposal"))
    def coin(n: Node): BigInt = n.value match
      case Value.UInt(value) => value
      case other             => fail(s"expected native failure coin, got $other")
    val nativeFailure = node(id, "native-failures").value match
      case Value.Arr(Vector(failure)) =>
        failure.value match
          case Value.Arr(Vector(tag, supplied, required)) =>
            assertEquals(tag.value, Value.UInt(4), "native GOV.hs:241 ProposalDepositIncorrect tag")
            GovFailure.ProposalDepositIncorrect(Mismatch(coin(supplied), coin(required)))
          case other => fail(s"expected native GOV deposit mismatch, got $other")
      case other => fail(s"expected exactly one native predicate failure, got $other")
    assertEquals(
      nativeFailure,
      GovFailure.ProposalDepositIncorrect(Mismatch(proposal.deposit, env.pp.govActionDeposit))
    )
    assertEquals(
      Files.readString(base.resolve(s"cases/$id/result.txt")),
      "implementation-rejected\nagda-rejected\n"
    )
    assertEquals(
      Sts.apply(GovKernel)(
        globals,
        govViewEnvironment(id),
        read(node(id, "state"), summon[Codec[Proposals]]),
        inputs
      ),
      Sts.apply(Gov)(globals, env, read(node(id, "state"), summon[Codec[Proposals]]), inputs)
    )
    assertEquals(
      Sts.apply(Gov)(globals, env, read(node(id, "state"), summon[Codec[Proposals]]), inputs),
      Left(
        LedgerFailure.Rejected(
          env.id,
          PredicateFailure.Gov(nativeFailure)
        )
      )
    )
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
    assertEquals(rows.map(_.hcursor.get[String]("id").toOption).toSet, expectedIds.map(Some(_)))
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
