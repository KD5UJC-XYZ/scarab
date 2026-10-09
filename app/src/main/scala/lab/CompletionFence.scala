// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import java.nio.file.{Files, LinkOption, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.network.ChainSync
import scala.concurrent.duration.*

/** Local test orchestration authority only; not a ledger or consensus certificate. */
private[lab] object CompletionFence:
  final case class Settings(path: Path, id: String, phase: String, minimum: Int)
  final case class Target(
      id: String,
      phase: String,
      context: String,
      depth: Int,
      blockNo: BigInt,
      slot: BigInt,
      hash: String
  ):
    def point: ChainSync.Point = ChainSync.Point.Block(
      ChainSync.UInt64.from(slot).toOption.get,
      Bytes.fromHex(hash).toOption.get
    )
  final case class Accepted(target: Target, sha256: String, identity: String)
  def sha256(raw: Array[Byte]): String =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(raw)).hex
  def parse(raw: Array[Byte], settings: Settings, context: String, maximum: Int): Target =
    require(raw.nonEmpty && raw.length <= 2048, "fence size")
    val text = new String(raw, UTF_8)
    require(java.util.Arrays.equals(text.getBytes(UTF_8), raw), "fence UTF-8")
    val lines = text.split("\n", -1).toVector
    val names =
      Vector("version", "fenceId", "phase", "contextId", "depth", "blockNo", "slot", "hash")
    require(lines.size == 9 && lines.last.isEmpty, "fence exactly eight terminated lines")
    val values = names.zip(lines.take(8)).map { (name, line) =>
      require(line.startsWith(name + "="), "fence canonical field order")
      line.substring(name.length + 1)
    }
    require(
      values(0) == "live-completion-fence-v1" && values(1) == settings.id &&
        values(2) == settings.phase && values(3) == context,
      "fence binding"
    )
    def number(i: Int): BigInt =
      require(values(i).matches("0|[1-9][0-9]{0,19}"), "fence canonical integer")
      val n = BigInt(values(i))
      require(ChainSync.UInt64.from(n).isRight, "fence integer bound")
      n
    val depth = number(4)
    require(depth >= settings.minimum && depth <= maximum && maximum <= 16, "fence depth bound")
    require(values(7).matches("[0-9a-f]{64}"), "fence hash")
    Target(settings.id, settings.phase, context, depth.toInt, number(5), number(6), values(7))

  trait Control[F[_]]:
    def settings: Settings
    def read: F[Option[Accepted]]
    def await: F[Accepted]
    def verify(accepted: Accepted): F[Unit]

  /** Every read checks inode/metadata and bytes. Atomic replacement, symlinks and rewriting reject.
    * The path is initially absent; a controller installs it once by atomic rename.
    */
  def create[F[_]: Async](s: Settings, context: String, maximum: Int): F[Control[F]] =
    val F = Async[F]
    for
      _ <- F.blocking {
        require(
          s.path.isAbsolute && s.id.matches("[0-9a-f]{64}") && Set("A", "B")(s.phase),
          "fence settings"
        )
        require(!Files.exists(s.path, LinkOption.NOFOLLOW_LINKS), "fence already exists")
        require(s.path.getParent.toRealPath() == s.path.getParent, "fence parent must be canonical")
      }
      saved <- Ref.of[F, Option[Accepted]](None)
    yield new Control[F]:
      val settings = s
      private def load: F[Option[Accepted]] = F.blocking {
        if !Files.exists(s.path, LinkOption.NOFOLLOW_LINKS) then None
        else
          def attr =
            Files.readAttributes(s.path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          val before = attr
          require(
            before.isRegularFile && before.size > 0 && before.size <= 2048 &&
              before.fileKey != null,
            "fence regular bounded identified file required"
          )
          val in = Files.newInputStream(s.path, LinkOption.NOFOLLOW_LINKS)
          val raw =
            try in.readNBytes(2049)
            finally in.close()
          val after = attr
          def identity(a: BasicFileAttributes) =
            s"${a.fileKey}:${a.creationTime}:${a.lastModifiedTime}:${a.size}"
          require(identity(before) == identity(after), "fence changed during read")
          Some(Accepted(parse(raw, s, context, maximum), sha256(raw), identity(after)))
      }
      def read: F[Option[Accepted]] = load.flatMap { now =>
        saved
          .modify {
            case None                           => (now, Right(now))
            case Some(old) if now.contains(old) => (Some(old), Right(now))
            case old =>
              (old, Left(new IllegalArgumentException("fence replaced, removed or rewritten")))
          }
          .flatMap(_.liftTo[F])
      }
      def await: F[Accepted] = read.flatMap {
        case Some(value) => F.pure(value)
        case None        => F.sleep(10.millis) *> await
      }
      def verify(a: Accepted): F[Unit] = read.flatMap(now =>
        F.raiseUnless(now.contains(a))(new IllegalArgumentException("fence identity changed"))
      )

  def matches(target: Target, state: CoherentSequence.State): Boolean =
    state.depth == target.depth && state.acquisition.tip == target.point &&
      state.certificates.state.tip.blockNo == target.blockNo
  def admissible(
      target: Target,
      state: CoherentSequence.State,
      context: SequenceInput.Context
  ): Boolean =
    target.context == context.id.hex && target.blockNo == context.certificateSeed.tip.blockNo + target.depth &&
      target.slot / context.nonces.context.epochLength == context.epoch &&
      state.depth <= target.depth &&
      (state.depth < target.depth || matches(target, state))
