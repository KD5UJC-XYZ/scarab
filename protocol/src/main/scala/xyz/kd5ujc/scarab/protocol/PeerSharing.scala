// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import scala.annotation.tailrec
import scala.concurrent.duration.*

import ChainSync.DecodeResult

/** The PeerSharing mini-protocol (node-to-node, number 10, from version 14): the pure codec, the
  * agency and the limits. No transport and no timer is implemented here.
  *
  * Reference (ouroboros-network, `2fb69829`, the commit of the vendored CDDL, models/.../SOURCES;
  * the tag `ouroboros-network-1.2.0.0` of docs/design/peer-selection.md section 1.1 has the same
  * files):
  *   - states and messages:
  *     `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/PeerSharing/Type.hs` lines 29
  *     (amount, a Word8), 38-40 (result), 45-58 (`StIdle` client agency, `StBusy` server agency,
  *     `StDone`), 74-95 (`MsgShareRequest`, `MsgSharePeers`, `MsgDone`; "it is a protocol error to
  *     respond with more peers than requested", 80-82);
  *   - codec: `.../PeerSharing/Codec.hs` 42-93 (`[0, amount]`, `[1, [* address]]`, `[2]`; a list of
  *     peers is written indefinite unless it is empty, 81-84; the decoder takes a definite or an
  *     indefinite list, 89-93), 127-128 (5760 bytes), 133-140 (the size limit of `StIdle` and
  *     `StBusy`), 153-162 (`StIdle` waits forever, `StBusy` `longWait`), with `longWait = 60 s` at
  *     `ouroboros-network/api/lib/Ouroboros/Network/Protocol/Limits.hs:114-115`;
  *   - addresses: `ouroboros-network/api/lib/Ouroboros/Network/PeerSelection/PeerSharing/Codec.hs`
  *     29-42 (encode: `[0, word32, port]` for IPv4, `[1, word32 x 4, port]` for IPv6) and 49-65
  *     (decode; the port is a Word16, 15-19);
  *   - the CDDL: `cardano-diffusion/protocols/cddl/specs/peer-sharing-v14.cddl`.
  *
  * Where this decoder is stricter than the reference: the reference reads the length of the outer
  * array and of an address and ignores it (`decodeListLen`), so a message whose declared length is
  * wrong is read as if it were right. Here the arity must match the message (2, 2 or 1, and 3 or 6
  * for an address), as the CDDL says. Everything the reference accepts and the CDDL allows is
  * accepted: unsigned integers in any definite width, definite and indefinite lists of peers.
  */
object PeerSharing:
  val MiniProtocolId: Int = 10

  /** 4 * 1440 (`Codec.hs:127-128`): a TCP segment of 1440 bytes and an initial window of 4, so that
    * the request and the response take one round trip. The limit applies to each state.
    */
  val UpstreamMessageBytes: Int = 4 * 1440

  /** Pinned protocol-driver metadata only, not a scheduling interval. */
  object UpstreamTimeLimits:
    /** In `StBusy` the server has this long to answer (`longWait`). In `StIdle` the client may wait
      * forever.
      */
    val busy: FiniteDuration = 60.seconds

  // --- values -----------------------------------------------------------------------------------

  /** The scalar types of the wire. They are opaque in this object only, so that the rest of
    * [[PeerSharing]] sees them as the extension methods they offer and no more.
    */
  object Values:
    /** How many peers a request asks for: the Word8 of `PeerSharingAmount`. */
    opaque type Amount = Int
    object Amount:
      val Max: Int = 255
      val Zero: Amount = 0
      def from(value: Int): Option[Amount] = Option.when(value >= 0 && value <= Max)(value)
      private[protocol] def trusted(value: Long): Amount = value.toInt

      /** The amount of a count, saturating at 255. */
      def saturating(value: Int): Amount = math.max(0, math.min(value, Max))
      given Ordering[Amount] = Ordering.Int
      extension (amount: Amount) def value: Int = amount

    /** An unsigned 32-bit word: a quarter of an IPv6 address, or an IPv4 address. */
    opaque type Word32 = Long
    object Word32:
      val Max: Long = 0xffffffffL
      def from(value: Long): Option[Word32] = Option.when(value >= 0 && value <= Max)(value)
      private[protocol] def trusted(value: Long): Word32 = value

      /** The word with the bits of an `Int`. */
      def ofBits(bits: Int): Word32 = bits.toLong & Max
      extension (word: Word32) def value: Long = word

    /** A TCP port: the Word16 of the CDDL `portNumber`. */
    opaque type Port = Int
    object Port:
      val Max: Int = 65535
      def from(value: Int): Option[Port] = Option.when(value >= 0 && value <= Max)(value)
      private[protocol] def trusted(value: Long): Port = value.toInt
      extension (port: Port) def value: Int = port

  export Values.{Amount, Port, Word32}

  /** A peer address as it travels: IPv4 or IPv6 and a port (`peerAddress` of the CDDL). The words
    * are the wire's, not an interpretation of them: the reference writes an IPv4 `HostAddress` (a
    * `Word32` that holds the four octets in memory order, so 127.0.0.1 is `0x0100007f` on a
    * little-endian host) as it is, and an IPv6 address as four such words in their order (`::1` is
    * `0, 0, 0, 1`). Observed against the reference node on the devnet: a peer at 127.0.0.1 arrives
    * as the word `0x0100007f`. Converting to and from a socket address (and the byte swap of an
    * IPv4 word) belongs to the code that has one, not to the codec.
    */
  enum Address:
    case V4(address: Word32, port: Port)
    case V6(a: Word32, b: Word32, c: Word32, d: Word32, port: Port)

    /** The address bytes in network order and then the port: 6 or 18 bytes. */
    def toBytes: Bytes =
      def word(w: Word32): Vector[Byte] =
        (3 to 0 by -1).toVector.map(i => ((w.value >> (i * 8)) & 255).toByte)
      def portBytes(p: Port): Vector[Byte] = Vector((p.value >> 8).toByte, p.value.toByte)
      this match
        case V4(x, p)          => Bytes(word(x) ++ portBytes(p))
        case V6(x, y, z, t, p) => Bytes(word(x) ++ word(y) ++ word(z) ++ word(t) ++ portBytes(p))

  object Address:
    /** Any total order will do: the bytes, lexicographically (a prefix sorts first). */
    given Ordering[Address] = (x, y) => x.toBytes.compareTo(y.toBytes)

  // --- the state machine ------------------------------------------------------------------------

  /** `StIdle` (client agency), `StBusy` (server agency), `StDone` (nobody). */
  enum State:
    case Idle, Busy, Done
  enum Role:
    case Client, Server
  enum Message:
    case ShareRequest(amount: Amount)
    case SharePeers(peers: Vector[Address])
    case Done

  def direction(sender: Role): Mux.Direction = sender match
    case Role.Client => Mux.Direction.Initiator
    case Role.Server => Mux.Direction.Responder

  /** The message is legal in `state` from `sender`; the state after it. */
  def transition(state: State, sender: Role, message: Message): Either[Error, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.ShareRequest(_)) => Right(State.Busy)
      case (State.Busy, Role.Server, Message.SharePeers(_))   => Right(State.Idle)
      case (State.Idle, Role.Client, Message.Done)            => Right(State.Done)
      case (State.Done, _, _)                                 => Left(Error.Terminal)
      case _ => Left(Error.WrongAgency(state, sender))

  // --- errors -----------------------------------------------------------------------------------

  /** What a field of the wire is; the subject of [[Error.OutOfRange]]. */
  enum Field:
    case Key, Amount, AddressTag, Word32, Port

  /** What the decoder wanted when the byte it found was something else. */
  enum Expectation:
    case UnsignedInteger, DefiniteArray, ArrayOrIndefiniteArray, Break

  /** The closed set of reasons a message is not encoded or decoded. */
  enum Error:
    /** A message in `StDone`: the protocol has ended. */
    case Terminal

    /** The sender does not have agency in this state. */
    case WrongAgency(state: State, sender: Role)

    /** The key is not a message of this state (`unexpected key` in the reference). */
    case UnexpectedKey(state: State, key: BigInt)

    /** The array of the message or of the address has not the arity the CDDL gives it. */
    case Arity(expected: Int, found: BigInt)

    /** A field outside its CDDL range (`word8`, `word32`, `word16`). */
    case OutOfRange(field: Field, value: BigInt)

    /** The initial byte is not the kind of item the position needs. */
    case Unexpected(expected: Expectation, initialByte: Int)

    /** An additional-information value that CBOR reserves (28 to 30), or an indefinite integer. */
    case ReservedHead(initialByte: Int)

    /** The message is longer than `limit` bytes (the byte limit of the state). */
    case TooLarge(limit: Int)

    /** [[PeerSharing.decode]]: the input ends inside the message. */
    case Truncated

    /** [[PeerSharing.decode]]: bytes follow the message. */
    case Trailing(surplus: Int)

    def message: String = this match
      case Terminal             => "PeerSharing is terminal"
      case WrongAgency(s, r)    => s"illegal PeerSharing message in $s from $r"
      case UnexpectedKey(s, k)  => s"unexpected PeerSharing key $k in $s"
      case Arity(e, f)          => s"PeerSharing array of arity $f, expected $e"
      case OutOfRange(field, v) => s"PeerSharing $field outside its range: $v"
      case Unexpected(e, b) => s"PeerSharing expected $e, found initial byte 0x${"%02x".format(b)}"
      case ReservedHead(b)  => s"PeerSharing reserved or indefinite head 0x${"%02x".format(b)}"
      case TooLarge(limit)  => s"PeerSharing message exceeds $limit bytes"
      case Truncated        => "truncated PeerSharing message"
      case Trailing(n)      => s"$n trailing bytes after a PeerSharing message"

  /** A message the peer sent that the protocol forbids although it decoded. */
  enum Violation:
    /** More peers came back than were asked for (`Type.hs:80-82`; the client checks at
      * `PeerSharing.hs:126-131`).
      */
    case TooManyPeers(requested: Amount, received: Int)

    def message: String = this match
      case TooManyPeers(requested, received) =>
        s"PeerSharing answered $received peers to a request for ${requested.value}"

  /** The ceiling of a message is the upstream one; a smaller local value is allowed. */
  final case class Limits(maxMessageBytes: Int = UpstreamMessageBytes)

  /** The decoding of one message from a prefix of the input. */
  enum Decoding[+A]:
    case NeedMore
    case Failed(error: Error)
    case Decoded(value: A, consumed: Int)

  /** `decodePrefix` as the multiplexer takes it (`MuxChannel.recv`): the error is its message. */
  def decoder(state: State, sender: Role, limits: Limits = Limits())(
      bytes: Bytes
  ): DecodeResult[Message] =
    decodePrefix(state, sender, bytes, limits) match
      case Decoding.NeedMore              => DecodeResult.NeedMore
      case Decoding.Failed(error)         => DecodeResult.Failed(error.message)
      case Decoding.Decoded(value, taken) => DecodeResult.Decoded(value, taken)

  // --- encoding ---------------------------------------------------------------------------------

  def encode(
      state: State,
      sender: Role,
      message: Message,
      limits: Limits = Limits()
  ): Either[Error, Bytes] =
    transition(state, sender, message)
      .map { _ =>
        val out = new java.io.ByteArrayOutputStream()
        def head(major: Int, value: Long): Unit =
          // `value` is an unsigned 32-bit value or less; the shortest form, as cborg writes it.
          if value < 24 then out.write(major * 32 + value.toInt)
          else if value <= 0xff then { out.write(major * 32 + 24); out.write(value.toInt) }
          else if value <= 0xffff then
            out.write(major * 32 + 25); out.write((value >> 8).toInt); out.write(value.toInt & 255)
          else
            out.write(major * 32 + 26)
            (3 to 0 by -1).foreach(i => out.write(((value >> (i * 8)) & 255).toInt))
        def address(a: Address): Unit = a match
          case Address.V4(x, p) =>
            head(4, 3); head(0, 0); head(0, x.value); head(0, p.value.toLong)
          case Address.V6(w, x, y, z, p) =>
            head(4, 6); head(0, 1)
            Vector(w, x, y, z).foreach(q => head(0, q.value))
            head(0, p.value.toLong)
        message match
          case Message.ShareRequest(amount) => head(4, 2); head(0, 0); head(0, amount.value.toLong)
          case Message.SharePeers(peers) =>
            head(4, 2); head(0, 1)
            if peers.isEmpty then head(4, 0)
            else
              out.write(0x9f)
              peers.foreach(address)
              out.write(0xff)
          case Message.Done => head(4, 1); head(0, 2)
        Bytes.fromArray(out.toByteArray)
      }
      .flatMap(bytes =>
        Either
          .cond(bytes.size <= limits.maxMessageBytes, bytes, Error.TooLarge(limits.maxMessageBytes))
      )

  // --- decoding ---------------------------------------------------------------------------------

  /** Why a read stopped: the input ended, or the bytes are wrong. */
  private enum Stop:
    case Short
    case Fail(error: Error)
  private type Read[A] = Either[Stop, A]

  private def fail[A](error: Error): Read[A] = Left(Stop.Fail(error))

  /** The head of a CBOR item: its initial byte, major type, and the argument (absent when
    * indefinite).
    */
  private final case class Head(initial: Int, major: Int, argument: Option[BigInt])

  /** A cursor over a prefix; the bytes read so far never pass `limit`. */
  private final class Cursor(input: Bytes, limit: Int):
    private var at = 0
    def position: Int = at

    private def need(n: Int): Read[Unit] =
      if n > limit - at then fail(Error.TooLarge(limit))
      else if n > input.size - at then Left(Stop.Short)
      else Right(())

    private def byte(): Read[Int] =
      need(1).map { _ =>
        val b = input(at) & 255
        at += 1
        b
      }

    def head(): Read[Head] =
      byte().flatMap { initial =>
        val major = initial >> 5
        val info = initial & 31
        if info < 24 then Right(Head(initial, major, Some(BigInt(info))))
        else if info == 31 then Right(Head(initial, major, None))
        else if info > 27 then fail(Error.ReservedHead(initial))
        else
          val width = 1 << (info - 24)
          need(width).map { _ =>
            val value = (0 until width).foldLeft(BigInt(0))((acc, i) =>
              (acc << 8) | BigInt(input(at + i) & 255)
            )
            at += width
            Head(initial, major, Some(value))
          }
      }

    /** An unsigned integer in any definite width. */
    def uint(): Read[BigInt] =
      head().flatMap {
        case Head(_, 0, Some(value)) => Right(value)
        case Head(initial, 0, None)  => fail(Error.ReservedHead(initial))
        case Head(initial, _, _)     => fail(Error.Unexpected(Expectation.UnsignedInteger, initial))
      }

    def ranged(field: Field, max: BigInt): Read[Long] =
      uint().flatMap(v => if v > max then fail(Error.OutOfRange(field, v)) else Right(v.toLong))

    def definiteArray(): Read[BigInt] =
      head().flatMap {
        case Head(_, 4, Some(n)) => Right(n)
        case Head(initial, _, _) => fail(Error.Unexpected(Expectation.DefiniteArray, initial))
      }

    /** The length of an array, or `None` for an indefinite one. */
    def arrayOrIndefinite(): Read[Option[BigInt]] =
      head().flatMap {
        case Head(_, 4, length) => Right(length)
        case Head(initial, _, _) =>
          fail(Error.Unexpected(Expectation.ArrayOrIndefiniteArray, initial))
      }

    def peekBreak(): Read[Boolean] =
      need(1).map(_ => (input(at) & 255) == 0xff)

    def break(): Read[Unit] =
      head().flatMap {
        case Head(_, 7, None)    => Right(())
        case Head(initial, _, _) => fail(Error.Unexpected(Expectation.Break, initial))
      }

  private def arity(found: BigInt, expected: Int): Read[Unit] =
    if found == BigInt(expected) then Right(()) else fail(Error.Arity(expected, found))

  private def address(c: Cursor): Read[Address] =
    def word() = c.ranged(Field.Word32, BigInt(Word32.Max)).map(Word32.trusted)
    def port() = c.ranged(Field.Port, BigInt(Port.Max)).map(Port.trusted)
    for
      n <- c.definiteArray()
      tag <- c.ranged(Field.AddressTag, BigInt(1))
      decoded <- tag match
        case 0 =>
          for
            _ <- arity(n, 3)
            a <- word()
            p <- port()
          yield Address.V4(a, p)
        case _ =>
          for
            _ <- arity(n, 6)
            a <- word()
            b <- word()
            d <- word()
            e <- word()
            p <- port()
          yield Address.V6(a, b, d, e, p)
    yield decoded

  /** The peers of a `MsgSharePeers`: a definite or an indefinite list (`Codec.hs:89-93`). */
  private def peers(c: Cursor): Read[Vector[Address]] =
    @tailrec def definite(left: BigInt, acc: Vector[Address]): Read[Vector[Address]] =
      if left == 0 then Right(acc)
      else
        address(c) match
          case Right(a) => definite(left - 1, acc :+ a)
          case Left(s)  => Left(s)
    @tailrec def indefinite(acc: Vector[Address]): Read[Vector[Address]] =
      c.peekBreak() match
        case Left(s)     => Left(s)
        case Right(true) => c.break().map(_ => acc)
        case Right(false) =>
          address(c) match
            case Right(a) => indefinite(acc :+ a)
            case Left(s)  => Left(s)
    c.arrayOrIndefinite().flatMap {
      case Some(n) => definite(n, Vector.empty)
      case None    => indefinite(Vector.empty)
    }

  /** One message from the front of `bytes`; surplus bytes are left to the caller. The caller bounds
    * what it keeps: a message cannot be longer than `limits.maxMessageBytes`, which this checks as
    * it reads, so a declared length that cannot fit fails at once.
    */
  def decodePrefix(
      state: State,
      sender: Role,
      bytes: Bytes,
      limits: Limits = Limits()
  ): Decoding[Message] =
    val cursor = new Cursor(bytes, limits.maxMessageBytes)
    val agency = state match
      case State.Done => Left(Error.Terminal)
      case State.Idle => Either.cond(sender == Role.Client, (), Error.WrongAgency(state, sender))
      case State.Busy => Either.cond(sender == Role.Server, (), Error.WrongAgency(state, sender))
    val decoded: Read[Message] = agency.left.map(Stop.Fail(_)).flatMap { _ =>
      for
        n <- cursor.definiteArray()
        key <- cursor.uint()
        message <- (state, key) match
          case (State.Idle, k) if k == 0 =>
            for
              _ <- arity(n, 2)
              amount <- cursor.ranged(Field.Amount, BigInt(Amount.Max))
            yield Message.ShareRequest(Amount.trusted(amount))
          case (State.Busy, k) if k == 1 =>
            for
              _ <- arity(n, 2)
              found <- peers(cursor)
            yield Message.SharePeers(found)
          case (State.Idle, k) if k == 2 => arity(n, 1).map(_ => Message.Done)
          case _                         => fail(Error.UnexpectedKey(state, key))
      yield message
    }
    decoded match
      case Right(message)         => Decoding.Decoded(message, cursor.position)
      case Left(Stop.Short)       => Decoding.NeedMore
      case Left(Stop.Fail(error)) => Decoding.Failed(error)

  /** The whole of `bytes` is one message. */
  def decode(
      state: State,
      sender: Role,
      bytes: Bytes,
      limits: Limits = Limits()
  ): Either[Error, Message] =
    decodePrefix(state, sender, bytes, limits) match
      case Decoding.NeedMore      => Left(Error.Truncated)
      case Decoding.Failed(error) => Left(error)
      case Decoding.Decoded(m, used) =>
        Either.cond(used == bytes.size, m, Error.Trailing(bytes.size - used))
end PeerSharing
