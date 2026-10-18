// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import scala.concurrent.duration.*

import ChainSync.DecodeResult
import ProtocolWire.{attempt, bad, get}

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
    def from(value: Int): Either[ProtocolError, Cookie] =
      if value < 0 || value > Max then Left(ProtocolError.KeepAliveCookieOutsideUint16(BigInt(value)))
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
    private[protocol] def wire(using ProtocolWire.Scope): ProtocolWire.Limits =
      if !valid then bad(ProtocolError.InvalidKeepAliveLimits)
      ProtocolWire.Limits(maxMessageBytes, 0, 0, maxDepth, maxItems, 0)

  /** Reference state deadlines used by the runtime; the keep-alive interval is separate policy. */
  object UpstreamTimeLimits:
    val client: FiniteDuration = 97.seconds
    val server: FiniteDuration = 60.seconds

  def direction(sender: Role): Mux.Direction = sender match
    case Role.Client => Mux.Direction.Initiator
    case Role.Server => Mux.Direction.Responder

  def transition(state: State, sender: Role, message: Message): Either[ProtocolError, State] =
    (state, sender, message) match
      case (State.Client, Role.Client, Message.Request(_))  => Right(State.Server)
      case (State.Server, Role.Server, Message.Response(_)) => Right(State.Client)
      case (State.Client, Role.Client, Message.Done)        => Right(State.Done)
      case _ => Left(ProtocolError.KeepAliveAgency(state, sender))

  /** Runtime must retain one outstanding cookie and clear it only after this check succeeds. A
    * duplicate response is unsolicited once the caller has cleared that outstanding cookie.
    */
  def matchResponse(expected: Option[Cookie], message: Message): Either[ProtocolError, Unit] =
    (expected, message) match
      case (Some(wanted), Message.Response(actual)) =>
        if wanted == actual then Right(()) else Left(ProtocolError.KeepAliveCookieMismatch(wanted, actual))
      case (None, Message.Response(_)) => Left(ProtocolError.UnsolicitedKeepAliveResponse)
      case _                           => Left(ProtocolError.ExpectedKeepAliveResponse)

  def encode(
      state: State,
      sender: Role,
      message: Message,
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] = attempt {
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
    ProtocolWire.prefix {
      val bounds = limits.wire
      if state == State.Done then bad(ProtocolError.KeepAliveIsTerminal)
      if (state == State.Client) != (sender == Role.Client) then bad(ProtocolError.WrongKeepAliveAgency)
      val r = new ProtocolWire.Reader(bytes, bounds)
      val arity = r.arrayLength()
      if (state == State.Client && arity != 1 && arity != 2) ||
        (state == State.Server && arity != 2)
      then bad(ProtocolError.WrongKeepAliveOuterArityForState)
      val tag = r.uint().value
      val request = state == State.Client && arity == 2 && tag == 0
      val response = state == State.Server && arity == 2 && tag == 1
      val done = state == State.Client && arity == 1 && tag == 2
      if !request && !response && !done then bad(ProtocolError.UnknownKeepAliveTagStateOrExactArity)
      val message =
        if done then Message.Done
        else
          val value = r.uint().value
          if value > Cookie.Max then bad(ProtocolError.KeepAliveCookieOutsideUint16(value))
          val cookie = get(Cookie.from(value.toInt))
          if request then Message.Request(cookie) else Message.Response(cookie)
      get(transition(state, sender, message))
      get(ProtocolWire.validateItem(bytes.take(r.position), bounds))
      DecodeResult.Decoded(message, r.position)
    }

  def decode(
      state: State,
      sender: Role,
      bytes: Bytes,
      limits: Limits = Limits()
  ): Either[ProtocolError, Message] =
    decodePrefix(state, sender, bytes, limits) match
      case DecodeResult.NeedMore      => Left(ProtocolError.TruncatedKeepAliveMessage)
      case DecodeResult.Failed(error) => Left(error)
      case DecodeResult.Decoded(message, consumed) =>
        if consumed == bytes.size then Right(message) else Left(ProtocolError.TrailingKeepAliveBytes)
