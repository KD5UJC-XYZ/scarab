// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.collection.immutable.{SortedSet, TreeSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Header, PreprodDeepTx}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** [[ProcessedForest]] against a plain map of its blocks: after any sequence of insertions,
  * removals, collections and seedings its blocks are the map's, its successors index is the one
  * rebuilt from them, and a block it refuses is one that would be its own ancestor.
  */
class ProcessedForestSuite extends ScalaCheckSuite:
  override def scalaCheckInitialSeed = "p3QzGm1sS0tZb8yLk2dVx4Rf7HcW9jNqA5uE6oIiT1M="

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  private def hash(id: Int): Bytes =
    Bytes.fromArray(
      Array.tabulate[Byte](32)(i => if i < 28 then 0 else (id >>> (8 * (31 - i))).toByte)
    )
  private def header(id: Int, parent: Option[Bytes], slot: Int): Header =
    Header
      .UnsizedByron(hash(id), hash(id), false, 0, slot, 100000, slot, parent)
      .withSizeHint(FetchTesting.sizeHint(1))

  private enum Op:
    case Insert(block: Header)
    case Remove(hash: Bytes)
    case Collect(slot: Int)
    case Seed(blocks: Vector[Header])

  /** A block of one of a few dozen hashes, after genesis or after another of them: blocks the
    * forest already has, blocks after absent ones, and blocks that close a cycle.
    */
  private val blocks: Gen[Header] =
    for
      id <- Gen.choose(1, 40)
      parent <- Gen.frequency(1 -> Gen.const(None), 6 -> Gen.choose(1, 45).map(n => Some(hash(n))))
      slot <- Gen.choose(0, 20)
    yield header(id, parent, slot)

  private val ops: Gen[Op] = Gen.frequency(
    6 -> blocks.map(Op.Insert(_)),
    2 -> Gen.choose(1, 40).map(n => Op.Remove(hash(n))),
    1 -> Gen.choose(0, 21).map(Op.Collect(_)),
    1 -> Gen.choose(0, 6).flatMap(Gen.listOfN(_, blocks)).map(b => Op.Seed(b.toVector))
  )

  private val origin =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)

  /** Whether `blocks` has a block that is its own ancestor: the model's whole-forest check. */
  private def cyclic(blocks: Map[Bytes, Header]): Boolean =
    AvailableBlocksModel.candidates(origin, blocks, Set.empty) match
      case Left(AvailableBlocksModel.Error.Cycle(_)) => true
      case _                                         => false

  private def rebuilt(blocks: Map[Bytes, Header]): Map[Option[Bytes], SortedSet[Bytes]] =
    blocks.values
      .groupBy(_.prevHash)
      .view
      .mapValues(hs =>
        TreeSet.from(hs.map(_.hash))(using ProcessedForest.hashOrder): SortedSet[Bytes]
      )
      .toMap

  /** One step of the forest and of the map; a refusal leaves both as they were. */
  private def step(
      forest: ProcessedForest[Header],
      model: Map[Bytes, Header],
      op: Op
  ): (ProcessedForest[Header], Map[Bytes, Header], Prop) = op match
    case Op.Insert(block) =>
      val expected =
        if model.contains(block.hash) then Right(model)
        else
          val grown = model.updated(block.hash, block)
          if cyclic(grown) then Left(ProcessedForest.Cycle(block.hash)) else Right(grown)
      forest.inserted(block) match
        case Right(next) =>
          (next, model ++ expected.toOption.getOrElse(model), Prop(expected.isRight))
        case Left(cycle) => (forest, model, Prop(expected == Left(cycle)) :| s"refused $cycle")
    case Op.Remove(hash) => (forest.removed(hash), model - hash, Prop.passed)
    case Op.Collect(slot) =>
      (forest.collectedBelow(slot), model.filter((_, h) => h.slot >= slot), Prop.passed)
    case Op.Seed(batch) =>
      val grown = batch.map(h => h.hash -> h).toMap ++ model
      forest.seeded(batch) match
        case Right(next) => (next, grown, Prop(!cyclic(grown)) :| "seeded a cycle")
        case Left(_)     => (forest, model, Prop(cyclic(grown)) :| "refused an acyclic seed")

  property("the forest is its blocks' map, with the successors index rebuilt from them") {
    forAll(Gen.listOf(ops)) { sequence =>
      val (forest, model, props) = sequence.foldLeft(
        (ProcessedForest.empty[Header], Map.empty[Bytes, Header], Prop.passed)
      ) { case ((f, m, p), op) =>
        val (nf, nm, np) = step(f, m, op)
        val consistent =
          Prop(nf.byHash == nm) :| s"blocks after $op" &&
            Prop(nf.successorIndex == rebuilt(nm)) :| s"index after $op" &&
            Prop(
              nf.slotIndex == nm.values.groupBy(_.slot).view.mapValues(_.map(_.hash).toSet).toMap
            ) :| s"slots after $op" &&
            Prop(
              nm.keys.forall(h =>
                nf.successors(Some(h)) == rebuilt(nm)
                  .getOrElse(Some(h), TreeSet.empty(using ProcessedForest.hashOrder))
              )
            )
        (nf, nm, p && np && consistent)
      }
      props && Prop(!cyclic(forest.byHash)) && Prop(forest.size == model.size)
    }
  }

  private def chain(length: Int): Vector[Header] =
    Vector.tabulate(length)(i => header(i + 1, Option.when(i > 0)(hash(i)), i))

  test("a deep chain is seeded, inserted tip first, refused and collected on a 256 KiB thread") {
    val headers = chain(30000)
    PreprodDeepTx.onSmallStack {
      val seeded = ProcessedForest.of(headers.reverse).fold(e => fail(e.toString), identity)
      val inserted = headers.reverse.foldLeft(ProcessedForest.empty[Header])((forest, h) =>
        forest.inserted(h).fold(e => fail(e.toString), identity)
      )
      assertEquals(inserted.byHash, seeded.byHash)
      assertEquals(inserted.successorIndex, rebuilt(seeded.byHash))
      // the first block again, after the tip: the walk back from the tip crosses the whole chain
      assertEquals(
        inserted.removed(hash(1)).inserted(header(1, Some(headers.last.hash), 30000)).map(_.size),
        Left(ProcessedForest.Cycle(hash(1)))
      )
      assertEquals(
        inserted.inserted(header(30001, Some(headers.last.hash), 30000)).map(_.size),
        Right(30001)
      )
      val collected = inserted.collectedBelow(30000)
      assertEquals(collected.size, 0)
      assertEquals(collected.successorIndex, Map.empty)
    }
  }
