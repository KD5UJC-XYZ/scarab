// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import com.comcast.ip4s.{Host, SocketAddress}
import io.circe.Json
import io.circe.syntax.*
import munit.FunSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.Trace

/** The fields of the peer governor's lines that the golden's samples do not tell apart. */
class PeerSelectionLinesSuite extends FunSuite:
  private def fields(trace: Trace[SocketAddress[Host]]): List[(String, Json)] =
    summon[Traced[NodeEvent]].line(NodeEvent.PeerSelection(trace)).fields

  test("a root or big-ledger failure line has a `defect` only when its request raised") {
    val raised = new IllegalStateException("injected roots defect")
    val counts = List("backoffs" -> (-1).asJson, "retryInSeconds" -> 2.0.asJson)
    val roots = ("action" -> "failure".asJson) :: counts
    val bigLedger = ("action" -> "failure".asJson) :: ("bigLedger" -> true.asJson) :: counts
    val defect = "defect" -> "injected roots defect".asJson
    assertEquals(fields(Trace.RootsFailure(-1, 2.seconds, Some(raised))), roots :+ defect)
    assertEquals(fields(Trace.RootsFailure(-1, 2.seconds, None)), roots)
    assertEquals(fields(Trace.BigLedgerFailure(-1, 2.seconds, Some(raised))), bigLedger :+ defect)
    assertEquals(fields(Trace.BigLedgerFailure(-1, 2.seconds, None)), bigLedger)
  }
