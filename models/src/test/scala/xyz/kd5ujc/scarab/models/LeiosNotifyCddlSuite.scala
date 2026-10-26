// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.Vote
import xyz.kd5ujc.scarab.models.leios.notify.LeiosNotifyMessage
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosNotify, LeiosWireError, LeiosWord}

/** The generated reader of the LeiosNotify messages (`models.conf`: consensus' `leiosNotify.cddl`,
  * which imports the ChainSync header, so `header.cddl` is a module of that name too) against the
  * hand-written codec of `protocol`: every message the one writes, the other reads as the same
  * case, and both refuse the same malformed ones, including more than sixteen votes.
  */
class LeiosNotifyCddlSuite extends munit.FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val vote: Vote =
    Vote
      .decode(
        Bytes.wrap(Files.readAllBytes(Path.of("fixtures", "leios", "bls", "LeiosVote.golden.cbor")))
      )
      .fold(e => fail(e.toString), identity)

  /** A real announcing header, as ChainSync sends it. */
  private val header: Bytes =
    Files
      .readAllLines(Path.of("fixtures", "leios", "testnet", "headers-notable.tsv"))
      .asScala
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))
      .find(_(0) == "first-announcement")
      .map(p => hex(p(5)))
      .getOrElse(fail("fixture"))

  private val point = EbPoint(
    LeiosWord.Word64.from(371614L).fold(e => fail(e.toString), identity),
    EbHash.fromBytes(hex("00" * 31 + "07"))
  )

  private def written(m: LeiosNotify.Message): Bytes =
    LeiosNotify.encode(m).fold(e => fail(e.toString), identity)

  private def generated(bytes: Bytes) = LeiosNotifyMessage.decodeBytes(bytes)

  test("each message the codec writes, the generated reader reads as the same case") {
    import LeiosNotifyMessage.*
    assertEquals(generated(written(LeiosNotify.Message.Quit)), Right(Quit))
    assertEquals(generated(written(LeiosNotify.Message.Done)), Right(Done))
    assertEquals(generated(written(LeiosNotify.Message.Canceled)), Right(Canceled))
    assertEquals(
      generated(written(LeiosNotify.Message.RequestNext)),
      Right(LeiosNotificationRequestNext)
    )
    assert(
      generated(
        written(
          LeiosNotify.Message
            .BlockOffer(point, LeiosWord.Word32.from(199983L).fold(e => fail(e.toString), identity))
        )
      ).exists {
        case LeiosBlockOffer(_, _) => true
        case _                     => false
      }
    )
    assert(generated(written(LeiosNotify.Message.BlockTxsOffer(point))).exists {
      case LeiosBlockTxsOffer(_) => true
      case _                     => false
    })
    assert(
      generated(written(LeiosNotify.Message.Votes(Vector(vote), Vector(vote.encode)))).exists {
        case LeiosVotes(vs) => vs.size == 1
        case _              => false
      }
    )
  }

  test("an announcement is a ChainSync header of any era: a real one is read") {
    val message = hex("8204") ++ header
    LeiosNotify.decodePrefix(LeiosNotify.State.Busy, message) match
      case Right((LeiosNotify.Message.BlockAnnouncement(h), _)) => assertEquals(h, header)
      case other                                                => fail(other.toString)
    assert(generated(message).exists {
      case LeiosNotifyMessage.LeiosBlockAnnouncement(_) => true
      case _                                            => false
    })
  }

  test("more than sixteen votes are refused by both, sixteen accepted by both") {
    def votes(n: Int): Bytes =
      hex("8207" + (0x80 + n).toHexString) ++ (1 to n).map(_ => vote.encode).reduce(_ ++ _)
    assert(generated(votes(16)).isRight)
    assert(LeiosNotify.decodePrefix(LeiosNotify.State.Busy, votes(16)).isRight)
    assert(generated(votes(17)).isLeft)
    assertEquals(
      LeiosNotify
        .decodePrefix(LeiosNotify.State.Busy, votes(17))
        .left
        .map(_ == LeiosWireError.BadField(7, xyz.kd5ujc.scarab.protocol.LeiosField.Votes)),
      Left(true)
    )
    // an empty list is refused too (`1*16`)
    assert(generated(hex("820780")).isLeft)
  }

  test("an unknown tag, a wrong arity and trailing bytes are refused by the generated reader") {
    assert(generated(hex("8108")).isLeft)
    assert(generated(hex("820300")).isLeft)
    assert(generated(hex("8103") ++ hex("00")).isLeft)
  }
end LeiosNotifyCddlSuite
