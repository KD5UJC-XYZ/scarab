// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The intersection of a candidate with the selected chain, found from the selection's newest slot
  * (issue 810), is the one a scan of the whole candidate finds.
  */
class FetchCandidatesIntersectionSuite extends ScalaCheckSuite:
  private val main = Fixtures.chain(40)

  /** The search before issue 810: the last candidate header on the selected chain, or the base. */
  private def scanned(
      selected: Set[Point],
      candidate: ChainSelection.Candidate
  ): Either[Refusal, (Point, Vector[Header])] =
    candidate.headers.lastIndexWhere(h => selected(h.point)) match
      case -1 =>
        Either.cond(
          selected(candidate.base),
          candidate.base -> candidate.headers,
          Refusal.IntersectionTooDeep
        )
      case i => Right(candidate.headers(i).point -> candidate.headers.drop(i + 1))

  property("suffix finds the scan's intersection, behind, at, ahead of and off the selection") {
    forAll(
      Gen.choose(-1, 38),
      Gen.choose(0, 39),
      Gen.choose(-1, 38),
      Gen.choose(0, 39),
      Gen.choose(0, 6),
      Gen.choose(1, 2)
    ) { (anchorAt, selectedLength, baseAt, candidateLength, forkLength, salt) =>
      val anchor = main.lift(anchorAt)
      val local = ChainSelection.Local(
        anchor.fold(Point.Origin)(_.point),
        anchor.fold(SelectView(-1, 0, None, 0, None))(SelectView.of),
        main
          .slice(anchorAt + 1, anchorAt + 1 + selectedLength)
          .map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
      )
      val selected = FetchCandidates.Selected.of(local)
      val kept = main.slice(baseAt + 1, baseAt + 1 + candidateLength)
      // a fork off the candidate's last main header, with its own (wider) slot spacing
      val fork = kept.lastOption.fold(Vector.empty[Header])(last =>
        Fixtures.chain(forkLength, salt = salt, from = Some(last))
      )
      val candidate = ChainSelection.Candidate(
        main.lift(baseAt).fold(Point.Origin)(_.point),
        kept ++ fork
      )
      val points = local.blocks.map(_.point).toSet + local.anchor
      FetchCandidates.suffix(selected, FetchCandidates.Announced.of(candidate)) ==
        scanned(points, candidate)
    }
  }

  private def localOf(anchor: Header, blocks: Vector[Header]): ChainSelection.Local =
    ChainSelection.Local(
      anchor.point,
      SelectView.of(anchor),
      blocks.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )

  test("a candidate whose slots decrease is scanned from its tip, as before") {
    val anchor = main(9)
    val selected = FetchCandidates.Selected.of(localOf(anchor, Vector.empty))
    // slots 20, 21, then the anchor's own (10): only an unchecked candidate can hold this
    val candidate =
      ChainSelection.Candidate(main(0).point, Vector(main(19), main(20), anchor))
    val announced = FetchCandidates.Announced.of(candidate)
    assertEquals(announced.order, FetchCandidates.SlotOrder.Unchecked)
    assertEquals(
      FetchCandidates.suffix(selected, announced),
      scanned(Set(anchor.point), candidate)
    )
    assertEquals(FetchCandidates.suffix(selected, announced), Right(anchor.point -> Vector.empty))
  }

  test("a Byron boundary and the main block sharing its slot are both within the bound") {
    def byron(
        hash: Int,
        boundary: Boolean,
        epoch: Int,
        slotInEpoch: Int,
        blockNo: Int,
        prev: Option[Header]
    ) =
      Header
        .UnsizedByron(
          Bytes.empty,
          Bytes(Vector(hash.toByte)),
          boundary,
          epoch,
          slotInEpoch,
          21600,
          blockNo,
          prev.map(_.hash)
        )
        .withSizeHint(FetchTesting.sizeHint(1))
    // epoch 0 ends at slot 21,599; the boundary of epoch 1 and its first main block share 21,600
    val last = byron(1, false, 0, 21599, 9, None)
    val boundary = byron(2, true, 1, 0, 9, Some(last))
    val first = byron(3, false, 1, 0, 10, Some(boundary))
    val next = byron(4, false, 1, 1, 11, Some(first))
    val candidate = ChainSelection.Candidate(last.point, Vector(boundary, first, next))
    assertEquals(
      boundary.point match
        case Point.Block(slot, _) => Some(slot)
        case Point.Origin         => None
      ,
      first.point match
        case Point.Block(slot, _) => Some(slot)
        case Point.Origin         => None
    )
    assertEquals(
      FetchCandidates.Announced.of(candidate).order,
      FetchCandidates.SlotOrder.NonDecreasing
    )
    for held <- Vector(Vector(boundary), Vector(boundary, first)) do
      val selected = FetchCandidates.Selected.of(localOf(last, held))
      // the bound is the shared slot, so an exclusive bound would miss the held headers
      assertEquals(selected.newestSlot, Some(BigInt(21600)))
      val points = held.map(_.point).toSet + last.point
      assertEquals(
        FetchCandidates.suffix(selected, FetchCandidates.Announced.of(candidate)),
        scanned(points, candidate)
      )
  }

  test("saturated point slots keep the bound inclusive") {
    val far = BigInt(1) << 64
    val anchor = Fixtures.header(0, far + 5, None)
    val held = Fixtures.header(1, far + 6, Some(anchor.hash))
    val ahead = Fixtures.header(2, far + 7, Some(held.hash))
    val candidate = ChainSelection.Candidate(anchor.point, Vector(held, ahead))
    val selected = FetchCandidates.Selected.of(localOf(anchor, Vector(held)))
    assertEquals(
      FetchCandidates.Announced.of(candidate).order,
      FetchCandidates.SlotOrder.NonDecreasing
    )
    assertEquals(
      FetchCandidates.suffix(selected, FetchCandidates.Announced.of(candidate)),
      Right(held.point -> Vector(ahead))
    )
  }

  test("both searches run on a small stack over a long candidate held only near its front") {
    val long = Fixtures.chain(20000, salt = 2)
    val selected = FetchCandidates.Selected.of(localOf(long(0), Vector(long(1))))
    val ordered = ChainSelection.Candidate(long(0).point, long.drop(1))
    // the last two swapped: unchecked, scanned from the tip down to the front
    val unordered = ordered.copy(headers = long.drop(1).dropRight(2) ++ long.takeRight(2).reverse)
    assertEquals(FetchCandidates.Announced.of(unordered).order, FetchCandidates.SlotOrder.Unchecked)
    for candidate <- Vector(ordered, unordered) do
      assertEquals(
        xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack(
          FetchCandidates.suffix(selected, FetchCandidates.Announced.of(candidate))
        ),
        scanned(Set(long(0).point, long(1).point), candidate)
      )
  }
