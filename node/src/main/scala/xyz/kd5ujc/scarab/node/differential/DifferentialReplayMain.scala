// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.Path
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.node.{JsonLog, NodeRules, NodeSetup}

/** Runs the repro bundle of a divergence ([[Repro]]) again, in seconds: the state before the block
  * and the block, through the old and the new rules, with the harness that found the divergence
  * (`NewRules.current`, the rules as far as they are ported).
  *
  * {{{
  * differential-replay <bundle> (--genesis-dir <dir> | --config <config.json>)
  * }}}
  *
  * Exit status: 0 when the sides agree on the block (the divergence does not reproduce), 1 when
  * they still disagree, 2 when the bundle or the configuration cannot be read. The status is the
  * predicate of a bisect: a commit is bad when the bundle of the first divergence still diverges.
  */
object DifferentialReplayMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    val setup = args match
      case _ :: "--genesis-dir" :: dir :: Nil => NodeSetup.fromDirectory(Path.of(dir))
      case _ :: "--config" :: file :: Nil     => NodeSetup.fromConfigFile(Path.of(file))
      case _ =>
        Left(
          xyz.kd5ujc.scarab.ledger.ConfigError(
            "usage: differential-replay <bundle> (--genesis-dir <dir> | --config <file>)"
          )
        )
    setup match
      case Left(e) => IO.println(e.message).as(ExitCode(2))
      case Right(configured) =>
        val env = configured.mirrorEnv()
        Repro
          .read[IO](Path.of(args.head), IO.pure(env), configured.geometry.byron.epochSlots)
          .flatMap {
            case Left(e) => IO.println(e.message).as(ExitCode(2))
            case Right(bundle) =>
              for
                events <- Ref.of[IO, Vector[Event]](Vector.empty)
                outcome <- Handle
                  .allow[NodeError] {
                    Handle
                      .allow[Divergence] {
                        for
                          harness <- DifferentialRules.create[IO](
                            NodeRules[IO](
                              configured.praos,
                              configured.globals,
                              configured.byronRules
                            ),
                            NewRules.current[IO],
                            IO.pure(env),
                            Sampling.EveryBlock,
                            Policy.Reseed,
                            Repro.none[IO],
                            e => events.update(_ :+ e)
                          )
                          _ <- harness(
                            Paired.of(bundle.state, bundle.pre.anchor),
                            bundle.header,
                            bundle.raw
                          )
                        yield ExitCode.Success
                      }
                      .rescue(_ => IO.pure(ExitCode.Error))
                  }
                  .rescue(e =>
                    IO.println(s"the old rules refuse the block: ${e.message}").as(ExitCode.Error)
                  )
                seen <- events.get
                header = bundle.header
                _ <- IO.println(
                  s"block ${header.blockNo} (slot ${header.slot}, ${header.era}): " +
                    s"${seen.collect { case Event.Diverged(d, _) => d }.map(d => cats.Show[Divergence].show(d)).mkString("; ")}"
                )
                _ <- seen
                  .collect { case Event.Diverged(d, _) => d }
                  .traverse_(d => JsonLog.rulesDiverged(d, None))
              yield
                if seen.exists { case Event.Diverged(d, _) => d.isFailure; case _ => false } then
                  ExitCode.Error
                else outcome
          }
