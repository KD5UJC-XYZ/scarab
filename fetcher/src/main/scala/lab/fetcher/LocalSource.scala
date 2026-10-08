// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import lab.cbor.Bytes

private[fetcher] object LocalFiles:
  def noLinks(path: Path): Path =
    val raw = path.toAbsolutePath
    val components = raw.iterator()
    while components.hasNext do
      if components.next().toString == ".." then
        FetchError.config("parent path components are unsupported")
    val absolute = raw.normalize
    var p: Path = absolute
    while p != null do
      if Files.isSymbolicLink(p) then FetchError.config("symbolic links are unsupported")
      p = p.getParent
    absolute
  def child(root: Path, name: String): Path =
    if !name.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,240}") || name
        .split("/")
        .exists(s => s == ".." || s == "." || s.isEmpty)
    then FetchError.config("unsafe relative source path")
    val p = root.resolve(name).normalize
    if !p.startsWith(root) then FetchError.config("source path escapes root")
    noLinks(p)
  def read(path: Path, max: Long): Array[Byte] =
    noLinks(path)
    if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
      FetchError.config("expected regular file")
    val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    try
      val size = channel.size
      if size < 0 || size > max || size > Int.MaxValue then throw new FetchError(3, "inputBudget")
      val buffer = ByteBuffer.allocate(size.toInt)
      while buffer.hasRemaining do
        if channel.read(buffer) < 0 then FetchError.integrity("file truncated during read")
      if channel.read(ByteBuffer.allocate(1)) != -1 then
        FetchError.integrity("file grew during read")
      buffer.array
    finally channel.close()
  def utf8(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString
  def lines(text: String): Vector[String] =
    if text.contains('\r') || !text.endsWith("\n") then
      FetchError.config("text format requires LF and a final newline")
    val lines = text.dropRight(1).split("\n", -1).toVector
    if lines.exists(_.isEmpty) then FetchError.config("blank lines are unsupported")
    lines

final class LocalConfig private (val manifest: Path, val manifestHash: String, val spec: FetchSpec)
object LocalConfig:
  val keys: Set[String] = Set(
    "format",
    "manifest",
    "manifestSha256",
    "after",
    "count",
    "end",
    "maxBlocks",
    "maxBlockBytes",
    "maxInputBytes",
    "maxStoredBytes",
    "maxFiles",
    "maxDurationSeconds",
    "forkPolicy"
  )
  def load(path: Path): LocalConfig =
    val p = LocalFiles.noLinks(path)
    parse(LocalFiles.utf8(LocalFiles.read(p, 65536)), p.getParent)
  def parse(text: String, root: Path): LocalConfig =
    val pairs = LocalFiles.lines(text).map { line =>
      line.split("\t", -1).toList match
        case k :: v :: Nil if keys(k) => k -> v
        case _ => FetchError.config("unknown config field or invalid tab-separated line")
    }
    if pairs.map(_._1).distinct.size != pairs.size || pairs.map(_._1).toSet != keys then
      FetchError.config("missing or duplicate config field")
    val m = pairs.toMap
    if m("format") != "chain-fetch-v1" || m("forkPolicy") != "fail" then
      FetchError.config("unsupported config format/fork policy")
    def int(k: String): Int = m(k).toIntOption.getOrElse(FetchError.config(s"invalid $k"))
    def long(k: String): Long = m(k).toLongOption.getOrElse(FetchError.config(s"invalid $k"))
    val limits = Limits
      .checked(
        int("maxBlocks"),
        int("maxBlockBytes"),
        long("maxInputBytes"),
        long("maxStoredBytes"),
        int("maxFiles"),
        int("maxDurationSeconds")
      )
      .fold(FetchError.config, identity)
    val after = Point.parse(m("after")).fold(FetchError.config, identity)
    val end =
      if m("end") == "-" then None
      else Some(Point.parse(m("end")).fold(FetchError.config, identity))
    val count = if m("count") == "-" then None else Some(int("count"))
    val spec = FetchSpec.checked(after, count, end, limits).fold(FetchError.config, identity)
    if !Digests.valid(m("manifestSha256")) then FetchError.config("invalid manifest hash")
    new LocalConfig(
      LocalFiles.child(root.toAbsolutePath.normalize, m("manifest")),
      m("manifestSha256"),
      spec
    )

object LocalBlockSource:
  private final case class Entry(path: Path, size: Int, hash: String)
  def open[F[_]: Async](config: LocalConfig): F[BlockSource[F]] =
    val F = Async[F]
    F.blocking {
      val limits = config.spec.limits
      val bytes = LocalFiles.read(config.manifest, math.min(1024 * 1024L, limits.maxInputBytes))
      if Digests.sha256(bytes) != config.manifestHash then
        FetchError.integrity("source manifest SHA256 mismatch")
      val lines = LocalFiles.lines(LocalFiles.utf8(bytes))
      if lines.size < 2 || lines.size > 4098 || lines.head != "local-blocks-v1" then
        FetchError.config("invalid local manifest")
      val sourceId = lines(1).split("\t", -1).toList match
        case label :: predecessor :: Nil =>
          SourceIdentity
            .checked(
              config.manifestHash,
              label,
              Point.parse(predecessor).fold(FetchError.config, identity)
            )
            .fold(FetchError.config, identity)
        case _ => FetchError.config("invalid source identity")
      val entries = lines.drop(2).map { line =>
        line.split("\t", -1).toList match
          case name :: size :: hash :: Nil =>
            val n = size.toIntOption.getOrElse(FetchError.config("invalid source size"))
            if n <= 0 || n > limits.maxBlockBytes || !Digests.valid(hash) then
              FetchError.config("invalid or oversized source entry")
            Entry(LocalFiles.child(config.manifest.getParent, name), n, hash)
          case _ => FetchError.config("invalid source entry")
      }
      if entries.map(_.path).distinct.size != entries.size then
        FetchError.config("duplicate source path")
      (sourceId, entries, bytes.length.toLong)
    }.flatMap { case (id, entries, metadataSize) =>
      Ref.of[F, Long](metadataSize).map { readBytes =>
        new BlockSource[F]:
          def identity: SourceIdentity = id
          def inputBytes: F[Long] = readBytes.get
          def open: Resource[F, BlockCursor[F]] = Resource.eval(Ref.of[F, Int](0)).map { position =>
            new BlockCursor[F]:
              def next: F[SourceEvent] = position.get.flatMap { i =>
                if i >= entries.size then F.pure(SourceEvent.End)
                else
                  val entry = entries(i)
                  readBytes
                    .modify { used =>
                      if entry.size > config.spec.limits.maxInputBytes - used then (used, false)
                      else (used + entry.size, true)
                    }
                    .flatMap { admitted =>
                      if !admitted then F.raiseError(new FetchError(3, "inputBudget"))
                      else
                        F.blocking {
                          val raw = LocalFiles.read(entry.path, entry.size)
                          if raw.length != entry.size || Digests.sha256(raw) != entry.hash then
                            FetchError.integrity("source block size/SHA256 mismatch")
                          SourceEvent.Raw(Bytes.fromArray(raw))
                        }.flatTap(_ => position.set(i + 1))
                    }
              }
          }
      }
    }
