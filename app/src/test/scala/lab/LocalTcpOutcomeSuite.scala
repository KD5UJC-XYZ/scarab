// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global

class LocalTcpOutcomeSuite extends munit.FunSuite:
  test("peer failure preserves nonzero client exit and stderr") {
    val peer = new IllegalStateException("connection reset")
    LocalTcpScript
      .observeBoth(IO.pure("exit=4; state deadline expired"), IO.raiseError(peer), identity[String])
      .attempt
      .map { result =>
        val error = result.swap.toOption.get
        assert(error.getMessage.contains("exit=4; state deadline expired"))
        assert(error.getCause eq peer)
      }
      .unsafeToFuture()
  }
  test("peer failure never turns successful client into successful fixture") {
    LocalTcpScript
      .observeBoth(
        IO.pure("exit=0"),
        IO.raiseError(new IllegalStateException("peer failed")),
        identity[String]
      )
      .attempt
      .map(result => assert(result.isLeft))
      .unsafeToFuture()
  }
