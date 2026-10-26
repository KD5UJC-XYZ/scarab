// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{StAnnTx, Test, UtxoEnv, Utxow}

/** Native scripts are evaluated over the key hashes of the vkey witnesses only (`validateTimelock`,
  * `Allegra/Tx.hs`; `validateMultiSig`, `Shelley/Scripts.hs`; `validateFailedNativeScripts`,
  * `Shelley/Rules/Utxow.hs`; cardano-ledger f649f975, all over `addrTxWitsL`). The address root of
  * a bootstrap witness satisfies a needed key hash (`MissingVKeyWitnesses`) but is no signer of a
  * script.
  */
class NativeScriptSignersSuite extends FunSuite:
  private val signer = TestLedger.key()
  private val bootKey = TestLedger.key()

  private def bytes(b: Int): Bytes = Bytes(Vector.fill(32)(b.toByte))

  private val scriptHash = Bytes(Vector.fill(28)(0x5c.toByte))

  /** An input locked by the script `scriptHash` (an enterprise script address), so the script is
    * needed in every era.
    */
  private val locked = TestLedger.txIn(0)

  private val funds = BigInt(10000000)

  private val scriptAddress: Address =
    Address.decode(Bytes(0x70.toByte +: scriptHash.value)).fold(e => fail(e.toString), identity)

  private def bootstrap(id: Bytes): BootstrapWitness =
    BootstrapWitness(
      bootKey.vkey,
      bootKey.sign(id),
      chainCode = bytes(7),
      attributes = Bytes(Vector(0xa0.toByte))
    )

  /** The witness checks of UTXOW (`Utxow.checks`) of a transaction that spends `locked`, whose
    * script is `native` (one that holds with no signer when there is none), in a Shelley-style era
    * (every provided native script) and a Babbage-style one (the needed ones).
    */
  private def run(
      required: Vector[Bytes],
      native: Option[NativeScript],
      vkeys: Vector[TestLedger.Key],
      boot: Boolean
  ): Vector[Either[LedgerFailure, Unit]] =
    val body =
      TestLedger.body(
        "native-signers",
        Vector(locked),
        Vector.empty,
        0,
        requiredSigners = required
      )
    val script = native.getOrElse(NativeScript.AllOf(Vector.empty))
    val witnesses = Witnesses.empty.copy(
      scripts = Vector(Script(ScriptKind.Native, scriptHash, Some(script))),
      bootstrap = if boot then Vector(bootstrap(body.id)) else Vector.empty
    )
    val tx = TestLedger.tx(body, vkeys, witnesses)
    val utxo = Utxo.fromMap(Map(locked -> TestLedger.out(scriptAddress, funds)))
    Vector(5, 9).map { major =>
      val env = UtxoEnv(TestLedger.env, TestLedger.params(major), ())
      TestLedger.verdict(tx.body.id, Utxow.checks(env, Map.empty, StAnnTx(tx, utxo), Test.pass))
    }

  private val root: Bytes = bootstrap(Bytes.empty).addressRoot

  test("RequireSignature(root) with only a bootstrap witness is refused") {
    run(Vector.empty, Some(NativeScript.Sig(root)), Vector.empty, boot = true).foreach {
      case Left(LedgerFailure.Rejected(_, failures)) =>
        assertEquals(
          failures.toNonEmptyList.toList,
          List(PredicateFailure.utxow(UtxowFailure.ScriptWitnessNotValidating(Set(scriptHash))))
        )
      case other => fail(s"not refused as ScriptWitnessNotValidatingUTXOW: $other")
    }
  }

  test("RequireSignature of a vkey witness's hash holds, with or without a bootstrap witness") {
    List(false, true).foreach { boot =>
      assertEquals(
        run(Vector.empty, Some(NativeScript.Sig(signer.hash)), Vector(signer), boot),
        Vector.fill(2)(Right(()))
      )
    }
  }

  test("the bootstrap root still satisfies a needed key hash") {
    assertEquals(run(Vector(root), None, Vector.empty, boot = true), Vector.fill(2)(Right(())))
    assert(run(Vector(root), None, Vector.empty, boot = false).forall(_.isLeft))
  }
