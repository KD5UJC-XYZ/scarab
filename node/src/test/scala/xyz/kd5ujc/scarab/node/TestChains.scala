// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.effect.IO
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{ChainSelection, HeaderChain}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** Session chains for the `Peer.over` suites that script Genesis controls without the node's own
  * header check: a check whose gates take any jump's headers, so that the controls the suite
  * scripts decide what a jump does.
  */
private[node] object TestChains:
  private def grafting(gate: HeaderGate[IO]): HeaderGate.Grafting[IO] =
    new HeaderGate.Forwarding[IO](gate) with HeaderGate.Grafting[IO]:
      def graft(
          snapshot: HeaderChain[NodeHeaderState],
          point: ChainSync.Point,
          selection: ChainSelection.Local
      ) = IO.pure(Right(()))

  /** `check` (none: every header accepted) with gates that take any jump's headers. */
  def grafting(check: Option[HeaderCheck[IO]]): HeaderCheck.Grafting[IO] =
    new HeaderCheck.Grafting[IO]:
      def startGrafting(intersection: ChainSync.Point) =
        check.fold(IO.pure(HeaderGate.none[IO]))(_.start(intersection)).map(grafting)

      def start(intersection: ChainSync.Point) = startGrafting(intersection).widen

      override def capture(using Applicative[IO]) = check.fold(IO.pure(None))(_.capture)

      def captureGrafting(using Applicative[IO]) =
        capture.map(
          _.map(c =>
            HeaderCheck.GraftingCaptured(c.points, point => c.start(point).map(grafting), c.heights)
          )
        )

  /** A Praos session over `headers`, or with `genesis`, a Genesis session over them. */
  def chain(
      headers: Option[HeaderCheck[IO]],
      genesis: Option[GenesisPeer[IO]]
  ): Peer.Chain[IO] =
    genesis.fold(Peer.Chain.Praos[IO](headers))(Peer.Chain.Genesis(grafting(headers), _))
