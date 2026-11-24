// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Header, PreprodDeepTx}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** [[AvailableBlocks.candidates]], which walks from the blocks it is asked about through the
  * successors index, against the whole-forest construction it replaced ([[AvailableBlocksModel]]):
  * the same candidates in the same order, over random forests with forks, orphans, Byron boundary
  * blocks, hidden blocks, selected chains anchored at Origin, in the forest or outside it, selected
  * blocks the forest no longer holds, selected chains that are not chains (a point twice, a block's
  * or the anchor's hash at another slot, Origin among the blocks), Byron's genesis alias for Origin
  * (also naming a block of the forest), and every kind of `containing`.
  */
class AvailableBlocksPropertySuite extends ScalaCheckSuite:
  override def scalaCheckInitialSeed = "Rbx7dVg8lF0kQ2cKXbYp8mVZJm4Uo1cQyCq3m7h2zLA="

  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(5000)

  private def hash(id: Int): Bytes =
    Bytes.fromArray(
      Array.tabulate[Byte](32)(i => if i < 28 then 0 else (id >>> (8 * (31 - i))).toByte)
    )

  private def header(
      id: Int,
      parent: Option[Bytes],
      number: Int,
      slot: Int,
      boundary: Boolean
  ): Header =
    Header
      .UnsizedByron(hash(id), hash(id), boundary, 0, slot, 100000, number, parent)
      .withSizeHint(FetchTesting.sizeHint(1))

  /** Hash of Byron's genesis predecessor, and of a parent no block of the forest has. */
  private val Alias = 900
  private val Absent = 950

  private final case class Case(
      forest: Vector[Header],
      local: ChainSelection.Local,
      hidden: Set[Bytes],
      containing: Option[Set[Bytes]],
      originPredecessor: Option[Bytes]
  )

  /** A forest of `size` blocks, each a child of genesis, of the alias, of a parent outside the
    * forest, or of an earlier block: a block number one above its parent's, or the same for a
    * boundary block, sometimes another, to cross the immutable floor either way.
    */
  private def forestOf(size: Int, aliasIsBlock: Boolean): Gen[Vector[Header]] =
    (1 to size).foldLeft(Gen.const(Vector.empty[Header])) { (acc, id) =>
      acc.flatMap { built =>
        for
          parent <- Gen.frequency(
            1 -> Gen.const(Option.empty[Header] -> Option.empty[Bytes]),
            1 -> Gen.const(Option.empty[Header] -> Some(hash(Alias))),
            1 -> Gen.choose(Absent, Absent + 3).map(n => Option.empty[Header] -> Some(hash(n))),
            8 -> (if built.isEmpty then Gen.const(Option.empty[Header] -> Option.empty[Bytes])
                  else Gen.oneOf(built).map(h => Some(h) -> Some(h.hash)))
          )
          boundary <- Gen.frequency(6 -> false, 1 -> true)
          skew <- Gen.frequency(8 -> Gen.const(0), 1 -> Gen.choose(-2, 2))
          base = parent._1.fold(-1)(_.blockNo.toInt)
          number = (if boundary then base.max(0) else base + 1) + skew
          slot = parent._1.fold(0)(_.slot.toInt) + (if boundary then 0 else 1)
          // the alias may name a block of the forest (after genesis), which then has two parents'
          // worth of children when the anchor is Origin
          alias = aliasIsBlock && id == 1
        yield built :+ header(
          if alias then Alias else id,
          if alias then None else parent._2,
          number.max(0),
          slot,
          boundary
        )
      }
    }

  /** A selected chain: an anchor (Origin, a block of the forest, or a point outside it) and a path
    * of the forest's blocks after it.
    */
  private def localOf(forest: Vector[Header], alias: Option[Bytes]): Gen[ChainSelection.Local] =
    val byParent = forest.groupBy(_.prevHash)
    def path(from: Option[Bytes], also: Option[Bytes], length: Int): Gen[Vector[Header]] =
      val next = byParent.getOrElse(from, Vector.empty) ++
        also.toVector.flatMap(a => byParent.getOrElse(Some(a), Vector.empty))
      if length == 0 || next.isEmpty then Gen.const(Vector.empty)
      else Gen.oneOf(next).flatMap(h => path(Some(h.hash), None, length - 1).map(h +: _))
    for
      kind <- Gen.frequency(3 -> 0, 3 -> 1, 1 -> 2)
      length <- Gen.frequency(2 -> Gen.choose(0, 2), 1 -> Gen.choose(3, 6))
      local <- kind match
        case 0 =>
          path(None, alias, length).map(blocks =>
            ChainSelection.Local(
              Point.Origin,
              SelectView(-1, 0, None, 0, None),
              blocks.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
            )
          )
        case 1 if forest.exists(h => byParent.contains(Some(h.hash))) =>
          // an anchor with blocks after it
          Gen
            .oneOf(forest.filter(h => byParent.contains(Some(h.hash))))
            .flatMap(anchor =>
              path(Some(anchor.hash), None, length).map(blocks =>
                ChainSelection.Local(
                  anchor.point,
                  SelectView.of(anchor),
                  blocks.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
                )
              )
            )
        case _ =>
          Gen.choose(Absent, Absent + 3).flatMap { n =>
            val anchor = header(n, None, 1, 0, false)
            path(Some(anchor.hash), None, length).map(blocks =>
              ChainSelection.Local(
                anchor.point,
                SelectView.of(anchor),
                blocks.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
              )
            )
          }
      odd <- Gen.frequency(3 -> Gen.const(0), 1 -> Gen.choose(1, 3))
      mangled <- (1 to odd).foldLeft(Gen.const(local))((acc, _) => acc.flatMap(mangle(_, forest)))
    yield mangled

  /** A selected chain that is not one, which the walk from every selected point handles all the
    * same: a point twice, a hash at another slot than its header's, Origin among the blocks, or a
    * block of the forest off the path.
    */
  private def mangle(
      local: ChainSelection.Local,
      forest: Vector[Header]
  ): Gen[ChainSelection.Local] =
    val view = SelectView(0, 0, None, 0, None)
    def at(point: Point): Gen[ChainSelection.Local] =
      Gen
        .choose(0, local.blocks.size)
        .map(i =>
          local
            .copy(blocks = local.blocks.patch(i, Vector(ChainSelection.LocalBlock(point, view)), 0))
        )
    Gen.oneOf(0, 1, 2, 3, 4).flatMap {
      // the anchor's hash at another slot
      case 4 =>
        Gen.const(local.anchor match
          case Point.Block(slot, hash) =>
            local.copy(anchor =
              Point.Block(
                xyz.kd5ujc.scarab.protocol.ChainSync.UInt64.saturating(slot.value + 5),
                hash
              )
            )
          case Point.Origin => local)
      case 0 if local.blocks.nonEmpty => Gen.oneOf(local.blocks).flatMap(b => at(b.point))
      case 1 if local.blocks.nonEmpty =>
        Gen.choose(0, local.blocks.size - 1).map { i =>
          local.copy(blocks =
            local.blocks.updated(
              i,
              local
                .blocks(i)
                .copy(point = local.blocks(i).point match
                  case Point.Block(slot, hash) =>
                    Point.Block(
                      xyz.kd5ujc.scarab.protocol.ChainSync.UInt64.saturating(slot.value + 5),
                      hash
                    )
                  case Point.Origin => Point.Origin)
            )
          )
        }
      case 2                    => at(Point.Origin)
      case _ if forest.nonEmpty => Gen.oneOf(forest).flatMap(h => at(h.point))
      case _                    => Gen.const(local)
    }

  /** Each of `hashes` with a chance of one in six. */
  private def few(hashes: Vector[Bytes]): Gen[Set[Bytes]] =
    Gen
      .sequence[Vector[Option[Bytes]], Option[Bytes]](
        hashes.map(h => Gen.frequency(5 -> Gen.const(None), 1 -> Gen.const(Some(h))))
      )
      .map(_.flatten.toSet)

  private val cases: Gen[Case] =
    for
      size <- Gen.frequency(1 -> Gen.choose(0, 3), 4 -> Gen.choose(4, 24))
      aliasIsBlock <- Gen.frequency(8 -> false, 1 -> true)
      originPredecessor <- Gen.frequency(1 -> None, 1 -> Some(hash(Alias)))
      all <- forestOf(size, aliasIsBlock)
      local <- localOf(all, originPredecessor)
      // a selected block the forest no longer holds: its successors still join the chain
      dropped <- few(local.blocks.flatMap(_.point match
        case Point.Block(_, h) => Vector(h)
        case Point.Origin      => Vector.empty))
      forest = all.filterNot(h => dropped(h.hash))
      hidden <- few(forest.map(_.hash))
      targets <- Gen.frequency(
        1 -> Gen.const(Option.empty[Set[Bytes]]),
        4 -> Gen
          .choose(1, 3)
          .flatMap(n =>
            Gen
              .listOfN(
                n,
                Gen.frequency(
                  6 -> (if forest.isEmpty then Gen.const(hash(Absent))
                        else Gen.oneOf(forest.map(_.hash))),
                  1 -> Gen.const(hash(Absent + 7))
                )
              )
              .map(t => Some(t.toSet))
          ),
        1 -> Gen.const(Some(Set.empty[Bytes]))
      )
    yield Case(forest, local, hidden, targets, originPredecessor)

  property("the candidates through the successors index are the whole-forest construction's") {
    forAll(cases) { c =>
      val forest = ProcessedForest.of(c.forest).fold(e => fail(e.toString), identity)
      val found =
        AvailableBlocks.candidates(c.local, forest, c.hidden, c.containing, c.originPredecessor)
      val expected = AvailableBlocksModel.candidates(
        c.local,
        c.forest.map(h => h.hash -> h).toMap,
        c.hidden,
        c.containing,
        c.originPredecessor
      )
      Prop(expected == Right(found)) :| s"expected $expected, found $found"
    }
  }

  property("a first-admission pass gives the candidates of the full pass that go through it") {
    forAll(cases) { c =>
      val forest = ProcessedForest.of(c.forest).fold(e => fail(e.toString), identity)
      val all = AvailableBlocks.candidates(c.local, forest, c.hidden, None, c.originPredecessor)
      c.containing.fold(Prop.passed) { targets =>
        val through =
          AvailableBlocks.candidates(c.local, forest, c.hidden, c.containing, c.originPredecessor)
        Prop(through == all.filter(_.headers.exists(h => targets(h.hash))))
      }
    }
  }

  private val origin =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
  private def chain(length: Int): Vector[Header] =
    Vector.tabulate(length)(i => header(i + 1, Option.when(i > 0)(hash(i)), i, i, false))

  test("a deep chain is walked back and forward on a 256 KiB thread") {
    val headers = chain(30000)
    PreprodDeepTx.onSmallStack {
      val forest = ProcessedForest.of(headers).fold(e => fail(e.toString), identity)
      val whole = Vector(ChainSelection.Candidate(Point.Origin, headers))
      // back from the tip to Origin
      assertEquals(
        AvailableBlocks.candidates(origin, forest, _ => false, Some(Set(headers.last.hash))),
        whole
      )
      // forward from the first block to the tip
      assertEquals(
        AvailableBlocks.candidates(origin, forest, _ => false, Some(Set(headers.head.hash))),
        whole
      )
      assertEquals(AvailableBlocks.candidates(origin, forest, _ => false), whole)
      // a selected chain of 20,000 blocks, and the block after its tip
      val selected = origin.copy(blocks =
        headers.take(20000).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
      )
      assertEquals(
        AvailableBlocks.candidates(selected, forest, _ => false, Some(Set(headers(20000).hash))),
        Vector(ChainSelection.Candidate(headers(19999).point, headers.drop(20000)))
      )
    }
  }
