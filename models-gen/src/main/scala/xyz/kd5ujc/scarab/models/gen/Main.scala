// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models.gen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** The generator entry points: load the configuration and the CDDL it names, resolve, emit. */
object Generator:
  def load(cddlDir: Path, config: Config): Either[GenError, Schema] =
    try
      val modules = config.modules.map { m =>
        val files = m.files.map { f =>
          val path = cddlDir.resolve(f)
          val text =
            try Files.readString(path, StandardCharsets.UTF_8)
            catch case NonFatal(e) => throw GenException(GenError.Io(path.toString, e.toString))
          CddlParser.parse(text) match
            case Right(parsed) => parsed
            case Left(e)       => throw GenException(GenError.Parse(f, e))
        }
        m.name -> files
      }
      Right(Schema.build(modules))
    catch case GenException(e) => Left(e)

  def generate(cddlDir: Path, config: Config): Either[GenError, Map[String, String]] =
    load(cddlDir, config).flatMap { schema =>
      try Right(new Emitter(schema, config).generate())
      catch case GenException(e) => Left(e)
    }

  def configFrom(cddlDir: Path): Either[GenError, Config] =
    val path = cddlDir.resolve("models.conf")
    try Right(Config.parse(Files.readString(path, StandardCharsets.UTF_8)))
    catch
      case GenException(e) => Left(e)
      case NonFatal(e)     => Left(GenError.Io(path.toString, e.toString))

object Main:
  def main(args: Array[String]): Unit =
    val cddlDir = Paths.get(args(0))
    val out = Paths.get(args(1))
    val result = Generator.configFrom(cddlDir).flatMap(Generator.generate(cddlDir, _))
    result match
      case Left(error) =>
        System.err.println(s"models generator failed: ${error.message}")
        throw new IllegalStateException(error.message)
      case Right(files) =>
        files.foreach { (rel, text) =>
          val target = out.resolve(rel)
          Files.createDirectories(target.getParent)
          Files.writeString(target, text, StandardCharsets.UTF_8)
        }
        println(s"generated ${files.size} files into $out")
