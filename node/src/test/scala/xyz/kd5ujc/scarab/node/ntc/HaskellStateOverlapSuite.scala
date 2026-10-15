// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.{Block, Nonce}
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
import xyz.kd5ujc.scarab.node.{MirrorCorpus, NodeRules, NodeSetup}
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
        assertEquals(codec.encodeBytes(a).hex, old(write(_, a)))
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
        Wire.setOf(nodeCredential).encodeBytes(Sorted.set(cs)).hex,
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
    assertEquals(Params.conwayCodec.encodeBytes(read), bytes)
    // and the old encoder agrees with both
    assertEquals(old(w => E.protocolParams(w, read)), bytes.hex)
  }

  test("the reference's GetConstitution is read and written again byte for byte") {
    val bytes = result(answerOf("GetConstitution"))
    val read = constitution.decodeBytes(bytes).fold(e => fail(e.render), identity)
    assertEquals(constitution.encodeBytes(read), bytes)
  }

  // ---- the state of a Conway node, against the node's answers

  private val fixtures = Path.of("fixtures/eras")
  private val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => fail(e.message), identity)
  private val mirrorEnv: MirrorEnv =
    MirrorEnv(setup.globals.network, setup.geometry, setup.globals.updateQuorum)
  private val queryEnv = QueryEnv(
    setup.byronParams
      .flatMap(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.byronParams.fold(EraHistory.ByronParams(50, 1000, 5))(b =>
      EraHistory.ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
    ),
    setup.globals.maxSupply,
    setup.genesis.raw
  )

  private val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => hex(l))

  private val blocks: Vector[Block] =
    raws.map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), identity))

  /** The Conway states of the capture, each with the block that made it. */
  private lazy val conwayStates: Vector[(Block, NodeState)] =
    blocks.indices
      .foldLeft(Vector.empty[(Block, NodeState)]) { (acc, i) =>
        val before = acc.lastOption.fold(setup.start)(_._2)
        NodeRules.applyBlock(
          before,
          blocks(i).header,
          raws(i),
          setup.praos,
          setup.globals,
          setup.byronRules
        ) match
          case Right(next)          => acc :+ (blocks(i) -> next)
          case Left((rule, reason)) => fail(s"block $i rejected by $rule: $reason")
      }
      .filter(_._1.era == xyz.kd5ujc.scarab.protocol.Era.Conway)

  private def ask(state: NodeState, block: Block, query: LedgerQuery): Bytes =
    val view = QueryView(Point.Origin, Some(block.header.blockNo), state)
    QueryAnswers
      .answer(
        queryEnv,
        view,
        Map.empty,
        NtcQuery.IfCurrent(xyz.kd5ujc.scarab.protocol.Era.Conway.index, query)
      )
      .map(result)
      .getOrElse(fail(s"$query is not answered"))

  private def mirrored(state: NodeState, block: Block) =
    val at = WithOrigin.At(SlotNo.fromUnsigned(block.header.slot.toLong))
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    MirrorCorpus.mirror.to[Conway](at, state.ledger).run(mirrorEnv) match
      case Right(m)    => m.value.nes
      case Left(error) => fail(s"the mirror refuses the state: $error")

  test("the capture has Conway states") {
    assert(conwayStates.nonEmpty)
  }

  private def overConway(name: String)(piece: (NodeState, Block) => Unit): Unit =
    test(s"Conway states of the capture: $name") {
      conwayStates.foreach { (block, state) => piece(state, block) }
    }

  import LedgerQuery.*

  overConway("GetCurrentPParams is the current parameters") { (state, block) =>
    val nes = mirrored(state, block)
    assertEquals(
      Params.conwayCodec.encodeBytes(nes.es.ls.utxo.gov.curPParams).hex,
      ask(state, block, GetCurrentPParams).hex
    )
  }

  overConway("GetGovState is the governance state") { (state, block) =>
    val nes = mirrored(state, block)
    assertEquals(
      conwayGovState.encodeBytes(nes.es.ls.utxo.gov).hex,
      ask(state, block, GetGovState).hex
    )
  }

  overConway("GetConstitution, GetProposals and GetRatifyState are parts of it") { (state, block) =>
    val gov = mirrored(state, block).es.ls.utxo.gov
    assertEquals(
      constitution.encodeBytes(gov.constitution).hex,
      ask(state, block, GetConstitution).hex
    )
    assertEquals(
      ratifyState.encodeBytes(gov.drepPulsing.ratify).hex,
      ask(state, block, GetRatifyState).hex
    )
    assertEquals(
      Wire.seqOf(govActionState).encodeBytes(gov.drepPulsing.snapshot.proposals).hex,
      ask(state, block, GetProposals(Set.empty)).hex
    )
  }

  overConway("GetPoolDistr2 is the pool distribution of the state") { (state, block) =>
    val nes = mirrored(state, block)
    assertEquals(poolDistr.encodeBytes(nes.pd).hex, ask(state, block, GetPoolDistr2(None)).hex)
  }

  overConway("GetAccountState is the chain account state") { (state, block) =>
    val nes = mirrored(state, block)
    assertEquals(
      chainAccountState.encodeBytes(nes.es.chainAccount).hex,
      ask(state, block, GetAccountState).hex
    )
  }

  overConway("GetDRepState is the map of the registered DReps") { (state, block) =>
    val dreps = mirrored(state, block).es.ls.cert.vState.dreps
    val codec = Wire.mapOf(credential[DRepRole], drepState)
    assertEquals(codec.encodeBytes(dreps).hex, ask(state, block, GetDRepState(Set.empty)).hex)
  }
