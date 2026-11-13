// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import io.circe.parser
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** The vendored plutus reference data (`plutus-reference`, a test resource of `plutus` that this
  * module reads without depending on it) as the view of one release: the base release's files, less
  * those the release lists as `absent`, with its `overlays/<tag>/` files in their place. This is
  * the rule of `PlutusReference` in the `plutus` tests, whose class path carries Scalus.
  */
object ReferenceCorpus:
  lazy val root: Path = Paths.get(getClass.getResource("/plutus-reference").toURI)

  private def filesBelow(directory: Path): Map[String, Path] =
    if !Files.isDirectory(directory) then Map.empty
    else
      val stream = Files.walk(directory)
      try
        stream
          .iterator()
          .asScala
          .filter(Files.isRegularFile(_))
          .map(file => directory.relativize(file).toString.replace('\\', '/') -> file)
          .toMap
      finally stream.close()

  /** The files of release `tag` by their path in the layout (`conformance/...`). */
  def view(tag: String): Map[String, Path] =
    val absent = parser
      .parse(Files.readString(root.resolve("releases.json")))
      .flatMap(
        _.hcursor
          .downField("releases")
          .as[Vector[io.circe.Json]]
          .map(_.find(_.hcursor.get[String]("tag").contains(tag)))
      )
      .toOption
      .flatten
      .map(_.hcursor.get[Vector[String]]("absent").getOrElse(Vector.empty))
    assert(absent.isDefined, s"plutus reference: no release $tag")
    val base = filesBelow(root).filterNot((name, _) => name.startsWith("overlays/"))
    base -- absent.getOrElse(Vector.empty) ++ filesBelow(root.resolve("overlays").resolve(tag))

  val Releases: Vector[String] = Vector("1.70.0.0", "1.71.0.0")
