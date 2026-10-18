// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

class ContextCollectionSuite extends FunSuite:
  import ContextReferenceCases.{all, field, hex}

  all.foreach { recorded =>
    test(s"${recorded.name}: reference collection verdict, count and request order") {
      recorded.collect match
        case Right(requests) =>
          assert(recorded.collected, recorded.oracle.toString)
          val hashes = requests.map { case (_, request) =>
            val hash = Blake2b.hash224.hash(
              Bytes(Vector((request.language.id + 1).toByte)) ++ request.script
            )
            s"ScriptHash \"${hash.hex}\""
          }
          assertEquals(hashes, field[Vector[String]](recorded.oracle, "scriptHashes"))
        case Left(errors) =>
          assert(!recorded.collected)
          val expected = CborRead
            .decode(hex(field[String](recorded.oracle, "collectErrorsCbor")))
            .fold(e => fail(e.toString), identity)
            .value match
            case CborValue.Arr(items) => items.size
            case _                    => fail("not reference collection errors")
          assertEquals(errors.length, expected)
      assertEquals(
        recorded.collect,
        Phase2.collect(
          recorded.tx.copy(isValid = !recorded.tx.isValid),
          recorded.params,
          recorded.scripts,
          recorded.spent,
          recorded.references,
          slot => Right(slot * 100)
        )
      )
    }
  }

  test("V2 and V3 reject rather than discard an unresolved redeemer-map pointer") {
    Vector(PlutusLanguage.V2, PlutusLanguage.V3).foreach { language =>
      val recorded = all
        .find(_.name == s"api-v${language.id + 1}-extra-redeemer-pointer")
        .getOrElse(fail("no pointer oracle case"))
      val env =
        ScriptContexts.Environment(10, recorded.spent, recorded.references, slot => Right(slot))
      assertEquals(
        ScriptContexts.txInfo(language, recorded.tx, env),
        Left(TranslationError.RedeemerPointerPointsToNothing(1, 99))
      )
    }
  }

  test(
    "a nonempty voting anchor survives transaction decoding without changing original body bytes"
  ) {
    val recorded = all.find(_.name == "api-v1-votes-with-anchor").getOrElse(fail("no anchor case"))
    val procedures = recorded.tx.body.votes.flatMap(_._2).map(_._2)
    assertEquals(
      procedures,
      Vector(
        VotingProcedure(
          Vote.Yes,
          Some(Anchor("https://example.invalid/vote", Bytes(Vector.fill(32)(0x33.toByte))))
        )
      )
    )
    assertEquals(
      recorded.collect.left.map(_.head),
      Left(
        CollectError.BadTranslation(
          TranslationError.VotingProceduresFieldNotSupported(recorded.tx.body.votes)
        )
      )
    )
    assertEquals(recorded.tx.body.id, Blake2b.hash256.hash(recorded.tx.body.raw))
  }

  test("twenty thousand missing redeemers collect and render on a 256 KiB stack") {
    val recorded =
      all.find(_.name == "api-collect-none").getOrElse(fail("no missing-redeemer case"))
    val policy = recorded.tx.body.mint.keys.toVector.sorted.headOption.getOrElse(fail("no mint"))
    val script = recorded.scripts.getOrElse(policy, fail("no script"))
    val address =
      Address.decode(Bytes(Vector(0x70.toByte)) ++ policy).fold(e => fail(e.toString), identity)
    val output = TestLedger.out(address, 1000000)
    val inputs = Vector.tabulate(20000)(i => TxIn(recorded.tx.body.id, BigInt(i)))
    val tx = recorded.tx.copy(
      body = recorded.tx.body.copy(inputs = inputs, mint = Map.empty),
      witnesses = recorded.tx.witnesses.copy(redeemers = Vector.empty)
    )
    val failure = PreprodDeepTx.onSmallStack {
      val result = Phase2.collect(
        tx,
        recorded.params,
        Map(policy -> script),
        inputs.map(_ -> output).toMap,
        Map.empty,
        slot => Right(slot)
      )
      val errors = result.swap.fold(_ => fail("collector unexpectedly succeeded"), identity)
      assertEquals(errors.length, 20000)
      assertEquals(
        errors.head,
        CollectError.NoRedeemer(Purpose.Spending(19999, inputs(19999), output))
      )
      val predicate = PredicateFailure.utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(errors)))
      assert(predicate.detail.startsWith("CollectErrors: no redeemer"))
      errors
    }
    assertEquals(
      failure.toVector.last,
      CollectError.NoRedeemer(Purpose.Spending(0, inputs(0), output))
    )
  }
