// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{Test, UtxoTests}

/** The Babbage and Conway minimum ada of an output is `(160 + size) * coinsPerUTxOByte` over the
  * `Sized` length of the output as it was decoded (`validateOutputTooSmallUTxO`,
  * `Babbage/Rules/Utxo.hs`; `decodeSized`, `Decoding/Sized.hs`; `babbageMinUTxOValue`,
  * `Babbage/TxOut.hs`; cardano-ledger f649f975). The map and the array form of an output count as
  * they arrived.
  */
class MinUtxoSizeSuite extends FunSuite:
  private val hash = "11" * 28
  // enterprise address, key payment, testnet (header 0x60): 29 bytes, `581d` + 29 bytes as a string
  private val address = s"581d60$hash"

  /** The coin as an unsigned 32-bit integer: 5 bytes whatever the value. */
  private def coin(n: Long): String = f"1a$n%08x"

  /** `{0: address, 1: coin}`: 1 + 1 + 31 + 1 + 5 = 39 bytes. */
  private def mapForm(n: Long): Bytes = bytes(s"a200${address}01${coin(n)}")

  /** `[address, coin]`: 1 + 31 + 5 = 37 bytes. */
  private def arrayForm(n: Long): Bytes = bytes(s"82${address}${coin(n)}")

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  private def decode(raw: Bytes): TxOut =
    TxOut.fromBytes(raw).fold(e => fail(e.toString), identity)

  private val perByte = TestLedger.params(7).coinsPerUTxOByte

  /** The failures of `validateOutputTooSmallUTxO` for the one output, in order. */
  private def checked(major: Int, out: TxOut): Either[List[PredicateFailure], Unit] =
    Test
      .failures(UtxoTests.outputTooSmall(TestLedger.params(major), Vector(out)))
      .fold(Right(()))(failures => Left(failures.toNonEmptyList.toList))

  List(7, 9).foreach { major =>
    test(s"map form: the minimum is over the original 39 bytes (protocol $major)") {
      val minimum = (160 + 39) * perByte
      assertEquals(decode(mapForm(0)).ledgerSize, 39)
      assertEquals(checked(major, decode(mapForm(minimum.toLong))), Right(()))
      val below = decode(mapForm(minimum.toLong - 1))
      assertEquals(
        checked(major, below),
        Left(
          List(PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(Vector(below -> minimum))))
        )
      )
    }

    test(s"map form: two bytes of the old normalisation are not subtracted (protocol $major)") {
      // the minimum of a 37 byte output was enough while the map form lost two bytes
      val old = (160 + 37) * perByte
      val out = decode(mapForm(old.toLong))
      assertEquals(
        checked(major, out),
        Left(
          List(
            PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(Vector(out -> (199 * perByte))))
          )
        )
      )
    }

    test(s"array form: the minimum is over the original 37 bytes (protocol $major)") {
      val minimum = (160 + 37) * perByte
      assertEquals(decode(arrayForm(0)).ledgerSize, 37)
      assertEquals(checked(major, decode(arrayForm(minimum.toLong))), Right(()))
      val below = decode(arrayForm(minimum.toLong - 1))
      assertEquals(
        checked(major, below),
        Left(
          List(PredicateFailure.utxo(UtxoFailure.BabbageOutputTooSmall(Vector(below -> minimum))))
        )
      )
    }
  }
