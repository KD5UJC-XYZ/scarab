// SPDX-License-Identifier: Apache-2.0
package lab
import cats.effect.{IO, IOApp, ExitCode}

/** Compatibility harness; the runtime is Compile-only. */
object PlutusSubmissionMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] = PlutusResearchRuntime.run(args)
