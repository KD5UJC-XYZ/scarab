// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.mtl.Handle
import fs2.io.file.Path
import java.nio.file.Files
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.storage.haskell.state.UtxoCodecs

/** The output of mainnet transaction 369826b2f60b443d (slot 39,249,764, Mary), whose address has 21
  * bytes after it, through every place the node keeps an output: the two UTxO stores, the Haskell
  * ledger-state codec of the UTxO and the node's own codec. The address in the set is the 57 bytes
  * that the reference keeps (`MainnetGarbageAddressSuite`), and a later transaction that spends the
  * output finds it by its input.
  */
class MainnetGarbageAddressStoreSuite extends FunSuite:
  private val block: Block.Shelley =
    val hex = Files
      .readString(java.nio.file.Path.of("fixtures/mainnet/mary-garbage-address-block.hex"))
      .trim
    Block.decode(Bytes.fromHex(hex).fold(e => fail(e.toString), identity), BigInt(21600)) match
      case Right(b: Block.Shelley) => b
      case other                   => fail(s"unexpected $other")

  private val tx: Tx =
    block.transactions
      .map(Tx.fromBlock(_, block.era).fold(e => fail(e.toString), identity))
      .find(_.body.id.hex.startsWith("369826b2f60b443d"))
      .getOrElse(fail("transaction 369826b2f60b443d is not in the block"))

  private val input = TxIn(tx.body.id, 0)
  private val output = tx.body.outputs.head

  private def orFail[A](program: Handle[IO, StorageError] ?=> IO[A]): A =
    Handle
      .allow[StorageError](program)
      .rescue(e => IO.raiseError(new RuntimeException(e.message)))
      .unsafeRunSync()

  test("the set holds the address of 57 bytes, not the 78 of the output") {
    assertEquals(output.address.raw.size, 57)
    assert(output.raw.size > 78)
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
    val dir = Files.createTempDirectory("garbage-address-store-suite")
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

  test("the value coding of the stores keeps the output, address and original bytes") {
    val coded = UtxoStore.Coding.decodeValue(UtxoStore.Coding.encodeValue(output))
    assertEquals(coded, Right(output))
    assertEquals(coded.map(_.raw), Right(output.raw))
  }

  test("the Haskell ledger-state codec writes the address of 57 bytes and reads it back") {
    val encoded = UtxoCodecs.utxo.encodeUnchecked(Map(input -> output))
    assert(encoded.hex.contains("5839" + output.address.raw.hex), encoded.hex)
    assert(!encoded.hex.contains("cb57afb0b35fc89c"), encoded.hex)
    val back = UtxoCodecs.utxo.decodeBytes(encoded).fold(e => fail(e.toString), identity)
    assertEquals(back.keySet, Set(input))
    assertEquals(back(input).address, output.address)
    assertEquals(back(input).value, output.value)
  }
