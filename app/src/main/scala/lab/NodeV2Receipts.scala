// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import lab.cbor.Bytes
import ReferenceJson.Json

/** Diagnostic exports only. The separately persisted controller journal selects resume authority;
  * these files cannot select a generation or replace a missing journal.
  */
private[lab] object NodeV2Receipts:
  val Format = "node-v2-acknowledged-v1"
  val MaxBytes = 8192
  val MaxCaptureBytes = 20 * 1024 * 1024
  final case class Reference(path: Path, sha256: Bytes)
  private def str(s: String): Json = Json.Str(s)
  private def hash(b: Bytes): Json = str(b.hex)
  def claimJson(c: LocalDerivedCheckpoint.Claim): Json = Json.Obj(
    Map(
      "token" -> Json.Obj(
        Map(
          "storeId" -> hash(c.token.storeId),
          "contextId" -> hash(c.token.contextId),
          "sessionId" -> hash(c.token.sessionId),
          "generation" -> str(c.token.generation.toString),
          "digest" -> hash(c.token.digest)
        )
      ),
      "format" -> str(c.format),
      "profile" -> str(c.profile),
      "authority" -> str(c.authority),
      "anchorId" -> hash(c.anchorId),
      "finalId" -> hash(c.finalId),
      "compactedBlocks" -> str(c.compactedBlocks.toString),
      "revision" -> str(c.revision.toString)
    )
  )
  def bindingJson(b: ControllerJournalCodec.Binding): Json = Json.Obj(
    Map(
      "journal" -> str(b.root),
      "checkpoint" -> str(b.checkpoint),
      "storeId" -> str(b.store.id.value),
      "contextId" -> str(b.store.context.value),
      "profile" -> str(b.profile),
      "format" -> str(b.format),
      "authority" -> str(b.authority),
      "launchPolicy" -> str(b.launchPolicy)
    )
  )
  def bytes(
      c: LocalDerivedCheckpoint.Claim,
      binding: ControllerJournalCodec.Binding,
      capacity: Int
  ): Bytes =
    Bytes.fromArray(
      (ValidatedRestartCapture.canonical(
        Json.Obj(
          Map(
            "format" -> str(Format),
            "diagnosticOnly" -> Json.Lit("true"),
            "capacity" -> Json.Num(capacity.toString),
            "binding" -> bindingJson(binding),
            "claim" -> claimJson(c)
          )
        )
      ) + "\n").getBytes("UTF-8")
    )
  def validatePaths(
      store: Path,
      journal: Path,
      receipts: Path,
      seed: Option[Path]
  ): Either[String, Unit] =
    for
      _ <- NodeDurableReceipts.validatePaths(store, receipts, seed)
      _ <- NodeDurableReceipts.validatePaths(journal, receipts, seed)
      _ <- NodeDurableReceipts.validatePaths(store, journal, seed)
      _ <- Either.cond(
        !seed.exists(_.startsWith(receipts)),
        (),
        "seed must be external to owned directories"
      )
    yield ()
  private def noLinks(path: Path): Unit =
    var p = path
    while p != null do
      require(!Files.isSymbolicLink(p), "symlink path rejected")
      p = p.getParent
  private def read(path: Path, limit: Int): Bytes =
    require(path.isAbsolute && path == path.normalize(), "absolute normalized path required")
    noLinks(path)
    val a = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    require(a.isRegularFile && a.size > 0 && a.size <= limit, "bounded regular file required")
    val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    val buffer = ByteBuffer.allocate(limit + 1)
    try
      var n = channel.read(buffer)
      while n >= 0 && buffer.hasRemaining do n = channel.read(buffer)
    finally channel.close()
    require(buffer.position() > 0 && buffer.position() <= limit, "file byte bound")
    Bytes.fromArray(buffer.array().take(buffer.position()))
  def seed(
      path: Path,
      pin: Bytes,
      context: SequenceInput.Context,
      through: lab.network.ChainSync.Point
  ): IO[CombinedLocalV2.Bootstrap] = IO.blocking {
    val original = read(path, MaxCaptureBytes)
    require(
      pin.size == 32 && ClusterHeaderObservation.sha256(original) == pin,
      "seed capture pin mismatch"
    )
    val text = java.nio.charset.StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(original.toArray))
      .toString
    val lines = text.linesIterator.filter(_.nonEmpty).take(9).toVector
    require(lines.nonEmpty && lines.size <= 8, "seed must contain 1..8 original records only")
    val originals = lines.map { line =>
      val json = ReferenceJson.parse(Bytes.fromArray(line.getBytes("UTF-8")))
      require(
        ReferenceJson.field(json, "record") == Json.Str("transfer-range-block"),
        "seed original record required"
      )
      def bytes(key: String) = Bytes
        .fromHex(ReferenceJson.string(ReferenceJson.field(json, key)))
        .fold(e => throw new IllegalArgumentException(e), identity)
      val header = bytes("headerEnvelopeHex"); val block = bytes("rawBlockHex")
      require(
        header.size > 0 && header.size <= 65535 && block.size > 0 && block.size <= 1048576,
        "seed original byte bounds"
      )
      BoundedChainFollower.Original(header, block)
    }
    require(originals.nonEmpty && originals.size <= 8, "seed original count 1..8")
    require(
      originals.exists(o =>
        SequenceInput
          .block(o)
          .toOption
          .exists(b =>
            lab.network.ChainSync.Point.Block(
              lab.network.ChainSync.UInt64.from(b.header.slot).toOption.get,
              b.header.hash
            ) == through
          )
      ),
      "compact point must belong to supplied originals"
    )
    // Combined.create independently replays every original through the complete tuple before compaction.
    CombinedLocalV2.Bootstrap(context, originals, through)
  }
  private def forceDirectory(path: Path): Unit =
    val c = FileChannel.open(path, StandardOpenOption.READ)
    try c.force(true)
    finally c.close()
  def record(
      directory: Path,
      binding: ControllerJournalCodec.Binding,
      capacity: Int,
      claim: LocalDerivedCheckpoint.Claim
  ): IO[Reference] = IO.blocking {
    validatePaths(Path.of(binding.checkpoint), Path.of(binding.root), directory, None)
      .fold(e => throw new IllegalArgumentException(e), identity)
    val original = bytes(claim, binding, capacity)
    require(original.size <= MaxBytes, "v2 receipt size bound")
    noLinks(directory)
    if !Files.exists(directory, LinkOption.NOFOLLOW_LINKS) then Files.createDirectory(directory)
    require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS), "receipt directory required")
    val pin = ClusterHeaderObservation.sha256(original)
    val path = directory.resolve(s"${claim.token.generation}-v2-acknowledged-${pin.hex}.json")
    if Files.exists(path, LinkOption.NOFOLLOW_LINKS) then
      require(read(path, MaxBytes) == original, "immutable v2 receipt conflict")
      val c = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
      try c.force(true)
      finally c.close()
    else
      val c = FileChannel.open(
        path,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS
      )
      try
        val buffer = ByteBuffer.wrap(original.toArray)
        while buffer.hasRemaining do c.write(buffer)
        c.force(true)
      finally c.close()
    forceDirectory(directory)
    forceDirectory(directory.getParent)
    Reference(path, pin)
  }
