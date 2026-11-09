// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import io.circe.parser.parse
import munit.FunSuite

/** The Byron linear fee and parameter decoders against the pinned native ones. Where the reference
  * converts the scaled size into `Word64` modulo 2^64, the node gives no verdict
  * ([[TxFeePolicy.Error.ScaledOutOfRange]]); where the reference's `Word64` sum of the summand and
  * the scaled size wraps, both refuse it, and the node's refusal names the exact sum
  * (overflow-improvements.md#byron-linear-fee).
  */
class ByronParamsNativeSuite extends FunSuite:
  private val modulus = BigInt(1) << 64

  test("the signed ceiling outside a Word64 is no verdict, where the reference wraps it") {
    assertEquals(TxFeePolicy(0, -1).minimumFee(1), Right(BigInt(0)))
    // the reference wraps a ceiling of -1 to 2^64 - 1 and then refuses the sum as an overflow
    assertEquals(
      TxFeePolicy(0, -1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.ScaledOutOfRange(-1))
    )
    assertEquals(
      TxFeePolicy(1000000000, -1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.ScaledOutOfRange(-1))
    )
    // the reference wraps a ceiling of 2^64 to 0 and takes a fee of the summand alone
    assertEquals(
      TxFeePolicy(0, modulus * 1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.ScaledOutOfRange(modulus))
    )
    // below the boundary the reference's own overflow refusal stands
    assertEquals(
      TxFeePolicy(0, (modulus - 1) * 1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.Overflow(modulus - 1))
    )
    assertEquals(
      TxFeePolicy(2000000000, (modulus - 1) * 1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.Overflow(modulus + 1))
    )
    assertEquals(TxFeePolicy(0, 1000000000).minimumFee(7), Right(BigInt(7)))
    assertEquals(TxFeePolicy(0, 0).minimumFee(-1), Left(TxFeePolicy.Error.SizeTooSmall(BigInt(-1))))
    assertEquals(
      TxFeePolicy(0, 0).minimumFee(ByronLedger.MaxLovelace + 1),
      Left(TxFeePolicy.Error.SizeTooLarge(ByronLedger.MaxLovelace + 1))
    )
  }

  test("a scaled size out of range is no verdict on the block, never a refusal") {
    val error = xyz.kd5ujc.scarab.ledger.state.VocabError
      .OutOfRange(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.Coin, modulus)
    assertEquals(
      xyz.kd5ujc.scarab.ledger.BlockFailure
        .Byron(xyz.kd5ujc.scarab.ledger.ByronChainError.Ledger(ByronError.StateOutOfRange(error)))
        .stateOutOfRange,
      Some(error)
    )
    assertEquals(
      xyz.kd5ujc.scarab.ledger.BlockFailure
        .Byron(
          xyz.kd5ujc.scarab.ledger.ByronChainError
            .Ledger(ByronError.FeeCalculation(TxFeePolicy.Error.Overflow(1)))
        )
        .stateOutOfRange,
      None
    )
  }

  test("CBOR Nano uses ties-even rounding") {
    assertEquals(TxFeePolicy.fromNano(2500000000L, 0), Right(TxFeePolicy(2000000000L, 0)))
    assertEquals(TxFeePolicy.fromNano(3500000000L, 0), Right(TxFeePolicy(4000000000L, 0)))
    assertEquals(TxFeePolicy.fromNano(-500000000, 0), Right(TxFeePolicy(0, 0)))
  }

  test("all pinned native parameter decoder rows and every fee outcome agree") {
    import io.circe.{ACursor, Decoder}
    import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
    def field[A: Decoder](cursor: ACursor, name: String): A =
      cursor.get[A](name).fold(e => fail(e.message), identity)
    def raw(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.message), identity)
    def decoded(value: Bytes) = Cbor.decode(value).fold(e => fail(e.message), identity)
    def array(values: Bytes*): Bytes =
      ByronCbor.of(0x80 + values.size) ++ values.foldLeft(Bytes.empty)(_ ++ _)
    val empty = ByronParamsUpdate
      .decode(decoded(array(Vector.fill(14)(array())*)))
      .fold(e => fail(e.message), identity)
    val rows = Files
      .readString(Path.of("fixtures/reference/byron-params/native.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(line => parse(line).fold(e => fail(e.message), identity).hcursor)
      .toVector
    assertEquals(rows.size, 29)
    assertEquals(rows.count(field[Boolean](_, "accepted")), 21)
    val fees = rows.flatMap(_.get[Vector[io.circe.Json]]("fees").getOrElse(Vector.empty))
    assertEquals(fees.size, 66)
    rows.foreach { row =>
      val label = field[String](row, "label")
      val bytes = raw(field[String](row, "cbor"))
      val parameters = field[String](row, "kind") == "parameters"
      val encoded =
        if parameters then bytes
        else array(Vector.tabulate(14)(i => if i == 12 then array(bytes) else array())*)
      val result = ByronParamsUpdate.decode(decoded(encoded))
      if !field[Boolean](row, "accepted") then assert(result.isLeft, label)
      else if parameters then
        val value = Some(field[BigInt](row, "naturalValue"))
        val expected = field[Int](row, "field") match
          case 1     => empty.copy(slotDurationMs = value)
          case 2     => empty.copy(maxBlockSize = value)
          case 3     => empty.copy(maxHeaderSize = value)
          case 4     => empty.copy(maxTxSize = value)
          case 5     => empty.copy(maxProposalSize = value)
          case index => fail(s"unexpected native Natural field $index")
        assertEquals(result, Right(expected), label)
      else
        val numerator = field[BigInt](row, "multiplierNumerator") * TxFeePolicy.Nano
        val denominator = field[BigInt](row, "multiplierDenominator")
        assertEquals(numerator % denominator, BigInt(0), label)
        val policy = TxFeePolicy(
          field[BigInt](row, "summandLovelace") * TxFeePolicy.Nano,
          numerator / denominator
        )
        assertEquals(result, Right(empty.copy(txFeePolicy = Some(policy))), label)
        field[Vector[io.circe.Json]](row, "fees").foreach { value =>
          val fee = value.hcursor
          val expected = fee.downField("result")
          val size = field[BigInt](fee, "size")
          val summand = field[BigInt](row, "summandLovelace")
          val scaled = policy.multiplierNano * size
          val ceiling = scaled / TxFeePolicy.Nano + (if scaled % TxFeePolicy.Nano > 0 then 1 else 0)
          val sized = size >= 0 && size <= ByronLedger.MaxLovelace
          val outcome =
            if field[Boolean](expected, "accepted") then Right(field[BigInt](expected, "lovelace"))
            else
              val error = expected.downField("error")
              val operands = field[Vector[BigInt]](error, "operands")
              assertEquals(operands.size, 1, label)
              val operand = operands.headOption.getOrElse(fail("missing native error operand"))
              val problem = field[String](error, "constructor") match
                case "LovelaceTooLarge" => TxFeePolicy.Error.SizeTooLarge(operand)
                case "LovelaceTooSmall" => TxFeePolicy.Error.SizeTooSmall(operand)
                case "LovelaceOverflow" =>
                  // the reference's operand is the `Word64` sum; the node's is the exact one
                  if sized && ceiling >= 0 && ceiling < modulus then
                    assertEquals(operand, (summand + ceiling).mod(modulus), label)
                    TxFeePolicy.Error.Overflow(summand + ceiling)
                  else TxFeePolicy.Error.Overflow(operand)
                case other => fail(s"unsupported native fee error $other")
              Left(problem)
          // a ceiling the reference wraps into `Word64` is no verdict, whatever the reference made
          // of it (overflow-improvements.md#byron-linear-fee)
          val node =
            if sized && (ceiling < 0 || ceiling >= modulus) then
              Left(TxFeePolicy.Error.ScaledOutOfRange(ceiling))
            else outcome
          assertEquals(policy.minimumFee(size), node, s"$label size=$size")
        }
    }
  }
