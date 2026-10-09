// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import lab.cbor.Bytes
import ReferenceJson.Json

/** Explicit private authority artifacts. Pending records are never resumable acknowledgements. The
  * checkpoint store and receipt directory must be separate. No record is overwritten.
  */
private[lab] object NodeDurableReceipts:
  val MaxBytes = 4096
  val AckFormat = "node-durable-acknowledged-v1"
  val PendingFormat = "node-durable-pending-v1"
  final case class Reference(path: Path, sha256: Bytes, generation: Long)
  final class Loaded private[NodeDurableReceipts] (
      val token: ValidatedCheckpoint.Token,
      val reference: Reference
  )
  private def raw(s: String): Bytes =
    Bytes.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  private def digest(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def canonical(j: Json): Bytes = raw(ValidatedRestartCapture.canonical(j) + "\n")
  private def number(g: Long): Json = Json.Str(g.toString)
  private def token(t: ValidatedCheckpoint.Token): Json = Json.Obj(
    Map(
      "storeId" -> Json.Str(t.storeId.hex),
      "contextId" -> Json.Str(t.contextId.hex),
      "generation" -> number(t.generation),
      "digest" -> Json.Str(t.digest.hex)
    )
  )
  private[lab] def acknowledgedBytes(t: ValidatedCheckpoint.Token): Bytes = token(t) match
    case Json.Obj(fields) =>
      canonical(
        Json.Obj(fields ++ Map("format" -> Json.Str(AckFormat), "capacity" -> Json.Num("8")))
      )
    case _ => throw new IllegalStateException("token rendering invariant")
  private def pendingBytes(t: CoherentSequence.PendingTokens): Bytes = canonical(
    Json.Obj(
      Map(
        "format" -> Json.Str(PendingFormat),
        "capacity" -> Json.Num("8"),
        "previous" -> t.previous.fold[Json](Json.Lit("null"))(token),
        "proposed" -> token(t.next)
      )
    )
  )

  def validatePaths(store: Path, receipts: Path, input: Option[Path]): Either[String, Unit] =
    // java Iterator is deliberately inspected without resolving paths or touching storage.
    def noTraversal(p: Path): Boolean =
      if p == null || !p.isAbsolute || p != p.normalize() then false
      else
        val it = p.iterator()
        var safe = true
        while it.hasNext do if it.next().toString == ".." then safe = false
        safe
    if !noTraversal(store) || !noTraversal(receipts) || input.exists(p => !noTraversal(p)) then
      Left("storage and receipt paths must be absolute and normalized without traversal")
    else if store.startsWith(receipts) || receipts.startsWith(store) || input.exists(
        _.startsWith(store)
      )
    then Left("receipt authority must be external to the checkpoint store")
    else Right(())
  private def noLinks(path: Path): Unit =
    val absolute = path.toAbsolutePath.normalize()
    var cursor: Path = absolute
    while cursor != null do
      require(!Files.isSymbolicLink(cursor), "symbolic links are not accepted for receipt paths")
      cursor = cursor.getParent
  private def read(path: Path): Bytes =
    noLinks(path)
    val attributes =
      Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    require(
      attributes.isRegularFile && attributes.size > 0 && attributes.size <= MaxBytes,
      "bounded regular receipt required"
    )
    val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    val buffer = ByteBuffer.allocate(MaxBytes + 1)
    try
      var count = channel.read(buffer)
      while count >= 0 && buffer.hasRemaining do count = channel.read(buffer)
    finally channel.close()
    require(buffer.position() > 0 && buffer.position() <= MaxBytes, "receipt byte bound")
    Bytes.fromArray(buffer.array().take(buffer.position()))
  private[lab] def parse(
      bytes: Bytes,
      pin: Bytes,
      expectedContext: Bytes
  ): Either[String, ValidatedCheckpoint.Token] =
    try
      require(bytes.size > 0 && bytes.size <= MaxBytes, "receipt byte bound")
      require(
        pin.size == 32 && expectedContext.size == 32 && digest(bytes) == pin,
        "receipt SHA256 mismatch"
      )
      val fields = ReferenceJson.parse(bytes) match
        case Json.Obj(fields) => fields
        case _                => throw new IllegalArgumentException("receipt object required")
      require(
        fields.keySet == Set("format", "capacity", "storeId", "contextId", "generation", "digest"),
        "exact acknowledged receipt fields required"
      )
      require(
        fields("format") == Json.Str(AckFormat) && fields("capacity") == Json.Num("8"),
        "acknowledged v1 capacity8 receipt required"
      )
      def hash(key: String): Bytes = fields(key) match
        case Json.Str(value) if value.matches("[0-9a-f]{64}") => Bytes.fromHex(value).toOption.get
        case _ => throw new IllegalArgumentException("canonical receipt hash required")
      val generation = fields("generation") match
        case Json.Str(value)
            if value.matches("0|[1-9][0-9]{0,18}") && value.toLongOption.exists(_ >= 0) =>
          value.toLong
        case _ => throw new IllegalArgumentException("canonical receipt generation required")
      val context = hash("contextId")
      require(context == expectedContext, "independent receipt context mismatch")
      Right(ValidatedCheckpoint.Token(hash("storeId"), context, generation, hash("digest")))
    catch
      case scala.util.control.NonFatal(_) =>
        Left(
          "invalid acknowledged receipt: exact shape, independent hash/context and capacity8 required"
        )
  def load(path: Path, pin: Bytes, expectedContext: Bytes, store: Path): IO[Loaded] = IO.blocking {
    require(
      path.isAbsolute && path == path.normalize() && !path.startsWith(
        store.toAbsolutePath.normalize()
      ),
      "external normalized receipt path required"
    )
    val bytes = read(path)
    val value =
      parse(bytes, pin, expectedContext).fold(s => throw new IllegalArgumentException(s), identity)
    new Loaded(value, Reference(path, pin, value.generation))
  }
  private def forceDirectory(directory: Path): Unit =
    val channel = FileChannel.open(directory, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()
  private def write(
      directory: Path,
      store: Path,
      kind: String,
      generation: Long,
      bytes: Bytes
  ): Reference =
    validatePaths(store, directory, None).fold(s => throw new IllegalArgumentException(s), identity)
    require(bytes.size > 0 && bytes.size <= MaxBytes, "receipt output bound")
    noLinks(directory)
    if !Files.exists(directory, LinkOption.NOFOLLOW_LINKS) then
      // The caller supplies an existing parent; do not silently create an arbitrary tree.
      Files.createDirectory(directory)
      forceDirectory(directory.getParent)
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "receipt directory required")
    val pin = digest(bytes)
    val path = directory.resolve(generation.toString + "-" + kind + "-" + pin.hex + ".json")
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      require(read(path) == bytes, "immutable receipt conflict")
      val channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
      try channel.force(true)
      finally channel.close()
      forceDirectory(directory)
      forceDirectory(directory.getParent)
    else
      // CREATE_NEW plus NOFOLLOW forbids replacement. Partial files are kept as evidence, but
      // cannot pass an independent expected SHA256 on resume. force occurs before callback return.
      val channel = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
      )
      try
        val buffer = ByteBuffer.wrap(bytes.toArray)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
      finally channel.close()
      forceDirectory(directory)
    // Also repair a preceding attempt that created the directory but failed its parent sync.
    forceDirectory(directory.getParent)
    Reference(path, pin, generation)
  def recordPending(
      directory: Path,
      store: Path,
      pending: CoherentSequence.PendingTokens
  ): IO[Unit] =
    IO.blocking(write(directory, store, "pending", pending.next.generation, pendingBytes(pending)))
      .void
  def recordAcknowledged(
      directory: Path,
      store: Path,
      value: ValidatedCheckpoint.Token
  ): IO[Reference] =
    IO.blocking(write(directory, store, "acknowledged", value.generation, acknowledgedBytes(value)))
