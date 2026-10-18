// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import munit.FunSuite
import xyz.kd5ujc.scarab.protocol.ProtocolError

class NetworkErrorSuite extends FunSuite:
  test("transport failure preserves the original operating-system cause") {
    val cause = new java.io.IOException("socket reset")
    assertEquals(
      NetworkError.fromThrowable("read", cause),
      NetworkError.BearerClosed(NetworkError.Closure.Transport("read", cause))
    )
  }

  test("unexpected failures remain defects rather than peer closure") {
    val cause = new IllegalStateException("broken invariant")
    assertEquals(NetworkError.fromThrowable("read", cause), NetworkError.Defect(cause))
  }

  test("mini-protocol decode and local validation retain structured causes") {
    val cause = ProtocolError.OutsideUint64(BigInt(-1))
    assertEquals(NetworkError.Decode(2, cause).cause, cause)
    assertEquals(NetworkError.IllegalTransition(2, cause).cause, cause)
  }

  test("service abort and query refusal retain domain causes and reject raw strings") {
    val cause = ProtocolError.OutsideUint64(BigInt(-1))
    assertEquals(
      NetworkError.BearerClosed(NetworkError.Closure.AbortedBy(cause)).reason,
      NetworkError.Closure.AbortedBy(cause)
    )
    assertEquals(
      LocalStateQueryServer.Refusal.Undecodable(cause, "00"),
      xyz.kd5ujc.scarab.protocol.QueryRefusal.Undecodable(cause, "00")
    )
    assert(compileErrors("""NetworkError.Closure.AbortedBy("text")""").nonEmpty)
    assert(compileErrors("""LocalStateQueryServer.Refusal.Undecodable("text", "00")""").nonEmpty)
  }
