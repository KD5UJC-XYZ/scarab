// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** Running a test body on a thread with a small stack (256 KiB), so that recursion per level of a
  * deep value overflows it for certain (ADR 0011).
  */
object SmallStack:
  def run[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.fold(throw new IllegalStateException("the small-stack thread left no result"))(
      _.fold(throw _, identity)
    )
