// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Outcome, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.ChainSelection
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.FetchGsv
import xyz.kd5ujc.scarab.protocol.ChainSync

/** Headers, candidates and session steps shared by the fetch-decision suites. */
private[node] object FetchTesting:
  /** A test node owns its loop independently of the peers it creates. */
  def decisions(pool: BlockPool[IO]): Resource[IO, Unit] =
    Resource
      .make(pool.fetchCoordinator.run.start) { fiber =>
        fiber.cancel *> fiber.join.flatMap {
          case Outcome.Errored(cause) => IO.raiseError(cause)
          case _                      => IO.unit
        }
      }
      .void

  /** A Byron header whose hash is `n` and whose predecessor is `n - 1`. */
  def header(n: Int): Header.Byron = Header
    .UnsizedByron(
      Bytes.empty,
      Bytes.wrap(Array(n.toByte)),
      false,
      0,
      n,
      21600,
      n,
      Some(Bytes.wrap(Array((n - 1).toByte)))
    )
    .withSizeHint(1)

  def candidate(headers: Vector[Header]): ChainSelection.Candidate =
    ChainSelection.Candidate(ChainSync.Point.Origin, headers)

  extension (session: FetchCoordinator.Session[IO])
    /** Report `headers` as the session's whole ChainSync candidate. */
    def follows(
        headers: Vector[Header],
        performance: FetchGsv.PeerGsv = FetchGsv.default,
        hints: Map[Bytes, Long] = Map.empty
    ): IO[Unit] =
      val sized = headers.map {
        case header: Header.Byron =>
          hints.get(header.hash).fold(header)(hint => header.copy(blockSizeHint = hint))
        case header => header
      }
      session.announce(sized.lastOption, performance, Some(candidate(sized)))

    /** The request the decisions added for the session, without waiting for one. */
    def requested: IO[Option[FetchCoordinator.Allocation]] = session.tryAcquire

    /** The headers of that request, none when there is none. */
    def requestedHeaders: IO[Vector[Header]] = requested.map(_.toVector.flatMap(_.headers))
