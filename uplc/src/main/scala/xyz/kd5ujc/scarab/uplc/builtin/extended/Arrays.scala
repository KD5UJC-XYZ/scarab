// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import scala.annotation.tailrec

/** `multiIndexArray` of plutus 1.70.0.0 (`507cab8c`), `PlutusCore/Arrays.hs`. */
object Arrays:
  /** `maximumIndexCount` (`Arrays.hs:34-35`). */
  val MaximumIndexCount: Int = 1024

  /** The elements of `items` at `indices`, in order (`multiIndexArray`, `Arrays.hs:48-69`). The
    * count is checked as the indices are read: the 1025th index fails on the count whatever it is,
    * and an index out of bounds before it fails on the bounds.
    */
  def multiIndexArray[A](
      items: Vector[A],
      indices: Vector[BigInt]
  ): Either[ArrayFailure, Vector[A]] =
    @tailrec def go(n: Int, rest: List[BigInt], acc: Vector[A]): Either[ArrayFailure, Vector[A]] =
      rest match
        case Nil                              => Right(acc)
        case _ :: _ if n >= MaximumIndexCount => Left(ArrayFailure.TooManyIndices)
        case i :: more =>
          Option.when(i.signum >= 0 && i < items.size)(i.toInt).flatMap(items.lift) match
            case Some(x) => go(n + 1, more, acc :+ x)
            case None    => Left(ArrayFailure.IndexOutOfBounds(i))
    go(0, indices.toList, Vector.empty)
