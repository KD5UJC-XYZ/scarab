// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

/** The mainnet block at slot 39,249,764 (epoch 288, Mary, hash `24dcc9a0ecbcbd9c...`) holds
  * transaction 369826b2f60b443d, whose output 0 pays a base address followed by 21 bytes that
  * belong to no address (78 bytes in all, 57 of them the address). The sixth mainnet sync from
  * genesis stopped there with `base address length`. The reference keeps no check that the bytes of
  * an address are all used before Babbage (`fromCborBackwardsBothAddr`, cardano-ledger-core
  * 1.21.0.0, `Cardano/Ledger/Address.hs` 495-503, chosen by `fromCborBothAddr`, 459-477, for a
  * decoder version below 7) and keeps the address without the bytes after it. The fixture is the
  * block as the relay sent it (`fixtures/mainnet/README.md`).
  */
class MainnetGarbageAddressSuite extends FunSuite:
  private val EpochSlots: BigInt = BigInt(21600)

  private val block: Block.Shelley =
    val hex = Files.readString(Path.of("fixtures/mainnet/mary-garbage-address-block.hex")).trim
    Block.decode(Bytes.fromHex(hex).fold(e => fail(e), identity), EpochSlots) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"unexpected $other")

  private val transaction: Block.Transaction =
    block.transactions
      .find(t => Blake2b.hash256.hash(t.body).hex.startsWith("369826b2f60b443d"))
      .getOrElse(fail("transaction 369826b2f60b443d is not in the block"))

  private val payment = "5bad085057ac10ecc7060f7ac41edd6f63068d8963ef7d86ca58669e"
  private val stake = "5ecf2d283418a60be5a848a2380eb721000da1e0bbf39733134beca4"
  private val garbage = "cb57afb0b35fc89c63061c9914e055001a518c7516"

  private def tx(era: Era): Either[String, Tx] = Tx.fromBlock(transaction, era)

  private val mary: Tx = tx(Era.Mary).fold(e => fail(e), identity)
  private val output: TxOut = mary.body.outputs.head

  test("the fixture is the block at slot 39,249,764 in the Mary era") {
    assertEquals(block.header.slot, BigInt(39249764))
    assert(block.header.hash.hex.startsWith("24dcc9a0ecbcbd9c"), block.header.hash.hex)
    assertEquals(block.era, Era.Mary)
  }

  test("the output carries a base address of 57 bytes and 21 bytes after it") {
    val original = output.raw.hex
    assert(original.contains(s"01$payment$stake$garbage"), original)
    assert(original.contains("584e01"), "the byte string is 78 bytes long")
  }

  test("Mary reads the address as the 57 bytes and drops the rest, as the reference does") {
    assertEquals(output.address.raw.hex, s"01$payment$stake")
    assertEquals(
      output.address.kind,
      Address.Kind.Shelley(
        1,
        Credential.Key(Bytes.fromHex(payment).fold(fail(_), identity)),
        Some(Credential.Key(Bytes.fromHex(stake).fold(fail(_), identity))),
        None
      )
    )
    assertEquals(output.value.coin, BigInt(55801808))
  }

  test("the transaction id is the hash of the body as it came") {
    assertEquals(
      mary.body.id.hex,
      "369826b2f60b443d27966d222fa5bedc6b900326b77a09f15473d77f5ef2871c"
    )
    assertEquals(mary.body.raw, transaction.body)
    assertEquals(mary.size, 1L + transaction.body.size + transaction.witnesses.size + 1L)
  }

  test(
    "Shelley, Allegra and Mary read the address leniently, as the reference does for versions below 7"
  ) {
    List(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo).foreach { era =>
      assertEquals(tx(era).map(_.body.outputs.head.address.raw.size), Right(57), era.toString)
    }
  }

  test("from Babbage the address must take all its bytes: the same output is refused") {
    List(Era.Babbage, Era.Conway).foreach { era =>
      assertEquals(tx(era).map(_ => ()), Left("base address length"), era.toString)
    }
  }

  test("an output the other way, a base address of 56 bytes, is refused in every era") {
    val short = Bytes.fromHex(s"01$payment${stake.dropRight(2)}").fold(fail(_), identity)
    Address.Mode.values.foreach { mode =>
      assertEquals(Address.decode(short, mode).map(_ => ()), Left("short address"), mode.toString)
    }
  }

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)

  test("a pointer address is read up to its three naturals, the bytes after them are dropped") {
    val pointer = bytes(s"41${payment}81010203")
    val tail = bytes(s"41${payment}81010203ffeedd")
    assertEquals(
      Address.decode(tail, Address.Mode.Backwards).map(a => (a.raw.hex, a.kind)),
      Right(
        (
          s"41${payment}81010203",
          Address.Kind.Shelley(1, Credential.Key(bytes(payment)), None, Some(Ptr(129, 2, 3)))
        )
      )
    )
    assertEquals(Address.decode(pointer, Address.Mode.Backwards).map(_.raw.hex), Right(pointer.hex))
    assertEquals(
      Address.decode(tail, Address.Mode.Rigorous).map(_ => ()),
      Left("pointer address: trailing bytes")
    )
  }

  test("an enterprise address with bytes after it follows the same rule") {
    val enterprise = bytes(s"61${payment}0102")
    assertEquals(
      Address.decode(enterprise, Address.Mode.Backwards).map(_.raw.hex),
      Right(s"61$payment")
    )
    assertEquals(
      Address.decode(enterprise, Address.Mode.Rigorous).map(_ => ()),
      Left("enterprise address length")
    )
  }

  test("an address with its exact length is the same in either mode") {
    val base = bytes(s"01$payment$stake")
    assertEquals(
      Address.decode(base, Address.Mode.Backwards),
      Address.decode(base, Address.Mode.Rigorous)
    )
  }

  test("the stake of the output is counted under the base address credential") {
    val utxo = Utxo.empty ++ Map(TxIn(mary.body.id, 0) -> output)
    assertEquals(
      utxo.stake,
      Map[Credential, BigInt](Credential.Key(bytes(stake)) -> output.value.coin)
    )
    assert(utxo.contains(TxIn(mary.body.id, 0)))
  }

  test("the mode follows the era: below Babbage lenient, from Babbage rigorous") {
    assertEquals(
      Era.values.toList.filter(_ != Era.Byron).map(e => e -> Address.Mode.of(e)),
      List(
        Era.Shelley -> Address.Mode.Backwards,
        Era.Allegra -> Address.Mode.Backwards,
        Era.Mary -> Address.Mode.Backwards,
        Era.Alonzo -> Address.Mode.Backwards,
        Era.Babbage -> Address.Mode.Rigorous,
        Era.Conway -> Address.Mode.Rigorous
      )
    )
  }

  test("every transaction of the block decodes in its era") {
    block.transactions.foreach(t => assert(Tx.fromBlock(t, block.era).isRight))
  }
