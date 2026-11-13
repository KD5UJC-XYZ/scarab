// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, ErrorClass, UnliftingBound, UnliftingCause}

import CoreTesting.*

/** The reference's verdicts on `sliceByteString` with wide indices in every variant
  * (`fixtures/reference/plutus-slice-bytestring/verdicts.jsonl`, V1 at protocols 5 to 11, V2 at 7
  * to 11, V3 at 9 to 11). The probes are those of `SliceByteStringCorpus` (`plutus` test tree),
  * rebuilt here: a result probe succeeds exactly when the slice equals the clamped bytes written
  * next to it, a 32-bit probe exactly when it equals the bytes a 32-bit truncation selects, and an
  * out-of-range probe exactly when the slice does not fail.
  */
final class SliceByteStringReferenceSuite extends munit.FunSuite:
  private enum Kind:
    case Result, Wrapped, OutOfRange

  private final case class Slice(from: BigInt, len: BigInt, bytes: Bytes, kind: Kind)

  private val two = BigInt(2)
  private val short = Bytes.fromArray(Array[Byte](1, 2, 3, 4, 5))

  private val wideInt64 = Vector(
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

  private val beyondInt64 = Vector(
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

  /** The bytes the corpus wrote into a result probe: `take len (drop from bs)`, clamped. */
  private def clamped(from: BigInt, len: BigInt, bs: Bytes): Bytes =
    val size = BigInt(bs.size)
    val start = from.max(0).min(size)
    val n = len.max(0).min(size - start)
    Bytes.fromArray(bs.toArray.slice(start.toInt, (start + n).toInt))

  /** The bytes a 32-bit truncation of both indices selects. */
  private def wrapped(from: BigInt, len: BigInt, bs: Bytes): Bytes =
    Bytes.fromArray(bs.toArray.drop(from.toInt).take(len.toInt))

  private def label(n: BigInt): String =
    if n.abs < 1024 then n.toString
    else
      val power = Vector(n.abs.bitLength - 1, n.abs.bitLength).minBy(p => (n.abs - two.pow(p)).abs)
      val rest = n.abs - two.pow(power)
      val magnitude = if rest == 0 then s"2^$power" else f"2^$power%d$rest%+d"
      if n > 0 then magnitude else if rest == 0 then s"-$magnitude" else s"-($magnitude)"

  private val probes: Map[String, Slice] = (for
    (from, len) <- pairs
    bytes <- Vector(short, Bytes.empty)
    where = s"from ${label(from)} len ${label(len)} on ${bytes.size} bytes"
    probe <-
      if !from.isValidLong || !len.isValidLong then
        Vector(where -> Slice(from, len, bytes, Kind.OutOfRange))
      else
        val result = where -> Slice(from, len, bytes, Kind.Result)
        if wrapped(from, len, bytes) == clamped(from, len, bytes) then Vector(result)
        else Vector(result, s"$where, 32-bit bytes" -> Slice(from, len, bytes, Kind.Wrapped))
  yield probe).toMap

  private val verdicts =
    ReferenceVerdicts.read("fixtures/reference/plutus-slice-bytestring/verdicts.jsonl")

  test("the probes are the reference's: 124 of them in 15 profiles, variants A to E") {
    assertEquals(probes.size, 124, "probes")
    assertEquals(verdicts.map(_.id).toSet, probes.keySet, "the ids of the verdicts")
    assertEquals(verdicts.size, 124 * 15, "rows")
    assertEquals(verdicts.map(_.variant).toSet, CoreVariant.values.toSet, "variants")
  }

  test("every verdict") {
    for verdict <- verdicts do
      val probe = probes(verdict.id)
      val where = s"${verdict.id} in V${verdict.language} at ${verdict.protocol}"
      val slice = result(
        CoreTesting(
          verdict.variant,
          DefaultFun.SliceByteString,
          int(probe.from),
          int(probe.len),
          bytes(probe.bytes)
        )
      )
      probe.kind match
        case Kind.Result =>
          assertEquals(
            slice == Right(Constant.ByteString(clamped(probe.from, probe.len, probe.bytes))),
            verdict.succeeded,
            where
          )
        case Kind.Wrapped =>
          assertEquals(
            slice == Right(Constant.ByteString(wrapped(probe.from, probe.len, probe.bytes))),
            verdict.succeeded,
            where
          )
        case Kind.OutOfRange =>
          assertEquals(slice.isRight, verdict.succeeded, where)
          // the index outside Int64 that fails is the first one
          val first = if probe.from.isValidLong then 1 else 0
          val value = if first == 0 then probe.from else probe.len
          slice match
            case Left(BuiltinFailure.Unlifting(f)) =>
              assertEquals(f.errorClass, ErrorClass.Operational, where)
              assertEquals(
                f.cause,
                UnliftingCause.NotWithinBounds(first, UnliftingBound.Int, value),
                where
              )
            case other => fail(s"$where: $other")
  }
