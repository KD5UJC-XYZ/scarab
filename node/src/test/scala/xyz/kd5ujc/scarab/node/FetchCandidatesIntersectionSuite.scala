// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
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
      FetchCandidates.suffix(selected, candidate) == scanned(points, candidate)
    }
  }
