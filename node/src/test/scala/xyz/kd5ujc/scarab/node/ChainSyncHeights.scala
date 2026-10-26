// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.{ChainSyncInternalProblem, Header}
import xyz.kd5ujc.scarab.protocol.ChainSync

object ChainSyncHeights extends munit.Assertions:
  def selected(headers: Vector[Header], points: Vector[ChainSync.Point]): Peer.HeightSnapshot[IO] =
    new Peer.HeightSnapshot[IO]:
      def capture = IO.pure(
        Peer.HeightSnapshot.Captured(
          points,
          headers
            .map(h => h.point -> Option(h.blockNo))
            .toMap
            .updated(ChainSync.Point.Origin, None)
        )
      )
      def unavailable[A](cause: ChainSyncInternalProblem): IO[A] = IO(fail(cause.message))

  val origin: Peer.HeightSnapshot[IO] = new Peer.HeightSnapshot[IO]:
    def capture = IO.pure(
      Peer.HeightSnapshot.Captured(
        Vector(ChainSync.Point.Origin),
        Map(ChainSync.Point.Origin -> None)
      )
    )
    def unavailable[A](cause: ChainSyncInternalProblem): IO[A] = IO(fail(cause.message))

  /** Our chain's points and heights as the node offers them, newest first. */
  def of[S](chain: Ref[IO, VolatileChain[S]]): Peer.HeightSnapshot[IO] =
    new Peer.HeightSnapshot[IO]:
      def capture = chain.get.map { current =>
        Peer.HeightSnapshot.Captured(
          current.blocks.reverse.map(_._1.point) :+ current.anchor.point,
          (current.anchor.block.toVector ++ current.blocks.map(_._1))
            .map(block => block.point -> Option(block.blockNo))
            .toMap
            .updated(ChainSync.Point.Origin, None)
        )
      }
      def unavailable[A](cause: ChainSyncInternalProblem): IO[A] = IO(fail(cause.message))
