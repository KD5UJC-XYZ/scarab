// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraTxBody, EraUTxO, ScriptsProvided, TxLevel}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

import DijkstraRecordedTxs.{Level, Recorded}

/** Dijkstra's scripts provided on the era types (`getDijkstraScriptsProvided`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/UTxO.hs:167-186`, cardano-ledger 9df9f98c): at
  * the top level the union of its own and each sub-transaction's, at a sub-transaction its own,
  * each Babbage's (`getBabbageScriptsProvided`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/
  * UTxO.hs:136-147`, cardano-ledger 9df9f98c): the reference scripts of the outputs its inputs and
  * reference inputs name, and the witness set's scripts.
  *
  * On every Dijkstra transaction the repository records that the reference reads
  * ([[DijkstraRecordedTxs]]), the Plutus scripts provided at each level, by hash with their
  * language, are exactly those the recorded bytes hold, read by plain CBOR and hashed with their
  * language's tag: the witness sets' and the recorded outputs' reference scripts. A transaction
  * whose fixture records no outputs is given none.
  */
class DijkstraScriptsProvidedSuite extends FunSuite:
  private val utxos = EraUTxO.dijkstra
  private val bodies = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  private lazy val recorded: Vector[Recorded] =
    DijkstraRecordedTxs.leiosTxs ++ DijkstraRecordedTxs.leiosBlockTxs ++
      DijkstraRecordedTxs.testnetBlockTxs ++ DijkstraRecordedTxs.devnetBlockTxs ++
      DijkstraRecordedTxs.contextTxs ++ DijkstraRecordedTxs.rulesTxs.map(_._2) ++
      DijkstraRecordedTxs.submittedTxs

  /** The Plutus scripts provided, by hash, with their language's tag. */
  private def plutus(p: ScriptsProvided[Dijkstra]): Map[Bytes, Int] =
    p.unScriptsProvided.flatMap((h, s) =>
      DijkstraScript.instance.toPlutusScript(s).map(x => h -> x.tag.toInt)
    )

  test("the Plutus scripts provided at each level are those the recorded bytes hold") {
    // the Leios corpus's 273 transactions and its blocks' 63, the testnet's blocks' 40, the
    // devnet's one, the script-context fixtures' 233, the reference's 18 UTXOW and 13 ENTITIES
    // cases and the 150 submitted
    assertEquals(recorded.size, 273 + 63 + 40 + 1 + 233 + 31 + 150)
    val counts = recorded.map { r =>
      val levels = DijkstraRecordedTxs.levels(r)
      val references = DijkstraRecordedTxs.referenceScripts(r)
      def expected(l: Level): Map[Bytes, Int] = l.inputs.flatMap(references.get).toMap ++
        l.plutusScripts
      val utxo = DijkstraRecordedTxs.eraUtxo(r)
      assertEquals(
        plutus(utxos.getScriptsProvided[TxLevel.Top](utxo, r.tx)),
        levels.map(expected).reduce(_ ++ _),
        r.name
      )
      val subs = bodies.subTransactions(r.tx.body).toVector
      assertEquals(
        subs.map(s => bodies.id[TxLevel.Sub](s.body)(using BothLevels.Sub)),
        levels.drop(1).map(DijkstraRecordedTxs.id),
        r.name
      )
      subs.zip(levels.drop(1)).foreach { (sub, level) =>
        assertEquals(
          plutus(utxos.getScriptsProvided[TxLevel.Sub](utxo, sub)(using BothLevels.Sub)),
          expected(level),
          s"${r.name}: sub-transaction ${sub.id.hex}"
        )
      }
      (
        levels.head.plutusScripts.values.toSet,
        levels.flatMap(_.inputs).count(references.contains),
        levels.drop(1).map(_.plutusScripts.size).sum
      )
    }
    // the witnesses' scripts of each language, reference scripts the outputs hold, and scripts in
    // a sub-transaction's witnesses are all reached
    assertEquals(counts.flatMap(_._1).toSet, Set(1, 2, 3, 4))
    assert(counts.exists(_._2 > 0), "no reference script")
    assert(counts.exists(_._3 > 0), "no script in a sub-transaction's witnesses")
  }
