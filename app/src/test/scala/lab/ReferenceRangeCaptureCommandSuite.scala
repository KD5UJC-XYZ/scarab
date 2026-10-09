// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Path
import lab.cbor.Bytes

class ReferenceRangeCaptureCommandSuite extends munit.FunSuite:
  private val args = List("3001", "1082026", "1", "00" * 32, "2", "11" * 32, "8")
  test("canonical bounded options reject before acquisition") {
    assert(ReferenceRangeCaptureCommand.options(args).isRight)
    Vector(
      (0, "65536"),
      (1, "4294967296"),
      (2, "01"),
      (2, "18446744073709551616"),
      (3, "ab"),
      (4, "1"),
      (5, "00" * 32),
      (6, "9"),
      (6, "08")
    ).foreach { (index, value) =>
      assert(ReferenceRangeCaptureCommand.options(args.updated(index, value)).isLeft)
    }
    assert(ReferenceRangeCaptureCommand.options(args ++ List("128", "33554432")).isRight)
    Vector(List("129", "10"), List("1", "33554433"), List("01", "10"), List("1", "0")).foreach(
      extra => assert(ReferenceRangeCaptureCommand.options(args ++ extra).isLeft)
    )
    ReferenceRangeCaptureCommand
      .run(args.updated(6, "9"))
      .map(code => assertEquals(code.code, 2))
      .unsafeToFuture()
  }
  test("event and byte allowance stops at the first excess including control messages") {
    val o = ReferenceRangeCaptureCommand.options(args ++ List("2", "5")).toOption.get
    (for
      c <- Ref.of[IO, (Int, Int)]((0, 0))
      _ <- ReferenceRangeCaptureCommand.account(c, o)(1, 5)
      _ <- ReferenceRangeCaptureCommand.account(c, o)(1, 0)
      excess <- ReferenceRangeCaptureCommand.account(c, o)(1, 0).attempt
      b <- Ref.of[IO, (Int, Int)]((0, 0))
      bytes <- ReferenceRangeCaptureCommand.account(b, o)(1, 6).attempt
    yield
      assert(excess.isLeft)
      assert(bytes.isLeft)
    ).unsafeToFuture()
  }
  sys.env.get("NODE_AUDIT_EVIDENCE").foreach { location =>
    test(
      "retained exact range binds every original and rejects gaps endpoints substitutions and missing blocks"
    ) {
      val c = SequenceInput.load(Path.of(location)).toOption.get
      val os = CoherentSequenceCommand.loadOracle(Path.of(location)).toOption.get
      val originals =
        CoherentSequenceCommand.captures(os.originals("scala-sequence-capture.md")).toOption.get
      val headers = originals.map(o => ReferenceCaptureCommand.header(o.envelope).toOption.get)
      val blocks = originals.map(_.block)
      val seed = c.certificateSeed.tip
      val last = headers.last
      val o = ReferenceRangeCaptureCommand
        .options(
          List(
            "3001",
            "1082026",
            seed.slot.toString,
            seed.hash.hex,
            last.slot.toString,
            last.hash.hex,
            "8"
          )
        )
        .toOption
        .get
      assert(ReferenceRangeCaptureCommand.validate(o, headers, blocks).isRight)
      assert(ReferenceRangeCaptureCommand.validate(o, headers.drop(1), blocks.drop(1)).isLeft)
      assert(ReferenceRangeCaptureCommand.validate(o, headers, blocks.dropRight(1)).isLeft)
      assert(
        ReferenceRangeCaptureCommand.validate(o, headers, blocks.updated(0, Bytes.empty)).isLeft
      )
      assert(
        ReferenceRangeCaptureCommand
          .validate(
            o,
            headers.updated(1, headers(1).copy(blockNo = headers(1).blockNo + 1)),
            blocks
          )
          .isLeft
      )
      assert(
        ReferenceRangeCaptureCommand
          .validate(o, headers.updated(1, headers(1).copy(parent = seed.hash)), blocks)
          .isLeft
      )
      assert(
        ReferenceRangeCaptureCommand
          .validate(o, headers.updated(1, headers(1).copy(slot = headers.head.slot)), blocks)
          .isLeft
      )
      assert(
        ReferenceRangeCaptureCommand.validate(o, headers.dropRight(1), blocks.dropRight(1)).isLeft
      )
      assert(ReferenceRangeCaptureCommand.validate(o.copy(max = 1), headers, blocks).isLeft)
      val rendered =
        headers.zip(blocks).map((h, b) => ReferenceRangeCaptureCommand.record(h, b)).mkString("\n")
      assertEquals(
        CoherentSequenceCommand.captures(Bytes.fromArray(rendered.getBytes("UTF-8"))).toOption.get,
        originals
      )
      val row = ReferenceJson.parse(
        Bytes.fromArray(
          ReferenceRangeCaptureCommand.record(headers.head, blocks.head).getBytes("UTF-8")
        )
      )
      assertEquals(ReferenceJson.string(ReferenceJson.field(row, "parentHash")), seed.hash.hex)
    }
  }
