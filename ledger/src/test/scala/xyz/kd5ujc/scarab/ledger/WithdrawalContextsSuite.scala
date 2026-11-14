// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}

/** Direct withdrawal-field translation checks, derived from cardano-ledger f649f975:
  * Alonzo/Plutus/TxInfo.hs:301-309 collapses by staking credential in V1/V2, into a map in the
  * order of plutus-ledger-api's `Credential` (plutus 507cab8c
  * PlutusLedgerApi/V1/Credential.hs:30-37: a public key before a script); Conway/TxInfo.hs:549-551,
  * 697-699 retains every account-map entry in V3, in the ledger's order (Address.hs:182-187,
  * Credential.hs:98-101: the network, then a script before a key). Mixed networks are intentional
  * inputs to this translation API; these tests do not claim that a ledger accepts the resulting
  * transaction.
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
      val env = ScriptContexts.Environment(
        TestLedger.usualEra(protocol),
        protocol,
        Map.empty,
        Map.empty,
        n => Right(n)
      )
      val info = ScriptContexts.txInfo(language, tx, env).fold(e => fail(e.toString), identity)
      val at = if language == PlutusLanguage.V1 then 5 else 6
      val obtained = info match
        case PlutusData.Constr(_, fields) => fields.lift(at).getOrElse(fail("missing withdrawals"))
        case other                        => fail(s"not TxInfo: $other")
      val expected = language match
        case PlutusLanguage.V4 => fail("no PlutusV4 context before Dijkstra")
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

  // A key hash on each side of the script hash, so that the kind, not the hash, decides.
  private val lowKeyHash = Bytes.fromHex("00" * 28).fold(e => fail(e.message), identity)
  private val highKeyHash = Bytes.fromHex("ff" * 28).fold(e => fail(e.message), identity)
  private val lowKey = Credential.Key(lowKeyHash)
  private val highKey = Credential.Key(highKeyHash)
  private val lowKeyData = constr(0, B(lowKeyHash))
  private val highKeyData = constr(0, B(highKeyHash))

  private def field(info: PlutusData, at: Int): PlutusData = info match
    case PlutusData.Constr(_, fields) => fields.lift(at).getOrElse(fail(s"no field $at"))
    case other                        => fail(s"not TxInfo: $other")

  test("credentials order as the ledger's derived Ord: a script before a key, then the hash") {
    val unsigned = Credential.Key(Bytes.fromHex("80" * 28).fold(e => fail(e.message), identity))
    val below = Credential.Key(Bytes.fromHex("7f" * 28).fold(e => fail(e.message), identity))
    assertEquals(
      Vector(highKey, unsigned, lowKey, script, below).sorted,
      Vector(script, lowKey, below, unsigned, highKey)
    )
  }

  test("a rewarding redeemer indexes the withdrawals with the script before both keys") {
    val withdrawals = Map(
      RewardAccount(0, highKey) -> state.Coin(6),
      RewardAccount(0, script) -> state.Coin(123456),
      RewardAccount(0, lowKey) -> state.Coin(5)
    )
    assertEquals(
      Purpose.sortedWithdrawals(withdrawals).map(_._1.credential),
      Vector(script, lowKey, highKey)
    )
  }

  Vector(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3).foreach { language =>
    test(s"$language: keys on each side of a script hash order by the credential's kind") {
      val mixed = Map(
        RewardAccount(0, highKey) -> BigInt(6),
        RewardAccount(0, script) -> BigInt(123456),
        RewardAccount(0, lowKey) -> BigInt(5)
      )
      val body = TestLedger.body("mixed kinds", Vector.empty, Vector.empty, 0, withdrawals = mixed)
      val env =
        ScriptContexts.Environment(TestLedger.usualEra(10), 10, Map.empty, Map.empty, n => Right(n))
      val info = ScriptContexts
        .txInfo(language, Tx(body, Witnesses.empty, 0, true, None), env)
        .fold(e => fail(e.toString), identity)
      val plutusOrder =
        Vector(lowKeyData -> I(5), highKeyData -> I(6), scriptData -> I(123456))
      val expected = language match
        case PlutusLanguage.V4 => fail("no PlutusV4 context before Dijkstra")
        case PlutusLanguage.V1 =>
          PlutusData.List(plutusOrder.map((c, amount) => constr(0, constr(0, c), amount)))
        case PlutusLanguage.V2 =>
          PlutusData.Map(plutusOrder.map((c, amount) => (constr(0, c): PlutusData) -> amount))
        case PlutusLanguage.V3 =>
          PlutusData.Map(Vector(scriptData -> I(123456), lowKeyData -> I(5), highKeyData -> I(6)))
      assertEquals(field(info, if language == PlutusLanguage.V1 then 5 else 6), expected)
    }
  }

  test("V3: treasury withdrawals of a proposal are in the order of their accounts, network first") {
    val withdrawals = Vector(
      RewardAccount(1, script) -> BigInt(7),
      RewardAccount(0, lowKey) -> BigInt(8),
      RewardAccount(0, script) -> BigInt(9),
      RewardAccount(1, highKey) -> BigInt(10)
    )
    val proposal = Proposal(
      BigInt(100000000000L),
      RewardAccount(0, key),
      GovAction.TreasuryWithdrawals(withdrawals, Some(scriptHash))
    )
    val body =
      TestLedger.body("treasury", Vector.empty, Vector.empty, 0, proposals = Vector(proposal))
    val env =
      ScriptContexts.Environment(TestLedger.usualEra(10), 10, Map.empty, Map.empty, n => Right(n))
    val info = ScriptContexts
      .txInfo(PlutusLanguage.V3, Tx(body, Witnesses.empty, 0, true, None), env)
      .fold(e => fail(e.toString), identity)
    val entries = field(info, 13) match
      case PlutusData.List(Vector(PlutusData.Constr(_, Vector(_, _, action)))) =>
        action match
          case PlutusData.Constr(tag, Vector(map, _)) if tag == 2 => map
          case other => fail(s"not TreasuryWithdrawals: $other")
      case other => fail(s"not one proposal procedure: $other")
    assertEquals(
      entries,
      PlutusData.Map(
        Vector(scriptData -> I(9), lowKeyData -> I(8), scriptData -> I(7), highKeyData -> I(10))
      )
    )
  }
