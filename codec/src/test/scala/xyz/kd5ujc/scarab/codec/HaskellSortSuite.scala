// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

/** The native `Data.List.sortBy` observations for nontransitive comparators are replayed in
  * `CandidateSelectionSuite` (`fixtures/reference/candidate-order/sort.jsonl`); these check the
  * algorithm against a clause-by-clause transliteration and its stack use.
  */
class HaskellSortSuite extends ScalaCheckSuite:
  /** `sortBy` of GHC 9.10.3 (`GHC/Internal/Data/OldList.hs:1646–1673`), one Scala clause per
    * Haskell clause and with the same recursion, so only for small inputs.
    */
  private def transliteration[A](cmp: (A, A) => Int)(input: List[A]): List[A] =
    def gt(a: A, b: A): Boolean = cmp(a, b) > 0
    def sequences(xs: List[A]): List[List[A]] = xs match
      case a :: b :: rest =>
        if gt(a, b) then descending(b, List(a), rest) else ascending(b, a :: _, rest)
      case _ => List(xs)
    def descending(a: A, as: List[A], bs: List[A]): List[List[A]] = bs match
      case b :: rest if gt(a, b) => descending(b, a :: as, rest)
      case _                     => (a :: as) :: sequences(bs)
    def ascending(a: A, as: List[A] => List[A], bs: List[A]): List[List[A]] = bs match
      case b :: rest if !gt(a, b) => ascending(b, ys => as(a :: ys), rest)
      case _                      => as(List(a)) :: sequences(bs)
    def mergeAll(xs: List[List[A]]): List[A] = xs match
      case x :: Nil => x
      case _        => mergeAll(mergePairs(xs))
    def mergePairs(xs: List[List[A]]): List[List[A]] = xs match
      case a :: b :: rest => merge(a, b) :: mergePairs(rest)
      case _              => xs
    def merge(as: List[A], bs: List[A]): List[A] = (as, bs) match
      case (a :: as1, b :: bs1) => if gt(a, b) then b :: merge(as, bs1) else a :: merge(as1, bs)
      case (Nil, _)             => bs
      case (_, Nil)             => as
    mergeAll(sequences(input))

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  test("natural runs and pairwise merges retain stable ties and adversarial comparison laws") {
    val identity = Vector(1, 2, 3, 4, 5, 6, 7)
    assertEquals(HaskellSort.sortBy(Vector.empty[Int])(Integer.compare), Vector.empty)
    assertEquals(HaskellSort.sortBy(Vector(1))(Integer.compare), Vector(1))
    assertEquals(HaskellSort.sortBy(identity)((_, _) => 0), identity)
    assertEquals(HaskellSort.sortBy(identity.reverse)(Integer.compare), identity)
    assertEquals(HaskellSort.sortBy(Vector(4, 2, 7, 1, 6, 3, 5))(Integer.compare), identity)
    // A synthetic nontransitive relation, as in the native observations of sort.jsonl.
    def partial(a: Int, b: Int): Int = if (a - b).abs > 5 then 0 else Integer.compare(a, b)
    assertEquals(HaskellSort.sortBy(Vector(4, 0, 8))(partial), Vector(0, 4, 8))
    assertEquals(HaskellSort.sortBy(Vector(8, 0, 4))(partial), Vector(8, 0, 4))
  }

  // Any function is a comparator here: a random table of results over element indices.
  property("any comparator orders exactly as the transliterated reference and never throws") {
    val case_ = for
      n <- Gen.choose(0, 40)
      table <- Gen.listOfN(n * n, Gen.choose(-1, 1))
      input <- Gen.listOfN(n, Gen.choose(0, (n - 1).max(0)))
    yield (n, table.toVector, input)
    Prop.forAll(case_) { (n, table, input) =>
      def compare(a: Int, b: Int): Int = table(a * n + b)
      assertEquals(
        HaskellSort.sortBy(input.toVector)(compare).toList,
        transliteration(compare)(input)
      )
    }
  }

  property("a lawful comparator sorts stably") {
    Prop.forAll(Gen.listOf(Gen.choose(0, 5))) { keys =>
      val input = keys.zipWithIndex.toVector
      assertEquals(
        HaskellSort.sortBy(input)((a, b) => Integer.compare(a._1, b._1)),
        input.sortBy(_._1)
      )
    }
  }

  test("sorting external width remains stack safe on a 256 KiB thread") {
    onSmallStack {
      val input = Vector.tabulate(50000)(i => (i * 7919) % 50000)
      assertEquals(HaskellSort.sortBy(input)(Integer.compare), Vector.range(0, 50000))
      // Many short runs: alternating pairs leave 25000 runs to merge.
      val zigzag = Vector.tabulate(50000)(i => if i % 2 == 0 then i + 1 else i - 1)
      assertEquals(HaskellSort.sortBy(zigzag)(Integer.compare), Vector.range(0, 50000))
    }
  }
