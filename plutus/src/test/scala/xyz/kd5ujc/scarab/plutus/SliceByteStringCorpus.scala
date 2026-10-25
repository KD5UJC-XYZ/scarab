// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** Phase-2 probes of `sliceByteString from len bs` whose `from` or `len` lies outside the 32-bit
  * range, near the bounds of the 64-bit range, or beyond it, on a short and on an empty bytestring.
  *
  * The reference (plutus 1.70.0.0, `PlutusCore/Default/Builtins.hs` `SliceByteString`) takes both
  * indices as a machine `Int` and computes `take len (drop from bs)`; `readKnownAsInteger`
  * (`PlutusCore/Default/Universe.hs`) unlifts that `Int` as an `Int64` and fails evaluation when
  * the integer is out of that range. So:
  *
  *   - [[Kind.Result]]: both indices are within Int64 and the script ends in `()` exactly when the
  *     slice equals the clamped `take` /`drop` result written next to it;
  *   - [[Kind.Wrapped]]: the same comparison with the bytes a 32-bit truncation of the indices
  *     would select, recorded only where those differ from the clamped result, so it ends in
  *     `error`;
  *   - [[Kind.OutOfRange]]: an index is outside Int64 and the slice, whose result is discarded,
  *     fails by itself.
  *
  * The bytes written here are a claim the reference checks: its verdicts and budgets, never
  * Scarab's, are in `fixtures/reference/plutus-slice-bytestring/verdicts.jsonl`.
  */
object SliceByteStringCorpus:
  enum Kind:
    case Result, Wrapped, OutOfRange

  final case class Probe(id: String, from: BigInt, len: BigInt, bytes: Bytes, kind: Kind):
    def script: Bytes = SliceByteStringCorpus.compiled(this)

  private def int64(n: BigInt): Boolean = n.isValidLong
  private val two = BigInt(2)

  /** The short bytestring: distinct bytes, so every different slice has different bytes. */
  val short: Bytes = Bytes.fromArray(Array[Byte](1, 2, 3, 4, 5))
  val empty: Bytes = Bytes.fromArray(Array.emptyByteArray)

  /** Within Int64 but outside the 32-bit range, or at its edges. */
  val wideInt64: Vector[BigInt] = Vector(
    two.pow(31) - 1,
    two.pow(31),
    -two.pow(31) - 1,
    two.pow(32) - 1,
    two.pow(32),
    two.pow(32) + 1,
    two.pow(32) + 2,
    -two.pow(32),
    -two.pow(32) + 1,
    two.pow(63) - 1,
    -two.pow(63)
  )

  /** Outside Int64. */
  val beyondInt64: Vector[BigInt] = Vector(
    two.pow(63),
    -two.pow(63) - 1,
    two.pow(64),
    two.pow(64) + 1,
    -two.pow(64),
    -two.pow(64) + 2,
    two.pow(100),
    two.pow(100) + 2,
    -two.pow(100)
  )

  private val pairs: Vector[(BigInt, BigInt)] =
    val small = Vector[(BigInt, BigInt)]((0, 5), (1, 2), (-1, 2), (3, 10), (5, 1))
    val both = Vector[(BigInt, BigInt)](
      (two.pow(31) - 1, two.pow(31) - 1),
      (two.pow(63) - 1, two.pow(63) - 1),
      (-two.pow(63), two.pow(63) - 1),
      (-two.pow(63), -two.pow(63)),
      (two.pow(32) + 1, two.pow(32) + 2),
      (two.pow(63) - 1, two.pow(63)),
      (two.pow(64), two.pow(64)),
      (-two.pow(64), 1)
    )
    val wide = wideInt64 ++ beyondInt64
    small ++ wide.map(from => (from, BigInt(2))) ++ wide.map(len => (BigInt(1), len)) ++ both

  /** `take len (drop from bs)`, as `Data.ByteString` clamps both to the bytestring. */
  def clamped(from: BigInt, len: BigInt, bs: Bytes): Bytes =
    val size = BigInt(bs.size)
    val start = from.max(0).min(size)
    val n = len.max(0).min(size - start)
    Bytes.fromArray(bs.toArray.slice(start.toInt, (start + n).toInt))

  /** The slice a 32-bit truncation of both indices selects. */
  def wrapped(from: BigInt, len: BigInt, bs: Bytes): Bytes =
    Bytes.fromArray(bs.toArray.drop(from.toInt).take(len.toInt))

  /** `n` as the nearest power of two and an offset, such as `2^31-1` or `-(2^64-2)`. */
  private def label(n: BigInt): String =
    if n.abs < 1024 then n.toString
    else
      val power = Vector(n.abs.bitLength - 1, n.abs.bitLength).minBy(p => (n.abs - two.pow(p)).abs)
      val rest = n.abs - two.pow(power)
      val magnitude = if rest == 0 then s"2^$power" else f"2^$power%d$rest%+d"
      if n > 0 then magnitude else if rest == 0 then s"-$magnitude" else s"-($magnitude)"

  val probes: Vector[Probe] =
    for
      (from, len) <- pairs
      bytes <- Vector(short, empty)
      where = s"from ${label(from)} len ${label(len)} on ${bytes.size} bytes"
      probe <-
        if !int64(from) || !int64(len) then Vector(Probe(where, from, len, bytes, Kind.OutOfRange))
        else
          val result = Probe(where, from, len, bytes, Kind.Result)
          if wrapped(from, len, bytes) == clamped(from, len, bytes) then Vector(result)
          else Vector(result, Probe(s"$where, 32-bit bytes", from, len, bytes, Kind.Wrapped))
    yield probe

  private def slice(probe: Probe): String =
    s"[ (builtin sliceByteString) (con integer ${probe.from}) (con integer ${probe.len}) (con bytestring #${probe.bytes.hex}) ]"

  private def holds(condition: String) =
    s"(force [ (force (builtin ifThenElse)) $condition (delay (con unit ())) (delay (error)) ])"

  private def equalTo(probe: Probe, bytes: Bytes) =
    holds(s"[ (builtin equalsByteString) ${slice(probe)} (con bytestring #${bytes.hex}) ]")

  private def body(probe: Probe): String = probe.kind match
    case Kind.Result     => equalTo(probe, clamped(probe.from, probe.len, probe.bytes))
    case Kind.Wrapped    => equalTo(probe, wrapped(probe.from, probe.len, probe.bytes))
    case Kind.OutOfRange => s"[ (lam ignored (con unit ())) ${slice(probe)} ]"

  private def compiled(probe: Probe): Bytes =
    Bytes.fromArray(
      Program
        .parseUplc(s"(program 1.0.0 ${body(probe)})")
        .fold(e => sys.error(s"${probe.id}: $e"), _.cborEncoded)
    )

  /** Every ledger profile of the costing matrix: V1 at protocols 5 to 11, V2 at 7 to 11 and V3 at 9
    * to 11, which between them run builtin semantics variants A to E.
    */
  val profiles: Vector[(Int, Int)] = BuiltinCostingCorpus.profiles

  /** Writes the requests for the reference costing oracle (`scripts/oracle/plutus/costing`). */
  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/slice-bytestring-requests.jsonl"))
    val rows = for
      probe <- probes
      (language, protocol) <- profiles
    yield Json.obj(
      "id" -> Json.fromString(probe.id),
      "language" -> Json.fromInt(language),
      "protocol" -> Json.fromInt(protocol),
      "script" -> Json.fromString(probe.script.hex),
      "costModel" -> Json
        .arr(BuiltinCostingCorpus.parameters(language, protocol).map(Json.fromLong)*),
      "cpu" -> Json.fromBigInt(PlutusBoundaryCorpus.budget.steps),
      "memory" -> Json.fromBigInt(PlutusBoundaryCorpus.budget.mem)
    )
    Files.writeString(out, rows.map(_.noSpaces).mkString("\n") + "\n")
