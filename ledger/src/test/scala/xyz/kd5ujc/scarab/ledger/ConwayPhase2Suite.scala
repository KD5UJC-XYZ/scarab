// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.{AlonzoStAnnTx, PParams, PlutusWithContext}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayPhase2
import xyz.kd5ujc.scarab.ledger.state.{Conway, ShelleyBasedEra}

/** Where Conway's block run locates a script it runs (`ConwayPhase2.locate`, which names a script
  * with no verdict and observes a transaction's scripts for `--plutus-cases`), on every recorded
  * Plutus case of `fixtures/preview/plutus`, each read by Conway's decoder and judged in a ledger
  * holding the case's outputs: the scripts it finds, collected one purpose at a time, are the ones
  * the transaction's annotation collects whole (`mkAlonzoStAnnTx`, as UTXOS runs them), in their
  * order, and each comes with the redeemer and the evaluator's request the flat rules collect for
  * it (`Phase2.collect`). The cases hold Plutus V1, V2 and V3 scripts for spending, minting,
  * certifying, withdrawing, voting and proposing; 40 of the 45 are Conway transactions (the rest
  * hold what its decoder refuses).
  */
class ConwayPhase2Suite extends FunSuite:
  test(
    "every recorded Plutus case: the scripts located are those UTXOS collects, in its order, with the flat rules' redeemers and requests"
  ) {
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    // the cases whose bytes Conway's decoder reads: those of Babbage that hold what Conway's
    // decoder refuses (an empty set) are not Conway transactions
    val read = PlutusCase.all.flatMap { c =>
      val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
      xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx
        .decode(
          Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, c.tx.isValid),
          xyz.kd5ujc.scarab.protocol.StateLayout.Node11_1
        )
        .toOption
        .map(c -> _)
    }
    val located = read.map { (c, tx) =>
      val ls = state.Build
        .funded(
          state.Build.nes[Conway]((c.slot / PreviewConwayWorld.epochLength).toLong, c.params),
          c.spent ++ c.references ++ c.collateral
        )
        .es
        .ls
      val pp = PParams[Conway](
        ConwayPParams.fromStored(c.params).fold(e => fail(s"${c.name}: $e"), identity)
      )
      val utxo =
        ConwayUTxO.fromStored(ls.utxo.utxo.utxo.toMap).fold(e => fail(s"${c.name}: $e"), identity)
      val annotated = AlonzoStAnnTx
        .mkAlonzoStAnnTx[Conway](c.posix, pp, utxo, Map.empty, tx)
        .asatPlutusScriptsWithContext
        .fold(e => fail(s"${c.name}: the annotation's collection $e"), identity)
      val scripts: Map[Bytes, Script] =
        (c.spent.values ++ c.references.values)
          .flatMap(_.referenceScript)
          .map(script => script.hash -> script)
          .toMap ++ c.tx.witnesses.scriptsByHash
      val flat = Phase2
        .collect[Conway](c.tx, c.params, scripts, c.spent, c.references, c.posix)
        .fold(e => fail(s"${c.name}: the flat collection $e"), identity)
      val found = ConwayPhase2
        .locate(Vector(tx), ls, pp, c.posix)(_ => true)
        .getOrElse(fail(s"${c.name}: no script located"))
      assertEquals(found.scripts.map(_._2), annotated.toVector, c.name)
      assertEquals(
        found.scripts.map((redeemer, pwc) => (redeemer, PlutusWithContext.evalRequest(pwc))),
        flat,
        c.name
      )
      // every script locates its transaction by itself, and is there at its place
      found.scripts.zipWithIndex.foreach { (script, place) =>
        assertEquals(
          ConwayPhase2
            .locate(Vector(tx), ls, pp, c.posix)(_ == script._2)
            .flatMap(_.scripts.lift(place)),
          Some(script),
          c.name
        )
      }
      found.scripts.map((redeemer, pwc) => (pwc.plutusLanguage.language, redeemer.tag))
    }
    assertEquals((PlutusCase.all.size, located.size), (45, 40))
    val seen = located.flatten.toSet
    assertEquals(seen.map(_._1.id), Set(0, 1, 2))
    assertEquals(seen.map(_._2), Set(0, 1, 2, 3, 4, 5))
  }
