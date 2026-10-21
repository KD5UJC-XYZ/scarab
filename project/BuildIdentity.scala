// SPDX-License-Identifier: Apache-2.0
import java.nio.file.{Files, Path}
import java.util.Properties
import scala.collection.JavaConverters._
import sbtdynver.GitDescribeOutput

/** Release identity is verified before immutable export; ordinary workspaces are always unreleased.
  * The generated properties are frozen transport inputs, not another version authority.
  */
object BuildIdentity {
  val fileName = ".scarab-build-identity.properties"
  private val number = "(?:0|[1-9][0-9]*)"
  private val releaseVersion =
    (number + "\\." + number + "\\." + number + "(?:-beta\\.(" + number + "))?").r
  private val commitPattern = "[0-9a-f]{40}".r
  private val keys = Set(
    "schemaVersion",
    "mode",
    "version",
    "gitCommit",
    "gitTag",
    "gitTagObject",
    "channel",
    "sourceRef",
    "sourceRefCommit"
  )

  final case class Identity(
      version: String,
      commit: String,
      mode: String,
      channel: String,
      tag: String,
      tagObject: String,
      sourceRef: String,
      sourceCommit: String
  ) {
    def metadata: Seq[(String, String)] = Seq(
      "version" -> version,
      "gitCommit" -> commit,
      "buildMode" -> mode,
      "releaseChannel" -> channel,
      "gitTag" -> tag,
      "gitTagObject" -> tagObject,
      "sourceRef" -> sourceRef,
      "sourceRefCommit" -> sourceCommit
    )
  }

  def load(root: Path, described: Option[GitDescribeOutput]): Identity = {
    val frozen = root.resolve(fileName)
    if (Files.exists(frozen)) {
      require(
        !Files.isRegularFile(root.resolve(".git")) && !Files.exists(root.resolve(".git/objects")),
        "frozen identity cannot override a live Git checkout"
      )
      val properties = new Properties
      val stream = Files.newInputStream(frozen)
      try properties.load(stream)
      finally stream.close()
      require(
        properties.stringPropertyNames().asScala.toSet == keys,
        "incomplete frozen build identity"
      )
      def field(key: String): String = properties.getProperty(key)
      val commit = field("gitCommit")
      require(
        field("schemaVersion") == "1" && commitPattern.pattern.matcher(commit).matches(),
        "unsupported build identity or incomplete source commit"
      )
      require(GitHead.commit(root).contains(commit), "frozen identity and source commit disagree")
      val version = field("version")
      field("mode") match {
        case "development" =>
          require(
            version == s"0.0.0-dev.g$commit" && field("channel") == "development" &&
              Seq("gitTag", "gitTagObject", "sourceRef", "sourceRefCommit").forall(field(_) == ""),
            "development identity cannot claim a release"
          )
        case "release" =>
          val channel = version match {
            case releaseVersion(beta) => if (beta == null) "prod" else "beta"
            case _                    => sys.error("release identity requires exact SemVer")
          }
          require(
            field("gitTag") == "v" + version && field("channel") == channel &&
              commitPattern.pattern.matcher(field("gitTagObject")).matches() && field(
                "sourceRefCommit"
              ) == commit &&
              Set(s"refs/heads/release/$channel", s"refs/remotes/origin/release/$channel")(
                field("sourceRef")
              ),
            "release tag, channel or approved source metadata disagree"
          )
        case _ => sys.error("unknown build identity mode")
      }
      Identity(
        version,
        commit,
        field("mode"),
        field("channel"),
        field("gitTag"),
        field("gitTagObject"),
        field("sourceRef"),
        field("sourceRefCommit")
      )
    } else {
      require(
        !Files.exists(root.resolve(".reproducible-source.json")),
        "frozen export has no verified build identity"
      )
      val commit = GitHead
        .commit(root)
        .filter(commitPattern.pattern.matcher(_).matches())
        .getOrElse(
          sys.error(
            "source has neither Git identity nor frozen export metadata; use reproducible_build.py"
          )
        )
      val suffix = described.fold(".workspace")(out => if (out.isDirty()) ".dirty" else "")
      val base = described
        .map(_.ref.dropPrefix)
        .filter(releaseVersion.pattern.matcher(_).matches())
        .getOrElse("0.0.0")
      Identity(s"$base-dev.g$commit$suffix", commit, "development", "development", "", "", "", "")
    }
  }
}
