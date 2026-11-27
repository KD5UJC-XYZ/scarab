// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import scala.concurrent.duration.*
import io.circe.Json
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockNoVerdict, FollowerEvent, NoVerdictRetry}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The first `blockNoVerdict` of a block the rules threw on carries the throwable's stack trace
  * (#1521): the line logged when the rules threw a `NullPointerException` named no frame.
  */
class NoVerdictStackSuite extends FunSuite:
  private val at =
    ChainSync.Point.Block(
      ChainSync.UInt64.saturating(4200L),
      Bytes.wrap(Array.fill(32)(0xab.toByte))
    )

  private def fields(cause: BlockNoVerdict, attempt: Int): Map[String, Json] =
    summon[Traced[Traced.FollowerLine]]
      .line(FollowerEvent.NoVerdict(at, cause, attempt, NoVerdictRetry.After(1.second)))
      .fields
      .toMap

  /** A throwable thrown here, so that its stack trace has this suite's frame first. */
  private def thrownHere(cause: Throwable): Throwable =
    try throw new IllegalStateException("injected rules defect", cause)
    catch case t: IllegalStateException => t

  private def stack(fs: Map[String, Json]): Vector[String] =
    fs.get("stack").flatMap(_.as[Vector[String]].toOption).getOrElse(fail(s"no stack in $fs"))

  test("the first judgement of a defect logs the stack trace, its causes after it") {
    val lines =
      stack(fields(BlockNoVerdict.Defect(thrownHere(new ArithmeticException("inner"))), 1))
    assertEquals(lines.head, "java.lang.IllegalStateException: injected rules defect")
    assert(lines(1).startsWith("\tat xyz.kd5ujc.scarab.node.log.NoVerdictStackSuite"), lines(1))
    assert(lines.contains("Caused by: java.lang.ArithmeticException: inner"), lines)
  }

  test("a retry of the same block logs no stack trace, nor does a cause that is not a defect") {
    val defect = BlockNoVerdict.Defect(thrownHere(new ArithmeticException("inner")))
    assertEquals(fields(defect, 2).get("stack"), None)
    val outOfRange = BlockNoVerdict.StateOutOfRange(
      xyz.kd5ujc.scarab.ledger.ChainError.StateOutOfRange(
        BigInt(10),
        xyz.kd5ujc.scarab.ledger.state.VocabError
          .OutOfRange(xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.CompactCoin, BigInt(-1))
      )
    )
    assertEquals(fields(outOfRange, 1).get("stack"), None)
  }

  test("a cause chain that loops ends at the first cause already printed") {
    val a = new IllegalStateException("a")
    val b = new IllegalArgumentException("b", a)
    val _ = a.initCause(b)
    val lines = stack(fields(BlockNoVerdict.Defect(a), 1))
    assertEquals(lines.count(_.startsWith("Caused by")), 1)
  }
