// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode, Ref}
import cats.syntax.all.*
import java.nio.file.Path
import lab.network.*
import lab.cbor.Bytes
import AcquisitionCheckpoint.*
import BoundedChainFollower.*
import NioAcquisitionCheckpointStore.Open
import scala.concurrent.duration.*

/** One process owns one acquisition phase; the Python harness owns independent context pins. */
object AcquisitionRestartCapture extends IOApp:
  final case class Options(
      peer: NumericPeer,
      context: Context,
      mode: Open,
      target: Int,
      phase: String,
      hold: Boolean
  )
  def options(args: List[String]): Either[String, Options] = args match
    case phase :: port :: magic :: slot :: hash :: source :: genesis :: generation :: digest :: Nil =>
      for
        parsed <- ReferenceCaptureCommand.options(List(port, magic, slot, hash))
        context <- Context.checked(source, genesis, parsed._2, parsed._3)
        mode <- (phase, generation, digest) match
          case ("a" | "a-hold", "-", "-") => Right(Open.Create)
          case ("b", g, d) if g.toLongOption.exists(_ >= 0) && lab.fetcher.Digests.valid(d) =>
            Right(Open.Resume(Some(Revision(g.toLong, d))))
          case _ => Left("phase a requires no revision; phase b requires exact expected revision")
      yield Options(
        parsed._1,
        context,
        mode,
        if phase == "b" then 4 else 2,
        if phase == "b" then "b" else "a",
        phase == "a-hold"
      )
    case _ =>
      Left(
        "usage: lab.AcquisitionRestartCapture a|a-hold|b PORT MAGIC SLOT HASH SOURCE_SHA GENESIS_SHA GENERATION|- DIGEST|-"
      )

  private def pointJson(point: ChainSync.Point): String = point match
    case ChainSync.Point.Block(slot, hash) => s"""{"slot":${slot.value},"hash":"${hash.hex}"}"""
    case _                                 => "null"
  private def originals(label: String, checkpoint: Checkpoint): Vector[String] =
    checkpoint.originals.zipWithIndex.map { (o, i) =>
      s"""{"record":"original","stage":"$label","index":$i,"headerEnvelopeHex":"${o.envelope.hex}","blockHex":"${o.block.hex}"}"""
    }

  def run(args: List[String]): IO[ExitCode] =
    def checkedIO[A](value: Either[String, A]) = IO.fromEither(value.leftMap(new Invalid(_)))
    if args == List("probe") then
      val context = Context
        .checked(
          "0" * 64,
          "1" * 64,
          1082026,
          ChainSync.Point.Block(ChainSync.UInt64.Zero, Bytes(Vector.fill(32)(0.toByte)))
        )
        .toOption
        .get
      val path = Path.of("/checkpoint/probe")
      val probe = for
        revision <- NioAcquisitionCheckpointStore.resource[IO](path, context, Open.Create).use {
          store =>
            store.snapshot.flatMap(s => store.save(s.revision, s.checkpoint)).map(_.revision)
        }
        _ <- NioAcquisitionCheckpointStore
          .resource[IO](path, context, Open.Resume(Some(revision)))
          .use(_.snapshot)
        _ <- IO.println(
          "{\"atomicPublicationProbe\":true,\"directoryForceCompleted\":true,\"networkExecuted\":false}"
        )
      yield ExitCode.Success
      return probe.handleErrorWith(error =>
        IO.println("ERROR: " + error.getMessage).as(ExitCode(2))
      )
    val work = for
      opts <- checkedIO(options(args))
      limits <- checkedIO(TcpLimits.checked())
      nonce <- IO(java.util.UUID.randomUUID().toString)
      _ <- IO.println(s"""{"record":"process","phase":"${opts.phase}","pid":${ProcessHandle
          .current()
          .pid()},"nonce":"$nonce"}""")
      result <- NioAcquisitionCheckpointStore
        .resource[IO](Path.of("/checkpoint/state"), opts.context, opts.mode)
        .use { store =>
          for
            before <- store.snapshot
            _ <- IO.raiseUnless(before.checkpoint.size == (if opts.phase == "a" then 0 else 2))(
              new Invalid("unexpected retained prefix size")
            )
            _ <- originals("loaded", before.checkpoint).traverse_(IO.println)
            offers <- Ref.of[IO, Vector[Vector[ChainSync.Point]]](Vector.empty)
            selected <- Ref.of[IO, Vector[ChainSync.Point]](Vector.empty)
            fetched <- Ref.of[IO, Vector[ChainSync.Point]](Vector.empty)
            peers = sessions[IO](
              AsyncTcpTransport.resource[IO](opts.peer, limits),
              opts.context.networkMagic
            ).map { delegate =>
              new Peer[IO]:
                def intersect(points: Vector[ChainSync.Point]) =
                  offers.update(_ :+ points) *> delegate
                    .intersect(points)
                    .flatTap(p => selected.update(_ :+ p))
                def next = delegate.next
                def fetch(point: ChainSync.Point): IO[Bytes] =
                  fetched.update(_ :+ point) *> delegate.fetch(point)
            }
            outcome <- persistedResource(
              store,
              peers,
              Policy(target = opts.target, reconnects = 0, duration = 45.seconds)
            ).use(_.run)
            after <- store.snapshot
            offered <- offers.get
            found <- selected.get
            requested <- fetched.get
            complete = outcome.reason == "targetReached" && after.checkpoint.size == opts.target &&
              after.checkpoint.originals.take(
                before.checkpoint.size
              ) == before.checkpoint.originals &&
              offered == Vector(before.checkpoint.candidates) && found == Vector(
                before.checkpoint.tip
              ) &&
              requested.size == opts.target - before.checkpoint.size &&
              !requested.exists(before.checkpoint.candidates.contains)
            _ <- originals("published", after.checkpoint).traverse_(IO.println)
            _ <- IO.println(s"""{"record":"intersections","offered":${offered
                .map(_.map(pointJson).mkString("[", ",", "]"))
                .mkString("[", ",", "]")},"selected":${found
                .map(pointJson)
                .mkString("[", ",", "]")},"fetched":${requested
                .map(pointJson)
                .mkString("[", ",", "]")}}""")
            _ <- IO.println(
              s"""{"scope":"bounded-acquisition-process-phase","phase":"${opts.phase}","complete":$complete,"loadedCount":${before.checkpoint.size},"publishedCount":${after.checkpoint.size},"loadedGeneration":${before.revision.generation},"loadedRevision":"${before.revision.digest}","publishedGeneration":${after.revision.generation},"publishedRevision":"${after.revision.digest}","loadedSource":"${before.checkpoint
                  .source[IO]
                  .identity
                  .digest}","publishedSource":"${after.checkpoint
                  .source[IO]
                  .identity
                  .digest}","upstreamSource":"${opts.context.sourceDigest}","genesisDigest":"${opts.context.genesisDigest}","profile":"${opts.context.profile}","anchor":${pointJson(
                  opts.context.anchor
                )},"networkMagic":${opts.context.networkMagic},"ledgerValidated":false,"consensusValidated":false,"segmentStoreReused":false}"""
            )
            _ <-
              if opts.hold && complete then
                IO.println(
                  s"""{"record":"acknowledged-hold","generation":${after.revision.generation},"digest":"${after.revision.digest}"}"""
                ) *>
                  IO.sleep(60.seconds) *> IO.raiseError(new Invalid("acknowledged hold expired"))
              else IO.unit
          yield if complete then ExitCode.Success else ExitCode(2)
        }
    yield result
    work.handleErrorWith(error => IO.println("ERROR: " + error.getMessage).as(ExitCode(2)))
