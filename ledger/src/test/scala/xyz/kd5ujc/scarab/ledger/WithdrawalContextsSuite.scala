// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}

/** Direct withdrawal-field translation checks, derived from cardano-ledger f649f975:
  * Alonzo/Plutus/TxInfo.hs:301-309 collapses by staking credential in V1/V2;
  * Conway/TxInfo.hs:549-551, 697-699 retains every account-map entry in V3. Mixed networks are
  * intentional inputs to this translation API; these tests do not claim that a ledger accepts the
  * resulting transaction.
  */
class WithdrawalContextsSuite extends FunSuite:
  import PlutusData.{B, I}

  private val keyHash = Bytes.fromHex("11" * 28).fold(e => fail(e.message), identity)
  private val scriptHash = Bytes.fromHex("22" * 28).fold(e => fail(e.message), identity)
  private val key = Credential.Key(keyHash)
  private val script = Credential.Script(scriptHash)
  private val accounts = Map(
    RewardAccount(1, key) -> BigInt(21),
    RewardAccount(0, key) -> BigInt(11),
    RewardAccount(1, script) -> BigInt(22),
    RewardAccount(0, script) -> BigInt(12)
  )

  private def constr(n: Int, fields: PlutusData*): PlutusData =
    PlutusData.Constr(n, fields.toVector)

  private val keyData = constr(0, B(keyHash))
  private val scriptData = constr(1, B(scriptHash))
  private val stakingKey = constr(0, keyData)
  private val stakingScript = constr(0, scriptData)
  private val translated = Vector(stakingKey -> I(21), stakingScript -> I(22))

  private val cases =
    Vector(5, 7, 9, 10, 11).map(_ -> PlutusLanguage.V1) ++
      Vector(7, 9, 10, 11).map(_ -> PlutusLanguage.V2) ++
      Vector(9, 10, 11).map(_ -> PlutusLanguage.V3)

  cases.foreach { (protocol, language) =>
    test(s"$language at protocol $protocol: credential collision follows the native translation") {
      val body =
        TestLedger.body("withdrawal context", Vector.empty, Vector.empty, 0, withdrawals = accounts)
      val tx = Tx(body, Witnesses.empty, 0, true, None)
      val env = ScriptContexts.Environment(protocol, Map.empty, Map.empty, n => Right(n))
      val info = ScriptContexts.txInfo(language, tx, env).fold(e => fail(e.toString), identity)
      val at = if language == PlutusLanguage.V1 then 5 else 6
      val obtained = info match
        case PlutusData.Constr(_, fields) => fields.lift(at).getOrElse(fail("missing withdrawals"))
        case other                        => fail(s"not TxInfo: $other")
      val expected = language match
        case PlutusLanguage.V1 =>
          PlutusData.List(translated.map((credential, amount) => constr(0, credential, amount)))
        case PlutusLanguage.V2 => PlutusData.Map(translated)
        case PlutusLanguage.V3 =>
          PlutusData.Map(
            Vector(
              scriptData -> I(12),
              keyData -> I(11),
              scriptData -> I(22),
              keyData -> I(21)
            )
          )
      assertEquals(obtained, expected)
      assertEquals(tx.body.raw, body.raw)
      assertEquals(tx.body.id, body.id)
    }
  }
