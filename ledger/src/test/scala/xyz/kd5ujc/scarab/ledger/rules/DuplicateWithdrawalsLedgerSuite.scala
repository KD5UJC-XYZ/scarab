// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  LedgerFailure,
  Mismatch,
  PlutusStats,
  PredicateFailure,
  Ptr,
  RewardAccount,
  TestLedger,
  Tx,
  TxBody,
  TxIn,
  Utxo,
  UtxoFailure,
  Value
}
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** Repeated withdrawals against native LEDGER state, without a flat-state conversion. */
class DuplicateWithdrawalsLedgerSuite extends FunSuite:
  private val alice = key()
  private val bob = key()
  private val input = txIn(1)
  private val funds = BigInt(100000000)
  private val reward = BigInt(7000000)
  private val deposit = BigInt(2000000)
  private val fee = BigInt(200)
  private val account = RewardAccount(0, bob.credential)
  private val stake =
    Convert.credential[Staking](bob.credential).fold(e => fail(e.toString), identity)

  private def state[E <: EraTag](major: Int)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): LedgerState[E] =
    val empty = Empty.ledgerState[E](params(major))
    val registered = ShelleyCertOps.register(
      pre.cert.get(empty),
      stake,
      Ptr(0, 0, 0),
      CompactCoin.fromUnsigned(deposit.toLong)
    )
    val rewarded = ShelleyCertLens.states.modify(
      _.updatedWith(stake)(_.map(_.copy(balance = CompactCoin.fromUnsigned(reward.toLong))))
    )(registered)
    pre.cert.replace(rewarded)(
      empty.copy[E](utxo =
        empty.utxo.copy[E](
          utxo = UtxoTables(Utxo.empty ++ Vector(input -> out(alice.address(), funds))),
          deposited = Coin.fromBigInt(deposit).fold(e => fail(e.toString), identity)
        )
      )
    )

  /** Definite CBOR, retaining every map pair, including the repeated account. */
  private object Hex:
    private def head(major: Int, n: BigInt): String =
      val m = major << 5
      if n < 24 then f"${m + n.toInt}%02x"
      else if n < 256 then f"${m + 24}%02x${n.toInt}%02x"
      else if n < 65536 then f"${m + 25}%02x${n.toInt}%04x"
      else if n < BigInt(1L << 32) then f"${m + 26}%02x${n.toLong}%08x"
      else f"${m + 27}%02x${n.toLong}%016x"
    def uint(n: BigInt): String = head(0, n)
    def bytes(b: Bytes): String = head(2, b.size) + b.hex
    def array(items: String*): String = head(4, items.size) + items.mkString
    def map(pairs: (String, String)*): String =
      head(5, pairs.size) + pairs.map(_ + _).mkString

  private def decoded(wire: Era, first: BigInt, payout: BigInt): TxBody =
    val accountBytes = Bytes(0xe0.toByte +: bob.hash.value)
    val raw = Hex.map(
      Hex.uint(0) -> Hex.array(Hex.array(Hex.bytes(input.id), Hex.uint(input.index))),
      Hex.uint(1) -> Hex.array(
        Hex.array(Hex.bytes(alice.address().raw), Hex.uint(funds - fee + payout))
      ),
      Hex.uint(2) -> Hex.uint(fee),
      Hex.uint(3) -> Hex.uint(100000),
      Hex.uint(5) -> Hex.map(
        Hex.bytes(accountBytes) -> Hex.uint(first),
        Hex.bytes(accountBytes) -> Hex.uint(reward)
      )
    )
    val bytes = Bytes.fromHex(raw).fold(e => fail(e.toString), identity)
    val body = TxBody.decode(bytes, wire).fold(e => fail(s"$wire: $e"), identity)
    assertEquals(body.raw, bytes)
    assertEquals(body.id, Blake2b.hash256.hash(bytes))
    body

  private def cases[E <: EraTag](name: String, major: Int, wire: Era)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Unit =
    def run(body: TxBody): Either[LedgerFailure, Counted[LedgerState[E]]] =
      val transaction: Tx = tx(body, Vector(alice, bob))
      Sts(ShelleyLedger.sts[E])(
        TestLedger.globals,
        ShelleyLedgerEnv(TestLedger.env, ChainAccountState(Coin.zero, Coin.zero)),
        Counted(state[E](major), PlutusStats.zero),
        transaction
      )

    test(s"$name: paying a repeated account's full balance twice is not conserved") {
      // `decodeMapByKey` uses Map.fromList below version 9, so consumed value counts the
      // account once (cardano-ledger f649f975, Binary/Decoding/Decoder.hs:815-823).
      val body = decoded(wire, reward, 2 * reward)
      assertEquals(
        run(body),
        Left(
          LedgerFailure.Rejected(
            body.id,
            PredicateFailure.utxo(
              UtxoFailure.ValueNotConserved(
                Mismatch(Value.coin(funds + reward), Value.coin(funds + 2 * reward))
              )
            )
          )
        )
      )
    }

    test(s"$name: native transaction construction cannot count a withdrawal account twice") {
      val decodedBody = decoded(wire, reward, 2 * reward)
      val body = decodedBody.copy(withdrawals = Map(account -> reward, account -> reward))
      assertEquals(body.raw, decodedBody.raw)
      assertEquals(body.id, decodedBody.id)
      assertEquals(
        run(body),
        Left(
          LedgerFailure.Rejected(
            body.id,
            PredicateFailure.utxo(
              UtxoFailure.ValueNotConserved(
                Mismatch(Value.coin(funds + reward), Value.coin(funds + 2 * reward))
              )
            )
          )
        )
      )
    }

    test(s"$name: the last amount drains the account once and preserves value") {
      val body = decoded(wire, reward + 1, reward)
      val after = run(body).fold(e => fail(e.toString), identity).state
      assertEquals(body.withdrawals, Map(account -> reward))
      assertEquals(
        ShelleyCertLens.states.get(pre.cert.get(after)).get(stake).map(_.balance.toBigInt),
        Some(BigInt(0))
      )
      assertEquals(after.utxo.deposited.value, deposit)
      assertEquals(after.utxo.fees.value, fee)
      assertEquals(after.utxo.utxo.utxo.coin, funds + reward - fee)
      assertEquals(after.utxo.utxo.utxo.count, 1L)
      assertEquals(after.utxo.utxo.utxo.get(TxIn(body.id, 0)), body.outputs.headOption)
    }

  cases[Shelley]("Shelley", 2, Era.Shelley)
  cases[Allegra]("Allegra", 3, Era.Allegra)
  cases[Mary]("Mary", 4, Era.Mary)
  cases[Alonzo]("Alonzo", 5, Era.Alonzo)
  cases[Babbage]("Babbage", 7, Era.Babbage)
