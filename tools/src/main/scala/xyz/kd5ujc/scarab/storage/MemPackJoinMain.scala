// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{ExitCode, IO, IOApp}

/** Runs [[MemPackJoin.run]] by hand:
  * `sbt "tools/runMain xyz.kd5ujc.scarab.storage.MemPackJoinMain"`.
  */
object MemPackJoinMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] = MemPackJoin.run(args)
