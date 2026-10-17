// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.protocol.Era

/** Transaction 9a47e091709e4fe1 (mainnet block 5,567,164, slot 26,366,727, epoch 258, Mary,
  * protocol version 4.0) pays 50 ada to a pointer address whose pointer is slot 16,292,793,057,
  * transaction index 1,011,302 and certificate index 20: more than a `Word32` and a `Word16`. Every
  * ledger snapshot of the follower from block 5,581,257 on was refused for it (B29).
  *
  * The decoder of a protocol version below 9 normalises such a pointer to `Ptr 0 0 0`
  * (`mkPtrNormalized`, `Credential.hs` 270-285, through `decodePtrLenient`, `Address.hs` 780-788)
  * and the address is kept as the pointer is written again (`cAddrNormalized`, `Address.hs`
  * 470-476); from version 9 (Conway) the address is refused (`decodePtr`, 769-777), and the
  * naturals of a pointer are read with a bound on their groups (`decodeVariableLengthWord32` and
  * `..16`, 832-870). cardano-ledger-core 1.21.0.0. The fixture is the transaction as Koios gives it
  * (`fixtures/mainnet/README.md`).
  */
class MainnetPointerSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)

  private val payment = "2813b99a80cfb4024374bd0f502959485aa56e0648564ff805f2e51b"
  private val original = "bcd9819561bddc6614"

  private val body: Bytes =
    val hex = Files.readString(Path.of("fixtures/mainnet/mary-pointer-tx-9a47e091.hex")).trim
    val tx =
      Cbor.decode(bytes(hex)).flatMap(CborRead.array(_, "transaction")).fold(fail(_), identity)
    tx.head.original

  private def decoded(era: Era): Either[String, TxBody] = TxBody.decode(body, Address.Mode.of(era))

  private val mary: TxBody = decoded(Era.Mary).fold(e => fail(e), identity)
  private val output: TxOut = mary.outputs.head

  private def shelley(ptr: Ptr): Address.Kind =
    Address.Kind.Shelley(1, Credential.Key(bytes(payment)), None, Some(ptr))

  test("the fixture is transaction 9a47e091 and its output 0 carries the pointer as it came") {
    assertEquals(
      mary.id.hex,
      "9a47e091709e4fe1a8ba1291bc8cfd0c0ed0bb9220688b3ccfad5f2d961e8d8e"
    )
    assert(output.raw.hex.contains(s"41$payment$original"), output.raw.hex)
    assertEquals(output.value.coin, BigInt(50000000))
  }

  test("the pointer of the fixture fits neither its slot nor its indexes") {
    // 16292793057 needs 34 bits, 1011302 needs 20
    val ptr = Ptr(BigInt("16292793057"), 1011302, 20)
    assertEquals((ptr.slot.bitLength, ptr.txIx.bitLength, ptr.certIx.bitLength), (34, 20, 5))
    assert(!ptr.fits)
    assertEquals(ptr.normalised, Ptr.zero)
  }

  test("Mary reads the pointer as Ptr 0 0 0 and keeps the address written again") {
    assertEquals(output.address.kind, shelley(Ptr.zero))
    assertEquals(output.address.raw.hex, s"41${payment}000000")
    assertEquals(output.address.raw.size, 32)
  }

  test("the transaction body and the output keep the bytes as they came") {
    assertEquals(mary.raw, body)
    assert(output.raw.size > output.address.raw.size)
  }

  test("every era below Conway normalises it the same way") {
    List(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage).foreach { era =>
      assertEquals(
        decoded(era).map(_.outputs.head.address),
        Right(output.address),
        era.toString
      )
    }
  }

  test("Conway refuses it, as the strict decoder of version 9 does") {
    assertEquals(decoded(Era.Conway).map(_ => ()), Left("pointer address: pointer out of range"))
  }

  test("the stake of the output is counted under Ptr 0 0 0, which names no account") {
    val utxo = Utxo.empty ++ Map(TxIn(mary.id, 0) -> output)
    assertEquals(utxo.ptrStake, Map(Ptr.zero -> BigInt(50000000)))
    // the other output is a base address
    val both = Utxo.empty ++ mary.outputs.zipWithIndex.map((o, i) => TxIn(mary.id, i) -> o)
    assertEquals(both.ptrStake, Map(Ptr.zero -> BigInt(50000000)))
    assertEquals(both.stake.values.sum, mary.outputs(1).value.coin)
  }

  test("a spend of the output removes what it added") {
    val in = TxIn(mary.id, 0)
    val spent = (Utxo.empty ++ Map(in -> output)) -- Vector(in)
    assertEquals(spent.ptrStake.values.sum, BigInt(0))
  }

  test("what a script sees of the address is the normalised pointer") {
    output.address.kind match
      case Address.Kind.Shelley(_, _, None, Some(ptr)) =>
        assertEquals((ptr.slot, ptr.txIx, ptr.certIx), (BigInt(0), BigInt(0), BigInt(0)))
      case other => fail(s"unexpected $other")
  }

  // The addresses below are tested in every mode.
  private def pointer(groups: String): Bytes = bytes(s"41$payment$groups")

  private def read(groups: String, mode: Address.Mode): Either[String, (String, Address.Kind)] =
    Address.decode(pointer(groups), mode).map(a => (a.raw.hex, a.kind))

  test("a pointer that fits is the same in every mode") {
    val fits = Right((s"41${payment}81010203", shelley(Ptr(129, 2, 3))))
    Address.Mode.values.foreach(mode => assertEquals(read("81010203", mode), fits, mode.toString))
  }

  test("a pointer written with more groups than needed is written again with the shortest") {
    // 80 01 is 1, 80 80 02 is 2, 80 80 03 is 3
    val shortest = Right((s"41${payment}010203", shelley(Ptr(1, 2, 3))))
    Address.Mode.values.foreach { mode =>
      assertEquals(read("8001" + "808002" + "808003", mode), shortest, mode.toString)
    }
  }

  test("a field that does not fit makes the whole pointer Ptr 0 0 0 before Conway") {
    val zero = Right((s"41${payment}000000", shelley(Ptr.zero)))
    List(
      "9080808000" + "0101", // slot 2^32
      "01" + "848000" + "01", // transaction index 2^16
      "01" + "01" + "848000" // certificate index 2^16
    ).foreach { groups =>
      assertEquals(read(groups, Address.Mode.Rigorous), zero, groups)
      assertEquals(read(groups, Address.Mode.Backwards), zero, groups)
    }
  }

  test("the bounds are the last value of a field and the one after") {
    def kind(groups: String) = read(groups, Address.Mode.Rigorous).map(_._2)
    assertEquals(kind("8fffffff7f" + "0101"), Right(shelley(Ptr(BigInt(4294967295L), 1, 1))))
    assertEquals(kind("9080808000" + "0101"), Right(shelley(Ptr.zero)))
    assertEquals(kind("01" + "83ff7f" + "01"), Right(shelley(Ptr(1, BigInt(65535), 1))))
    assertEquals(kind("01" + "848000" + "01"), Right(shelley(Ptr.zero)))
  }

  test("a natural beyond 64 bits wraps as a Word64 before it is judged") {
    def kind(groups: String) = read(groups, Address.Mode.Rigorous).map(_._2)
    // 2^64 + 5 and 2^64 are ten groups: the top one is 2 (bit 64), then eight of 0
    assertEquals(kind("82" + "80" * 8 + "05" + "0101"), Right(shelley(Ptr(5, 1, 1))))
    assertEquals(kind("82" + "80" * 8 + "00" + "0101"), Right(shelley(Ptr(0, 1, 1))))
    // a run of continuation bytes, as the fuzzer found, decodes in linear time and wraps
    val long = "81" * 100000 + "01"
    val started = System.nanoTime()
    assert(kind(long + "0101").isRight)
    assert((System.nanoTime() - started) / 1000000 < 1000)
  }

  test("Conway reads the naturals with the bounds of the strict decoder") {
    def strict(groups: String) = read(groups, Address.Mode.Strict).map(_._2)
    val refused = Left("pointer address: pointer out of range")
    assertEquals(strict("8fffffff7f" + "0101"), Right(shelley(Ptr(BigInt(4294967295L), 1, 1))))
    // six groups for a value that fits a Word32 are too many bytes
    assertEquals(strict("808080808001" + "0101"), refused)
    // five groups with a leading 0x80 are accepted: the value is 1
    assertEquals(strict("8080808001" + "0101"), Right(shelley(Ptr(1, 1, 1))))
    // four groups for an index are too many, and three that exceed 16 bits are too large
    assertEquals(strict("01" + "80808001" + "01"), refused)
    assertEquals(strict("01" + "848000" + "01"), refused)
    assertEquals(strict("01" + "808001" + "01"), Right(shelley(Ptr(1, 1, 1))))
  }

  test("a truncated pointer is refused in every mode") {
    Address.Mode.values.foreach { mode =>
      assertEquals(read("8101", mode).map(_ => ()), Left("pointer address: truncated natural"))
      assertEquals(read("81018102", mode).map(_ => ()), Left("pointer address: truncated natural"))
    }
  }

  test("bytes after the pointer are dropped before Babbage and refused from it") {
    assertEquals(
      read("81010203ff", Address.Mode.Backwards).map(_._1),
      Right(s"41${payment}81010203")
    )
    List(Address.Mode.Rigorous, Address.Mode.Strict).foreach { mode =>
      assertEquals(read("81010203ff", mode).map(_ => ()), Left("pointer address: trailing bytes"))
    }
  }

  test("the output size is the one of the address the ledger holds") {
    // [address, 1 ada] with the address of the fixture: 38 bytes as it came, 32 written again
    val came = bytes(s"82582641$payment$original" + "1a000f4240")
    val out = TxOut.fromBytes(came, Address.Mode.Rigorous).fold(e => fail(e), identity)
    assertEquals(out.address.raw.size, 32)
    assertEquals(out.raw.size - out.ledgerSize, 6)
    // an address already written as the ledger writes it is not changed
    val plain = TxOut(
      Address.decode(pointer("81010203")).fold(e => fail(e), identity),
      Value.coin(1000000)
    )
    assertEquals(plain.ledgerSize, plain.raw.size)
  }
