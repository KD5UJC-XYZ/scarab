// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

class FetchCandidatesSuite extends FunSuite:
  private val flavor = ChainSelection.Tiebreaker.Restricted(5)
  private val originView = SelectView(-1, 0, None, 0, None)
  private def local(headers: Vector[Header]): ChainSelection.Local =
    ChainSelection.Local(
      Point.Origin,
      originView,
      headers.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )

  test("extension requests only headers beyond the latest shared point") {
    val chain = Fixtures.chain(5)
    val selected = local(chain.take(3))
    val result =
      FetchCandidates.select(flavor, selected, ChainSelection.Candidate(Point.Origin, chain))
    assertEquals(
      result,
      Right(FetchCandidates.Selection(chain(2).point, chain.drop(3), Vector(chain.drop(3))))
    )
  }

  test("a better fork is fetched from the latest common ancestor") {
    val chain = Fixtures.chain(3)
    val fork = Fixtures.chain(3, salt = 7, from = Some(chain(0)))
    val candidate = ChainSelection.Candidate(Point.Origin, Vector(chain(0)) ++ fork)
    assertEquals(
      FetchCandidates.select(flavor, local(chain), candidate),
      Right(FetchCandidates.Selection(chain(0).point, fork, Vector(fork)))
    )
  }

  test("shorter, identical and empty candidates are not plausible") {
    val chain = Fixtures.chain(4)
    Vector(chain.take(2), chain, Vector.empty).foreach { headers =>
      assertEquals(
        FetchCandidates
          .select(flavor, local(chain), ChainSelection.Candidate(Point.Origin, headers)),
        Left(Refusal.ChainNotPlausible)
      )
    }
  }

  test("no retained intersection declines at the first native stage") {
    val chain = Fixtures.chain(3)
    val base = Fixtures.header(19, 99, None, salt = 42)
    val elsewhere = Fixtures.header(20, 100, Some(base.hash))
    val candidate = ChainSelection.Candidate(base.point, Vector(elsewhere))
    assertEquals(
      FetchCandidates.select(flavor, local(chain), candidate),
      Left(Refusal.ChainNotPlausible)
    )
    assertEquals(FetchCandidates.suffix(local(chain), candidate), Left(Refusal.IntersectionTooDeep))
  }

  test("an older base can intersect a retained header after the immutable anchor moved") {
    val chain = Fixtures.chain(6)
    val selected = ChainSelection.Local(
      chain(2).point,
      SelectView.of(chain(2)),
      Vector(ChainSelection.LocalBlock(chain(3).point, SelectView.of(chain(3))))
    )
    assertEquals(
      FetchCandidates.select(flavor, selected, ChainSelection.Candidate(Point.Origin, chain)),
      Right(FetchCandidates.Selection(chain(3).point, chain.drop(4), Vector(chain.drop(4))))
    )
  }

  test("downloaded holes retain separate contiguous fetch fragments") {
    val chain = Fixtures.chain(5)
    val fetched = Set(chain(1).point, chain(3).point)
    assertEquals(
      FetchCandidates.select(
        flavor,
        local(Vector.empty),
        ChainSelection.Candidate(Point.Origin, chain),
        fetched
      ),
      Right(
        FetchCandidates.Selection(
          Point.Origin,
          chain,
          Vector(Vector(chain(0)), Vector(chain(2)), Vector(chain(4)))
        )
      )
    )
    assertEquals(
      FetchCandidates.select(
        flavor,
        local(Vector.empty),
        ChainSelection.Candidate(Point.Origin, chain),
        chain.map(_.point).toSet
      ),
      Left(Refusal.AlreadyFetched)
    )
  }

  test("empty current fragment admits Byron boundary extension with equal chain difficulty") {
    val anchor = Fixtures.header(10, 100, None)
    val boundary =
      Header.Byron(Bytes.empty, Bytes(Vector(99.toByte)), true, 1, 0, 101, 10, Some(anchor.hash))
    val selected = ChainSelection.Local(anchor.point, SelectView.of(anchor), Vector.empty)
    assert(
      FetchCandidates.plausible(
        flavor,
        selected,
        ChainSelection.Candidate(anchor.point, Vector(boundary))
      )
    )
    assertEquals(
      FetchCandidates
        .select(flavor, selected, ChainSelection.Candidate(anchor.point, Vector(boundary))),
      Right(FetchCandidates.Selection(anchor.point, Vector(boundary), Vector(Vector(boundary))))
    )
  }
