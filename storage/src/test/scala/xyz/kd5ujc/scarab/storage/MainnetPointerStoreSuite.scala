// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import fs2.io.file.Path
import java.nio.file.Files
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, UtxoCodecs, Versions}
import xyz.kd5ujc.scarab.protocol.Era

/** The output of mainnet transaction 9a47e091709e4fe1 (slot 26,366,727, Mary), whose pointer
  * address names slot 16,292,793,057, transaction 1,011,302 and certificate 20 (B29,
  * `MainnetPointerSuite`), through every place the node keeps an output: the two UTxO stores, the
  * value coding, the Haskell UTxO codec and the pointer codec of the Haskell state. The reference
  * keeps the output with the address written again, `Ptr 0 0 0`.
  */
class MainnetPointerStoreSuite extends FunSuite:
  private val body: TxBody =
    val hex = Files
      .readString(java.nio.file.Path.of("fixtures/mainnet/mary-pointer-tx-9a47e091.hex"))
      .trim
    val tx = Cbor
      .decode(Bytes.fromHex(hex).fold(e => fail(e.toString), identity))
      .fold(e => fail(e.toString), identity)
    val parts = tx.value match
      case Value.Arr(xs) => xs
      case other         => fail(s"unexpected $other")
    TxBody
      .decode(parts.head.original, Address.Mode.of(Era.Mary))
      .fold(e => fail(e.toString), identity)

  private val input = TxIn(body.id, 0)
  private val output = body.outputs.head

  private def orFail[A](program: Handle[IO, StorageError] ?=> IO[A]): A =
    Handle
      .allow[StorageError](program)
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

  test("the set holds the address written again, the output its own bytes") {
    assertEquals(output.address.raw.size, 32)
    assert(output.raw.hex.contains("bcd9819561bddc6614"))
  }

  test("the memory store returns the output by its input") {
    val read = orFail(
      MemoryUtxoStore[IO].flatMap { store =>
        store.write(Vector(input -> Some(output)), 1) *> store.read(Vector(input))
      }
    )
    assertEquals(read, Map(input -> output))
  }

  test("the rocksdb store returns the output by its input, and after a checkpoint") {
    val dir = Files.createTempDirectory("pointer-store-suite")
    val read = orFail(
      RocksUtxoStore
        .resource[IO](Path.fromNioPath(dir) / "live", 8L * 1024 * 1024)
        .use { store =>
          for
            _ <- store.write(Vector(input -> Some(output)), 1)
            first <- store.read(Vector(input))
            _ <- store.checkpoint(Path.fromNioPath(dir) / "cp")
            _ <- store.write(Vector(input -> None), 2)
            gone <- store.read(Vector(input))
            _ <- store.restore(Path.fromNioPath(dir) / "cp")
            again <- store.all
          yield (first, gone, again)
        }
        .guarantee(IO.blocking(RocksUtxoStore.deleteTree(dir)))
    )
    assertEquals(read, (Map(input -> output), Map.empty[TxIn, TxOut], Map(input -> output)))
  }

  test("the value coding keeps the output as it came, and reads it back to the same output") {
    val encoded = UtxoStore.Coding.encodeValue(output)
    // the original bytes, not the structural codec: the output reads back to itself
    assertEquals(encoded.head, 1.toByte)
    val coded = UtxoStore.Coding.decodeValue(encoded)
    assertEquals(coded, Right(output))
    assertEquals(coded.map(_.raw), Right(output.raw))
  }

  test("an entry written before B29, with the original bytes, reads back normalised") {
    // a store of the follower from before the fix holds tag 1 and the output as it came
    val before = 1.toByte +: output.raw.toArray
    assertEquals(UtxoStore.Coding.decodeValue(before), Right(output))
  }

  test("the Haskell UTxO codec writes the address of 32 bytes and reads it back") {
    val encoded = UtxoCodecs.utxo.encodeUnchecked(Map(input -> output))
    assert(encoded.hex.contains("5820" + output.address.raw.hex), encoded.hex)
    assert(!encoded.hex.contains("bcd9819561bddc66"), encoded.hex)
    val back = UtxoCodecs.utxo.decodeBytes(encoded).fold(e => fail(e.toString), identity)
    assertEquals(back.keySet, Set(input))
    assertEquals(back(input).address, output.address)
    assertEquals(back(input).value, output.value)
  }

  test("the pointer codec of the Haskell state writes the pointer of the state") {
    given xyz.kd5ujc.scarab.ledger.state.ProtVer = Versions.eraLow(ShelleyBasedEra.SConway)
    val ptr = output.address.kind match
      case Address.Kind.Shelley(_, _, _, Some(p)) => p
      case other                                  => fail(s"unexpected $other")
    val codec = HaskellStateCodecs.ptr
    assertEquals(codec.check(ptr), None)
    // the pointer as the output came is what the codec refused before B29
    assert(codec.check(Ptr(BigInt("16292793057"), 1011302, 20)).isDefined)
    val bytes = codec.encodeUnchecked(ptr)
    assertEquals(codec.decodeBytes(bytes), Right(ptr))
  }
