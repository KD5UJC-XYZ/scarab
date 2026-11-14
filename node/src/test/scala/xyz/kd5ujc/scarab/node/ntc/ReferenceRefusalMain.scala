// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import java.nio.file.{Files, Path}

/** Replay any manifest, including private cases, through the same complete judgment as the suite.
  * Writes every result; unavailable cases are explicit failures and never skipped.
  */
object ReferenceRefusalMain extends IOApp:
  import ReferenceRefusalSuite.*

  def run(args: List[String]): IO[ExitCode] = args match
    case root :: output :: Nil =>
      IO.blocking(load(Path.of(root))).flatMap {
        case Left(problem) => IO.println(problem.show).as(ExitCode.Error)
        case Right(loaded) =>
          val rows = loaded.corpora.flatMap { corpus =>
            corpus.cases.map { entry =>
              val identity = Vector("corpus" -> Json.fromString(corpus.path),
                "case" -> Json.fromString(entry.label))
              replay(corpus, entry) match
                case Right(Judgement.Refused(reason)) =>
                  val equal = reason == entry.reason
                  (equal, Json.obj((identity ++ Vector(
                    "verdict" -> Json.fromString("refused"),
                    "reason" -> Json.fromString(reason.hex), "equal" -> Json.fromBoolean(equal)))*))
                case Right(other) =>
                  (false, Json.obj((identity :+ ("incomplete" -> Json.fromString(other.show)))*))
                case Left(problem) =>
                  (false, Json.obj((identity :+ ("incomplete" -> Json.fromString(problem.show)))*))
            }
          }
          val text = rows.map(_._2.noSpaces).mkString("", "\n", "\n")
          IO.blocking(Files.writeString(Path.of(output), text)).as(
            if rows.nonEmpty && rows.forall(_._1) then ExitCode.Success else ExitCode.Error)
      }
    case _ => IO.println("usage: ReferenceRefusalMain <manifest-directory> <output.jsonl>")
        .as(ExitCode.Error)
