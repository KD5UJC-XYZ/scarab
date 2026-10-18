// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}

/** Build-pinned private reference messages: success alone cannot identify a wire dialect. */
class LeiosReferenceCaptureSuite extends munit.FunSuite:
  private final case class Capture(protocol: Int, kind: String, raw: Bytes)
  private val required = Set("announcement", "block-offer", "votes", "block", "block-txs")

  private def captures(profile: String): Vector[Capture] =
    Files.readString(Path.of("fixtures", "leios", "reference", profile, "messages.tsv"))
      .linesIterator.filterNot(line => line.isEmpty || line.startsWith("#"))
      .map { line =>
        val fields = line.split("\t", -1)
        assertEquals(fields.length, 3)
        Capture(fields(0).toInt, fields(1), Bytes.fromHex(fields(2)).fold(fail(_), identity))
      }.toVector

  private def decode(capture: Capture, raw: Bytes): Either[LeiosWireError, Int] =
    capture.protocol match
      case 18 => LeiosNotify.decodePrefix(LeiosNotify.State.Busy, raw).map(_._2)
      case 19 =>
        val state = if capture.kind == "block" then LeiosFetch.State.Block else LeiosFetch.State.BlockTxs
        LeiosFetch.decodePrefix(state, raw).map(_._2)
      case other => fail(s"unexpected protocol $other")

  private val oldTags = Map(
    "announcement" -> 4, "block-offer" -> 5, "txs-offer" -> 6,
    "votes" -> 7, "block" -> 2, "block-txs" -> 4
  )

  List("w38a", "main").foreach { profile =>
    test(s"$profile original messages retain their measured codec verdicts") {
      val rows = captures(profile)
      assert(required.subsetOf(rows.map(_.kind).toSet))
      rows.foreach { row =>
        val expected: Either[LeiosWireError, Int] = (profile, row.kind) match
          case ("main", "block-txs") => Left(LeiosWireError.WrongArity(4, 3, 1))
          case ("main", _) | ("w38a", "votes") => Right(row.raw.size)
          case ("w38a", "announcement") => Left(LeiosWireError.NotAdmitted(1))
          case ("w38a", "block-offer") => Left(LeiosWireError.WrongArity(2, 0, 2))
          case ("w38a", "txs-offer") | ("w38a", "block-txs") => Left(LeiosWireError.NotAdmitted(3))
          case ("w38a", "block") => Left(LeiosWireError.NotAdmitted(1))
          case other => fail(s"unexpected capture $other")
        assertEquals(decode(row, row.raw), expected, row.kind)
      }
    }
  }

  test("old tag renumbering isolates numbering differences without being a production adapter") {
    captures("w38a").foreach { row =>
      assert((row.raw(0) & 0xe0) == 0x80)
      val tag = oldTags.getOrElse(row.kind, fail(s"unexpected kind ${row.kind}"))
      val renumbered = row.raw.take(1) ++ Bytes.wrap(Array(tag.toByte)) ++ row.raw.drop(2)
      assertEquals(decode(row, renumbered), Right(renumbered.size), row.kind)
    }
  }

  test("w38a votes silently satisfy the Blueprint announcement shape") {
    val votes = captures("w38a").filter(_.kind == "votes")
    assertEquals(votes.size, 1)
    votes.foreach { row =>
      LeiosNotify.decodePrefix(LeiosNotify.State.Busy, row.raw) match
        case Right((_: LeiosNotify.Message.BlockAnnouncement, consumed)) => assertEquals(consumed, row.raw.size)
        case other => fail(s"unexpected vote interpretation $other")
    }
  }

  test("the newer transaction reply contains only tag-24 transactions") {
    val replies = captures("main").filter(_.kind == "block-txs")
    assertEquals(replies.size, 1)
    replies.foreach { row =>
      val root = Cbor.decode(row.raw).fold(fail(_), identity)
      root.value match
        case Value.Arr(Vector(tag, transactions)) =>
          assertEquals(tag.value, Value.UInt(BigInt(4)))
          transactions.value match
            case Value.Arr(txs) =>
              assert(txs.nonEmpty)
              txs.foreach { tx =>
                tx.value match
                  case Value.Tag(number, payload) =>
                    assertEquals(number, BigInt(24))
                    payload.value match
                      case Value.ByteString(raw) => assert(Cbor.decode(raw).isRight)
                      case other => fail(s"tag-24 payload is $other")
                  case other => fail(s"transaction is $other")
              }
            case other => fail(s"transaction list is $other")
        case other => fail(s"transaction reply is $other")
    }
  }
