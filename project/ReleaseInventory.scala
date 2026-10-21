// SPDX-License-Identifier: Apache-2.0
import sbt._
import sbt.librarymanagement.UpdateReport

/** Export resolver evidence before the independent clean build directories are removed. */
object ReleaseInventory {
  def write(report: UpdateReport, classpath: Seq[File], output: File): File = {
    def coordinate(module: ModuleID): String =
      Seq(module.organization, module.name, module.revision).mkString(":")
    def json(value: String): String = "\"" + value.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
    val runtime = report.configurations.filter(_.configuration.name == "runtime")
    require(runtime.size == 1, "exactly one resolved runtime configuration is required")
    val modules = runtime.flatMap(_.modules).filterNot(_.evicted)
    val rows = modules
      .sortBy(m => coordinate(m.module))
      .map { module =>
        val artifacts = module.artifacts
          .map { case (artifact, file) =>
            s"""{"path":${json(file.getAbsolutePath)},"classifier":${json(
                artifact.classifier.getOrElse("")
              )}}"""
          }
          .mkString(",")
        val licenses = module.licenses
          .map { case (name, url) =>
            s"""{"name":${json(name)},"url":${json(url.getOrElse(""))}}"""
          }
          .mkString(",")
        val callers =
          module.callers.map(c => json(coordinate(c.caller))).distinct.sorted.mkString(",")
        s"""{"coordinate":${json(
            coordinate(module.module)
          )},"artifacts":[$artifacts],"licenses":[$licenses],"callers":[$callers]}"""
      }
      .mkString(",")
    val paths = classpath.map(file => json(file.getAbsolutePath)).mkString(",")
    IO.write(output, s"""{"schemaVersion":1,"modules":[$rows],"classpath":[$paths]}\n""")
    output
  }
}
