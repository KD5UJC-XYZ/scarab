// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{StorageError, TxOut}
import xyz.kd5ujc.scarab.ledger.mempack.{MemPack, TxOutMemPack}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.storage.haskell.state.{SnapshotTables, SnapshotTableStream, UtxoCodecs}

/** Same-snapshot acceptance. Different-tip historical intersections are not an acceptance oracle;
  * the old partial-join measurements remain recorded in haskell-snapshots.md section 8.
  */
class MemPackJoinSuite extends CatsEffectSuite:
  override val munitIOTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(60, "min")

  test("the pinned preprod tables decode and canonically pack back") {
    val tables = sys.env.get("SCARAB_MEMPACK_TABLES")
    assume(tables.nonEmpty, "SCARAB_MEMPACK_TABLES names the pinned preprod table")
    tables.traverse_(p => MemPackJoin.scanTables(Path(p)).map(r => assert(r.isRight, r.toString)))
  }

  test("the same-snapshot checkpoint and native table have identical keys and output semantics") {
    val tables = sys.env.get("SCARAB_MEMPACK_TABLES")
    val checkpoint = sys.env.get("SCARAB_MEMPACK_CHECKPOINT")
    assume(
      tables.nonEmpty && checkpoint.nonEmpty,
      "SCARAB_MEMPACK_TABLES and SCARAB_MEMPACK_CHECKPOINT"
    )
    (tables, checkpoint).tupled.traverse_ { (t, c) =>
      MemPackJoin
        .join(Path(t), Path(c), sys.env.contains("SCARAB_MEMPACK_BENCH"))
        .map(r => assert(r.isRight, r.toString))
    }
  }

object MemPackJoin extends IOApp:
  enum Encoding:
    case Canonical, HistoricalConstructors

  enum Error:
    case Framing(cause: SnapshotTables.Error)
    case Storage(cause: StorageError)
    case Read(path: Path, cause: Throwable)
    case Identity(expected: String, actual: String)
    case Empty
    case Count(expected: Long, actual: Long)
    case Stamp(expected: Long, actual: Long)
    case KeySet(native: Option[Bytes], stored: Option[Bytes])
    case Order(previous: Bytes, current: Bytes)
    case Decode(key: Bytes, cause: MemPack.Failure)
    case Semantics(key: Bytes)
    case PackedBytes(key: Bytes)
    case Script(key: Bytes)
    case CanonicalFields(key: Bytes)
    case KeyEncoding(in: xyz.kd5ujc.scarab.ledger.TxIn)
    case Arguments

  final case class Summary(entries: Long, differentPackedBytes: Long, differentOriginalCbor: Long)
  private val EmptySummary = Summary(0L, 0L, 0L)
  val PreprodEntries: Long = 4526058L
  val PreprodBlock: Long = 5300868L
  val PreprodSha256 = "62ee546d9cf0c47f95444c213fb1bd55509a29a3f08cf8a9e9afa22bbaa5c2ed"

  private def attempt[A](body: Handle[IO, Error] ?=> IO[A]): IO[Either[Error, A]] =
    Handle.allow[Error](body.map(_.asRight[Error])).rescue(e => IO.pure(e.asLeft[A]))

  private def lift[A](value: Either[Error, A])(using Handle[IO, Error]): IO[A] =
    value.fold(_.raise[IO, A], IO.pure)

  private def mapped[E](wrap: E => Error)(using Handle[IO, Error]): Raise[IO, E] = new Raise[IO, E]:
    def functor = cats.Functor[IO]
    def raise[E1 <: E, A](error: E1): IO[A] = wrap(error).raise[IO, A]

  private def bytes(path: Path)(using Handle[IO, Error]): Stream[IO, Byte] =
    Files[IO].readAll(path).handleErrorWith(e => Stream.eval(Error.Read(path, e).raise[IO, Byte]))

  private def table(
      input: Stream[IO, Byte]
  )(using Handle[IO, Error]): Stream[IO, SnapshotTables.Entry] =
    Stream.eval(Ref.of[IO, Option[Bytes]](None)).flatMap { previous =>
      // This comparison profile is sorted; the production decoder itself accepts unordered maps.
      SnapshotTableStream
        .entries[IO](input)(using summon, mapped(Error.Framing.apply))
        .evalTap { entry =>
          previous
            .getAndSet(Some(entry.key))
            .flatMap(_.traverse_ { before =>
              lift(
                Either.cond(
                  java.util.Arrays.compareUnsigned(before.toArray, entry.key.toArray) < 0,
                  (),
                  Error.Order(before, entry.key)
                )
              )
            })
        }
    }

  private def output(entry: SnapshotTables.Entry): Either[Error, TxOut] =
    TxOutMemPack.decode[Conway](entry.value).leftMap(Error.Decode(entry.key, _))

  private def packed(
      entry: SnapshotTables.Entry,
      out: TxOut,
      encoding: Encoding
  ): Either[Error, Boolean] =
    for
      back <- TxOutMemPack.encode[Conway](out).leftMap(Error.Decode(entry.key, _))
      changed = back != entry.value
      historical =
        encoding == Encoding.HistoricalConstructors &&
          ((entry.value.headOption.map(_.toInt), back.headOption.map(_.toInt)) match
            case (Some(0), Some(2)) | (Some(1), Some(3)) => true
            case _                                       => false)
      _ <- Either.cond(!changed || historical, (), Error.PackedBytes(entry.key))
      decoded <- TxOutMemPack.decode[Conway](back).leftMap(Error.Decode(entry.key, _))
      originalParts <- TxOutMemPack.parts(out).leftMap(Error.Decode(entry.key, _))
      backParts <- TxOutMemPack.parts(decoded).leftMap(Error.Decode(entry.key, _))
      _ <- Either.cond(originalParts == backParts, (), Error.Semantics(entry.key))
    yield changed

  private def finish(summary: Summary, expected: Option[Long]): Either[Error, Summary] =
    for
      _ <- Either.cond(summary.entries > 0, (), Error.Empty)
      _ <- expected.traverse_(n =>
        Either.cond(n == summary.entries, (), Error.Count(n, summary.entries))
      )
    yield summary

  def scan(
      input: Stream[IO, Byte],
      encoding: Encoding,
      expected: Option[Long] = None
  ): IO[Either[Error, Summary]] =
    attempt {
      table(input)
        .evalMap { entry =>
          lift(output(entry).flatMap(out => packed(entry, out, encoding)))
        }
        .compile
        .fold(EmptySummary) { (s, changed) =>
          s.copy(
            entries = s.entries + 1,
            differentPackedBytes = s.differentPackedBytes + (if changed then 1 else 0)
          )
        }
        .flatMap(s => lift(finish(s, expected)))
    }

  /** Original CBOR can be noncanonical. Compare compact semantics and script identity directly;
    * compare sizing and NTC bytes with the canonical reconstruction of those same stored fields.
    * The reference retains no original output CBOR (haskell-snapshots.md section 8.3).
    */
  private def compare(
      entry: SnapshotTables.Entry,
      stored: TxOut,
      encoding: Encoding
  ): Either[Error, (Boolean, Boolean)] =
    for
      native <- output(entry)
      nativeParts <- TxOutMemPack.parts(native).leftMap(Error.Decode(entry.key, _))
      storedParts <- TxOutMemPack.parts(stored).leftMap(Error.Decode(entry.key, _))
      _ <- Either.cond(nativeParts == storedParts, (), Error.Semantics(entry.key))
      canonical <- TxOutMemPack.toTxOut(storedParts).leftMap(Error.Decode(entry.key, _))
      changed <- packed(entry, canonical, encoding)
      _ <- Either.cond(
        stored.referenceScriptSize == native.referenceScriptSize &&
          stored.referenceScript.map(s => (s.kind, s.hash, s.plutus)) ==
          native.referenceScript.map(s => (s.kind, s.hash, s.plutus)),
        (),
        Error.Script(entry.key)
      )
      _ <- Either.cond(
        canonical.valueSize == native.valueSize &&
          UtxoCodecs.txOut.encodeUnchecked(canonical) == UtxoCodecs.txOut.encodeUnchecked(native),
        (),
        Error.CanonicalFields(entry.key)
      )
    yield changed -> (stored.raw != native.raw)

  def compareStreams(
      input: Stream[IO, Byte],
      stored: Stream[IO, (Bytes, TxOut)],
      encoding: Encoding,
      expected: Option[Long] = None,
      observe: (SnapshotTables.Entry, TxOut) => IO[Unit] = (_, _) => IO.unit
  ): IO[Either[Error, Summary]] =
    attempt {
      table(input)
        .map(Option(_))
        .zipAll(stored.map(Option(_)), None, None)
        .evalMap {
          case (Some(entry), Some((key, out))) if entry.key == key =>
            lift(compare(entry, out, encoding)).flatTap(_ => observe(entry, out))
          case (native, local) =>
            Error.KeySet(native.map(_.key), local.map(_._1)).raise[IO, (Boolean, Boolean)]
        }
        .compile
        .fold(EmptySummary) { case (s, (packedChanged, rawChanged)) =>
          Summary(
            s.entries + 1,
            s.differentPackedBytes + (if packedChanged then 1 else 0),
            s.differentOriginalCbor + (if rawChanged then 1 else 0)
          )
        }
        .flatMap(s => lift(finish(s, expected)))
    }

  private def pinned(path: Path)(using Handle[IO, Error]): IO[Unit] =
    bytes(path)
      .through(fs2.hashing.Hashing.forSync[IO].hash(fs2.hashing.HashAlgorithm.SHA256))
      .compile
      .lastOrError
      .map(hash => Bytes.wrap(hash.bytes.toArray).hex)
      .flatMap(actual =>
        lift(Either.cond(actual == PreprodSha256, (), Error.Identity(PreprodSha256, actual)))
      )

  def scanTables(path: Path): IO[Either[Error, Summary]] = attempt {
    pinned(path) *> scan(bytes(path), Encoding.Canonical, Some(PreprodEntries)).flatMap(lift)
  }

  def join(path: Path, checkpoint: Path, benchmark: Boolean): IO[Either[Error, Summary]] = attempt {
    for
      _ <- pinned(path)
      samples <- Ref.of[IO, Vector[(Array[Byte], Array[Byte])]](Vector.empty)
      result <- Files[IO].tempDirectory.use { logs =>
        RocksUtxoCheckpoint
          .resource[IO](checkpoint, logs)(using summon, mapped(Error.Storage.apply))
          .use { db =>
            given cats.mtl.Raise[IO, StorageError] = mapped(Error.Storage.apply)
            for
              stamp <- db.flushed
              _ <- lift(Either.cond(stamp == PreprodBlock, (), Error.Stamp(PreprodBlock, stamp)))
              compared <- compareStreams(
                bytes(path),
                db.entries.evalMap { (in, out) =>
                  lift(UtxoStore.Coding.encodeKey(in).toRight(Error.KeyEncoding(in)))
                    .map(key => Bytes.wrap(key) -> out)
                },
                Encoding.Canonical,
                Some(PreprodEntries),
                (entry, out) =>
                  samples.update(xs =>
                    if benchmark && xs.size < 400000 then
                      xs :+ (UtxoStore.Coding.encodeValue(out) -> entry.value.toArray)
                    else xs
                  )
              )
              summary <- lift(compared)
            yield summary
          }
      }
      _ <-
        if benchmark then
          samples.get.flatMap { rows =>
            IO.blocking(MemPackJoinBenchmark.measure(rows.map(_._1), rows.map(_._2)))
              .flatMap(IO.println)
          }
        else IO.unit
    yield result
  }

  def run(args: List[String]): IO[ExitCode] =
    val result: IO[Either[Error, Summary]] = args match
      case "scan" :: path :: Nil => scanTables(Path(path))
      case "join" :: path :: checkpoint :: rest if rest.isEmpty || rest == List("bench") =>
        join(Path(path), Path(checkpoint), rest.nonEmpty)
      case _ => IO.pure(Left(Error.Arguments))
    result.flatMap(r => IO.println(r).as(r.fold(_ => ExitCode.Error, _ => ExitCode.Success)))
