// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes
import lab.node.plutus.{PlutusData, ScalusEvaluator}
import scalus.uplc.builtin.Data

/** Where the node's script contexts knowingly differ from Scalus's translation, which the context
  * tests use as an oracle.
  *
  * `Constitution` (the second field of a `NewConstitution` governance action) is a one-field data
  * type of the ledger API, encoded `Constr 0 [Maybe ScriptHash]`. Scalus 1.2.0 puts the `Maybe` in
  * its place. A real Preview transaction (slot 116688298, a Sundae cosponsor proposal) only
  * validates with the wrapper: its script compares the context with a value it builds itself.
  *
  * Reproducer: `ScalusReproducersSuite`. Still present in Scalus 1.3.0 (B11, to be reported
  * upstream).
  *
  * Only the proposal procedures of the transaction info, the `Proposing` purposes (script info and
  * redeemer keys) are touched; datums and redeemers that happen to look alike are left alone.
  */
object ScalusDeviations:
  def mend(theirs: Data): Data =
    PlutusData.decode(Bytes.fromArray(Data.toCbor(theirs))) match
      case Right(d) => ScalusEvaluator.toData(context(d))
      case Left(_)  => theirs

  private def context(d: PlutusData): PlutusData = d match
    case PlutusData.Constr(t, Vector(PlutusData.Constr(i, info), redeemer, script)) if t == 0 =>
      PlutusData.Constr(
        0,
        Vector(PlutusData.Constr(i, txInfo(info)), redeemer, scriptInfo(script))
      )
    case other => other

  /** V3 `TxInfo`: the proposal procedures are its 14th field. */
  private def txInfo(fields: Vector[PlutusData]): Vector[PlutusData] =
    fields.zipWithIndex.map {
      case (PlutusData.List(items), 13) => PlutusData.List(items.map(proposal))
      // The redeemers map is keyed by script purpose, `Proposing` included.
      case (PlutusData.Map(entries), 9) =>
        PlutusData.Map(entries.map((purpose, redeemer) => scriptInfo(purpose) -> redeemer))
      case (other, _) => other
    }

  private def scriptInfo(d: PlutusData): PlutusData = d match
    case PlutusData.Constr(t, Vector(index, p)) if t == 5 =>
      PlutusData.Constr(5, Vector(index, proposal(p)))
    case other => other

  /** `ProposalProcedure deposit returnAddress action`. */
  private def proposal(d: PlutusData): PlutusData = d match
    case PlutusData.Constr(
          t,
          Vector(deposit, returnAddress, PlutusData.Constr(a, Vector(previous, maybe)))
        ) if t == 0 && a == 5 =>
      PlutusData.Constr(
        0,
        Vector(
          deposit,
          returnAddress,
          PlutusData.Constr(5, Vector(previous, PlutusData.constr(0, maybe)))
        )
      )
    case other => other
