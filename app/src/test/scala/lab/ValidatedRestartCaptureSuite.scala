// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration.*
import ReferenceJson.Json

class ValidatedRestartCaptureSuite extends munit.FunSuite:
  test("canonical nested JSON sorts keys and escapes controller projections") {
    val value = Json.Obj(
      Map("z" -> Json.Str("x\n\"\\"), "a" -> Json.Arr(Vector(Json.Num("1"), Json.Lit("null"))))
    )
    assertEquals(
      ValidatedRestartCapture.canonical(value),
      "{\"a\":[1,null],\"z\":\"x\\n\\\"\\\\\"}".replace("\\n", "\\u000a")
    )
  }
  test("expected token requires exact lowercase widths and canonical generation") {
    val h = "ab" * 32
    val token = s"$h:$h:9223372036854775807:$h"
    assert(ValidatedRestartCapture.parseToken(token).isRight)
    for bad <- Vector(
        token.replace("ab", "AB"),
        s"$h:$h:01:$h",
        s"$h:$h:-1:$h",
        s"$h:$h:9223372036854775808:$h",
        token + ":extra"
      )
    do assert(ValidatedRestartCapture.parseToken(bad).isLeft)
  }
  test("controller publication confirmation must match sequence and digest") {
    val expected = ValidatedRestartCapture.confirmation(4, "ab" * 32)
    (for
      _ <- ValidatedRestartCapture.awaitControl(expected, IO.pure(Some(expected)), 1.second)
      wrong <- ValidatedRestartCapture
        .awaitControl(expected, IO.pure(Some("retained 3 " + "ab" * 32)), 1.second)
        .attempt
    yield assert(wrong.isLeft)).unsafeToFuture()
  }
  test("missing controller release is a failing bounded timeout") {
    ValidatedRestartCapture
      .awaitControl("release token", IO.pure(None), 30.millis)
      .attempt
      .map { result =>
        assert(result.isLeft)
        assert(result.swap.toOption.get.getMessage.contains("timeout"))
      }
      .unsafeToFuture()
  }
