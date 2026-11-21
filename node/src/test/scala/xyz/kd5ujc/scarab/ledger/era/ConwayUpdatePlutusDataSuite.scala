// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.protocol.StateLayout
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{
  ConwayParamUpdate,
  GovAction as FlatGovAction,
  ScriptContexts,
  Tx as FlatTx
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.*
import xyz.kd5ujc.scarab.protocol.Era

/** The parameter changes of the recorded Conway refusal corpora as Plutus data: each proposal's
  * update read by Conway's types (`PParamsUpdate[Conway]`) and shown through
  * `ToPlutusData (PParamsUpdate era)` is what the flat path puts in a V3 script context.
  */
class ConwayUpdatePlutusDataSuite extends FunSuite:
  private val tx = EraTx.conway
  private val top: tx.bodyOf.Level[TxLevel.Top] = tx.bodyOf.top

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  test("every parameter change of the Conway refusal corpora is the flat path's Plutus data"):
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    val compared = loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      c.cases.flatMap { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val flat = right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway))
        val typed = right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1))
        val proposals =
          tx.bodyOf
            .proposalProcedures[TxLevel.Top](tx.body[TxLevel.Top](typed)(using top))(using
              top
            )
            .toVector
        assertEquals(proposals.size, flat.body.proposals.size, what)
        proposals.zip(flat.body.proposals).flatMap { (p, f) =>
          (p.pProcGovAction, f.action) match
            case (
                  GovAction.ParameterChange(_, update, _),
                  FlatGovAction.ParameterChange(_, u: ConwayParamUpdate, _)
                ) =>
              assertEquals(
                ToPlutusData[PParamsUpdate[Conway]].toPlutusData(update),
                ScriptContexts.changedParameters(u),
                what
              )
              Some(what)
            case (GovAction.ParameterChange(_, _, _), other)     => fail(s"$what: flat $other")
            case (other, FlatGovAction.ParameterChange(_, _, _)) => fail(s"$what: typed $other")
            case _                                               => None
        }
      }
    }
    assertEquals(compared.size, 14, compared)
