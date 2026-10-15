// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  DRepRole,
  MirrorEnv,
  ProtVer,
  ShelleyBasedEra,
  SlotNo,
  Sorted,
  Word64,
  WithOrigin
}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.node.MirrorCorpus
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellStateCodecs,
  NodeOrders,
  Params,
  StateGen,
  Wire
}

import HaskellStateCodecs.given
import NodeOrders.given

/** The Haskell ledger-state codec against `LedgerEncoders`, which is byte-proven against the
  * reference node (the 763 node-to-client answers of the devnet soak). Where the two write the same
  * thing they must write the same bytes:
  *
  *   - every shared piece, on generated values: the parameters, the governance records, the pool
  *     parameters, the credentials and the rationals;
  *   - the reference's own answers (`fixtures/devnet/lsq/golden.json`) read and written again;
  *   - the pieces of the state of a Conway node of the eras capture, converted by the ledger-side
  *     mirror and written by the new codec, against the node's answer to the query that asks for
  *     that piece.
  */
class HaskellStateOverlapSuite extends ScalaCheckSuite:
  private val E = LedgerEncoders

  /** The version of a Conway state, which is where the old encoders write (protocol 9 and up). */
  given conwayVersion: ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  private def old(body: CborOut => Unit): String = CborOut.build(body).hex

  private def same[A](name: String, gen: Gen[A], codec: Codec[A])(
      write: (CborOut, A) => Unit
  ): Unit =
    property(s"$name: the new codec writes what LedgerEncoders writes") {
      forAll(gen) { a =>
        assertEquals(codec.encodeUnchecked(a).hex, old(write(_, a)))
        assertEquals(codec.check(a), None)
      }
    }

  // ---- generated values

  same("protocolParams (Conway)", StateGen.paramsOf[Conway], Params.conwayCodec)((w, p) =>
    E.protocolParams(w, p)
  )
  same("poolParams", StateGen.poolParams, poolParams)((w, p) => E.poolParams(w, p))
  same("committee", StateGen.committee, committee)((w, c) => E.committee(w, c))
  same("constitution", StateGen.constitution, constitution)((w, c) => E.constitution(w, c))
  same("anchor", StateGen.nodeAnchor, anchor)((w, a) => E.anchor(w, a))
  same("govActionId", StateGen.govActionId, govActionId)((w, i) => E.govActionId(w, i))
  same("govAction", StateGen.govAction, govAction)((w, a) => E.govAction(w, a))
  same("proposalProcedure", StateGen.proposal, proposal)((w, p) => E.proposalProcedure(w, p))
  same("govActionState", StateGen.govActionState, govActionState)((w, g) => E.govActionState(w, g))
  same("prevIds (GovRelation)", StateGen.prevIds, prevIds)((w, p) => E.prevIds(w, p))
  same("proposals", StateGen.proposals, proposals)((w, p) => E.proposals(w, p))
  same("enactState", StateGen.enactState, enactState)((w, e) => E.enactState(w, e))
  same("ratifyState", StateGen.ratifyState, ratifyState)((w, r) => E.ratifyState(w, r))
  same("paramUpdate", StateGen.paramUpdate, paramUpdate)((w, u) => E.paramUpdate(w, u))
  same("credential", StateGen.nodeCredential, nodeCredential)((w, c) => E.credential(w, c))
  same("drep", StateGen.nodeDRep, nodeDRep)((w, d) => E.drep(w, d))
  same("rewardAccount", StateGen.rewardAccount, rewardAccount)((w, a) => E.rewardAccount(w, a))
  same("vote", StateGen.nodeVote, vote)((w, v) => E.vote(w, v))
  same("nonce", Gen.oneOf(Nonce.Neutral, Nonce.Hash(Bytes(Vector.fill(32)(9.toByte)))), Wire.nonce)(
    (w, n) => E.nonce(w, n)
  )
  same("unit interval", StateGen.unit, Wire.unitInterval)((w, r) => E.rational(w, r))
  same("plain ratio (version 9)", StateGen.nonNegative, Wire.ratio)((w, r) => E.rational(w, r))

  property("a set of credentials: tag 258 and the variable-length framing, in Haskell order") {
    forAll(Gen.listOf(StateGen.nodeCredential).map(_.toSet)) { cs =>
      assertEquals(
        Wire.setOf(nodeCredential).encodeUnchecked(Sorted.set(cs)).hex,
        old(E.set(_, cs, E.credentialOrdering)(E.credential))
      )
    }
  }

  // ---- the reference's own answers

  private val golden = parser
    .parse(Files.readString(Path.of("fixtures/devnet/lsq/golden.json")))
    .fold(e => fail(e.message), identity)
    .hcursor

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e), identity)

  private def answerOf(label: String): Bytes =
    golden
      .downField("queries")
      .values
      .toVector
      .flatten
      .map(_.hcursor)
      .find(_.get[String]("label").toOption.contains(label))
      .flatMap(_.get[String]("result").toOption)
      .map(hex)
      .getOrElse(fail(s"no $label in the golden answers"))

  /** An `IfCurrent` answer is `[result]`: the result's bytes. */
  private def result(answer: Bytes): Bytes = Bytes(answer.value.tail)

  test("the reference's GetCurrentPParams is read and written again byte for byte") {
    val bytes = result(answerOf("GetCurrentPParams"))
    val read = Params.conwayCodec.decodeBytes(bytes).fold(e => fail(e.render), identity)
    assertEquals(Params.conwayCodec.encodeUnchecked(read), bytes)
    // and the old encoder agrees with both
    assertEquals(old(w => E.protocolParams(w, read)), bytes.hex)
  }

  test("the reference's GetConstitution is read and written again byte for byte") {
    val bytes = result(answerOf("GetConstitution"))
    val read = constitution.decodeBytes(bytes).fold(e => fail(e.render), identity)
    assertEquals(constitution.encodeUnchecked(read), bytes)
  }

  // ---- the state of a Conway node, against the node's answers

  private val mirrorEnv: MirrorEnv = NtcGolden.mirrorEnv

  private type Subject = (String, NodeState, Point, Option[BigInt])

  private def ask(subject: Subject, query: LedgerQuery): Bytes =
    val (_, state, point, blockNo) = subject
    QueryAnswers
      .answer(
        NtcGolden.queryEnv,
        QueryView(point, blockNo, state),
        Map.empty,
        NtcQuery.IfCurrent(xyz.kd5ujc.scarab.protocol.Era.Conway.index, query)
      )
      .map(result)
      .getOrElse(fail(s"$query is not answered"))

  private def mirrored(subject: Subject) =
    val (_, state, point, _) = subject
    val slot = point match
      case Point.Block(s, _) => s.value
      case Point.Origin      => BigInt(0)
    val at = WithOrigin.At(SlotNo.fromUnsigned(slot.toLong))
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    MirrorCorpus.ledgerMirror.to[Conway](at, state.ledger).run(mirrorEnv) match
      case Right(m)    => m.value.nes
      case Left(error) => fail(s"the mirror refuses the state: $error")

  test("the capture has Conway states") {
    assert(NtcGolden.conwayStates.nonEmpty)
  }

  /** The Conway states of the capture and the two built on the last one, see [[NtcGolden]]. */
  private def overConway(name: String)(piece: Subject => Unit): Unit =
    test(s"Conway states: $name") {
      NtcGolden.subjects.foreach(piece)
    }

  import LedgerQuery.*

  overConway("GetCurrentPParams is the current parameters") { subject =>
    val nes = mirrored(subject)
    assertEquals(
      Params.conwayCodec.encodeUnchecked(nes.es.ls.utxo.gov.curPParams).hex,
      ask(subject, GetCurrentPParams).hex
    )
  }

  overConway("GetGovState is the governance state") { subject =>
    val nes = mirrored(subject)
    assertEquals(
      conwayGovState.encodeUnchecked(nes.es.ls.utxo.gov).hex,
      ask(subject, GetGovState).hex,
      subject._1
    )
  }

  overConway("GetConstitution, GetProposals and GetRatifyState are parts of it") { subject =>
    val gov = mirrored(subject).es.ls.utxo.gov
    assertEquals(
      constitution.encodeUnchecked(gov.constitution).hex,
      ask(subject, GetConstitution).hex
    )
    assertEquals(
      ratifyState.encodeUnchecked(gov.drepPulsing.ratify).hex,
      ask(subject, GetRatifyState).hex,
      subject._1
    )
    assertEquals(
      Wire.seqOf(govActionState).encodeUnchecked(gov.drepPulsing.snapshot.proposals).hex,
      ask(subject, GetProposals(Set.empty)).hex
    )
  }

  overConway("GetPoolDistr2 is the pool distribution of the state") { subject =>
    val nes = mirrored(subject)
    assertEquals(poolDistr.encodeUnchecked(nes.pd).hex, ask(subject, GetPoolDistr2(None)).hex)
  }

  overConway("GetAccountState is the chain account state") { subject =>
    val nes = mirrored(subject)
    assertEquals(
      chainAccountState.encodeUnchecked(nes.es.chainAccount).hex,
      ask(subject, GetAccountState).hex
    )
  }

  overConway("GetDRepState is the map of the registered DReps") { subject =>
    val state = subject._2.ledger
    val dreps = mirrored(subject).es.ls.cert.vState.dreps
    val codec = Wire.mapOf(credential[DRepRole], drepState)
    // the query reports the expiries brought forward by the dormant epochs
    if state.dormantEpochs == 0 then
      assertEquals(codec.encodeUnchecked(dreps).hex, ask(subject, GetDRepState(Set.empty)).hex)
  }
