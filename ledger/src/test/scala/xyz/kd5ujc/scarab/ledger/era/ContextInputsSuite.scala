// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayImp, ConwayScriptedBlocks, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.state.Conway

/** What Conway's contexts are collected from ([[ContextInputs]], #1499): the scripts used are
  * compared by their purpose, their language's translation and their runnable whole (its binary
  * too), where a runnable's own equality takes the language and hash alone; so contexts kept for
  * other scripts used, or for a runnable of another binary under the same hash, are not taken.
  */
class ContextInputsSuite extends FunSuite:
  private given EraTx.conway.bodyOf.Level[TxLevel.Top] = EraTx.conway.bodyOf.top

  private val scripted = ConwayScriptedBlocks.honest(1, 20)
  private val tx =
    EraBlockBody.conway.txSeqBlockBody(ConwayImp.decoded(scripted.raw).blockBody).head
  private val inputs: ContextInputs[Conway] =
    val t = EraTx.conway
    val stored = scripted.state.utxo.utxo.utxo
    val found = t.bodyOf
      .allInputs(t.body[TxLevel.Top](tx))
      .iterator
      .flatMap(in => stored.get(in).map(in -> _))
      .toMap[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
    AlonzoStAnnTx
      .resolve[Conway](
        ConwayScriptedBlocks.params,
        ConwayUTxO.fromStored(found).getOrElse(fail("outputs not read")),
        Map.empty,
        tx
      )
      .inputs

  private def withUsed(
      used: List[(PlutusPurpose[AsIxItem, Conway], SupportedPlutusRunnable[Conway])]
  ) =
    new ContextInputs[Conway](inputs.protVer, inputs.utxo, inputs.tx, used, inputs.costModels)

  private val kept =
    KeptContexts.of(inputs, ConwayImp.slotToPosix).getOrElse(fail("not collected"))

  test("contexts kept are taken for inputs equal to their own") {
    assert(kept.answers(withUsed(inputs.used), ConwayImp.slotToPosix))
  }

  test("contexts kept are not taken for other scripts used") {
    val none = withUsed(Nil)
    assert(!kept.answers(none, ConwayImp.slotToPosix))
    assertNotEquals(none.collect(ConwayImp.slotToPosix), inputs.collect(ConwayImp.slotToPosix))
  }

  test("a runnable of another binary under the same hash is another script used") {
    val (purpose, runnable) = inputs.used.headOption.getOrElse(fail("no script used"))
    val other = SupportedPlutusRunnable(
      runnable.plutusTxInfo,
      PlutusRunnable(
        Plutus[runnable.L](runnable.plutusRunnable.plutusRunnableBinary.binary ++ Bytes(Vector(0))),
        runnable.plutusRunnable.plutusRunnableScriptHash
      )
    )
    // the runnable's own equality, the reference's, takes the language and hash alone
    assertEquals(other, runnable)
    val swapped = withUsed((purpose -> other) :: inputs.used.drop(1))
    assert(!kept.answers(swapped, ConwayImp.slotToPosix))
    assertNotEquals(
      swapped.collect(ConwayImp.slotToPosix).map(_.map(PlutusWithContext.evalRequest)),
      inputs.collect(ConwayImp.slotToPosix).map(_.map(PlutusWithContext.evalRequest))
    )
  }
