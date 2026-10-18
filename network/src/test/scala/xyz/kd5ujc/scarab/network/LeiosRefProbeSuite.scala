// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*

class LeiosRefProbeSuite extends FailsOnUncaught:
  test("a silent window completes when neither channel receives a message") {
    TestControl
      .executeEmbed(LeiosRefProbe.silentWindow(1.second, IO.never[Unit], IO.never[Unit]))
      .map(silent => assert(silent))
  }

  test("a message on either channel ends silence even if the other stays empty") {
    List((IO.unit, IO.never[Unit]), (IO.never[Unit], IO.unit)).traverse_ { (first, second) =>
      TestControl
        .executeEmbed(LeiosRefProbe.silentWindow(1.second, first, second))
        .map(silent => assert(!silent))
    }
  }

  test("a failed observer is not reported as a silent window") {
    val failure = new java.io.IOException("observer closed")
    TestControl
      .executeEmbed(
        LeiosRefProbe.silentWindow(1.second, IO.raiseError[Unit](failure), IO.never[Unit]).attempt
      )
      .map(result => assertEquals(result, Left(failure)))
  }
