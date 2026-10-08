// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Deferred, Ref, Resource, Outcome}
import cats.effect.std.{Queue, Semaphore}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** Closed initiator routes 3/8 beneath the existing single-protocol handshake/BF engine. Only this
  * transport reads the physical bearer. Queued SDUs retain their original headers; the virtual BF
  * transport does not relabel protocol numbers or directions. Not a general mux.
  */
private[network] final class KeepAliveTransport[F[_]: Async] private (
    raw: ByteTransport[F],
    config: BlockFetchSession.Config,
    policy: KeepAliveTransport.Policy,
    state: Ref[F, KeepAliveTransport.State],
    stop: Deferred[F, Throwable],
    changed: Queue[F, Unit],
    kaChanged: Queue[F, Unit],
    writerChanged: Queue[F, Unit],
    bfOut: Queue[F, KeepAliveTransport.Write[F]],
    kaOut: Queue[F, KeepAliveTransport.Write[F]],
    bfWriteGate: Semaphore[F],
    outbound: Ref[F, Mux.Decoder],
    start: Deferred[F, Unit],
    firstPing: Deferred[F, Unit],
    finishSignal: Deferred[F, Unit],
    kaDone: Deferred[F, Unit],
    cookie: F[KeepAlive.Cookie],
    readerStop: Deferred[F, Unit],
    readerFinished: Deferred[F, Unit],
    readHandoff: F[Unit]
):
  import KeepAliveTransport.*
  private val F = Async[F]
  private def error(message: String) = new IllegalStateException(message)
  private def checked[A](e: Either[String, A]): F[A] = F.fromEither(e.leftMap(error))
  private def interrupted[A](fa: F[A]): F[A] =
    F.race(stop.get.flatMap(F.raiseError[A]), fa).map(_.merge)
  private def ensure: F[Unit] = stop.tryGet.flatMap(_.traverse_(F.raiseError[Unit]))
  private[network] def metrics: F[Metrics] =
    state.get.map(s => Metrics(s.retained, s.peak, s.wire, s.frames, s.outBytes, s.outFrames))
  private def notifyReaders: F[Unit] = changed.tryOffer(()).void *> kaChanged.tryOffer(()).void
  private def fail(e: Throwable): F[Unit] = state
    .modify { s =>
      if s.closed then (s, None)
      else
        val primary = s.failure.getOrElse(e)
        (s.copy(failure = Some(primary)), Some(primary))
    }
    .flatMap(_.traverse_(primary => stop.complete(primary).void *> notifyReaders *> close))
  private def releaseBearer: F[Unit] = F.uncancelable { _ =>
    state.modify(s => (s.copy(physicalCloseStarted = true), !s.physicalCloseStarted)).flatMap {
      won => if won then raw.close else F.unit
    }
  }
  def close: F[Unit] = F.uncancelable { _ =>
    state.modify(s => (s.copy(closed = true), !s.closed)).flatMap { won =>
      if won then stop.complete(error("connection closed")).void *> notifyReaders *> releaseBearer
      else F.unit
    }
  }
  private def guarded(fa: F[Unit]): F[Unit] = fa.handleErrorWith(e => fail(e).attempt.void)

  private[network] def admit(chunk: Bytes): F[Unit] = state
    .modify { s =>
      def reject(reason: String) =
        val failure = s.failure.getOrElse(error(reason))
        (
          if s.closed then s else s.copy(failure = Some(failure), wire = s.wire + chunk.size),
          Left(failure)
        )
      if s.closed then reject("connection closed")
      else if chunk.size <= 0 || chunk.size > config.maxChunkBytes then
        reject("invalid transport chunk size")
      else if chunk.size.toLong > config.maxWireBytes - s.wire then
        reject("wire byte budget exceeded")
      else if s.retained + chunk.size > policy.routedIngressBytes(config) then
        reject("routed ingress byte budget exceeded")
      else
        s.decoder.feed(chunk) match
          case Left(reason) => reject(reason)
          case Right((decoder, frames)) =>
            val bf = frames.filter(f => f.protocol == 0 || f.protocol == 3)
            val ka = frames.filter(_.protocol == 8)
            val invalid = frames.exists(f =>
              f.direction != Mux.Direction.Responder ||
                !(f.protocol == 3 || f.protocol == 8 || (f.protocol == 0 && !s.active))
            )
            val bfBytes = s.bfBytes + bf.map(f => f.payload.size + 8).sum
            val kaBytes = s.kaBytes + ka.map(_.payload.size).sum
            if invalid then reject("unexpected mux protocol or direction")
            else if ka.nonEmpty && (!s.active || !s.expecting) then
              reject("unsolicited KeepAlive response")
            else if frames.size.toLong > config.maxTotalFrames - s.frames then
              reject("total frame limit exceeded")
            else if bfBytes > policy.bfQueueBytes(config) || s.bf.size + bf.size > config.maxFrames
            then reject("BlockFetch ingress queue full")
            else if kaBytes > policy.keepAliveQueueBytes || s.ka.size + ka.size > policy.keepAliveQueueFrames
            then reject("KeepAlive ingress queue full")
            else
              val next = s.copy(
                decoder = decoder,
                bf = s.bf ++ bf,
                ka = s.ka ++ ka,
                bfBytes = bfBytes,
                kaBytes = kaBytes,
                wire = s.wire + chunk.size,
                frames = s.frames + frames.size
              )
              (next.copy(peak = math.max(s.peak, next.retained)), Right(()))
    }
    .flatMap(F.fromEither) *> notifyReaders

  private[network] def reader: F[Unit] = guarded {
    def delivered(result: Outcome[F, Throwable, Option[Bytes]], closing: Boolean): F[Boolean] =
      result match
        case Outcome.Succeeded(value) =>
          value.flatMap {
            case Some(bytes) => readHandoff *> admit(bytes).as(!closing)
            case None =>
              state
                .modify { s =>
                  val result = s.decoder.finish
                  val failure = result.left.toOption.map(error)
                  (s.copy(eof = true, failure = s.failure.orElse(failure)), result)
                }
                .flatMap(checked) *> notifyReaders.as(false)
          }
        case Outcome.Errored(_: java.nio.channels.AsynchronousCloseException) if closing =>
          F.pure(false)
        case Outcome.Errored(error) => F.raiseError(error)
        case Outcome.Canceled() => F.raiseError(error("physical read canceled before completion"))
    // Stop does not cancel a successful raw-read handoff. Closing the bearer explicitly keeps
    // close failures in the acquisition result, rather than a canceled read's finalizer report.
    def step: F[Boolean] = F.uncancelable { poll =>
      poll(F.racePair(readerStop.get, raw.read)).flatMap {
        case Left((_, reading)) =>
          (releaseBearer *> poll(reading.join).flatMap(delivered(_, true)))
            .guarantee(reading.cancel)
        case Right((waiting, result)) =>
          (waiting.cancel *> delivered(result, false)).guarantee(waiting.cancel)
      }
    }
    def loop: F[Unit] = step.flatMap(again => if again then loop else F.unit)
    loop
  }.guarantee(readerFinished.complete(()).void)

  private def take(protocol: Int): F[Option[Bytes]] =
    def loop: F[Option[Bytes]] = ensure *> state
      .modify { s =>
        if protocol == 8 then
          s.ka.headOption match
            case Some(frame) =>
              (
                s.copy(ka = s.ka.tail, kaBytes = s.kaBytes - frame.payload.size),
                Some(Some(frame.payload))
              )
            case None => (s, if s.eof then Some(None) else None)
        else if s.virtualPending.size > 0 then
          val (chunk, rest) = s.virtualPending.value.splitAt(config.maxChunkBytes)
          (s.copy(virtualPending = Bytes(rest)), Some(Some(Bytes(chunk))))
        else
          s.bf.headOption match
            case Some(frame) =>
              // Already validated by the physical decoder; preserve the exact SDU fields.
              val bytes = Mux.encode(frame).toOption.get
              val (chunk, rest) = bytes.value.splitAt(config.maxChunkBytes)
              (
                s.copy(
                  bf = s.bf.tail,
                  bfBytes = s.bfBytes - bytes.size,
                  virtualPending = Bytes(rest)
                ),
                Some(Some(Bytes(chunk)))
              )
            case None => (s, if s.eof then Some(None) else None)
      }
      .flatMap {
        case Some(result) => F.pure(result)
        case None => interrupted((if protocol == 8 then kaChanged else changed).take) *> loop
      }
    loop

  private def submit(frame: Mux.Sdu): F[Unit] =
    for
      _ <- ensure
      bytes <- checked(Mux.encode(frame))
      ack <- Deferred[F, Unit]
      queue = if frame.protocol == 8 then kaOut else bfOut
      ok <- queue.tryOffer(Write(bytes, ack))
      _ <- F.raiseUnless(ok)(error("outbound mailbox full"))
      _ <- writerChanged.tryOffer(()).void
      _ <- interrupted(ack.get)
    yield ()

  private[network] def writer: F[Unit] = guarded {
    def next(preferKa: Boolean): F[Option[(Write[F], Boolean)]] =
      val first = if preferKa then kaOut else bfOut
      val second = if preferKa then bfOut else kaOut
      first.tryTake.flatMap {
        case Some(write) => F.pure(Some(write -> !preferKa))
        case None        => second.tryTake.map(_.map(_ -> preferKa))
      }
    def loop(preferKa: Boolean): F[Unit] = next(preferKa).flatMap {
      case None => writerChanged.take *> loop(preferKa)
      case Some((write, preference)) =>
        state
          .modify { s =>
            if s.closed || s.failure.nonEmpty then (s, Left(error("connection closed")))
            else if write.bytes.size.toLong > policy.maxOutgoingBytes - s.outBytes then
              (s, Left(new KeepAliveBlockFetchSession.OutgoingBudgetExceeded("outgoingWireBudget")))
            else if s.outFrames >= policy.maxOutgoingFrames then
              (
                s,
                Left(new KeepAliveBlockFetchSession.OutgoingBudgetExceeded("outgoingFrameBudget"))
              )
            else
              (
                s.copy(
                  outBytes = s.outBytes + write.bytes.size,
                  outFrames = s.outFrames + 1,
                  writing = true
                ),
                Right(())
              )
          }
          .flatMap(F.fromEither) *>
          write.bytes.value
            .grouped(config.maxChunkBytes)
            .toVector
            .traverse_(part => interrupted(raw.write(Bytes(part)))) *>
          state.update(_.copy(writing = false)) *> write.ack.complete(()).void *> loop(preference)
    }
    loop(true)
  }

  val blockFetch: ByteTransport[F] = new ByteTransport[F]:
    def read = take(3)
    def close = KeepAliveTransport.this.close
    def isClosed = state.get.map(_.closed)
    def write(bytes: Bytes): F[Unit] = bfWriteGate.permit.use { _ =>
      (ensure *> outbound
        .modify { decoder =>
          decoder.feed(bytes) match
            case Left(reason)          => (decoder, Left(reason))
            case Right((next, frames)) => (next, Right(frames))
        }
        .flatMap(checked)
        .flatMap(_.traverse_ { frame =>
          if frame.direction != Mux.Direction.Initiator || !(frame.protocol == 0 || frame.protocol == 3)
          then F.raiseError(error("invalid outgoing virtual route"))
          else submit(frame)
        })).onCancel(close).onError { case e => fail(e).attempt.void }
    }

  def activate: F[Unit] =
    ensure *> state.update(_.copy(active = true)) *> start.complete(()).void *> interrupted(
      firstPing.get
    )
  private def sendKa(message: KeepAlive.Message): F[Unit] =
    checked(KeepAlive.encode(KeepAlive.State.Client, KeepAlive.Role.Client, message))
      .flatMap(bytes => submit(Mux.Sdu(0L, 8, Mux.Direction.Initiator, bytes)))

  private def reply(expected: KeepAlive.Cookie): F[Unit] =
    def loop(pending: Bytes): F[Unit] =
      KeepAlive.decodePrefix(KeepAlive.State.Server, KeepAlive.Role.Server, pending) match
        case ChainSync.DecodeResult.Failed(reason) => F.raiseError(error(reason))
        case ChainSync.DecodeResult.Decoded(message, consumed) =>
          checked(KeepAlive.matchResponse(Some(expected), message)) *>
            F.raiseWhen(consumed != pending.size)(error("KeepAlive response suffix")) *>
            F.monotonic.flatMap { now =>
              state
                .modify { s =>
                  if s.closed || s.failure.nonEmpty then
                    (s, Left(s.failure.getOrElse(error("connection closed"))))
                  else if !s.expecting || !s.replyExpires.exists(now < _) then
                    (s, Left(error("KeepAlive response deadline expired")))
                  else if s.ka.nonEmpty then (s, Left(error("duplicate KeepAlive response")))
                  else (s.copy(expecting = false, replyExpires = None), Right(()))
                }
                .flatMap(F.fromEither)
            }
        case ChainSync.DecodeResult.NeedMore =>
          take(8).flatMap {
            case None => F.raiseError(error("EOF awaiting KeepAlive response"))
            case Some(bytes) =>
              if pending.size + bytes.size > policy.keepAliveQueueBytes then
                F.raiseError(error("KeepAlive pending byte budget exceeded"))
              else loop(Bytes(pending.value ++ bytes.value))
          }
    for
      now <- F.monotonic
      end <- state.get.flatMap(s =>
        F.fromOption(s.replyExpires, error("missing KeepAlive reply deadline"))
      )
      _ <-
        if now >= end then F.raiseError[Unit](error("KeepAlive response deadline expired"))
        else
          F.timeoutTo(
            loop(Bytes.empty),
            end - now,
            F.raiseError(error("KeepAlive response deadline expired"))
          )
    yield ()

  private[network] def keepAlive: F[Unit] = guarded {
    def done: F[Unit] = sendKa(KeepAlive.Message.Done) *> kaDone.complete(()).void
    def loop: F[Unit] = finishSignal.tryGet.flatMap {
      case Some(_) => done
      case None =>
        for
          value <- cookie
          now <- F.monotonic
          reserved <- state.modify(s =>
            if s.finishing then (s, false)
            else (s.copy(expecting = true, replyExpires = Some(now + policy.response)), true)
          )
          _ <-
            if !reserved then done
            else
              sendKa(KeepAlive.Message.Request(value)) *> firstPing.complete(()).void *> reply(
                value
              ) *>
                F.race(finishSignal.get, F.sleep(policy.interval))
                  .flatMap(_.fold(_ => done, _ => loop))
        yield ()
    }
    start.get *> loop
  }

  def freeze: F[Unit] = F.monotonic.flatMap { now =>
    ensure *> state.update(s =>
      s.copy(finishing = true, finishAt = s.finishAt.orElse(Some(now + policy.finish)))
    ) *>
      finishSignal.complete(()).void
  }
  def finishBounded[A](work: F[A]): F[A] = for
    now <- F.monotonic
    end <- state.get.map(_.finishAt.getOrElse(now + policy.finish))
    result <-
      if now >= end then F.raiseError[A](error("graceful finish deadline expired"))
      else
        F.timeoutTo(
          work,
          end - now,
          close.attempt.void *> F.raiseError[A](error("graceful finish deadline expired"))
        )
  yield result
  def finishKeepAlive: F[Unit] = F.timeoutTo(
    interrupted(kaDone.get),
    policy.finish,
    F.raiseError(error("KeepAlive graceful finish deadline expired"))
  )
  def requireBlockFetchDrained: F[Unit] = ensure *> state.get.flatMap { s =>
    F.raiseWhen(s.bf.nonEmpty || s.virtualPending.size != 0)(
      error("unsolicited buffered BlockFetch bytes after terminal batch")
    )
  }
  def barrier: F[Unit] =
    ensure *> readerStop.complete(()).void *> interrupted(readerFinished.get) *> ensure *> state.get
      .flatMap { s =>
        F.raiseWhen(s.retained != 0 || s.expecting || s.writing)(
          error("application suffix or pending work at final barrier")
        )
      } *> outbound.get.flatMap(d => checked(d.finish)) *> bfOut.size.flatMap(n =>
      F.raiseWhen(n != 0)(error("pending BlockFetch write"))
    ) *>
      kaOut.size.flatMap(n => F.raiseWhen(n != 0)(error("pending KeepAlive write"))) *>
      F.monotonic.flatMap { now =>
        state
          .modify { s =>
            s.failure match
              case Some(primary) => (s, Left(primary))
              case None if s.finishAt.exists(now >= _) =>
                (s, Left(error("graceful finish deadline expired")))
              case None if s.retained != 0 || s.expecting || s.writing || s.closed =>
                (s, Left(error("final owner barrier changed")))
              case None => (s.copy(closed = true), Right(()))
          }
          .flatMap(F.fromEither)
      } *> releaseBearer

object KeepAliveTransport:
  private[network] final case class Metrics(
      routedIngressBytes: Int,
      peakRoutedIngressBytes: Int,
      incomingWireBytes: Long,
      incomingFrames: Long,
      outgoingWireBytes: Long,
      outgoingFrames: Long
  )

  /** Every value is part of the explicit v2 source identity; these are logical-byte bounds. The
    * unchanged BF engine has its own disjoint message+chunk reservation. A physical decoder retains
    * at most one SDU; a feed stages at most one raw chunk before atomic route admission.
    */
  final case class Policy(
      interval: FiniteDuration = 10.seconds,
      response: FiniteDuration = 10.seconds,
      finish: FiniteDuration = 5.seconds,
      keepAliveQueueBytes: Int = 1408,
      keepAliveQueueFrames: Int = 16,
      maxOutgoingBytes: Long = 16384,
      maxOutgoingFrames: Long = 256
  ):
    def bfQueueBytes(config: BlockFetchSession.Config): Int =
      config.maxRawBlockBytes + 9 + config.maxChunkBytes
    def virtualRemainderBytes(config: BlockFetchSession.Config): Int =
      math.max(0, 65543 - config.maxChunkBytes)
    def routedIngressBytes(config: BlockFetchSession.Config): Int =
      bfQueueBytes(
        config
      ) + keepAliveQueueBytes + 65543 + config.maxChunkBytes + virtualRemainderBytes(config)
    def globalIngressBytes(config: BlockFetchSession.Config): Int =
      routedIngressBytes(config) + bfQueueBytes(config) + 2 * keepAliveQueueBytes
    def valid: Boolean =
      interval > Duration.Zero && interval <= 10.seconds && response > Duration.Zero && response <= 10.seconds && finish > Duration.Zero && finish <= 5.seconds &&
        keepAliveQueueBytes > 0 && keepAliveQueueBytes <= 1408 && keepAliveQueueFrames > 0 && keepAliveQueueFrames <= 16 && maxOutgoingBytes > 0 && maxOutgoingBytes <= 16384 && maxOutgoingFrames > 0 && maxOutgoingFrames <= 256
  private final case class Write[F[_]](bytes: Bytes, ack: Deferred[F, Unit])
  private final case class State(
      decoder: Mux.Decoder,
      bf: Vector[Mux.Sdu] = Vector.empty,
      ka: Vector[Mux.Sdu] = Vector.empty,
      bfBytes: Int = 0,
      kaBytes: Int = 0,
      virtualPending: Bytes = Bytes.empty,
      active: Boolean = false,
      expecting: Boolean = false,
      replyExpires: Option[FiniteDuration] = None,
      closed: Boolean = false,
      physicalCloseStarted: Boolean = false,
      finishing: Boolean = false,
      finishAt: Option[FiniteDuration] = None,
      failure: Option[Throwable] = None,
      eof: Boolean = false,
      wire: Long = 0,
      frames: Long = 0,
      outBytes: Long = 0,
      outFrames: Long = 0,
      writing: Boolean = false,
      peak: Int = 0
  ):
    def retained: Int = decoder.pendingBytes + bfBytes + kaBytes + virtualPending.size

  private[network] def resource[F[_]: Async](
      raw: ByteTransport[F],
      config: BlockFetchSession.Config,
      policy: Policy,
      cookie: F[KeepAlive.Cookie],
      readHandoff: Option[F[Unit]] = None
  ): Resource[F, KeepAliveTransport[F]] =
    val F = Async[F]
    Resource
      .make {
        for
          _ <- F.raiseUnless(policy.valid)(
            new IllegalArgumentException("invalid KeepAlive profile limits")
          )
          decoder <- F.fromEither(
            Mux.Decoder
              .create(Mux.Limits(65535, config.maxChunkBytes, config.maxFrames))
              .leftMap(new IllegalArgumentException(_))
          )
          ref <- Ref.of[F, State](State(decoder))
          stop <- Deferred[F, Throwable]
          changed <- Queue.bounded[F, Unit](1)
          kaChanged <- Queue.bounded[F, Unit](1)
          writerChanged <- Queue.bounded[F, Unit](1)
          bfOut <- Queue.bounded[F, Write[F]](1)
          kaOut <- Queue.bounded[F, Write[F]](1)
          gate <- Semaphore[F](1)
          outbound <- Ref.of[F, Mux.Decoder](decoder)
          start <- Deferred[F, Unit]
          first <- Deferred[F, Unit]
          finish <- Deferred[F, Unit]
          done <- Deferred[F, Unit]
          readerStop <- Deferred[F, Unit]
          readerFinished <- Deferred[F, Unit]
        yield new KeepAliveTransport(
          raw,
          config,
          policy,
          ref,
          stop,
          changed,
          kaChanged,
          writerChanged,
          bfOut,
          kaOut,
          gate,
          outbound,
          start,
          first,
          finish,
          done,
          cookie,
          readerStop,
          readerFinished,
          readHandoff.getOrElse(F.unit)
        )
      }(_.close)
      .flatMap(t => t.reader.background *> t.writer.background *> t.keepAlive.background.as(t))
