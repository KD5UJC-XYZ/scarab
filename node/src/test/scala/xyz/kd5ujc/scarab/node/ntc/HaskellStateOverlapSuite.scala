// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.state.{DRepRole, NewEpochState, ProtVer, ShelleyBasedEra, Word64}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params, Wire}

import HaskellStateCodecs.given

/** The node-to-client answers and the Haskell ledger-state codec, two paths to the same bytes. The
  * answers are written from the node's own types ([[QueryAnswers]]); the mirror is the ledger-side
  * conversion of the same state, written by the codec of the state file. Where a query asks for a
  * piece of the state they must agree:
  *
  *   - the reference's own answers (`fixtures/devnet/lsq/golden.json`) read and written again;
  *   - the pieces of the Conway states of the eras capture and of the two built on the last one
  *     ([[NtcGolden]]), converted by the mirror and written by the codec, against the node's answer
  *     to the query that asks for that piece.
  *
  * The bytes of the answers themselves are pinned by [[NtcGoldenSuite]] and `QueryGoldenSuite`.
  */
class HaskellStateOverlapSuite extends FunSuite:
  /** The version of a Conway state, which is where the answers are written (protocol 9 and up). */
  given conwayVersion: ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)

  // ---- the reference's own answers

  private val golden = parser
    .parse(Files.readString(Path.of("fixtures/devnet/lsq/golden.json")))
    .fold(e => fail(e.message), identity)
    .hcursor

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.message), identity)

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
  }

  test("the reference's GetConstitution is read and written again byte for byte") {
    val bytes = result(answerOf("GetConstitution"))
    val read = constitution.decodeBytes(bytes).fold(e => fail(e.render), identity)
    assertEquals(constitution.encodeUnchecked(read), bytes)
  }

  // ---- the state of a Conway node, against the node's answers

  private type Subject = (String, MirrorState, Point, Option[BigInt])

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
    subject._2.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        NewEpochState
          .retype(i.era, ShelleyBasedEra.SConway)(i.state.nes)
          .getOrElse(fail("the state is not Conway's"))
      case _ => fail("the state is in Byron")

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
    val vState = mirrored(subject).es.ls.cert.vState
    val dreps = vState.dreps
    val codec = Wire.mapOf(credential[DRepRole], drepState)
    // the query reports the expiries brought forward by the dormant epochs
    if vState.numDormantEpochs.toBigInt == 0 then
      assertEquals(codec.encodeUnchecked(dreps).hex, ask(subject, GetDRepState(Set.empty)).hex)
  }
