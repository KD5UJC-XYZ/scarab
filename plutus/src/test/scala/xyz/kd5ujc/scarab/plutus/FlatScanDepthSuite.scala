// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import scalus.uplc.{Constant, Term}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data

/** [[FlatScan.decode]] keeps its stack on the heap (ADR 0011): deep terms, deep `data` constants
  * and deep constant types decode on a thread with a small stack.
  */
class FlatScanDepthSuite extends FunSuite:
  private val SmallStack = 256L * 1024

  private def onSmallStack[A](body: => A): Either[Throwable, A] =
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, A]]()
    val worker = new Thread(
      null,
      () =>
        result.set(
          try Right(body)
          catch case error: Throwable => Left(error)
        ),
      "flat-small-stack",
      SmallStack
    )
    worker.start()
    worker.join()
    result.get()

  /** The levels of single-element lists from the root of `data`, walked iteratively. */
  private def depth(data: Data): Int =
    Iterator
      .iterate(Option(data)) {
        case Some(Data.List(PList.Cons(head, _))) => Some(head)
        case _                                    => None
      }
      .takeWhile(_.isDefined)
      .size - 1

  test("a data constant of 16000 levels decodes on a small stack") {
    val levels = DeepFlatPrograms.DataLevels
    val flat = DeepFlatPrograms.deepData
    val decoded = onSmallStack(
      FlatScan
        .decode(flat)
        .map(_.program.term match
          case Term.Const(Constant.Data(data), _) => depth(data)
          case _                                  => -1)
    )
    assertEquals(decoded, Right(Right(levels)))
  }

  test("a term of 100000 nested delays decodes on a small stack") {
    val levels = DeepFlatPrograms.Delays
    val flat = DeepFlatPrograms.nestedDelays
    val decoded = onSmallStack(FlatScan.decode(flat).map { d =>
      Iterator
        .iterate(d.program.term) {
          case Term.Delay(body, _) => body
          case other               => other
        }
        .drop(levels)
        .next()
    })
    assertEquals(decoded, Right(Right(Term.Error())))
  }

  test("an application spine of 50000 builtins decodes on a small stack") {
    val flat = DeepFlatPrograms.applicationSpine
    val decoded = onSmallStack(FlatScan.decode(flat).map(_.scan.openVariable))
    assertEquals(decoded, Right(Right(None)))
  }

  test("a constant of 5000 nested list types decodes on a small stack") {
    val flat = DeepFlatPrograms.nestedListTypes
    val decoded = onSmallStack(FlatScan.decode(flat).map(_.scan.maxTypeHeader))
    assertEquals(decoded, Right(Right(10001)))
  }
