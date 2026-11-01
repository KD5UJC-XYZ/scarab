// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.log.NodeEvent
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.ledger.Header
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.ledger.{Utxo, TxIn, TxOut}
import xyz.kd5ujc.scarab.storage.UtxoStore

/** Ledger-state digests for the db-analyser comparison (`follow --dump-state <dir>`, roadmap K1).
  *
  * The `/state` projection ([[Projection]]) is too large to write at every epoch boundary of a long
  * chain, so each object with more than [[Threshold]] fields is replaced by a digest of its
  * entries: the entry count, the sum modulo 2^256 of the SHA-256 of each entry's canonical text,
  * and the same sum per bucket of the first nibble of the SHA-256 of the key (to narrow a
  * difference down). The sum does not depend on the order, so the UTxO set is hashed as it streams
  * out of the store. The Python side (`scripts/oracle/digest.py`) computes the same digests from
  * the reference node's ledger state, so both must keep the exact definitions below:
  *
  *   - canonical text of a value: compact JSON, object keys sorted by code point, no whitespace;
  *   - entry text: key, a NUL character, canonical text of the value, in UTF-8.
  */
object StateDump:
  /** Objects with more fields than this are digested. */
  val Threshold = 64
  // Format tag written into the dump files (the digest definition above is unchanged; dumps written
  // before the rename say `csl-state-digest-1` and are the same format).
  val Format = "scarab-state-digest-1"

  /** Canonical text of a JSON value (numbers are integers here: ledger quantities). The walk keeps
    * what is still to write on a list, so a deeply nested value does not use the thread's stack.
    */
  def canon(j: Json): String =
    enum Piece:
      case Text(text: String)
      case Value(value: Json)
    // the items of a container, a comma between each two
    def separated(items: List[List[Piece]]): List[Piece] =
      items.zipWithIndex.flatMap((pieces, i) =>
        if i == 0 then pieces else Piece.Text(",") :: pieces
      )
    def opened(x: Json): List[Piece] = x.fold(
      List(Piece.Text("null")),
      b => List(Piece.Text(b.toString)),
      n => List(Piece.Text(n.toString)),
      s => List(Piece.Text(Json.fromString(s).noSpaces)),
      a =>
        (Piece.Text("[") :: separated(a.toList.map(e => List(Piece.Value(e))))) :+ Piece.Text("]"),
      o =>
        val fields = o.toList
          .sortBy(_._1)
          .map((k, v) => List(Piece.Text(Json.fromString(k).noSpaces + ":"), Piece.Value(v)))
        (Piece.Text("{") :: separated(fields)) :+ Piece.Text("}")
    )
    // the text written so far, newest first
    @tailrec def go(todo: List[Piece], out: List[String]): String = todo match
      case Nil                      => out.reverse.mkString
      case Piece.Text(text) :: rest => go(rest, text :: out)
      case Piece.Value(x) :: rest   => go(opened(x) ::: rest, out)
    go(List(Piece.Value(j)), Nil)

  /** The digest of a map, accumulated entry by entry: how many entries and the sum of their hashes
    * modulo 2^256, in all and in 16 buckets by the first byte of the hash of the key.
    */
  final case class Digest(count: Long, sum: BigInteger, buckets: Vector[(Long, BigInteger)]):
    def add(key: String, canonValue: String): Digest =
      val sha = Digest.sha.get()
      val k = key.getBytes(StandardCharsets.UTF_8)
      val bucket = sha.digest(k)(0) & 0x0f
      sha.update(k)
      sha.update(0.toByte)
      val h = new BigInteger(1, sha.digest(canonValue.getBytes(StandardCharsets.UTF_8)))
      val (n, total) = buckets.lift(bucket).getOrElse((0L, BigInteger.ZERO))
      Digest(
        count + 1,
        sum.add(h).mod(Digest.Modulus),
        buckets.updated(bucket, (n + 1, total.add(h).mod(Digest.Modulus)))
      )

    def json: Json = Json.obj(
      "count" -> count.asJson,
      "digest" -> Digest.hex(sum).asJson,
      "buckets" -> buckets.map((n, total) => s"$n:${Digest.hex(total)}").asJson
    )

  object Digest:
    val empty: Digest = Digest(0L, BigInteger.ZERO, Vector.fill(16)((0L, BigInteger.ZERO)))
    private val Modulus = BigInteger.ONE.shiftLeft(256)
    // one SHA-256 instance a thread: a dump adds millions of entries
    private val sha = ThreadLocal.withInitial(() => MessageDigest.getInstance("SHA-256"))
    private def hex(b: BigInteger): String = String.format("%064x", b)

  /** Every object with more than [[Threshold]] fields replaced by its digest. */
  def reduce(j: Json): Json =
    j.fold(
      j,
      _ => j,
      _ => j,
      _ => j,
      a => Json.fromValues(a.map(reduce)),
      o =>
        if o.size > Threshold then
          o.toIterable.foldLeft(Digest.empty)((d, entry) => d.add(entry._1, canon(entry._2))).json
        else Json.fromFields(o.toIterable.map((k, v) => k -> reduce(v)))
    )

  /** Digest of the whole UTxO set: the store's entries the state's diff does not decide, then the
    * diff's live entries.
    */
  def utxoDigest[F[_]: Sync](store: Option[UtxoStore[F]], utxo: Utxo)(using
      cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]
  ): F[Json] =
    def add(d: Digest, in: TxIn, out: TxOut): Digest =
      d.add(in.render, canon(Projection.txOut(out)))
    val stored = store.fold(Sync[F].pure(Digest.empty))(
      _.scan(Digest.empty)((d, in, out) => if utxo.diff.contains(in) then d else add(d, in, out))
    )
    stored.map(d =>
      utxo.diff.foldLeft(d) { case (acc, (in, c)) => c.out.fold(acc)(add(acc, in, _)) }.json
    )

  /** When to write a digest. */
  enum Targets:
    /** At the first block of every `every`-th epoch (counted by epoch number). */
    case EpochBoundaries(every: Long)

    /** At the first block with a slot at or after each listed slot (what `db-analyser
      * --store-ledger` stores).
      */
    case Slots(slots: List[BigInt])

  object Targets:
    def parse(text: String): Option[Targets] =
      if text == "epoch-boundaries" then Some(EpochBoundaries(1))
      else if text.startsWith("epoch-boundaries/") then
        text.drop("epoch-boundaries/".length).toLongOption.filter(_ > 0).map(EpochBoundaries(_))
      else
        val parts = text.split(',').toList.map(_.trim)
        val slots = parts.flatMap(p => scala.util.Try(BigInt(p)).toOption)
        if parts.nonEmpty && slots.size == parts.size then Some(Slots(slots.sorted)) else None

  /** The hook the follower runs after each block. */
  final class Dumper[F[_]: Sync, S] private (
      dir: Path,
      targets: Targets,
      store: Option[UtxoStore[F]],
      lastEpoch: Ref[F, Option[BigInt]],
      pending: Ref[F, List[BigInt]],
      emit: NodeEvent => F[Unit],
      full: Boolean
  )(using read: ChainRead[S]):
    def afterBlock(header: Header, s: S)(using
        cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]
    ): F[Unit] = header match
      case h: Header.Shelley =>
        val epoch = s.ledgerEpoch
        val due: F[Boolean] = targets match
          case Targets.EpochBoundaries(every) =>
            lastEpoch
              .getAndSet(Some(epoch))
              .map(_.exists(_ < epoch) && epoch % every == 0)
          case Targets.Slots(_) =>
            pending.modify { ps =>
              val (hit, rest) = ps.span(_ <= h.slot)
              (rest, hit.nonEmpty)
            }
        due.flatMap(if _ then write(h, s) else Sync[F].unit)
      case _ => Sync[F].unit

    private def write(h: Header.Shelley, s: S)(using
        cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.StorageError]
    ): F[Unit] =
      val file = dir.resolve(f"${h.slot.toLong}%012d-${h.hash.hex}.json")
      Sync[F].blocking(Files.exists(file)).flatMap {
        case true => Sync[F].unit
        case false =>
          for
            started <- Sync[F].delay(System.nanoTime())
            utxo <- utxoDigest(store, s.utxo)
            projected <- Sync[F].delay(Projection.node(h.point, s, Utxo.empty))
            _ <- projected match
              case Left(refused) =>
                emit(NodeEvent.StateDumpRefused(h.slot, h.hash, s.ledgerEpoch, refused.show))
              case Right(projection) =>
                for
                  body <- Sync[F].delay(
                    reduce(projection)
                      .deepMerge(Json.obj("utxo" -> utxo, "format" -> Format.asJson))
                  )
                  _ <- Sync[F].blocking {
                    Files.createDirectories(dir)
                    val tmp = dir.resolve(file.getFileName.toString + ".tmp")
                    Files.writeString(tmp, body.noSpaces)
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
                  }
                  // drill-down: the whole projection, UTxO included (small states only)
                  _ <-
                    if !full then Sync[F].unit
                    else
                      for
                        all <- store.fold(Sync[F].pure(s.utxo))(st =>
                          UtxoStore.materialize(st, s.utxo).map(Utxo.fromMap)
                        )
                        _ <- Projection.node(h.point, s, all).traverse_ { whole =>
                          Sync[F].blocking {
                            val name = file.getFileName.toString.stripSuffix(".json") + ".full"
                            Files.writeString(dir.resolve(name), whole.noSpaces)
                          }.void
                        }
                      yield ()
                  took <- Sync[F].delay((System.nanoTime() - started) / 1000000)
                  _ <- emit(NodeEvent.StateDumped(h.slot, h.hash, s.ledgerEpoch, took))
                yield ()
          yield ()
      }

  object Dumper:
    def create[F[_]: Sync, S: ChainRead](
        dir: Path,
        targets: Targets,
        store: Option[UtxoStore[F]],
        emit: NodeEvent => F[Unit],
        full: Boolean = false
    ): F[Dumper[F, S]] =
      for
        last <- Ref.of[F, Option[BigInt]](None)
        pending <- Ref.of[F, List[BigInt]](targets match
          case Targets.Slots(ss) => ss
          case _                 => Nil)
      yield new Dumper[F, S](dir, targets, store, last, pending, emit, full)
