// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, IO, IOApp, ExitCode, Ref, Resource}
import cats.syntax.all.*
import lab.network.*
import lab.cbor.Bytes
import BoundedChainFollower.*
import scala.concurrent.duration.*

/** Private-cluster adapter: explicit checked resume and one locally injected disconnect. */
object BoundedFollowerCapture extends IOApp:
  final case class Evidence(
      first: Outcome,
      resumed: Outcome,
      offered: Vector[Vector[ChainSync.Point]],
      selected: Vector[(Int, ChainSync.Point)],
      injected: Boolean
  ):
    def complete: Boolean = first.reason == "targetReached" && resumed.reason == "targetReached" &&
      injected && offered.size == 2 && selected.size == 2
    def retainedPrefix: Boolean =
      resumed.checkpoint.originals.take(first.checkpoint.size) == first.checkpoint.originals

  def capture[F[_]: Async](
      initial: Checkpoint,
      peers: Resource[F, Peer[F]],
      firstCount: Int = 2,
      totalCount: Int = 4
  ): F[Evidence] =
    val F = Async[F]
    def checkedF[A](v: Either[String, A]) = F.fromEither(v.leftMap(new Invalid(_)))
    val valid = initial.size < firstCount && firstCount < totalCount && totalCount <= 8
    val work =
      F.raiseUnless(valid)(new Invalid("require initial size < first count < total count <= 8")) *>
        resource(initial, peers, Policy(target = firstCount, reconnects = 0, duration = 30.seconds))
          .use(_.run)
          .flatMap { first =>
            if first.reason != "targetReached" then
              F.pure(Evidence(first, first, Vector.empty, Vector.empty, false))
            else
              for
                restored <- checkedF(checked(first.checkpoint.anchor, first.checkpoint.originals))
                injected <- Ref.of[F, Boolean](false)
                offers <- Ref.of[F, Vector[Vector[ChainSync.Point]]](Vector.empty)
                selected <- Ref.of[F, Vector[(Int, ChainSync.Point)]](Vector.empty)
                scripted = peers.map { underlying =>
                  new Peer[F]:
                    def intersect(points: Vector[ChainSync.Point]) =
                      offers
                        .modify(seen => (seen :+ points, seen.size))
                        .flatMap(attempt =>
                          underlying
                            .intersect(points)
                            .flatTap(p => selected.update(_ :+ (attempt -> p)))
                        )
                    def next = injected.getAndSet(true).flatMap { already =>
                      if already then underlying.next
                      else
                        F.raiseError[Event](
                          new RuntimeException("locally injected reconnect exercise")
                        )
                    }
                    def fetch(point: ChainSync.Point): F[Bytes] = underlying.fetch(point)
                }
                resumed <- resource(
                  restored,
                  scripted,
                  Policy(target = totalCount, reconnects = 1, duration = 30.seconds)
                ).use(_.run)
                seen <- offers.get
                found <- selected.get
                fired <- injected.get
              yield Evidence(first, resumed, seen, found, fired)
          }
    F.timeoutTo(work, 65.seconds, F.raiseError(new Invalid("capture adapter time budget")))

  private def pointJson(p: ChainSync.Point): String = p match
    case ChainSync.Point.Block(slot, hash) => s"""{"slot":${slot.value},"hash":"${hash.hex}"}"""
    case _                                 => "null"

  private def quote(s: String): String = "\"" + s.flatMap {
    case '"'          => "\\\""
    case '\\'         => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c            => c.toString
  } + "\""

  /** Line-oriented original-byte evidence; caller owns redirection outside Git. */
  def records(e: Evidence): Vector[String] =
    def branch(label: String, out: Outcome): Vector[String] =
      out.checkpoint.originals.zipWithIndex.map { (o, i) =>
        s"""{"record":"acquisition-original","phase":"$label","index":$i,"headerEnvelopeHex":"${o.envelope.hex}","blockHex":"${o.block.hex}"}"""
      }
    Vector(
      s"""{"record":"acquisition-anchor","point":${pointJson(e.first.checkpoint.anchor)}}"""
    ) ++
      branch("initial", e.first) ++ branch("resumed", e.resumed) ++
      e.offered.zipWithIndex.map { (points, attempt) =>
        s"""{"record":"resume-intersection-offer","attempt":$attempt,"points":${points
            .map(pointJson)
            .mkString("[", ",", "]")}}"""
      } ++ e.selected.map { (attempt, point) =>
        s"""{"record":"resume-intersection-selected","attempt":$attempt,"point":${pointJson(
            point
          )}}"""
      } ++ Vector(
        s"""{"scope":"bounded-private-cluster-acquisition","complete":${e.complete},"initialCount":${e.first.checkpoint.size},"resumedCount":${e.resumed.checkpoint.size},"initialReason":${quote(
            e.first.reason
          )},"resumedReason":${quote(
            e.resumed.reason
          )},"initialEvents":${e.first.events},"resumedEvents":${e.resumed.events},"initialAdmittedBytes":${e.first.admittedBytes},"resumedAdmittedBytes":${e.resumed.admittedBytes},"initialPrefixPresentAtEnd":${e.retainedPrefix},"disconnectInjectedLocally":${e.injected},"resumeAttempts":${e.offered.size},"originalBytesChecked":true,"consensusValidated":false,"ledgerValidated":false}"""
      )

  def options(args: List[String]): Either[String, (NumericPeer, Long, ChainSync.Point, Int, Int)] =
    args match
      case port :: magic :: slot :: hash :: first :: total :: Nil =>
        for
          parsed <- ReferenceCaptureCommand.options(List(port, magic, slot, hash))
          start <- first.toIntOption
            .filter(n => n >= 1 && n < 8)
            .toRight("first count requires 1..7")
          end <- total.toIntOption
            .filter(n => n > start && n <= 8)
            .toRight("total count must exceed first and be <= 8")
        yield (parsed._1, parsed._2, parsed._3, start, end)
      case _ =>
        Left(
          "usage: lab.BoundedFollowerCapture PORT MAGIC ANCHOR_SLOT ANCHOR_HASH FIRST_COUNT TOTAL_COUNT (loopback only)"
        )

  def run(args: List[String]): IO[ExitCode] =
    def checkedIO[A](v: Either[String, A]) = IO.fromEither(v.leftMap(new Invalid(_)))
    (for
      opts <- checkedIO(options(args))
      (peer, magic, anchor, first, total) = opts
      limits <- checkedIO(TcpLimits.checked())
      initial <- checkedIO(checked(anchor, Vector.empty))
      evidence <- capture[IO](
        initial,
        sessions[IO](AsyncTcpTransport.resource[IO](peer, limits), magic),
        first,
        total
      )
      _ <- records(evidence).traverse_(IO.println)
    yield if evidence.complete then ExitCode.Success else ExitCode(2))
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
