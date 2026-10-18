// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** The generator entry points: load the configuration and the CDDL it names, resolve, emit. */
object Generator:
  private[gen] def io[A](path: Path)(action: => A): Either[GenError, A] =
    try Right(action)
    catch
      case e: java.io.IOException => Left(GenError.Io(path.toString, e))
      case NonFatal(e)            => Left(GenError.Defect(path.toString, e))

  def load(cddlDir: Path, config: Config): Either[GenError, Schema] =
    config.modules
      .traverse { m =>
        m.files
          .traverse { f =>
            val path = cddlDir.resolve(f)
            io(path)(Files.readString(path, StandardCharsets.UTF_8))
              .flatMap(text => CddlParser.parse(text).left.map(GenError.Parse(f, _)))
          }
          .map(m.name -> _)
      }
      .map(Schema.build)

  def generate(cddlDir: Path, config: Config): Either[GenError, Map[String, String]] =
    load(cddlDir, config).flatMap(schema => new Emitter(schema, config).generate())

  def configFrom(cddlDir: Path): Either[GenError, Config] =
    val path = cddlDir.resolve("models.conf")
    io(path)(Files.readString(path, StandardCharsets.UTF_8)).flatMap(Config.parse)

  def write(out: Path, files: Map[String, String]): Either[GenError, Unit] =
    files.toVector.traverse_ { (rel, text) =>
      val target = out.resolve(rel)
      io(target) {
        Files.createDirectories(target.getParent)
        Files.writeString(target, text, StandardCharsets.UTF_8)
        ()
      }
    }

object Main:
  def main(args: Array[String]): Unit =
    val cddlDir = Paths.get(args(0))
    val out = Paths.get(args(1))
    val result = for
      config <- Generator.configFrom(cddlDir)
      files <- Generator.generate(cddlDir, config)
      _ <- Generator.write(out, files)
    yield files.size
    result match
      case Left(error) =>
        System.err.println(s"models generator failed: ${error.message}")
        sys.exit(1)
      case Right(count) => println(s"generated $count files into $out")
