// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import cats.effect.{ExitCode, IO, IOApp}

/** Runs [[ProjectionGolden.run]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.node.read.ProjectionGoldenMain"`.
  */
object ProjectionGoldenMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] = ProjectionGolden.run(args)
