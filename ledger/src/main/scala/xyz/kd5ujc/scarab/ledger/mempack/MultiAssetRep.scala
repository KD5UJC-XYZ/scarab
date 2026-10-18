// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.mempack

import scala.collection.mutable.ArrayBuffer
import xyz.kd5ujc.scarab.codec.Bytes

import MemPack.{Failure, MultiAssetFault, Quantity, Result}

/** The flat representation of a multi-asset value inside `CompactValue` (`Value.hs`, the comment at
  * 475-578, `to` at 580 and `from` at 730 of cardano-ledger-mary 1.x of the pinned revision):
  *
  * {{{
  * A  n * Word64 quantities (little endian)
  * B  n * Word16 policy offsets          C  n * Word16 asset name offsets
  * D  the distinct policy ids, ascending, 28 bytes each
  * E  the distinct asset names, descending, one after the other
  * }}}
  *
  * The offsets count from the start of the whole representation. The triples are those of
  * `flattenMultiAsset` (policy ascending, name ascending), sorted *stably* by the offset of the
  * name (so by name descending, and equal names keep the policy order), and written in that order.
  * The bytes carry no padding. An empty asset name has no bytes and its offset is the end of E, so
  * a name's length is the distance to the next greater offset, or to the end of the representation.
  */
private[mempack] object MultiAssetRep:
  private val PolicySize = 28
  private val MaxOffset = 0xffff
  private val TwoTo64 = BigInt(1) << 64

  /** The number of assets and the representation of non-empty `assets` (zero quantities and empty
    * policies are dropped first, as the ledger's value never holds them).
    */
  def build(assets: Map[Bytes, Map[Bytes, BigInt]]): Result[(Int, Bytes)] =
    val policies = assets.toVector.sortBy(_._1)(using Ordering[Bytes])
    val triples = ArrayBuffer.empty[(Int, Bytes, BigInt)]
    val ids = ArrayBuffer.empty[Bytes]
    policies.foreach { (policy, names) =>
      val live = names.toVector.filter(_._2.signum != 0).sortBy(_._1)(using Ordering[Bytes])
      if live.nonEmpty then
        val index = ids.size
        ids += policy
        live.foreach((name, q) => triples += ((index, name, q)))
    }
    val n = triples.size
    val p = ids.size
    if 44L * n + 28L * p > MaxOffset then Left(Failure.MultiAssetTooLarge(n, p))
    else
      val quantities = triples.iterator.map { (_, _, q) =>
        if q.signum < 0 || q.bitLength > 64 then Left(Failure.OutOfRange(Quantity.Asset))
        else Right(q.toLong)
      }.toVector
      quantities.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None =>
          val names =
            triples.iterator.map(_._2).toVector.distinct.sorted(using Ordering[Bytes].reverse)
          val abc = 12 * n
          val nameBase = abc + PolicySize * p
          val offsets = names.scanLeft(nameBase)((at, name) => at + name.size)
          val total = offsets.last
          val offsetOf = names.zip(offsets).toMap
          if total > MaxOffset then Left(Failure.MultiAssetTooLarge(n, p))
          else
            val out = new Array[Byte](total)
            def put16(at: Int, v: Int): Unit =
              out(at) = v.toByte
              out(at + 1) = (v >>> 8).toByte
            def put64(at: Int, v: Long): Unit =
              var i = 0
              while i < 8 do
                out(at + i) = (v >>> (8 * i)).toByte
                i += 1
            // Stable by name offset: the triples are already in policy order.
            val ordered = triples.zip(quantities.collect { case Right(q) => q }).sortBy { t =>
              offsetOf(t._1._2)
            }
            ordered.zipWithIndex.foreach { case (((pi, name, _), q), i) =>
              put64(8 * i, q)
              put16(8 * n + 2 * i, abc + PolicySize * pi)
              put16(10 * n + 2 * i, offsetOf(name))
            }
            ids.zipWithIndex.foreach((id, i) => id.copyTo(out, abc + PolicySize * i))
            names.zip(offsets).foreach((name, at) => name.copyTo(out, at))
            Right((n, Bytes.wrap(out)))

  /** The assets of a representation of `n` of them (`from`): zero quantities are dropped, equal
    * (policy, name) pairs added.
    */
  def read(n: Int, rep: Bytes): Result[Map[Bytes, Map[Bytes, BigInt]]] =
    val size = rep.size
    if n < 0 || n.toLong * 12 > size then
      Left(Failure.BadMultiAsset(MultiAssetFault.ShorterThanRegions))
    else
      def u16(at: Int): Int = (rep(at) & 0xff) | ((rep(at + 1) & 0xff) << 8)
      def u64(at: Int): BigInt =
        var v = 0L
        var i = 7
        while i >= 0 do
          v = (v << 8) | (rep(at + i) & 0xffL)
          i -= 1
        if v >= 0 then BigInt(v) else BigInt(v) + TwoTo64
      val nameOffsets = Vector.tabulate(n)(i => u16(10 * n + 2 * i))
      val policyOffsets = Vector.tabulate(n)(i => u16(8 * n + 2 * i))
      if policyOffsets.exists(_ + PolicySize > size) then
        Left(Failure.BadMultiAsset(MultiAssetFault.PolicyOutOfRange))
      else if nameOffsets.exists(_ > size) then
        Left(Failure.BadMultiAsset(MultiAssetFault.NameOutOfRange))
      else if nameOffsets.zip(nameOffsets.drop(1)).exists((a, b) => b < a) then
        Left(Failure.BadMultiAsset(MultiAssetFault.NameOffsetsDescend))
      else
        // The name at offset o ends where the next greater offset begins, or at the end.
        val distinct = nameOffsets.distinct
        val end = distinct.zip(distinct.drop(1) :+ size).toMap
        val triples = (0 until n).map { i =>
          val o = nameOffsets(i)
          (
            rep.slice(policyOffsets(i), policyOffsets(i) + PolicySize),
            rep.slice(o, end(o)),
            u64(8 * i)
          )
        }
        Right(
          triples.foldLeft(Map.empty[Bytes, Map[Bytes, BigInt]]) {
            case (acc, (_, _, q)) if q.signum == 0 => acc
            case (acc, (policy, name, q)) =>
              val held = acc.getOrElse(policy, Map.empty)
              acc.updated(policy, held.updated(name, held.getOrElse(name, BigInt(0)) + q))
          }
        )
