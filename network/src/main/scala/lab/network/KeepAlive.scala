// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import ChainSync.DecodeResult
import ProtocolWire.{attempt, bad, get}
import scala.concurrent.duration.*

/** Pure KeepAlive codec v2 and agency. Pinned source provenance is in
  * docs/keepalive-direct-range.md. Cookie correlation is separate from decoding; no transport or
  * timer is implemented here.
  */
object KeepAlive:
  val MiniProtocolId: Int = 8
  val CodecVersion: Int = 2
  val UpstreamMessageBytes: Int = 65535

  final class Cookie private (val value: Int):
    override def equals(other: Any): Boolean = other match
      case cookie: Cookie => value == cookie.value
      case _              => false
    override def hashCode: Int = value.hashCode
    override def toString: String = value.toString
  object Cookie:
    val Max: Int = 65535
    val Zero: Cookie = new Cookie(0)
    def from(value: Int): Either[String, Cookie] =
      if value < 0 || value > Max then Left("KeepAlive cookie outside uint16")
      else Right(new Cookie(value))

  enum State:
    case Client, Server, Done
  enum Role:
    case Client, Server
  enum Message:
    case Request(cookie: Cookie)
    case Response(cookie: Cookie)
    case Done

  /** Upstream message ceiling; smaller values and structural bounds are local parser policy. This
    * is not the mux queue bound or an acquisition-wide budget.
    */
  final case class Limits(
      maxMessageBytes: Int = UpstreamMessageBytes,
      maxDepth: Int = 24,
      maxItems: Int = 8192
  ):
    def valid: Boolean = maxMessageBytes > 0 && maxMessageBytes <= UpstreamMessageBytes &&
      maxDepth >= 0 && maxDepth <= 64 && maxItems > 0 && maxItems <= 65535
    private[network] def wire: ProtocolWire.Limits =
      if !valid then bad("invalid KeepAlive limits")
      ProtocolWire.Limits(maxMessageBytes, 0, 0, maxDepth, maxItems, 0)

  /** Pinned protocol-driver metadata only, not a scheduling interval or local timeout policy. */
  object UpstreamTimeLimits:
    val client: FiniteDuration = 97.seconds
    val server: FiniteDuration = 60.seconds

  def direction(sender: Role): Mux.Direction = sender match
    case Role.Client => Mux.Direction.Initiator
    case Role.Server => Mux.Direction.Responder

  def transition(state: State, sender: Role, message: Message): Either[String, State] =
    (state, sender, message) match
      case (State.Client, Role.Client, Message.Request(_))  => Right(State.Server)
      case (State.Server, Role.Server, Message.Response(_)) => Right(State.Client)
      case (State.Client, Role.Client, Message.Done)        => Right(State.Done)
      case _ => Left(s"illegal KeepAlive message/agency in $state from $sender")

  /** Runtime must retain one outstanding cookie and clear it only after this check succeeds. A
    * duplicate response is unsolicited once the caller has cleared that outstanding cookie.
    */
  def matchResponse(expected: Option[Cookie], message: Message): Either[String, Unit] =
    (expected, message) match
      case (Some(wanted), Message.Response(actual)) =>
        if wanted == actual then Right(()) else Left("KeepAlive cookie mismatch")
      case (None, Message.Response(_)) => Left("unsolicited KeepAlive response")
      case _                           => Left("expected KeepAlive response")

  def encode(
      state: State,
      sender: Role,
      message: Message,
      limits: Limits = Limits()
  ): Either[String, Bytes] = attempt {
    get(transition(state, sender, message))
    val bounds = limits.wire
    val w = new ProtocolWire.Writer(bounds)
    message match
      case Message.Request(cookie)  => w.head(4, 2); w.head(0, 0); w.head(0, cookie.value)
      case Message.Response(cookie) => w.head(4, 2); w.head(0, 1); w.head(0, cookie.value)
      case Message.Done             => w.head(4, 1); w.head(0, 2)
    val result = w.result
    get(ProtocolWire.validateItem(result, bounds))
    result
  }

  /** One immutable input prefix, no internal accumulation. Caller must bound retained ingress.
    * Accepts definite, non-shortest unsigned numeric forms, as the pinned noncanonical decoder
    * does. UInt16 is checked before narrowing. Surplus bytes are left to the caller.
    */
  def decodePrefix(
      state: State,
      sender: Role,
      bytes: Bytes,
      limits: Limits = Limits()
  ): DecodeResult[Message] =
    try
      val bounds = limits.wire
      if state == State.Done then bad("KeepAlive is terminal")
      if (state == State.Client) != (sender == Role.Client) then bad("wrong KeepAlive agency")
      val r = new ProtocolWire.Reader(bytes, bounds)
      val arity = r.arrayLength()
      if (state == State.Client && arity != 1 && arity != 2) ||
        (state == State.Server && arity != 2)
      then bad("wrong KeepAlive outer arity for state")
      val tag = r.uint().value
      val request = state == State.Client && arity == 2 && tag == 0
      val response = state == State.Server && arity == 2 && tag == 1
      val done = state == State.Client && arity == 1 && tag == 2
      if !request && !response && !done then bad("unknown KeepAlive tag, state or exact arity")
      val message =
        if done then Message.Done
        else
          val value = r.uint().value
          if value > Cookie.Max then bad("KeepAlive cookie outside uint16")
          val cookie = get(Cookie.from(value.toInt))
          if request then Message.Request(cookie) else Message.Response(cookie)
      get(transition(state, sender, message))
      get(ProtocolWire.validateItem(Bytes(bytes.value.take(r.position)), bounds))
      DecodeResult.Decoded(message, r.position)
    catch
      case ProtocolWire.Failure(_, true)      => DecodeResult.NeedMore
      case ProtocolWire.Failure(error, false) => DecodeResult.Failed(error)

  def decode(
      state: State,
      sender: Role,
      bytes: Bytes,
      limits: Limits = Limits()
  ): Either[String, Message] =
    decodePrefix(state, sender, bytes, limits) match
      case DecodeResult.NeedMore      => Left("truncated KeepAlive message")
      case DecodeResult.Failed(error) => Left(error)
      case DecodeResult.Decoded(message, consumed) =>
        if consumed == bytes.size then Right(message) else Left("trailing KeepAlive bytes")
