// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.chain.CardanoBlockIndex.IndexedBlock
import java.security.MessageDigest
import scala.concurrent.duration.*

final class FetchError(val code: Int, message: String) extends RuntimeException(message)
object FetchError:
  def config(message: String): Nothing = throw new FetchError(2, message)
  def integrity(message: String): Nothing = throw new FetchError(5, message)
  def output(message: String): Nothing = throw new FetchError(6, message)

final class Point private (val slot: BigInt, val hash: String):
  def encoded: String = s"$slot:$hash"
  override def equals(other: Any): Boolean = other match
    case p: Point => slot == p.slot && hash == p.hash
    case _        => false
  override def hashCode(): Int = (slot, hash).hashCode
object Point:
  def parse(s: String): Either[String, Point] = s.split(":", -1).toList match
    case slot :: hash :: Nil if slot.matches("0|[1-9][0-9]{0,19}") && Digests.valid(hash) =>
      val n = BigInt(slot)
      Either.cond(n < (BigInt(1) << 64), new Point(n, hash), "slot exceeds UInt64")
    case _ => Left("expected UINT64:lowercase-64-hex point; origin is unsupported")
  def of(b: IndexedBlock): Point = new Point(b.slot, b.headerHash.hex)

object Digests:
  def valid(s: String): Boolean = s.matches("[0-9a-f]{64}")
  def sha256(bytes: Array[Byte]): String =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes)).hex
  def text(s: String): String = sha256(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))

final class Limits private (
    val maxBlocks: Int,
    val maxBlockBytes: Int,
    val maxInputBytes: Long,
    val maxStoredBytes: Long,
    val maxFiles: Int,
    val maxDuration: FiniteDuration
)
object Limits:
  def checked(
      blocks: Int,
      blockBytes: Int,
      inputBytes: Long,
      storedBytes: Long,
      files: Int,
      seconds: Int
  ): Either[String, Limits] =
    Either.cond(
      blocks > 0 && blocks <= 4096 && blockBytes > 0 && blockBytes <= 1024 * 1024 &&
        inputBytes > 0 && storedBytes > 0 && files >= 8 && files <= 20000 && seconds > 0 && seconds <= 86400,
      new Limits(blocks, blockBytes, inputBytes, storedBytes, files, seconds.seconds),
      "invalid limits"
    )

final class FetchSpec private (
    val after: Point,
    val count: Option[Int],
    val end: Option[Point],
    val limits: Limits
):
  def identity: String =
    s"${after.encoded}\t${count.fold("-")(_.toString)}\t${end.fold("-")(_.encoded)}\tfail"
object FetchSpec:
  def checked(
      after: Point,
      count: Option[Int],
      end: Option[Point],
      limits: Limits
  ): Either[String, FetchSpec] =
    Either.cond(
      (count.nonEmpty || end.nonEmpty) && count.forall(n => n > 0 && n <= 4096) &&
        end.forall(p => p != after && p.slot > after.slot),
      new FetchSpec(after, count, end, limits),
      "selection needs positive count or a later inclusive end"
    )

final class SourceIdentity private (val digest: String, val label: String, val predecessor: Point):
  def encoded: String = s"$digest\t$label\t${predecessor.encoded}"
object SourceIdentity:
  def checked(digest: String, label: String, predecessor: Point): Either[String, SourceIdentity] =
    Either.cond(
      Digests.valid(digest) && label.matches("[A-Za-z0-9._-]{1,100}"),
      new SourceIdentity(digest, label, predecessor),
      "invalid pinned source identity"
    )

enum SourceEvent:
  case Raw(bytes: Bytes)
  case Rollback(point: Point)
  case End
trait BlockCursor[F[_]]:
  def next: F[SourceEvent]
trait BlockSource[F[_]]:
  def identity: SourceIdentity
  def open: Resource[F, BlockCursor[F]]
  def inputBytes: F[Long]

final case class Record(point: Point, parent: String, rawHash: String, size: Int, era: String):
  def encoded: String = s"${point.encoded}\t$parent\t$rawHash\t$size\t$era"
object Record:
  def of(b: IndexedBlock): Record =
    Record(Point.of(b), b.parentHash.hex, b.rawSha256.hex, b.rawBytes.size, b.era)
  private[fetcher] def parse(s: String): Record = s.split("\t", -1).toList match
    case point :: parent :: hash :: size :: era :: Nil =>
      val p = Point.parse(point).fold(FetchError.output, identity)
      val n = size.toIntOption.getOrElse(FetchError.output("invalid record size"))
      if !Digests.valid(parent) || !Digests.valid(hash) || n <= 0 || !lab.chain.CardanoBlockIndex
          .supportedEraLabels(era)
      then FetchError.output("invalid record")
      Record(p, parent, hash, n, era)
    case _ => FetchError.output("invalid record fields")

final case class Snapshot(records: Vector[Record], manifestHash: String)
trait SegmentStore[F[_]]:
  def selectionIdentity: String
  def sourceIdentity: SourceIdentity
  def snapshot: F[Snapshot]
  def append(block: IndexedBlock): F[Snapshot]

final case class FetchResult(
    reason: String,
    snapshot: Snapshot,
    inputBytes: Long,
    sourceLabel: String = "unspecified",
    sourceManifestSha256: String = ""
):

  def complete: Boolean = reason == "countReached" || reason == "endReached"
  private def quote(s: String): String =
    "\"" + s.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case '\n'         => "\\n"
      case '\r'         => "\\r"
      case '\t'         => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
  def json: String =
    val status =
      if reason == "inspected" then "inspected" else if complete then "complete" else "incomplete"
    val first = snapshot.records.headOption.fold("null")(r => quote(r.point.encoded))
    val last = snapshot.records.lastOption.fold("null")(r => quote(r.point.encoded))
    s"""{"status":${quote(status)},"reason":${quote(reason)},"sourceLabel":${quote(
        sourceLabel
      )},"sourceManifestSha256":${quote(
        sourceManifestSha256
      )},"first":$first,"last":$last,"count":${snapshot.records.size},"admittedSourceBytes":$inputBytes,"manifestSha256":${quote(
        snapshot.manifestHash
      )},"bytesPreserved":true,"networkAuthenticated":false,"ledgerValidated":false,"consensusValidated":false,"mithrilAuthenticated":false,"referenceReplayChecked":false}"""

object Fetch:
  def run[F[_]: Async](
      spec: FetchSpec,
      source: BlockSource[F],
      store: SegmentStore[F]
  ): F[FetchResult] =
    val F = Async[F]
    def result(reason: String): F[FetchResult] =
      (store.snapshot, source.inputBytes).mapN((snapshot, admitted) =>
        FetchResult(reason, snapshot, admitted, source.identity.label, source.identity.digest)
      )
    val checkBinding = F.raiseUnless(
      store.selectionIdentity == spec.identity && store.sourceIdentity.encoded == source.identity.encoded
    )(new FetchError(6, "source/selection binding mismatch"))
    val work = checkBinding *> store.snapshot.flatMap { initial =>
      source.open.use { cursor =>
        def loop(previous: Point, selected: Boolean, replayed: Int): F[FetchResult] =
          store.snapshot.flatMap { current =>
            val caughtUp = replayed == initial.records.size
            if selected && caughtUp && spec.end.exists(e =>
                current.records.lastOption.exists(_.point == e)
              )
            then result("endReached")
            else if selected && caughtUp && spec.count.exists(current.records.size >= _) then
              result("countReached")
            else if selected && caughtUp && current.records.size >= spec.limits.maxBlocks then
              result("objectBudget")
            else
              cursor.next.flatMap {
                case SourceEvent.End =>
                  if !selected then F.raiseError(new FetchError(5, "anchorNotFound"))
                  else if !caughtUp then
                    F.raiseError(new FetchError(5, "source ended before committed overlap"))
                  else result("sourceExhausted")
                case SourceEvent.Rollback(p) =>
                  if p == previous then loop(previous, selected, replayed)
                  else F.raiseError(new FetchError(5, "rollbackConflict: fail-on-fork"))
                case SourceEvent.Raw(raw) =>
                  lab.chain.CardanoBlockIndex
                    .inspect(raw, spec.limits.maxBlockBytes)
                    .leftMap(m => new FetchError(5, m))
                    .liftTo[F]
                    .flatMap { b =>
                      val p = Point.of(b)
                      if b.parentHash.hex != previous.hash || p.slot <= previous.slot then
                        F.raiseError(new FetchError(5, "parent/slot continuity mismatch"))
                      else if !selected then loop(p, p == spec.after, replayed)
                      else if !caughtUp then
                        if Record.of(b) != initial.records(replayed) then
                          F.raiseError(new FetchError(5, "committed source overlap mismatch"))
                        else loop(p, true, replayed + 1)
                      else if spec.end.exists(e => p.slot >= e.slot && p != e) then
                        F.raiseError(new FetchError(5, "inclusive end missing or forked"))
                      else store.append(b) *> loop(p, true, replayed)
                    }
              }
          }
        loop(source.identity.predecessor, source.identity.predecessor == spec.after, 0)
      }
    }
    F.timeoutTo(work, spec.limits.maxDuration, result("timeBudget"))
      .handleErrorWith {
        case e: FetchError if e.code == 3 => result(e.getMessage)
        case e                            => F.raiseError(e)
      }
