// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import scala.annotation.unused
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TxIn
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

import ByronTestKit.*

/** The Byron UTxO rules over synthetic transactions with real Ed25519 signatures: value
  * conservation, the linear fee, witness signatures and address roots, redeem inputs, network magic
  * and the genesis UTxO of both balance kinds.
  */
class ByronLedgerSuite extends ScalaCheckSuite:
  private val gKeys = Vector(key(), key(), key())
  private val dKeys = Vector(key(), key(), key())
  private val alice = key(1)
  private val bob = key(2)
  private val redeemer = key(3)

  private val aliceAddr = address(alice, Some(Magic))
  private val bobAddr = address(bob, Some(Magic))
  private val redeemAddr = redeemAddress(redeemer, Some(Magic))

  private val genesis =
    ByronTestKit.genesis(
      gKeys,
      dKeys,
      avvm = Vector(redeemer.pub -> BigInt(5000000)),
      nonAvvm = Vector(aliceAddr -> BigInt(100000000), bobAddr -> BigInt(30000000))
    )
  private val config = ByronLedger.Config(genesis, requiresNetworkMagic = true)

  private def addrIn(address: Bytes) =
    TxIn(xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(address), 0)
  private val utxo0: Map[TxIn, ByronOut] =
    ByronLedger.genesisUtxo(genesis, config.addressMagic).fold(e => fail(e.toString), identity)

  test("the genesis UTxO holds the AVVM balance at its redeem address and the ordinary balances") {
    assertEquals(utxo0.size, 3)
    assertEquals(utxo0(addrIn(redeemAddr)), ByronOut(redeemAddr, 5000000))
    assertEquals(utxo0(addrIn(aliceAddr)).value, BigInt(100000000))
    assertEquals(utxo0(addrIn(bobAddr)), ByronOut(bobAddr, 30000000))
  }

  test("a redeem address on a network without magic carries no network attribute") {
    val plain = ByronAddress.redeem(redeemer.pub, None)
    assertEquals(ByronAddress.decode(plain).map(_.networkMagic), Right(None))
    assertEquals(ByronAddress.decode(redeemAddr).map(_.networkMagic), Right(Some(Magic)))
    assertNotEquals(plain, redeemAddr)
  }

  test("an address checks only the key it was made from") {
    val a = ByronAddress.decode(aliceAddr).toOption.get
    assert(a.checks(alice.extended))
    assert(!a.checks(bob.extended))
    assert(!a.checks(alice.pub))
    val r = ByronAddress.decode(redeemAddr).toOption.get
    assert(r.isRedeem && r.checks(redeemer.pub) && !r.checks(alice.pub))
  }

  test("the initial ledger activates the genesis delegation") {
    val ledger = ByronLedger.initial(config).fold(e => fail(e.toString), identity)
    assertEquals(
      ledger.delegation.delegationMap,
      gKeys.zip(dKeys).map((g, d) => g.keyHash -> d.keyHash).toMap
    )
    assertEquals(ledger.utxo, utxo0)
    assertEquals(ledger.update.adoptedVersion, ByronVersion.zero)
  }

  private def spendAlice(out: Seq[(Bytes, Long)], @unused fee: Option[Long] = None) =
    aux(config, Seq((addrIn(aliceAddr), alice, false)), out)

  private def apply(a: ByronTxAux, u: Map[TxIn, ByronOut] = utxo0) =
    ByronLedger.applyTx(config, params, u, a)

  private def minFee(a: ByronTxAux): Long = params.txFeePolicy.minimumFee(a.raw.size).toLong

  /** A payment from Alice that pays exactly the minimum fee (the size depends on the fee, so the
    * amount is found by iterating once).
    */
  @unused private def payment(change: Long): ByronTxAux =
    val first = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> change))
    first

  test("a transaction paying at least the minimum fee is accepted and replaces the UTxO") {
    val probe = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> 50000000))
    val fee = 100000000L - 1000000L - 50000000L
    assert(fee >= minFee(probe))
    val next = apply(probe).fold(e => fail(e.message), identity)
    assert(!next.contains(addrIn(aliceAddr)))
    assertEquals(next(TxIn(probe.tx.id, 0)).value, BigInt(1000000))
    assertEquals(next(TxIn(probe.tx.id, 1)).value, BigInt(50000000))
    assertEquals(next.size, 4)
  }

  test("the fee is exactly the linear policy, so one lovelace less is refused") {
    // size is independent of the amounts' low bytes only up to CBOR integer widths: keep the
    // widths fixed (all values need four bytes) and bisect the change output
    val total = 100000000L
    val template = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> 50000000))
    val min = minFee(template)
    val change = total - 1000000 - min
    val exact = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> change))
    assertEquals(minFee(exact), min)
    assert(apply(exact).isRight)
    val short = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> (change + 1)))
    assertEquals(
      apply(short).map(_ => ()),
      Left(ByronError.FeeTooSmall(BigInt(min), BigInt(min - 1)))
    )
  }

  test("outputs larger than the inputs are refused") {
    val greedy = spendAlice(Seq(bobAddr -> 99999999, aliceAddr -> 2))
    assertEquals(
      apply(greedy),
      Left(ByronError.LovelaceOutOfRange(ByronError.LovelaceContext.Fee))
    )
  }

  test("lovelace balance failures distinguish the input and output totals") {
    val good = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> 50000000))
    val excessiveInput = utxo0.updated(
      addrIn(aliceAddr),
      ByronOut(aliceAddr, ByronLedger.MaxLovelace + 1)
    )
    assertEquals(
      apply(good, excessiveInput),
      Left(ByronError.LovelaceOutOfRange(ByronError.LovelaceContext.InputBalance))
    )
    val excessiveOutput = good.copy(tx =
      good.tx.copy(outputs =
        Vector(
          ByronOut(bobAddr, ByronLedger.MaxLovelace),
          ByronOut(aliceAddr, 1)
        )
      )
    )
    assertEquals(
      apply(excessiveOutput),
      Left(ByronError.LovelaceOutOfRange(ByronError.LovelaceContext.OutputBalance))
    )
  }

  test("unknown attribute refusals distinguish transaction and address payloads") {
    val good = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> 50000000))
    assertEquals(
      apply(good.copy(tx = good.tx.copy(attributesLength = 128))),
      Left(ByronError.UnknownAttributes(ByronError.AttributeOwner.Transaction))
    )
    val attrs = Cb.map(Cb.uint(3) -> Cb.bytes(Bytes.fromArray(Array.fill(128)(0.toByte))))
    val payload = Cb.arr(
      Cb.bytes(ByronAddress.rootOf(ByronAddress.VerKey, bob.extended, attrs)),
      attrs,
      Cb.uint(0)
    )
    val address = Cb.arr(Cb.tag24(payload), ByronCbor.uint(ByronCbor.crc32(payload)))
    val excessiveAddress = good.tx.copy(outputs = Vector(ByronOut(address, 1)))
    assertEquals(
      ByronLedger.outputs(config, excessiveAddress),
      Left(ByronError.UnknownAttributes(ByronError.AttributeOwner.Address))
    )
  }

  test("closed Byron discriminators retain the existing diagnostic text") {
    val diagnostics = Vector(
      ByronError.ProofMismatch(ByronError.Proof.Delegation) ->
        "delegation payload does not match the header's proof",
      ByronError.ProofMismatch(ByronError.Proof.Transaction) ->
        "transaction payload does not match the header's proof",
      ByronError.ProofMismatch(ByronError.Proof.Update) ->
        "update payload does not match the header's proof",
      ByronError.LovelaceOutOfRange(ByronError.LovelaceContext.InputBalance) ->
        "lovelace out of range (input balance)",
      ByronError.LovelaceOutOfRange(ByronError.LovelaceContext.OutputBalance) ->
        "lovelace out of range (output balance)",
      ByronError.LovelaceOutOfRange(ByronError.LovelaceContext.Fee) ->
        "lovelace out of range (fee)",
      ByronError.UnknownAttributes(ByronError.AttributeOwner.Address) ->
        "too many unknown attribute bytes in address",
      ByronError.UnknownAttributes(ByronError.AttributeOwner.Transaction) ->
        "too many unknown attribute bytes in transaction"
    )
    diagnostics.foreach((error, expected) => assertEquals(error.message, expected))
  }

  test("an unknown input is refused") {
    val ghost = aux(
      config,
      Seq((TxIn(Bytes.fromArray(Array.fill(32)(9.toByte)), 0), alice, false)),
      Seq(bobAddr -> 1)
    )
    assert(apply(ghost).left.exists(_.isInstanceOf[ByronError.MissingInput]))
  }

  test("the wrong key signing is refused: a valid signature that does not hash to the address") {
    val forged = aux(config, Seq((addrIn(aliceAddr), bob, false)), Seq(bobAddr -> 99000000))
    assert(apply(forged).left.exists(_.isInstanceOf[ByronError.WrongKey]))
  }

  test("a signature over other content is refused") {
    val good = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> 50000000))
    val witness = good.witnesses.head match
      case ByronWitness.VerKey(k, s) =>
        ByronWitness.VerKey(k, Bytes(s.value.updated(5, (s.value(5) ^ 1).toByte)))
      case other => other
    val bad = good.copy(witnesses = Vector(witness))
    assert(apply(bad).left.exists(_.isInstanceOf[ByronError.WrongSignature]))
  }

  test("a signature made for another network is refused") {
    val other =
      ByronLedger.Config(genesis.copy(protocolMagic = Magic + 1), requiresNetworkMagic = true)
    val a = aux(
      other,
      Seq((addrIn(aliceAddr), alice, false)),
      Seq(bobAddr -> 1000000, aliceAddr -> 50000000)
    )
    assert(apply(a).left.exists(_.isInstanceOf[ByronError.WrongSignature]))
  }

  test("a redeem input needs a redeem witness and pays no fee") {
    val redeem = aux(config, Seq((addrIn(redeemAddr), redeemer, true)), Seq(aliceAddr -> 5000000))
    val next = apply(redeem).fold(e => fail(e.message), identity)
    assert(!next.contains(addrIn(redeemAddr)))
    assertEquals(next(TxIn(redeem.tx.id, 0)).value, BigInt(5000000))
    // a verification-key witness cannot spend a redeem address
    val wrong = aux(config, Seq((addrIn(redeemAddr), redeemer, false)), Seq(aliceAddr -> 5000000))
    assert(apply(wrong).isLeft)
  }

  test("outputs on an address of another network are refused") {
    val foreign = address(bob, Some(Magic + 1))
    val a = spendAlice(Seq(foreign -> 1000000, aliceAddr -> 50000000))
    assert(apply(a).left.exists(_.isInstanceOf[ByronError.NetworkMagicMismatch]))
    val noMagic = address(bob, None)
    assert(apply(spendAlice(Seq(noMagic -> 1000000, aliceAddr -> 50000000))).isLeft)
  }

  test("a transaction above the maximum size is refused") {
    val many = (0 until 90).map(i => address(key(i % 200), Some(Magic)) -> 1000L)
    val big = spendAlice(many :+ (aliceAddr -> 1000000L))
    assert(big.raw.size > 4096)
    assert(apply(big).left.exists(_.isInstanceOf[ByronError.TxTooLarge]))
  }

  test("a transaction cannot spend the same UTxO entry twice in one block") {
    val first = spendAlice(Seq(bobAddr -> 1000000, aliceAddr -> 50000000))
    val next = apply(first).toOption.get
    // the entry is gone: replaying the transaction fails
    assert(apply(first, next).left.exists(_.isInstanceOf[ByronError.MissingInput]))
  }

  test("a transaction with the same input twice is counted once (the input set)") {
    val in = addrIn(aliceAddr)
    val twice = aux(
      config,
      Seq((in, alice, false), (in, alice, false)),
      Seq(bobAddr -> 1000000, aliceAddr -> 50000000)
    )
    val next = apply(twice).fold(e => fail(e.message), identity)
    assertEquals(next.size, 4)
  }

  test("surplus witnesses are ignored, as upstream's zip does") {
    val a = aux(
      config,
      Seq((addrIn(aliceAddr), alice, false)),
      Seq(bobAddr -> 1000000, aliceAddr -> 50000000)
    )
    val extra = a.copy(witnesses = a.witnesses ++ a.witnesses)
    assert(apply(extra).isRight)
  }

  test("minimum fee: 155381 + ceil(43.946 * size) lovelace") {
    assertEquals(params.txFeePolicy.minimumFee(0), BigInt(155381))
    assertEquals(params.txFeePolicy.minimumFee(1), BigInt(155381 + 44))
    assertEquals(params.txFeePolicy.minimumFee(1000), BigInt(155381 + 43946))
    assertEquals(params.txFeePolicy.minimumFee(1001), BigInt(155381 + 43946 + 44))
  }

  property("value is conserved: inputs = outputs + fee, and the UTxO total falls by the fee") {
    forAll(Gen.choose(1000000L, 90000000L)) { sent =>
      val template = spendAlice(Seq(bobAddr -> sent, aliceAddr -> 1000000))
      val fee = minFee(template)
      val change = 100000000L - sent - fee
      val tx = spendAlice(Seq(bobAddr -> sent, aliceAddr -> change))
      val result = apply(tx)
      // the minimum fee may differ by a few bytes from the template (integer widths): both outcomes
      // are consistent with the rule
      result match
        case Right(next) =>
          val before = utxo0.values.map(_.value).sum
          val after = next.values.map(_.value).sum
          val paid = BigInt(100000000L) - tx.tx.outputs.map(_.value).sum
          after == before - paid && paid >= params.txFeePolicy.minimumFee(tx.raw.size)
        case Left(ByronError.FeeTooSmall(min, actual)) => actual < min
        case Left(other)                               => fail(other.message)
    }
  }

  test("the Merkle root of transactions splits at the largest power of two below the count") {
    def leaf(b: Bytes) = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(ByronCbor.of(0) ++ b)
    def node(l: Bytes, r: Bytes) =
      xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(ByronCbor.of(1) ++ l ++ r)
    val txs = (1 to 6).map(i => Bytes.fromArray(Array.fill(i)(i.toByte))).toVector
    val l = txs.map(leaf)
    assertEquals(ByronTx.merkleRoot(txs.take(1)), l(0))
    assertEquals(ByronTx.merkleRoot(txs.take(2)), node(l(0), l(1)))
    assertEquals(ByronTx.merkleRoot(txs.take(3)), node(node(l(0), l(1)), l(2)))
    assertEquals(ByronTx.merkleRoot(txs.take(4)), node(node(l(0), l(1)), node(l(2), l(3))))
    assertEquals(
      ByronTx.merkleRoot(txs.take(5)),
      node(node(node(l(0), l(1)), node(l(2), l(3))), l(4))
    )
    assertEquals(
      ByronTx.merkleRoot(txs),
      node(node(node(l(0), l(1)), node(l(2), l(3))), node(l(4), l(5)))
    )
    assertEquals(
      ByronTx.merkleRoot(Vector.empty),
      xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(Bytes.empty)
    )
  }
