// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

/** A total order of decoded CBOR items that agrees with their equality, which compares the labels
  * of two trees and their children and ignores the original bytes: two items compare equal exactly
  * when they are equal. It sorts the items whose repeats a decoder looks for ([[Unique]]), so the
  * check costs the sort whatever their hash codes. The order itself has no meaning: the kind of
  * item, then its label, then the number of its children and the children in turn.
  */
object CborOrder:
  val values: Ordering[Value] = (a, b) => walk(List(a -> b))

  private def rank(v: Value): Int = v match
    case Value.UInt(_)       => 0
    case Value.NInt(_)       => 1
    case Value.ByteString(_) => 2
    case Value.Text(_)       => 3
    case Value.Arr(_)        => 4
    case Value.Map(_)        => 5
    case Value.Tag(_, _)     => 6
    case Value.Bool(_)       => 7
    case Value.Simple(_)     => 8
    case Value.Float(_, _)   => 9
    case _                   => 10

  /** The labels as `Value.sameLabel` compares them: zero exactly when it holds. */
  private def label(x: Value, y: Value): Int = (x, y) match
    case (Value.UInt(a), Value.UInt(b))             => a.compare(b)
    case (Value.NInt(a), Value.NInt(b))             => a.compare(b)
    case (Value.ByteString(a), Value.ByteString(b)) => a.compareTo(b)
    case (Value.Text(a), Value.Text(b))             => a.compareTo(b)
    case (Value.Bool(a), Value.Bool(b))             => java.lang.Boolean.compare(a, b)
    case (Value.Simple(a), Value.Simple(b))         => Integer.compare(a, b)
    case (Value.Float(a, wa), Value.Float(b, wb)) =>
      val widths = Integer.compare(wa.ordinal, wb.ordinal)
      if widths != 0 then widths
      else
        java.lang.Long.compare(
          java.lang.Double.doubleToLongBits(a),
          java.lang.Double.doubleToLongBits(b)
        )
    case (Value.Tag(a, _), Value.Tag(b, _)) => a.compare(b)
    case _                                  => Integer.compare(rank(x), rank(y))

  // The pairs still to compare, the children of a pair before the pairs after it: a walk on the
  // heap, so the depth of the items costs no stack.
  @tailrec private def walk(pending: List[(Value, Value)]): Int = pending match
    case Nil => 0
    case (x, y) :: rest =>
      val labels = label(x, y)
      if labels != 0 then labels
      else
        val xs = Value.nodes(x)
        val ys = Value.nodes(y)
        val counts = Integer.compare(xs.length, ys.length)
        if counts != 0 then counts
        else walk(xs.lazyZip(ys).map((p, q) => p.value -> q.value).toList ::: rest)
