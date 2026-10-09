// SPDX-License-Identifier: Apache-2.0
package lab

import lab.network.ChainSync
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

class ReferenceCaptureBoundsSuite extends munit.FunSuite:
  test("public collector refuses more than eight before opening a transport") {
    ReferenceCaptureCommand
      .headersThrough(null, 1082026L, ChainSync.Point.Origin, None, 9)
      .attempt
      .map(r => assert(r.left.toOption.exists(_.getMessage.contains("eight-block"))))
      .unsafeToFuture()
  }
  test("nonce observation collector refuses zero and seventeen before opening a transport") {
    Vector(0, 17)
      .traverse_ { size =>
        ReferenceCaptureCommand
          .headersThroughBounded(null, 1082026L, ChainSync.Point.Origin, None, size)
          .attempt
          .map(r => assert(r.left.toOption.exists(_.getMessage.contains("sixteen-block"))))
      }
      .unsafeToFuture()
  }
