// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import cats.data.State

/** A pure splittable generator (SplitMix64): the state of a seed of the term generator. Equal
  * states give equal draws, so a program is a function of its seed alone, on every platform.
  */
final case class SplitRng(state: Long):
  def next: (SplitRng, Long) =
    val advanced = state + SplitRng.Golden
    (SplitRng(advanced), SplitRng.mix(advanced))

  /** Two generators whose draws are independent of each other and of this one's. */
  def split: (SplitRng, SplitRng) =
    val (once, a) = next
    val (_, b) = once.next
    (SplitRng(a), SplitRng(SplitRng.mix(b ^ SplitRng.Golden)))

object SplitRng:
  private val Golden = 0x9e3779b97f4a7c15L

  private def mix(z0: Long): Long =
    val z1 = (z0 ^ (z0 >>> 30)) * 0xbf58476d1ce4e5b9L
    val z2 = (z1 ^ (z1 >>> 27)) * 0x94d049bb133111ebL
    z2 ^ (z2 >>> 31)

  /** A draw in the state of a generator. */
  type Draw[A] = State[SplitRng, A]

  val long: Draw[Long] = State(_.next)

  /** A fresh generator for a child seed. */
  val fork: Draw[SplitRng] = State(r => r.split.swap)

  /** A number in `[0, bound)`; `bound` is positive. */
  def below(bound: Int): Draw[Int] = long.map(l => java.lang.Math.floorMod(l, bound.toLong).toInt)

  /** A number in `[low, high]`. */
  def between(low: Int, high: Int): Draw[Int] = below(high - low + 1).map(_ + low)

  /** True with probability `percent` / 100. */
  def chance(percent: Int): Draw[Boolean] = below(100).map(_ < percent)

  def oneOf[A](first: A, rest: A*): Draw[A] =
    val all = first +: rest.toVector
    below(all.size).map(all)

  def element[A](all: Vector[A]): Draw[Option[A]] =
    if all.isEmpty then State.pure(None) else below(all.size).map(i => Some(all(i)))

  /** A choice by weight among the entries of positive weight; the first when none has one. */
  def weighted[A](entries: Vector[(Int, A)]): Draw[A] =
    val live = entries.filter(_._1 > 0)
    val total = live.map(_._1).sum
    if total <= 0 then State.pure(entries.head._2)
    else
      below(total).map { roll =>
        live
          .scanLeft((0, Option.empty[A])) { case ((acc, _), (w, a)) => (acc + w, Some(a)) }
          .drop(1)
          .collectFirst { case (upTo, Some(a)) if roll < upTo => a }
          .getOrElse(live.last._2)
      }

  /** A draw chosen by weight. */
  def pick[A](entries: Vector[(Int, Draw[A])]): Draw[A] = weighted(entries).flatMap(identity)

  def listOf[A](n: Int, item: Draw[A]): Draw[Vector[A]] =
    import cats.syntax.all.*
    Vector.fill(n)(item).sequence
