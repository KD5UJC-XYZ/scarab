// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Duplicates in a sequence, found by sorting it, as the reference's decoders find them: they read
  * a set or a map into `Data.Set` or `Data.Map` (`Set.fromList`, `Map.fromList`) and compare its
  * size with the count read (`decodeSetEnforceNoDuplicates`, `decodeMapLikeEnforceNoDuplicates`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:824-845, 942-966,
  * 1033-1053`, cardano-ledger 29a7d656). Its cost is that of the sort, whatever the elements' hash
  * codes: `distinct` and `toSet` cost the square of the count when the elements share one hash.
  *
  * Each function judges two elements alike when `order` compares them equal, so it answers as its
  * hash-based counterpart does for an order that agrees with `==` (`Bytes`, `TxIn`, a credential,
  * tuples and products of those).
  */
object Unique:
  /** Whether two elements of `xs` are alike: `xs.distinct.size != xs.size`. */
  def repeats[A](xs: Vector[A])(using order: Ordering[A]): Boolean =
    xs.sizeIs > 1 && {
      val sorted = xs.sorted
      sorted.iterator.zip(sorted.iterator.drop(1)).exists((a, b) => order.equiv(a, b))
    }

  /** The first of the elements alike under `key`, in the order of `xs`: `xs.distinctBy(key)`. The
    * sort is stable, so of the indices of one key the first comes first.
    */
  def firstsBy[A, K](xs: Vector[A])(key: A => K)(using order: Ordering[K]): Vector[A] =
    if xs.sizeIs < 2 then xs
    else
      val keys = xs.map(key)
      val byKey = keys.indices.toVector.sortBy(keys)
      val starts =
        byKey.indices.filter(at => at == 0 || !order.equiv(keys(byKey(at - 1)), keys(byKey(at))))
      if starts.sizeIs == xs.size then xs else starts.map(byKey).sorted.map(xs).toVector

  /** The first of the elements alike, in the order of `xs`: `xs.distinct`. */
  def firsts[A](xs: Vector[A])(using Ordering[A]): Vector[A] = firstsBy(xs)(identity)
