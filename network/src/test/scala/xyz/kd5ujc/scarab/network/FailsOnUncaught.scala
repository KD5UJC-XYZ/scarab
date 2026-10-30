// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import java.util.concurrent.ConcurrentLinkedQueue
import munit.CatsEffectSuite
import scala.annotation.tailrec

/** A suite whose tests run on a runtime that records every failure it is asked to report, and that
  * fails the test that caused it. A fiber that ends with an error nobody observed (a
  * `cats.mtl.Handle$Submarine` raised outside the `allow` scope that made its `Handle`, a defect in
  * a background fiber) is reported by the runtime to its failure reporter and is otherwise only a
  * line on stderr: with this trait it is a failed test (issue 130).
  */
trait FailsOnUncaught extends CatsEffectSuite:
  private val reported = new ConcurrentLinkedQueue[Throwable]

  // The runtime is not shut down at the end of the suite: its pools are daemon threads, and
  // shutting a work-stealing pool down from one of its own threads (munit's afterAll runs there)
  // does not return.

  /** The configuration of the suite's runtime. A suite may make fibers yield more often, so that a
    * race between a fiber's end and its join is taken often enough to be seen.
    */
  protected def runtimeConfig: IORuntimeConfig = IORuntimeConfig()

  override implicit lazy val munitIORuntime: IORuntime =
    IORuntime
      .builder()
      .setConfig(runtimeConfig)
      .setFailureReporter(t => { reported.add(t); () })
      .build()

  override def beforeEach(context: BeforeEach): Unit =
    reported.clear()
    super.beforeEach(context)

  /** The failures reported since the last call (or the start of the test); empties the record. */
  protected def takeUncaught(): List[Throwable] =
    FailsOnUncaught.drain(reported)

  // The check runs as part of the test's body: munit 1.0.2 discards what `afterEach` throws
  // (`MUnitRunner.runTestBody` keeps the body's result), so a check there never failed a test.
  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "fails on uncaught fiber failures",
      test =>
        test.withBody(() =>
          test
            .body()
            .map { value =>
              val uncaught = takeUncaught()
              if uncaught.nonEmpty then
                fail(
                  s"the runtime reported ${uncaught.size} uncaught fiber failure(s): " +
                    uncaught
                      .map(t => t.getClass.getName + Option(t.getMessage).fold("")(": " + _))
                      .mkString("; ")
                )(using test.location)
              value
            }(using munitExecutionContext)
        )
    )

object FailsOnUncaught:
  private[network] def drain(reported: ConcurrentLinkedQueue[Throwable]): List[Throwable] =
    @tailrec
    def loop(seen: List[Throwable]): List[Throwable] =
      Option(reported.poll()) match
        case Some(failure) => loop(failure :: seen)
        case None          => seen.reverse
    loop(Nil)
