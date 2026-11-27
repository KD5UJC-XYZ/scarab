// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{EvalRequest, EvalResult, ExUnits, PlutusData, PlutusLanguage}

/** The comparison the kept results are found by ([[ScriptsAhead.Same]], #1499): it says what `==`
  * says of two requests, for copies and for requests that differ in one node, with and without the
  * pairs a hit proved equal; and a proven pair answers only for the very node it was proved for.
  */
class ScriptsAheadSameSuite extends ScalaCheckSuite:
  import ScriptsAheadSameSuite.*

  property("the comparison of requests is ==, for copies and for one node changed") {
    forAll(data(4), Gen.choose(0, 40), Gen.oneOf(true, false)) { (d, at, change) =>
      val kept = request(d)
      val other = request(if change then changed(copied(d), at) else copied(d))
      assertEquals(ScriptsAhead.Same.requests(other, kept, Map.empty), other == kept)
      assertEquals(ScriptsAhead.Same.data(other.args.head, kept.args.head), other.args == kept.args)
    }
  }

  property("with the pairs a hit proved, the comparison is still ==") {
    forAll(data(4), Gen.choose(0, 40)) { (d, at) =>
      val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
      val kept = request(info(d, PlutusData.int(0)))
      assert(results.put(block, kept, result))
      // a hit of the first script proves the shared information
      val shared = copied(d)
      assertEquals(results.lookup(request(info(shared, PlutusData.int(0)))), Some(result))
      // the next script of the transaction shares it; one of another transaction has its own copy,
      // equal or changed in one node
      assertEquals(results.lookup(request(info(shared, PlutusData.int(0)))), Some(result))
      val other = changed(copied(d), at)
      assertEquals(
        results.lookup(request(info(other, PlutusData.int(0)))).isDefined,
        other == d
      )
    }
  }

  test("a proven pair answers only for the node it was proved for") {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    val d =
      PlutusData.Constr(0, Vector(PlutusData.List(Vector(PlutusData.int(1), PlutusData.int(2)))))
    val kept = request(info(d, PlutusData.int(0)))
    assert(results.put(block, kept, result))
    val shared = copied(d)
    assertEquals(results.lookup(request(info(shared, PlutusData.int(0)))), Some(result))
    // another object for the information, which differs inside it: not found, though the kept
    // information has a proven counterpart
    val inside =
      PlutusData.Constr(0, Vector(PlutusData.List(Vector(PlutusData.int(1), PlutusData.int(3)))))
    assertEquals(results.lookup(request(info(inside, PlutusData.int(0)))), None)
    // the proven information with another purpose: not found
    assertEquals(results.lookup(request(info(shared, PlutusData.int(1)))), None)
    assertEquals(results.counts, ScriptsAhead.Counts(1, 0, 2))
    assert(results.provenPairs > 0)
    // dropping the block drops its proven pairs too
    results.drop(block)
    assertEquals(results.held._1, 0)
    assertEquals(results.provenPairs, 0)
  }

  test("the proven pairs stay within their bound") {
    val results = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    // contexts whose information has many children, each a pair of its own once proved
    (0 until 40).foreach { t =>
      val d = PlutusData.List(
        Vector.tabulate(200)(i => PlutusData.List(Vector(PlutusData.int(t), PlutusData.int(i))))
      )
      val kept = request(info(d, PlutusData.int(t)))
      assert(results.put(block, kept, result))
      assertEquals(results.lookup(request(info(copied(d), PlutusData.int(t)))), Some(result))
      assert(results.provenPairs <= ScriptsAhead.Same.Proven, results.provenPairs.toString)
    }
    assertEquals(results.provenPairs, ScriptsAhead.Same.Proven)
    results.drop(block)
    assertEquals(results.provenPairs, 0)
  }

  test("a transaction's information shared by its scripts' contexts is charged once a block") {
    val big = PlutusData.List(Vector.tabulate(1000)(i => PlutusData.int(i)))
    val one = request(info(big, PlutusData.int(0)))
    val two = request(info(big, PlutusData.int(1)))
    val alone = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    assert(alone.put(block, one, result))
    val (_, single) = alone.held
    val both = ScriptsAhead.Results.create(ScriptsAhead.Bounds)
    assert(both.put(block, one, result))
    assert(both.put(block, two, result))
    val (n, bytes) = both.held
    assertEquals(n, 2)
    // the second charged for its own nodes only, far less than the information
    assert(bytes < single + single / 10, s"$bytes against $single")
    // another block's result with the same information is charged in full
    val three = request(info(big, PlutusData.int(2)))
    assert(both.put(Bytes(Vector.fill(32)(2.toByte)), three, result))
    assert(both.held._2 >= bytes + single - 1, s"${both.held} against $bytes and $single")
    both.drop(block)
    assertEquals(both.held, (1, single))
  }

object ScriptsAheadSameSuite:
  val block: Bytes = Bytes(Vector.fill(32)(1.toByte))
  val result: EvalResult = EvalResult.Success(ExUnits(1, 1), Vector.empty)

  def request(arg: PlutusData): EvalRequest =
    EvalRequest(
      PlutusLanguage.V3,
      9,
      Bytes(Vector(7)),
      Vector(arg),
      Vector(1L, 2L),
      ExUnits(10, 10)
    )

  /** A context as the collection makes one: its information first, its purpose after. */
  def info(txInfo: PlutusData, purpose: PlutusData): PlutusData =
    PlutusData.Constr(0, Vector(txInfo, purpose))

  def data(depth: Int): Gen[PlutusData] =
    val leaf = Gen.oneOf(
      Gen.choose(-3, 3).map(i => PlutusData.int(i)),
      Gen.choose(0, 2).map(n => PlutusData.bytes(Bytes(Vector.fill(n)(n.toByte))))
    )
    if depth <= 0 then leaf
    else
      val child = data(depth - 1)
      Gen.frequency(
        3 -> leaf,
        1 -> Gen
          .choose(0, 3)
          .flatMap(n => Gen.listOfN(n, child))
          .map(l => PlutusData.List(l.toVector)),
        1 -> Gen
          .zip(Gen.choose(0, 2), Gen.choose(0, 3).flatMap(n => Gen.listOfN(n, child)))
          .map((t, l) => PlutusData.Constr(t, l.toVector)),
        1 -> Gen
          .choose(0, 2)
          .flatMap(n => Gen.listOfN(n, Gen.zip(child, child)))
          .map(l => PlutusData.Map(l.toVector))
      )

  def copied(d: PlutusData): PlutusData = d match
    case PlutusData.Constr(t, fs) => PlutusData.Constr(BigInt(t.toByteArray), fs.map(copied))
    case PlutusData.Map(kvs)      => PlutusData.Map(kvs.map((k, v) => copied(k) -> copied(v)))
    case PlutusData.List(xs)      => PlutusData.List(xs.map(copied))
    case PlutusData.I(i)          => PlutusData.I(BigInt(i.toByteArray))
    case PlutusData.B(b)          => PlutusData.B(Bytes.fromArray(b.toArray))

  /** `d` with its node at pre-order index `at` (modulo its size) changed: a leaf's value or a
    * node's tag or kind; the result may equal `d` only where the change is undone by chance.
    */
  def changed(d: PlutusData, at: Int): PlutusData =
    def size(x: PlutusData): Int = x match
      case PlutusData.Constr(_, fs) => 1 + fs.map(size).sum
      case PlutusData.Map(kvs)      => 1 + kvs.map((k, v) => size(k) + size(v)).sum
      case PlutusData.List(xs)      => 1 + xs.map(size).sum
      case _                        => 1
    def go(x: PlutusData, i: Int): PlutusData =
      if i == 0 then
        x match
          case PlutusData.I(v)          => PlutusData.I(v + 1)
          case PlutusData.B(b)          => PlutusData.B(b ++ Bytes(Vector(9)))
          case PlutusData.Constr(t, fs) => PlutusData.Constr(t + 1, fs)
          case PlutusData.List(xs)      => PlutusData.Map(xs.map(v => v -> v))
          case PlutusData.Map(kvs)      => PlutusData.List(kvs.map(_._1))
      else
        def within(children: Vector[PlutusData], j: Int): Vector[PlutusData] =
          children
            .foldLeft((Vector.empty[PlutusData], j)) { case ((done, k), c) =>
              val n = size(c)
              if k >= 0 && k < n then (done :+ go(c, k), -1) else (done :+ c, k - n)
            }
            ._1
        x match
          case PlutusData.Constr(t, fs) => PlutusData.Constr(t, within(fs, i - 1))
          case PlutusData.List(xs)      => PlutusData.List(within(xs, i - 1))
          case PlutusData.Map(kvs) =>
            val flat = within(kvs.flatMap((k, v) => Vector(k, v)), i - 1)
            PlutusData.Map(flat.grouped(2).collect { case Vector(k, v) => k -> v }.toVector)
          case leaf => leaf
    go(d, at % size(d))
