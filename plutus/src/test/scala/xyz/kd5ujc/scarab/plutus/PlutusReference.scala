// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** A plutus release whose reference data the tests read. Each is recorded in
  * `plutus-reference/releases.json`; [[PlutusReferenceSuite]] keeps the two lists equal.
  */
enum PlutusRelease(val tag: String):
  case Plutus1_70 extends PlutusRelease("1.70.0.0")
  case Plutus1_71 extends PlutusRelease("1.71.0.0")

/** The test resources `plutus-reference`: one copy of the plutus data, read per release.
  *
  * The top level holds the base release whole; an older release keeps the files that differ from it
  * under `overlays/<tag>/` and lists the base files it lacks as `absent`. A [[View]] is the
  * release's files by their path in that layout (`conformance/...`, `cost-model/...`,
  * `ledger-api/...`), whichever directory holds them. `scripts/plutus_reference.py check` keeps the
  * views equal to the recorded upstream digests, and its `release_view` is the same rule for
  * scripts.
  */
object PlutusReference:
  val Directory = "plutus-reference"

  final case class View(release: String, files: Map[String, Path]):
    def contains(name: String): Boolean = files.contains(name)

    def path(name: String): Path =
      files.getOrElse(name, throw new NoSuchElementException(s"plutus $release: no $name"))

    def read(name: String): String = Files.readString(path(name))

    def json(name: String): Json = parser.parse(read(name)).fold(throw _, identity)

    /** The files below a directory of the layout, by their path relative to it, in path order. */
    def under(directory: String): Vector[(String, Path)] =
      val prefix = directory.stripSuffix("/") + "/"
      files.toVector
        .collect {
          case (name, file) if name.startsWith(prefix) => name.stripPrefix(prefix) -> file
        }
        .sortBy(_._1)

  private final case class Release(tag: String, absent: Vector[String]) derives Decoder

  private val Own = raw"(.*/)?README\.md|releases\.json".r

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
          .filterNot((name, _) => Own.matches(name))
          .toMap
      finally stream.close()

  /** The view of a release in a reference directory laid out as above. */
  def viewOf(root: Path, tag: String): View =
    val releases = parser
      .parse(Files.readString(root.resolve("releases.json")))
      .flatMap(_.hcursor.get[Vector[Release]]("releases"))
      .fold(throw _, identity)
    val release = releases
      .find(_.tag == tag)
      .getOrElse(throw new NoSuchElementException(s"plutus reference: no release $tag"))
    val base = filesBelow(root).filterNot((name, _) => name.startsWith("overlays/"))
    View(tag, base -- release.absent ++ filesBelow(root.resolve("overlays").resolve(tag)))

  lazy val root: Path = Paths.get(getClass.getResource(s"/$Directory").toURI)

  /** The tags `releases.json` records, in its order. */
  lazy val recordedTags: Vector[String] =
    parser
      .parse(Files.readString(root.resolve("releases.json")))
      .flatMap(_.hcursor.get[Vector[Release]]("releases"))
      .fold(throw _, identity)
      .map(_.tag)

  private lazy val views: Map[PlutusRelease, View] =
    PlutusRelease.values.map(release => release -> viewOf(root, release.tag)).toMap

  def view(release: PlutusRelease): View = views(release)
