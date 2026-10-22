// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import io.circe.parser.parse
import munit.FunSuite

class ByronParamsNativeSuite extends FunSuite:
  test("native signed ceiling and Word64 conversion precede checked fee addition") {
    assertEquals(TxFeePolicy(0, -1).minimumFee(1), Right(BigInt(0)))
    assertEquals(
      TxFeePolicy(0, -1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.Overflow((BigInt(1) << 64) - 1))
    )
    assertEquals(
      TxFeePolicy(1000000000, -1000000000).minimumFee(1),
      Left(TxFeePolicy.Error.Overflow(BigInt(0)))
    )
    assertEquals(TxFeePolicy(0, (BigInt(1) << 64) * 1000000000).minimumFee(1), Right(BigInt(0)))
    assertEquals(TxFeePolicy(0, 0).minimumFee(-1), Left(TxFeePolicy.Error.SizeTooSmall(BigInt(-1))))
    assertEquals(
      TxFeePolicy(0, 0).minimumFee(ByronLedger.MaxLovelace + 1),
      Left(TxFeePolicy.Error.SizeTooLarge(ByronLedger.MaxLovelace + 1))
    )
  }

  test("CBOR Nano uses ties-even rounding while genesis JSON uses Word64 division") {
    assertEquals(TxFeePolicy.fromNano(2500000000L, 0), Right(TxFeePolicy(2000000000L, 0)))
    assertEquals(TxFeePolicy.fromNano(3500000000L, 0), Right(TxFeePolicy(4000000000L, 0)))
    assertEquals(TxFeePolicy.fromNano(-500000000, 0), Right(TxFeePolicy(0, 0)))
    val json = parse(Files.readString(Path.of("fixtures/eras/genesis/byron-genesis.json")))
      .fold(e => fail(e.message), identity)
    val cursor = json.hcursor.downField("blockVersionData")
    def genesis(a: BigInt) =
      val changed = cursor.withFocus(
        _.mapObject(
          _.add(
            "txFeePolicy",
            io.circe.Json.obj(
              "summand" -> io.circe.Json.fromString(a.toString),
              "multiplier" -> io.circe.Json.fromString("-1")
            )
          )
        )
      )
      ByronParams.fromGenesis(changed).map(_.txFeePolicy)
    assertEquals(genesis(3500000000L), Right(TxFeePolicy(3000000000L, -1)))
    assert(genesis(BigInt(1) << 64).isLeft)
    assert(genesis(-1).isLeft)
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
                case "LovelaceOverflow" => TxFeePolicy.Error.Overflow(operand)
                case other              => fail(s"unsupported native fee error $other")
              Left(problem)
          assertEquals(policy.minimumFee(size), outcome, s"$label size=$size")
        }
    }
  }
