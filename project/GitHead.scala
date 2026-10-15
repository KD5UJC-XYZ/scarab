import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.util.Try

/** The commit of HEAD, read from the repository's files, so it is known where the `git` tool is not
  * (the build container): `.git` as a directory, or as a `gitdir:` file in a worktree, then `HEAD`,
  * the loose ref it names, else `packed-refs`.
  */
object GitHead {
  private def read(p: Path): Option[String] =
    Try(new String(Files.readAllBytes(p), UTF_8).trim).toOption.filter(_.nonEmpty)

  private def gitDir(root: Path): Option[Path] = {
    val dot = root.resolve(".git")
    if (Files.isDirectory(dot)) Some(dot)
    else
      read(dot)
        .collect {
          case line if line.startsWith("gitdir:") => root.resolve(line.stripPrefix("gitdir:").trim)
        }
        .filter(Files.isDirectory(_))
  }

  /** A worktree keeps its own HEAD but shares the refs of the common directory. */
  private def commonDir(dir: Path): Path =
    read(dir.resolve("commondir")).map(c => dir.resolve(c).normalize).getOrElse(dir)

  def commit(root: Path): Option[String] =
    for {
      dir <- gitDir(root)
      head <- read(dir.resolve("HEAD"))
      hash <-
        if (!head.startsWith("ref:")) Some(head)
        else {
          val ref = head.stripPrefix("ref:").trim
          val common = commonDir(dir)
          read(dir.resolve(ref)).orElse(read(common.resolve(ref))).orElse {
            read(common.resolve("packed-refs")).flatMap(
              _.linesIterator.map(_.split(' ')).collectFirst { case Array(h, r) if r == ref => h }
            )
          }
        }
    } yield hash
}
