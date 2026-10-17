// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.unsafe.IORuntime
import java.util.concurrent.ConcurrentLinkedQueue
import munit.CatsEffectSuite
import scala.jdk.CollectionConverters.*

/** A suite whose tests run on a runtime that records every failure it is asked to report, and that
  * fails the test that caused it. A fiber that ends with an error nobody observed (a
  * `cats.mtl.Handle$Submarine` raised outside the `allow` scope that made its `Handle`, a defect in
  * a background fiber) is reported by the runtime to its failure reporter and is otherwise only a
  * line on stderr: with this trait it is a failed test (issue 130).
  */
trait FailsOnUncaught extends CatsEffectSuite:
  private val reported = new ConcurrentLinkedQueue[Throwable]

  override lazy val munitIORuntime: IORuntime =
    IORuntime.builder().setFailureReporter(t => { reported.add(t); () }).build()

  override def beforeEach(context: BeforeEach): Unit =
    reported.clear()
    super.beforeEach(context)

  /** The failures reported since the last call (or the start of the test); empties the record. */
  protected def takeUncaught(): List[Throwable] =
    val seen = reported.asScala.toList
    reported.clear()
    seen

  override def afterEach(context: AfterEach): Unit =
    val uncaught = takeUncaught()
    super.afterEach(context)
    if uncaught.nonEmpty then
      fail(
        s"the runtime reported ${uncaught.size} uncaught fiber failure(s): " +
          uncaught.map(t => t.getClass.getName + Option(t.getMessage).fold("")(": " + _)).mkString("; ")
      )
