// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.Header
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
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
  val Format = "csl-state-digest-1"

  /** Canonical text of a JSON value (numbers are integers here: ledger quantities). */
  def canon(j: Json): String =
    val sb = new StringBuilder
    def go(x: Json): Unit = x.fold(
      sb.append("null"),
      b => sb.append(b.toString),
      n => sb.append(n.toString),
      s => sb.append(Json.fromString(s).noSpaces),
      a =>
        sb.append('[')
        var first = true
        a.foreach { e =>
          if !first then sb.append(',')
          first = false
          go(e)
        }
        sb.append(']')
      ,
      o =>
        sb.append('{')
        var first = true
        o.toList.sortBy(_._1).foreach { (k, v) =>
          if !first then sb.append(',')
          first = false
          sb.append(Json.fromString(k).noSpaces).append(':')
          go(v)
        }
        sb.append('}')
    )
    go(j)
    sb.toString

  /** Accumulates the digest of a map entry by entry. */
  final class Digest:
    private val mod = BigInteger.ONE.shiftLeft(256)
    private val sha = MessageDigest.getInstance("SHA-256")
    private var n = 0L
    private var sum = BigInteger.ZERO
    private val bucketCount = new Array[Long](16)
    private val bucketSum = Array.fill(16)(BigInteger.ZERO)

    def add(key: String, canonValue: String): Unit =
      val k = key.getBytes(StandardCharsets.UTF_8)
      val bucket = sha.digest(k)(0) & 0x0f
      sha.update(k)
      sha.update(0.toByte)
      val h = new BigInteger(1, sha.digest(canonValue.getBytes(StandardCharsets.UTF_8)))
      n += 1
      sum = sum.add(h).mod(mod)
      bucketCount(bucket) += 1
      bucketSum(bucket) = bucketSum(bucket).add(h).mod(mod)

    private def hex(b: BigInteger): String = String.format("%064x", b)

    def json: Json = Json.obj(
      "count" -> n.asJson,
      "digest" -> hex(sum).asJson,
      "buckets" -> (0 until 16).map(i => s"${bucketCount(i)}:${hex(bucketSum(i))}").asJson
    )

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
          val d = new Digest
          o.toIterable.foreach((k, v) => d.add(k, canon(v)))
          d.json
        else Json.fromFields(o.toIterable.map((k, v) => k -> reduce(v)))
    )

  /** Digest of the whole UTxO set: the store's entries the state's diff does not decide, then the
    * diff's live entries.
    */
  def utxoDigest[F[_]: Sync](store: Option[UtxoStore[F]], utxo: Utxo): F[Json] =
    val d = new Digest
    def add(in: TxIn, out: TxOut): Unit = d.add(in.render, canon(Projection.txOut(out)))
    val stored = store.fold(Sync[F].unit)(
      _.scan((in, out) => if !utxo.diff.contains(in) then add(in, out))
    )
    stored *> Sync[F].delay {
      utxo.diff.foreach { case (in, c) => c.out.foreach(add(in, _)) }
      d.json
    }

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
  final class Dumper[F[_]: Sync] private (
      dir: Path,
      targets: Targets,
      store: Option[UtxoStore[F]],
      lastEpoch: Ref[F, Option[BigInt]],
      pending: Ref[F, List[BigInt]],
      emit: Json => F[Unit],
      full: Boolean
  ):
    def afterBlock(header: Header, s: NodeState): F[Unit] = header match
      case h: Header.Shelley =>
        val epoch = s.ledger.epoch
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

    private def write(h: Header.Shelley, s: NodeState): F[Unit] =
      val file = dir.resolve(f"${h.slot.toLong}%012d-${h.hash.hex}.json")
      Sync[F].blocking(Files.exists(file)).flatMap {
        case true => Sync[F].unit
        case false =>
          val empty = s.copy(ledger = s.ledger.copy(utxo = Utxo.empty))
          for
            started <- Sync[F].delay(System.nanoTime())
            utxo <- utxoDigest(store, s.ledger.utxo)
            body <- Sync[F].delay(
              reduce(Projection.node(h.point, empty))
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
                  all <- store.fold(Sync[F].pure(s.ledger.utxo))(st =>
                    UtxoStore.materialize(st, s.ledger.utxo).map(Utxo.fromMap)
                  )
                  _ <- Sync[F].blocking {
                    val name = file.getFileName.toString.stripSuffix(".json") + ".full"
                    Files.writeString(
                      dir.resolve(name),
                      Projection.node(h.point, s.copy(ledger = s.ledger.copy(utxo = all))).noSpaces
                    )
                  }
                yield ()
            took <- Sync[F].delay((System.nanoTime() - started) / 1000000)
            _ <- emit(
              Json.obj(
                "event" -> "stateDump".asJson,
                "slot" -> h.slot.asJson,
                "hash" -> h.hash.hex.asJson,
                "epoch" -> s.ledger.epoch.asJson,
                "millis" -> took.asJson
              )
            )
          yield ()
      }

  object Dumper:
    def create[F[_]: Sync](
        dir: Path,
        targets: Targets,
        store: Option[UtxoStore[F]],
        emit: Json => F[Unit],
        full: Boolean = false
    ): F[Dumper[F]] =
      for
        last <- Ref.of[F, Option[BigInt]](None)
        pending <- Ref.of[F, List[BigInt]](targets match
          case Targets.Slots(ss) => ss
          case _                 => Nil)
      yield new Dumper[F](dir, targets, store, last, pending, emit, full)
