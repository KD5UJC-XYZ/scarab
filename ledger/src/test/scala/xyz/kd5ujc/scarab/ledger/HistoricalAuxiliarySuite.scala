// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.protocol.Era

/** Every row of the independent Shelley/Allegra/Mary auxiliary corpus (#290) against Scarab's
  * shared reader and hash/presence predicate. The reference ran the unchanged cardano-ledger
  * f649f975 decoder, `validateTxAuxData` and the Utxow rule's `validateMetadata`; nothing is
  * excluded.
  */
class HistoricalAuxiliarySuite extends FunSuite:
  import ContextReferenceCases.{field, hex}

  private val rows: Vector[Json] =
    parse(Files.readString(Path.of("fixtures/reference/auxiliary-historical/auxiliary.json")))
      .fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("not an array"))

  private def era(row: Json): Era = field[String](row, "era") match
    case "Shelley" => Era.Shelley
    case "Allegra" => Era.Allegra
    case "Mary"    => Era.Mary
    case other     => fail(s"unknown era $other")

  /** A valid pre-Alonzo body (one input, one output) carrying an optional auxiliary-data hash. */
  private def body(hash: Option[Bytes]): Bytes =
    val input = "825820" + "00" * 32 + "00"
    val output = "82581d61" + "22" * 28 + "00"
    hex(
      (if hash.isDefined then "a5"
       else "a4") + "0081" + input + "0181" + output + "0200" + "031864" +
        hash.fold("")(h => "075820" + h.hex)
    )

  private def reference(row: Json, key: String): Json = field[Json](field[Json](row, "oracle"), key)

  private def failures(row: Json): Vector[String] =
    reference(row, "failures").asArray.getOrElse(fail("no failures")).map(_.asString.getOrElse(""))

  rows.foreach { row =>
    val name = field[String](row, "name")
    val major = field[Int](row, "protocolMajor")
    val decoded = field[Boolean](field[Json](row, "oracle"), "decoded")
    val mode = field[String](row, "mode")

    test(s"$name: Scarab matches the reference at the decoder/predicate boundary") {
      val bytes = hex(field[String](row, "cbor"))
      if mode == "aux" then
        // The shared reader, the block path and the predicate agree with the reference verdict.
        val valid = body(None)
        val block = Block.Transaction(valid, hex("a0"), Some(bytes), true)
        assertEquals(AuxiliaryScripts.read(bytes, major).isRight, decoded)
        assertEquals(Tx.fromBlock(block, era(row)).isRight, decoded)
        if decoded then
          val tx = Tx
            .fromBlock(
              block.copy(body = body(Some(Blake2b.hash256.hash(bytes)))),
              era(row)
            )
            .fold(error => fail(error.toString), identity)
          assertEquals(
            field[String](row, "expectedAuxHash"),
            Blake2b.hash256.hash(tx.auxiliary.getOrElse(fail("no auxiliary"))).hex
          )
          assertEquals(
            TxChecks.auxiliaryChecks(TestLedger.params(major), tx, TestLedger.env),
            Right(())
          )
          assertEquals(reference(row, "valid"), Json.True)
      else
        val parsed = Tx.fromEnvelope(bytes, era(row))
        assertEquals(parsed.isRight, decoded)
        parsed.foreach { tx =>
          val checked = TxChecks.auxiliaryChecks(TestLedger.params(major), tx, TestLedger.env)
          val wanted = failures(row)
          // The auxiliary bytes reach the predicate exactly as supplied.
          assertEquals(
            tx.auxiliary.map(_.hex),
            field[Option[String]](row, "auxiliary")
          )
          wanted match
            case Vector() => assertEquals(checked, Right(()))
            case Vector("MissingTxMetadata") =>
              assert(
                checked.left.exists {
                  case LedgerFailure.Rejected.Single(
                        _,
                        PredicateFailure.Utxow(_: UtxowFailure.MissingTxMetadata)
                      ) =>
                    true
                  case _ => false
                },
                checked.toString
              )
            case Vector("MissingTxBodyMetadataHash") =>
              assert(
                checked.left.exists {
                  case LedgerFailure.Rejected.Single(
                        _,
                        PredicateFailure.Utxow(_: UtxowFailure.MissingTxBodyMetadataHash)
                      ) =>
                    true
                  case _ => false
                },
                checked.toString
              )
            case Vector("ConflictingMetadataHash") =>
              assert(
                checked.left.exists {
                  case LedgerFailure.Rejected.Single(
                        _,
                        PredicateFailure.Utxow(_: UtxowFailure.ConflictingMetadataHash)
                      ) =>
                    true
                  case _ => false
                },
                checked.toString
              )
            case other => fail(s"reference failure set not modelled: $other")
        }
    }
  }

  test("a bad historical auxiliary refuses its transaction at block decoding, before any state") {
    val good = body(None)
    val block = Block.Shelley(
      Era.Mary,
      PreparedBlockFixture.header,
      Vector(
        Block.Transaction(good, hex("a0"), Some(hex("a0")), true),
        Block.Transaction(good, hex("a0"), Some(hex("d90103a0")), true)
      ),
      100,
      Bytes.empty
    )
    val decoded = rules.EraBlock.decode(block)
    assert(decoded(0).isRight)
    assert(decoded(1).left.exists(_.isInstanceOf[LedgerDecodeError.Expected]))
  }

  test("deep historical metadata and nested native scripts read on a 256KiB stack") {
    val metadata =
      Bytes(Vector(0xa1.toByte, 0.toByte) ++ Vector.fill(20000)(0x81.toByte) ++ Vector(0.toByte))
    val scripts = Bytes(
      Vector(0x82.toByte, 0xa0.toByte, 0x81.toByte) ++
        Vector.fill(5000)(Vector(0x82.toByte, 1.toByte, 0x81.toByte)).flatten ++
        Vector(0x82.toByte, 4.toByte, 7.toByte)
    )
    for protocol <- Vector(2, 3, 4) do
      assertEquals(
        PreprodDeepTx.onSmallStack(AuxiliaryScripts.read(metadata, protocol)),
        Right(Vector.empty)
      )
    for protocol <- Vector(3, 4) do
      assertEquals(
        PreprodDeepTx.onSmallStack(AuxiliaryScripts.read(scripts, protocol)),
        Right(Vector.empty)
      )
  }
