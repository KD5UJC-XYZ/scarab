// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ProtocolError

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult
import xyz.kd5ujc.scarab.protocol.{EbPoint, LeiosFetch, LeiosNotify, LeiosWireError}
import scala.concurrent.duration.*

/** A decoded server message with the exact bytes it arrived as. */
final case class Observed[+A](message: A, raw: Bytes)

private object LeiosDecode:
  def result[A](
      decoded: Either[LeiosWireError, (A, Int)],
      bytes: Bytes
  ): DecodeResult[Observed[A]] =
    decoded match
      case Right((message, consumed)) =>
        DecodeResult.Decoded(Observed(message, bytes.take(consumed).compact), consumed)
      case Left(LeiosWireError.Incomplete) => DecodeResult.NeedMore
      case Left(error)                     => DecodeResult.Failed(ProtocolError.Leios(error))

/** LeiosNotify client (mini-protocol 18): pull one notification at a time. Single fiber. */
trait LeiosNotifyClient[F[_]]:
  /** Send `MsgLeiosNotificationRequestNext` and wait for the server's reply. */
  def requestNext: F[Observed[LeiosNotify.Message]]

  /** Leave: send `MsgQuit` and wait for `MsgDone` (replies already in flight are skipped). */
  def quit: F[Unit]

object LeiosNotifyClient:
  /** The server may hold a request for as long as it has nothing to announce. */
  final case class Settings(
      busyTimeout: FiniteDuration = 10.minutes,
      maxMessageBytes: Int = 2500000
  )

  def apply[F[_]: Temporal](channel: MuxChannel[F], settings: Settings = Settings())(using
      Raise[F, NetworkError]
  ): LeiosNotifyClient[F] =
    val id = Protocols.LeiosNotify
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(m: LeiosNotify.Message): F[Unit] =
      LeiosNotify.encode(m).fold(e => illegal(ProtocolError.Leios(e)), channel.send)
    def receive(state: LeiosNotify.State): F[Observed[LeiosNotify.Message]] =
      Timed.within(settings.busyTimeout, id, state.toString)(
        channel.recv(settings.maxMessageBytes)(bytes =>
          LeiosDecode.result(LeiosNotify.decodePrefix(state, bytes), bytes)
        )
      )
    new LeiosNotifyClient[F]:
      def requestNext: F[Observed[LeiosNotify.Message]] =
        send(LeiosNotify.Message.RequestNext) *> receive(LeiosNotify.State.Busy)
      def quit: F[Unit] =
        send(LeiosNotify.Message.Quit) *> receive(LeiosNotify.State.Quit).void

/** LeiosFetch client (mini-protocol 19). Single fiber. */
trait LeiosFetchClient[F[_]]:
  def block(point: EbPoint): F[Observed[LeiosFetch.Message]]
  def done: F[Unit]

object LeiosFetchClient:
  final case class Settings(timeout: FiniteDuration = 60.seconds, maxMessageBytes: Int = 2500000)

  def apply[F[_]: Temporal](channel: MuxChannel[F], settings: Settings = Settings())(using
      Raise[F, NetworkError]
  ): LeiosFetchClient[F] =
    val id = Protocols.LeiosFetch
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(m: LeiosFetch.Message): F[Unit] =
      LeiosFetch.encode(m).fold(e => illegal(ProtocolError.Leios(e)), channel.send)
    new LeiosFetchClient[F]:
      def block(point: EbPoint): F[Observed[LeiosFetch.Message]] =
        send(LeiosFetch.Message.BlockRequest(point)) *>
          Timed.within(settings.timeout, id, "Block")(
            channel.recv(settings.maxMessageBytes)(bytes =>
              LeiosDecode.result(LeiosFetch.decodePrefix(LeiosFetch.State.Block, bytes), bytes)
            )
          )
      def done: F[Unit] = send(LeiosFetch.Message.Done)
