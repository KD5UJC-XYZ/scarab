// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

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

  private def bootstrap(id: Bytes): BootstrapWitness =
    BootstrapWitness(
      bootKey.vkey,
      bootKey.sign(id),
      chainCode = bytes(7),
      attributes = Bytes(Vector(0xa0.toByte))
    )

  private def run(
      required: Vector[Bytes],
      native: Option[NativeScript],
      vkeys: Vector[TestLedger.Key],
      boot: Boolean
  ) =
    val body =
      TestLedger.body("native-signers", Vector.empty, Vector.empty, 0, requiredSigners = required)
    val withBoot =
      if boot then Witnesses.empty.copy(bootstrap = Vector(bootstrap(body.id)))
      else Witnesses.empty
    val tx = TestLedger.tx(body, vkeys, withBoot)
    val scripts =
      native.map(s => scriptHash -> Script(ScriptKind.Native, scriptHash, Some(s))).toMap
    TxChecks.witnessChecks(
      Map.empty,
      tx,
      Vector.empty,
      TxChecks.Collateral.none,
      scripts,
      native.map(_ => scriptHash -> Set.empty[TxChecks.Pointer]).toMap,
      tx.witnesses.vkeys.map(w => Verdicts.Direct.keyHash(w._1)),
      Verdicts.Direct
    )

  private val root: Bytes = bootstrap(Bytes.empty).addressRoot

  test("RequireSignature(root) with only a bootstrap witness is refused") {
    run(Vector.empty, Some(NativeScript.Sig(root)), Vector.empty, boot = true) match
      case Left(LedgerFailure.Rejected(_, failure)) =>
        assertEquals(
          failure,
          PredicateFailure.utxow(UtxowFailure.ScriptWitnessNotValidating(Set(scriptHash)))
        )
      case other => fail(s"not refused as ScriptWitnessNotValidatingUTXOW: $other")
  }

  test("RequireSignature of a vkey witness's hash holds, with or without a bootstrap witness") {
    List(false, true).foreach { boot =>
      assertEquals(
        run(Vector.empty, Some(NativeScript.Sig(signer.hash)), Vector(signer), boot).isRight,
        true
      )
    }
  }

  test("the bootstrap root still satisfies a needed key hash") {
    assertEquals(run(Vector(root), None, Vector.empty, boot = true).isRight, true)
    assert(run(Vector(root), None, Vector.empty, boot = false).isLeft)
  }
