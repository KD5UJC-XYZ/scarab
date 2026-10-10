// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files, Path, LinkOption, StandardOpenOption as Open}
import lab.cbor.Bytes
import ReferenceJson.{Json as J, field, string}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Opt-in supervisor-controlled clean restart. No default authorizer or crash durability. */
private[lab] object PlutusServiceCheckpoint:
  private val R = RestrictedRuntimeCheckpoint
  import PlutusResearchIO.{get, sha, record, text, num, point, bool}
  val optionNames = Set(
    "--checkpoint-after",
    "--store-id",
    "--session-id",
    "--generation",
    "--restore-checkpoint",
    "--restore-authority",
    "--restore-authority-sha256"
  )
  final case class Identity(storeId: Bytes, sessionId: Bytes, generation: Long)
  final case class Restore(checkpoint: Path, authority: Path, authoritySHA256: Bytes)
  final case class Mode(checkpointAfter: Option[Int], identity: Identity, restore: Option[Restore]):
    def exportGeneration: Long =
      if restore.nonEmpty then Math.addExact(identity.generation, 1L) else identity.generation
  final case class Started(
      runtime: CoherentSequence.Runtime[IO],
      snapshot: CoherentSequence.Snapshot,
      restored: Boolean
  )
  final case class Saved(publication: R.Publication, requestSHA256: Bytes)
  def options(
      values: Map[String, String],
      maxBlocks: Int,
      output: Path
  ): Either[String, Option[Mode]] =
    try
      val supplied = values.keySet.intersect(optionNames)
      if supplied.isEmpty then Right(None)
      else
        val identityKeys = Set("--store-id", "--session-id", "--generation")
        val restoreKeys =
          Set("--restore-checkpoint", "--restore-authority", "--restore-authority-sha256")
        require(identityKeys.subsetOf(supplied), "checkpoint identity group must be complete")
        require(
          supplied.contains("--checkpoint-after") || supplied.exists(restoreKeys.contains),
          "checkpoint or restore mode required"
        )
        require(
          !supplied.exists(restoreKeys.contains) || restoreKeys.subsetOf(supplied),
          "restore group must be complete"
        )
        def hash(key: String): Bytes =
          require(values(key).matches("[0-9a-f]{64}"), "canonical checkpoint hash required")
          get(Bytes.fromHex(values(key)))
        val g = values("--generation")
        require(g.matches("0|[1-9][0-9]{0,18}"), "canonical generation required")
        val generation = BigInt(g); require(generation <= Long.MaxValue, "generation bound")
        val identity = Identity(hash("--store-id"), hash("--session-id"), generation.toLong)
        val after = values.get("--checkpoint-after").map { n =>
          require(n.matches("[1-8]"), "checkpoint-after must be 1..8")
          val count = n.toInt;
          require(count <= maxBlocks, "checkpoint budget exceeds service budget"); count
        }
        val restore = Option.when(restoreKeys.subsetOf(supplied)) {
          def path(key: String): Path =
            val p = Path.of(values(key));
            require(
              p.isAbsolute && p.normalize == p && !p.startsWith(output),
              "restore inputs must be absolute normalized paths outside new output"
            )
            p
          val checkpoint = path("--restore-checkpoint"); val authority = path("--restore-authority")
          require(checkpoint != authority, "checkpoint and authority must be separate files")
          Restore(checkpoint, authority, hash("--restore-authority-sha256"))
        }
        val mode = Mode(after, identity, restore)
        if after.nonEmpty then mode.exportGeneration
        Right(Some(mode))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid checkpoint mode"))
  def claim(c: R.Claim): J = record(
    "storeId" -> text(c.storeId.hex),
    "sessionId" -> text(c.sessionId.hex),
    "generation" -> num(c.generation),
    "publicationSHA256" -> text(c.publicationSHA256.hex),
    "contextId" -> text(c.contextId.hex),
    "sourceJoinId" -> text(c.sourceJoinId.hex),
    "manifestSHA256" -> text(c.manifestSHA256.hex),
    "sourcePoint" -> point(c.sourcePoint),
    "terminalPoint" -> point(c.terminalPoint),
    "historicalStateId" -> text(c.historicalStateId.hex),
    "historicalRevision" -> num(c.historicalRevision),
    "capacity" -> num(c.capacity),
    "originalCount" -> num(c.originalCount)
  )
  private def read(path: Path, maximum: Int): IO[Bytes] = IO.blocking {
    require(
      Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
      "regular nonsymlink restore input required"
    )
    val in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)
    try
      val bytes = in.readNBytes(maximum + 1)
      require(bytes.nonEmpty && bytes.length <= maximum, "restore input byte bound")
      Bytes.fromArray(bytes)
    finally in.close()
  }
  private[lab] def authority(
      raw: Bytes,
      pinned: Bytes,
      identity: Identity,
      expected: R.Claim
  ): Either[String, Unit] =
    try
      require(
        raw != null && raw.size > 0 && raw.size <= 65536 && sha(raw) == pinned,
        "independent authority file pin mismatch"
      )
      val parsed = ReferenceJson.parse(raw)
      parsed match
        case J.Obj(fields) =>
          require(fields.keySet == Set("schema", "decision", "claim"), "exact authority fields")
        case _ => throw new IllegalArgumentException("authority object required")
      require(
        string(field(parsed, "schema")) == "plutus-service-restore-authority-v1" &&
          string(field(parsed, "decision")) == "accept" && field(parsed, "claim") == claim(
            expected
          ),
        "independent controller acceptance mismatch"
      )
      require(
        expected.storeId == identity.storeId && expected.sessionId == identity.sessionId && expected.generation == identity.generation,
        "configured current store/session/generation mismatch"
      )
      Right(())
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse("invalid authority record"))
  def start(
      mode: Option[Mode],
      joined: NativeLedgerV2.Checked,
      manifestSHA256: Bytes
  ): IO[Started] =
    val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    val started = mode.flatMap(_.restore) match
      case None =>
        CoherentSequence
          .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
          .map(get(_))
          .flatMap(r => r.snapshot.map(Started(r, _, false)))
      case Some(spec) =>
        for
          checkpoint <- read(spec.checkpoint, R.MaxBytes)
          envelope <- IO(get(R.decode(checkpoint)))
          _ <- IO(
            require(
              envelope.claim.manifestSHA256 == manifestSHA256,
              "configured initial manifest mismatch"
            )
          )
          rawAuthority <- read(spec.authority, 65536)
          accepted <- R
            .accept[IO](
              envelope,
              new R.ControllerAuthority[IO]:
                def authorize(c: R.Claim) =
                  IO.pure(authority(rawAuthority, spec.authoritySHA256, mode.get.identity, c))
            )
            .map(get(_))
          restored <- R.recover[IO](checkpoint, Some(accepted), joined, 10.seconds).map(get(_))
        yield Started(restored.runtime, restored.snapshot, true)
    started.flatTap { s =>
      IO {
        mode
          .flatMap(_.checkpointAfter)
          .foreach(n =>
            require(
              s.snapshot.state.depth + n <= math.min(8, s.runtime.maxBlocks),
              "restored depth plus checkpoint budget exceeds linear window"
            )
          )
      }
    }
  private[lab] def publishBytes(path: Path, bytes: Bytes, maximum: Int): IO[Unit] = IO.blocking {
    require(bytes.size > 0 && bytes.size <= maximum, "checkpoint publication byte bound")
    val tmp = Files.createTempFile(path.getParent, ".checkpoint-", ".part")
    try
      Files.write(tmp, bytes.toArray, Open.WRITE, Open.TRUNCATE_EXISTING)
      Files.createLink(path, tmp)
      ()
    finally Files.deleteIfExists(tmp)
  }
  def save(
      root: Path,
      snapshot: CoherentSequence.Snapshot,
      joined: NativeLedgerV2.Checked,
      manifest: Bytes,
      mode: Mode
  ): IO[Saved] = saveObserved(root, snapshot, joined, manifest, mode, IO.unit)
  private[lab] def saveObserved(
      root: Path,
      snapshot: CoherentSequence.Snapshot,
      joined: NativeLedgerV2.Checked,
      manifest: Bytes,
      mode: Mode,
      betweenFiles: IO[Unit]
  ): IO[Saved] =
    for
      publication <- IO {
        require(mode.checkpointAfter.nonEmpty, "checkpoint export opt-in required")
        val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
        val sources = CoherentStakeImages.Sources(
          joined.ledger.epochComponents.parameters.genesisOriginal,
          joined.ledger.epochComponents.parameters.current.original,
          manifest
        )
        val binding = CoherentStakeImages.SourceBinding(
          context.id,
          joined.id,
          context.stakeSourceId,
          sha(sources.genesis),
          sha(sources.parameters),
          sha(manifest)
        )
        get(
          R.encode(
            snapshot,
            context,
            sources,
            binding,
            mode.identity.storeId,
            mode.identity.sessionId,
            mode.exportGeneration
          )
        )
      }
      request = record(
        "schema" -> text("plutus-service-checkpoint-publication-v1"),
        "checkpointFile" -> text("checkpoint.bin"),
        "checkpointBytes" -> num(publication.bytes.size),
        "claim" -> claim(publication.claim),
        "restoreAuthorized" -> bool(false),
        "crashDurable" -> bool(false),
        "scope" -> text("linear-epoch-zero-max8")
      )
      encoded = EvidenceJson.encode(request)
      _ <- publishBytes(root.resolve("checkpoint.bin"), publication.bytes, R.MaxBytes)
      _ <- betweenFiles
      _ <- publishBytes(root.resolve("checkpoint-request.json"), encoded, 65536)
    yield Saved(publication, sha(encoded))
