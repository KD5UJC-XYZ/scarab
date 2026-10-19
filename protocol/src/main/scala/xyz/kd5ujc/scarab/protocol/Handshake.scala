// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.*
import cats.syntax.all.*

/** Handshake-only source conformance to ouroboros-network c45735a56c567fa977969173d18943bac6bb3821.
  * Independently implemented from Protocol/Handshake/{Codec,Client,Server}.hs and Cardano version
  * codecs. Resource policy (stricter than wire): depth 24, 512 terms, 4096 string bytes, 1024
  * refusal bytes. Unknown map entries are parsed as bounded generic CBOR before being skipped.
  * Refusal arrays must have exact arity locally; the pinned decoder reads but ignores that length.
  */
object Handshake:
  val MaxMessageBytes = 5760
  val DefaultPhaseTimeout: FiniteDuration = 10.seconds
  val MaxDepth = 24
  val MaxItems = 512
  val MaxStringBytes = 4096
  val MaxRefusalBytes = 1024

  enum Suite:
    case NodeToNode, NodeToClient
  enum State:
    case Propose, Confirm, Done
  enum Term:
    case Integer(value: BigInt)
    case Bool(value: Boolean)
    case Arr(values: Vector[Term], definite: Boolean = true)
    case Map(values: Vector[(Term, Term)], definite: Boolean = true)
    case Text(value: String, definite: Boolean = true)
    case ByteString(value: Bytes)
    case Tag(number: BigInt, value: Term)
    case Simple(value: Int)
    case FloatBits(width: Int, bits: BigInt)
  enum Reason:
    case VersionMismatch(versions: Vector[Int], unknown: Vector[BigInt] = Vector.empty)
    case DecodeError(version: Int, text: String)
    case Refused(version: Int, text: String)
  enum Message:
    case Propose(versions: Vector[(Int, Term)])
    case Accept(version: Int, data: Term)
    case Refuse(reason: Reason)
    case QueryReply(versions: Vector[(Int, Term)])
  final case class Data(
      magic: Long,
      initiatorOnly: Boolean = true,
      peerSharing: Boolean = false,
      query: Boolean = false,
      peras: Boolean = false
  )
  enum Result:
    case Negotiated(version: Int, data: Data)
    case QueryResult(versions: Vector[(Int, Either[ProtocolError, Data])])
    case Rejected(reason: Reason)
  final case class DecodeError(cause: ProtocolError, incomplete: Boolean = false):
    def message: String = cause.message
  private def bad(error: ProtocolError)(using ProtocolWire.Scope): Nothing = ProtocolWire.bad(error)
  private def attempt[A](body: ProtocolWire.Scope ?=> A): Either[ProtocolError, A] =
    ProtocolWire.attempt(body)

  def recognized(suite: Suite, version: Int): Boolean = suite match
    case Suite.NodeToNode   => NtnVersion.fromNumber(version).isDefined
    case Suite.NodeToClient => NtcVersion.fromNumber(version).isDefined
  def wireVersion(suite: Suite, version: Int): Either[ProtocolError, Int] =
    if !recognized(suite, version) then Left(ProtocolError.UnsupportedLogicalVersion)
    else Right(if suite == Suite.NodeToClient then version | 0x8000 else version)

  /** Wire policy, independent of version-data negotiation. Unknown map keys are skipped. The
    * Cardano facades below retain their closed NtnVersion/NtcVersion policies.
    */
  trait VersionCodec:
    def encode(version: Int): Either[ProtocolError, Int]
    def decode(wire: Int): Option[Int]

  private def versions(suite: Suite): VersionCodec = new VersionCodec:
    def encode(version: Int): Either[ProtocolError, Int] = wireVersion(suite, version)
    def decode(wire: Int): Option[Int] =
      val v = if suite == Suite.NodeToClient then wire ^ 0x8000 else wire
      Option.when(recognized(suite, v))(v)

  private def logical(codec: VersionCodec, term: Term): Option[Int] = term match
    case Term.Integer(n) if n.isValidInt => codec.decode(n.toInt)
    case _                               => None
  private def known(codec: VersionCodec, term: Term)(using ProtocolWire.Scope): Int =
    logical(codec, term).getOrElse(bad(ProtocolError.UnrecognizedSelectedVersion))
  private def versionTerm(codec: VersionCodec, v: Int)(using ProtocolWire.Scope): Term =
    Term.Integer(codec.encode(v).fold(bad, identity))
  private def arr(t: Term)(using ProtocolWire.Scope): Vector[Term] = t match
    case Term.Arr(v, true) => v
    case _                 => bad(ProtocolError.ExpectedDefiniteArray)
  private def bool(t: Term)(using ProtocolWire.Scope): Boolean = t match
    case Term.Bool(b) => b
    case _            => bad(ProtocolError.ExpectedBoolean)
  private def magic(t: Term)(using ProtocolWire.Scope): Long = t match
    case Term.Integer(n) if n >= 0 && n <= BigInt("ffffffff", 16) => n.toLong
    case _ => bad(ProtocolError.NetworkMagicOutsideUint32)

  /** Whether node-to-node version data of this version has the Peras flag. */
  private def carriesPeras(version: Int): Boolean =
    NtnVersion.fromNumber(version).exists(_.carriesPeras)

  def dataTerm(suite: Suite, version: Int, data: Data): Either[ProtocolError, Term] = attempt {
    if !recognized(suite, version) then bad(ProtocolError.UnsupportedLogicalVersion)
    if data.magic < 0 || data.magic > 0xffffffffL then bad(ProtocolError.NetworkMagicOutsideUint32)
    if suite == Suite.NodeToClient then
      if !data.initiatorOnly || data.peerSharing || data.peras then
        bad(ProtocolError.NtCHasNoNtNFeatures)
      Term.Arr(Vector(Term.Integer(data.magic), Term.Bool(data.query)))
    else
      if data.peras && !carriesPeras(version) then bad(ProtocolError.PerasRequiresVersion16)
      Term.Arr(
        Vector(
          Term.Integer(data.magic),
          Term.Bool(data.initiatorOnly),
          Term.Integer(if data.peerSharing then 1 else 0),
          Term.Bool(data.query)
        ) ++
          (if carriesPeras(version) then Vector(Term.Bool(data.peras)) else Vector.empty)
      )
  }
  def decodeData(suite: Suite, version: Int, term: Term): Either[ProtocolError, Data] = attempt {
    if !recognized(suite, version) then bad(ProtocolError.UnsupportedLogicalVersion)
    val fields = arr(term)
    if suite == Suite.NodeToClient then
      if fields.size != 2 then bad(ProtocolError.NtCDataArity)
      Data(magic(fields(0)), query = bool(fields(1)))
    else
      if fields.size != (if carriesPeras(version) then 5 else 4) then
        bad(ProtocolError.NtNDataArity)
      val peers = fields(2) match
        case Term.Integer(n) if n == 0 || n == 1 => n == 1
        case _                                   => bad(ProtocolError.PeerSharingMustBe0Or1)
      Data(
        magic(fields(0)),
        bool(fields(1)),
        peers,
        bool(fields(3)),
        if carriesPeras(version) then bool(fields(4)) else false
      )
  }
  private def agree(local: Data, remote: Data): Either[ProtocolError, Data] =
    if local.magic != remote.magic then Left(ProtocolError.NetworkMagicMismatch)
    else
      Right(
        Data(
          local.magic,
          local.initiatorOnly || remote.initiatorOnly,
          local.peerSharing && remote.peerSharing,
          local.query || remote.query,
          local.peras && remote.peras
        )
      )

  private def versionMap(codec: VersionCodec, term: Term)(using
      ProtocolWire.Scope
  ): Vector[(Int, Term)] =
    term match
      case Term.Map(entries, true) =>
        val recognized = entries.flatMap { case (key, data) => logical(codec, key).map(_ -> data) }
        if recognized.zip(recognized.drop(1)).exists((a, b) => a._1 >= b._1) then
          bad(ProtocolError.RecognizedVersionsMustBeDistinctAndIncreasing)
        recognized
      case _ => bad(ProtocolError.ExpectedDefiniteVersionMap)
  private def mapTerm(codec: VersionCodec, entries: Vector[(Int, Term)])(using
      ProtocolWire.Scope
  ): Term =
    if entries.map(_._1).distinct.size != entries.size then bad(ProtocolError.DuplicateVersion)
    Term.Map(entries.sortBy(_._1).map { case (v, d) => (versionTerm(codec, v), d) })
  private def text(t: Term)(using ProtocolWire.Scope): String = t match
    case Term.Text(s, true) if s.getBytes(StandardCharsets.UTF_8).length <= MaxRefusalBytes => s
    case _ => bad(ProtocolError.InvalidOrOversizedRefusalText)
  private def reason(codec: VersionCodec, term: Term)(using ProtocolWire.Scope): Reason = arr(
    term
  ) match
    case Vector(Term.Integer(tag), versions) if tag == 0 =>
      val all = arr(versions)
      Reason.VersionMismatch(
        all.flatMap(logical(codec, _)),
        all.collect {
          case t @ Term.Integer(n) if logical(codec, t).isEmpty => n
        }
      )
    case Vector(Term.Integer(tag), v, s) if tag == 1 => Reason.DecodeError(known(codec, v), text(s))
    case Vector(Term.Integer(tag), v, s) if tag == 2 => Reason.Refused(known(codec, v), text(s))
    case _                                           => bad(ProtocolError.InvalidRefusalTagOrArity)
  private def reasonTerm(codec: VersionCodec, r: Reason)(using ProtocolWire.Scope): Term = r match
    case Reason.VersionMismatch(vs, _) =>
      Term.Arr(Vector(Term.Integer(0), Term.Arr(vs.map(versionTerm(codec, _)))))
    case Reason.DecodeError(v, s) =>
      text(Term.Text(s)); Term.Arr(Vector(Term.Integer(1), versionTerm(codec, v), Term.Text(s)))
    case Reason.Refused(v, s) =>
      text(Term.Text(s)); Term.Arr(Vector(Term.Integer(2), versionTerm(codec, v), Term.Text(s)))
  private def message(codec: VersionCodec, state: State, term: Term)(using
      ProtocolWire.Scope
  ): Message =
    if state == State.Done then bad(ProtocolError.HandshakeAlreadyDone)
    arr(term) match
      case Vector(Term.Integer(tag), map) if tag == 0 => Message.Propose(versionMap(codec, map))
      case Vector(Term.Integer(tag), v, d) if tag == 1 && state == State.Confirm =>
        Message.Accept(known(codec, v), d)
      case Vector(Term.Integer(tag), r) if tag == 2 && state == State.Confirm =>
        Message.Refuse(reason(codec, r))
      case Vector(Term.Integer(tag), map) if tag == 3 && state == State.Confirm =>
        Message.QueryReply(versionMap(codec, map))
      case _ => bad(ProtocolError.UnexpectedHandshakeTagArityOrState)
  def encode(codec: VersionCodec, msg: Message): Either[ProtocolError, Bytes] = attempt {
    val term = msg match
      case Message.Propose(vs)    => Term.Arr(Vector(Term.Integer(0), mapTerm(codec, vs)))
      case Message.Accept(v, d)   => Term.Arr(Vector(Term.Integer(1), versionTerm(codec, v), d))
      case Message.Refuse(r)      => Term.Arr(Vector(Term.Integer(2), reasonTerm(codec, r)))
      case Message.QueryReply(vs) => Term.Arr(Vector(Term.Integer(3), mapTerm(codec, vs)))
    encodeTerm(term)
  }
  def decodePrefix(
      codec: VersionCodec,
      state: State,
      bytes: Bytes
  ): Either[DecodeError, (Message, Bytes)] =
    new Parser(bytes)
      .term(0)
      .run(Cursor(0, 0))
      .flatMap { case (cursor, term) =>
        attempt((message(codec, state, term), bytes.drop(cursor.position)))
      }
      .left
      .map(e => DecodeError(e, e.incomplete))
  def decode(codec: VersionCodec, state: State, bytes: Bytes): Either[DecodeError, Message] =
    decodePrefix(codec, state, bytes).flatMap { case (m, rest) =>
      if rest.size == 0 then Right(m) else Left(DecodeError(ProtocolError.TrailingBytes))
    }

  def encode(suite: Suite, msg: Message): Either[ProtocolError, Bytes] =
    encode(versions(suite), msg)
  def decodePrefix(
      suite: Suite,
      state: State,
      bytes: Bytes
  ): Either[DecodeError, (Message, Bytes)] =
    decodePrefix(versions(suite), state, bytes)
  def decode(suite: Suite, state: State, bytes: Bytes): Either[DecodeError, Message] =
    decode(versions(suite), state, bytes)

  def encodeReason(codec: VersionCodec, value: Reason): Either[ProtocolError, Bytes] =
    attempt(encodeTerm(reasonTerm(codec, value)))
  def decodeReason(codec: VersionCodec, bytes: Bytes): Either[DecodeError, Reason] =
    new Parser(bytes)
      .term(0)
      .run(Cursor(0, 0))
      .flatMap { case (cursor, term) =>
        if cursor.position != bytes.size then Left(ProtocolError.TrailingBytes)
        else attempt(reason(codec, term))
      }
      .left
      .map(e => DecodeError(e, e.incomplete))

  def proposal(suite: Suite, offers: Vector[(Int, Data)]): Either[ProtocolError, Message] =
    offers
      .foldLeft[Either[ProtocolError, Vector[(Int, Term)]]](Right(Vector.empty)) { (acc, entry) =>
        for xs <- acc; d <- dataTerm(suite, entry._1, entry._2) yield xs :+ (entry._1 -> d)
      }
      .flatMap(vs =>
        if vs.map(_._1).distinct.size != vs.size then Left(ProtocolError.DuplicateVersion)
        else Right(Message.Propose(vs.sortBy(_._1)))
      )

  /** The node-to-node offers of a node: every version up to `max`, each with the same version data
    * (the Peras flag is only written for the versions that have it, and we do not set it).
    */
  def nodeToNodeOffers(max: NtnVersion, data: Data): Vector[(Int, Data)] =
    NtnVersion.upTo(max).map(v => v.number -> data)

  /** What a node offers by default: the versions of the latest release, as the reference does. */
  def defaultNodeToNode(magic: Long): Vector[(Int, Data)] =
    nodeToNodeOffers(NtnVersion.LatestReleased, Data(magic))

  /** The node-to-client offers: every version the reference supports, all with the same data. */
  def nodeToClientOffers(data: Data): Vector[(Int, Data)] =
    NtcVersion.All.map(v => v.number -> data)
  private def queryResult(suite: Suite, vs: Vector[(Int, Term)]): Result =
    Result.QueryResult(vs.map { case (v, d) => v -> decodeData(suite, v, d) })

  /** Version-data policy: errors stay typed until a refusal is encoded for the peer. */
  trait DataCodec[A, E]:
    def decode(version: Int, term: Term): Either[E, A]
    def agree(local: A, remote: A): Either[E, A]
    def refusalText(error: E): String

  enum SelectionError[+E]:
    case VersionMismatch(versions: Vector[Int])
    case DecodeError(version: Int, cause: E)
    case Refused(version: Int, cause: E)

  /** Convert a typed selection failure into its wire diagnostic at the handshake boundary. */
  def refusal[A, E](codec: DataCodec[A, E], error: SelectionError[E]): Reason = error match
    case SelectionError.VersionMismatch(vs)   => Reason.VersionMismatch(vs)
    case SelectionError.DecodeError(v, cause) => Reason.DecodeError(v, codec.refusalText(cause))
    case SelectionError.Refused(v, cause)     => Reason.Refused(v, codec.refusalText(cause))

  /** Highest-common-version selection, shared by the Cardano facade and mock-domain adapters.
    * Mirrors Handshake/Client.hs:acceptOrRefuse at upstream 2fb69829. A failed highest candidate
    * never falls back to a lower version.
    */
  def acceptOrRefuse[A, E](
      codec: DataCodec[A, E],
      offers: Vector[(Int, A)],
      remote: Vector[(Int, Term)]
  ): Either[SelectionError[E], (Int, A)] =
    val common = remote.filter { case (v, _) => offers.exists(_._1 == v) }
    common.maxByOption(_._1) match
      case None => Left(SelectionError.VersionMismatch(offers.map(_._1).sorted))
      case Some((v, term)) =>
        codec.decode(v, term).left.map(e => SelectionError.DecodeError(v, e)).flatMap { decoded =>
          offers.find(_._1 == v) match
            case None => Left(SelectionError.VersionMismatch(offers.map(_._1).sorted))
            case Some((_, own)) =>
              codec
                .agree(own, decoded)
                .left
                .map(e => SelectionError.Refused(v, e))
                .map(v -> _)
        }

  private def cardanoData(suite: Suite): DataCodec[Data, ProtocolError] =
    new DataCodec[Data, ProtocolError]:
      def decode(version: Int, term: Term): Either[ProtocolError, Data] =
        decodeData(suite, version, term)
      def agree(local: Data, remote: Data): Either[ProtocolError, Data] =
        Handshake.agree(local, remote)
      def refusalText(error: ProtocolError): String = error.message

  private def negotiate(
      suite: Suite,
      offers: Vector[(Int, Data)],
      remote: Vector[(Int, Term)]
  ): Result =
    val codec = cardanoData(suite)
    acceptOrRefuse(codec, offers, remote)
      .fold(error => Result.Rejected(refusal(codec, error)), (v, d) => Result.Negotiated(v, d))
  final case class Client private[Handshake] (
      suite: Suite,
      offers: Vector[(Int, Data)],
      state: State
  ):
    def receive(msg: Message): Either[ProtocolError, (Client, Result)] =
      if state != State.Confirm then Left(ProtocolError.ClientIsNotAwaitingConfirmation)
      else
        val result: Either[ProtocolError, Result] = msg match
          case Message.Propose(vs)    => Right(negotiate(suite, offers, vs))
          case Message.QueryReply(vs) => Right(queryResult(suite, vs))
          case Message.Refuse(r)      => Right(Result.Rejected(r))
          case Message.Accept(v, t) =>
            offers.find(_._1 == v) match
              case None => Left(ProtocolError.ServerSelectedAVersionNotOffered)
              case Some((_, local)) =>
                decodeData(suite, v, t).flatMap(agree(local, _)).map(Result.Negotiated(v, _))
        result.map(r => (copy(state = State.Done), r))
  def clientStart(
      suite: Suite,
      offers: Vector[(Int, Data)]
  ): Either[ProtocolError, (Client, Message)] =
    proposal(suite, offers).map(p => (Client(suite, offers, State.Confirm), p))
  def responder(
      suite: Suite,
      offers: Vector[(Int, Data)],
      msg: Message
  ): Either[ProtocolError, (Message, Result)] =
    proposal(suite, offers).flatMap { local =>
      msg match
        case Message.Propose(vs) =>
          negotiate(suite, offers, vs) match
            case r @ Result.Rejected(reason) => Right((Message.Refuse(reason), r))
            case Result.Negotiated(_, d) if d.query =>
              local match
                case Message.Propose(localVs) =>
                  Right((Message.QueryReply(localVs), queryResult(suite, vs)))
                case _ => Left(ProtocolError.InvalidLocalProposal)
            case r @ Result.Negotiated(v, d) =>
              dataTerm(suite, v, d).map(t => (Message.Accept(v, t), r))
            case _ => Left(ProtocolError.InvalidNegotiationResult)
        case _ => Left(ProtocolError.ResponderExpectsProposal)
    }

  private final case class Cursor(position: Int, items: Int)
  private type ResultOf[A] = Either[ProtocolError, A]
  private type Read[A] = cats.data.StateT[ResultOf, Cursor, A]

  /** Handshake negotiation is infrequent: keep its bounded parser as immutable state. */
  private final class Parser(input: Bytes):
    private def pure[A](value: A): Read[A] = cats.data.StateT.pure(value)
    private def failed[A](error: ProtocolError): Read[A] = checked(Left(error))
    private def checked[A](result: Either[ProtocolError, A]): Read[A] =
      cats.data.StateT.liftF(result)
    private def cursor: Read[Cursor] = cats.data.StateT.get
    private def read(): Read[Int] = cats.data.StateT { state =>
      if state.position >= MaxMessageBytes then Left(ProtocolError.HandshakeByteLimitExceeded)
      else if state.position >= input.size then Left(ProtocolError.IncompleteCbor)
      else Right((state.copy(position = state.position + 1), input(state.position) & 255))
    }
    private def peekBreak: Read[Boolean] = cursor.map { state =>
      state.position < input.size && (input(state.position) & 255) == 255
    }
    private def count: Read[Unit] = cats.data.StateT { state =>
      if state.items >= MaxItems then Left(ProtocolError.CBORItemLimitExceeded)
      else Right((state.copy(items = state.items + 1), ()))
    }
    private def argument(ai: Int): Read[BigInt] =
      if ai < 24 then pure(BigInt(ai))
      else
        val width = ai match
          case 24 => Right(1)
          case 25 => Right(2)
          case 26 => Right(4)
          case 27 => Right(8)
          case _  => Left(ProtocolError.ReservedCBORArgument)
        checked(width).flatMap(n =>
          Vector.fill(n)(read()).sequence.map(_.foldLeft(BigInt(0))((a, b) => (a << 8) | b))
        )
    private def size(n: BigInt, bound: Int): Read[Int] =
      if n < 0 || n > bound then failed(ProtocolError.CBORResourceLimitExceeded)
      else pure(n.toInt)
    private def utf8(bytes: Vector[Byte]): Either[ProtocolError, String] =
      try
        Right(
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toArray))
            .toString
        )
      catch case _: java.nio.charset.CharacterCodingException => Left(ProtocolError.InvalidUTF8)
    private def chunk(major: Int, ai: Int, remaining: Int): Read[Vector[Byte]] =
      for
        length <- argument(ai).flatMap(size(_, remaining))
        bytes <- Vector.fill(length)(read().map(_.toByte)).sequence
        _ <- if major == 3 then checked(utf8(bytes)).void else pure(())
      yield bytes
    private def string(major: Int, ai: Int): Read[Term] =
      val bytes =
        if ai != 31 then chunk(major, ai, MaxStringBytes)
        else
          cats.Monad[Read].tailRecM(Vector.empty[Byte]) { collected =>
            peekBreak.flatMap {
              case true => read().as(Right(collected))
              case false =>
                for
                  _ <- count
                  head <- read()
                  _ <-
                    if head / 32 != major || (head & 31) == 31 then
                      failed[Unit](ProtocolError.InvalidIndefiniteStringChunk)
                    else pure(())
                  next <- chunk(major, head & 31, MaxStringBytes - collected.size)
                yield Left(collected ++ next)
            }
          }
      bytes.flatMap { value =>
        if major == 2 then pure(Term.ByteString(Bytes(value)))
        else checked(utf8(value)).map(Term.Text(_, ai != 31))
      }
    private def indefinite[A](item: Read[A]): Read[Vector[A]] =
      cats.Monad[Read].tailRecM(Vector.empty[A]) { collected =>
        peekBreak.flatMap {
          case true  => read().as(Right(collected))
          case false => item.map(value => Left(collected :+ value))
        }
      }
    def term(depth: Int): Read[Term] =
      for
        _ <-
          if depth > MaxDepth then failed[Unit](ProtocolError.CBORDepthLimitExceeded) else pure(())
        _ <- count
        head <- read()
        major = head / 32
        ai = head & 31
        value <- major match
          case 0     => argument(ai).map(Term.Integer(_))
          case 1     => argument(ai).map(n => Term.Integer(-1 - n))
          case 2 | 3 => string(major, ai)
          case 4 =>
            val values =
              if ai == 31 then indefinite(term(depth + 1))
              else
                for
                  state <- cursor; n <- argument(ai).flatMap(size(_, MaxItems - state.items));
                  values <- Vector.fill(n)(term(depth + 1)).sequence
                yield values
            values.map(Term.Arr(_, ai != 31))
          case 5 =>
            val pair = (term(depth + 1), term(depth + 1)).tupled
            val values =
              if ai == 31 then indefinite(pair)
              else
                for
                  state <- cursor; n <- argument(ai).flatMap(size(_, (MaxItems - state.items) / 2));
                  values <- Vector.fill(n)(pair).sequence
                yield values
            values.map(Term.Map(_, ai != 31))
          case 6 => (argument(ai), term(depth + 1)).mapN(Term.Tag(_, _))
          case _ =>
            ai match
              case 20 => pure(Term.Bool(false))
              case 21 => pure(Term.Bool(true))
              case 25 | 26 | 27 =>
                argument(ai).map(
                  Term.FloatBits(if ai == 25 then 2 else if ai == 26 then 4 else 8, _)
                )
              case 31           => failed(ProtocolError.UnexpectedBreak)
              case 28 | 29 | 30 => failed(ProtocolError.ReservedCBORSimpleValue)
              case 24 =>
                read().flatMap(n =>
                  if n < 32 then failed(ProtocolError.InvalidExtendedCBORSimpleValue)
                  else pure(Term.Simple(n))
                )
              case n => pure(Term.Simple(n))
      yield value

  private final case class Written(bytes: Vector[Byte], items: Int)
  private type Write[A] = cats.data.StateT[ResultOf, Written, A]

  private def encodeTerm(term: Term)(using ProtocolWire.Scope): Bytes =
    def checked[A](result: Either[ProtocolError, A]): Write[A] = cats.data.StateT.liftF(result)
    def emit(bytes: Vector[Byte]): Write[Unit] = cats.data.StateT { state =>
      if bytes.size > MaxMessageBytes - state.bytes.size then
        Left(ProtocolError.HandshakeByteLimitExceeded)
      else Right((state.copy(bytes = state.bytes ++ bytes), ()))
    }
    def byte(value: Int): Write[Unit] = emit(Vector(value.toByte))
    def number(value: BigInt, width: Int): Vector[Byte] =
      (0 until width).reverseIterator.map(i => ((value >> (i * 8)) & 255).toByte).toVector
    def head(major: Int, n: BigInt): Write[Unit] =
      if n < 0 || n >= (BigInt(1) << 64) then checked(Left(ProtocolError.CBORIntegerOutsideUint64))
      else if n < 24 then byte(major * 32 + n.toInt)
      else
        val width =
          if n <= 255 then 1 else if n <= 65535 then 2 else if n <= 0xffffffffL then 4 else 8
        byte(
          major * 32 + (if width == 1 then 24
                        else if width == 2 then 25
                        else if width == 4 then 26
                        else 27)
        ) *> emit(number(n, width))
    def raw(major: Int, bytes: Vector[Byte]): Write[Unit] =
      if bytes.size > MaxStringBytes then checked(Left(ProtocolError.StringByteLimitExceeded))
      else head(major, bytes.size) *> emit(bytes)
    def count(depth: Int): Write[Unit] = cats.data.StateT { state =>
      if state.items >= MaxItems || depth > MaxDepth then
        Left(ProtocolError.CBORResourceLimitExceeded)
      else Right((state.copy(items = state.items + 1), ()))
    }
    def unicode(value: String): Either[ProtocolError, Vector[Byte]] =
      try
        val encoded = StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(java.nio.CharBuffer.wrap(value))
        Right(Vector.tabulate(encoded.remaining())(i => encoded.get(i)))
      catch
        case _: java.nio.charset.CharacterCodingException => Left(ProtocolError.InvalidUnicodeText)
    def go(value: Term, depth: Int): Write[Unit] = count(depth).flatMap { _ =>
      value match
        case Term.Integer(n) => if n >= 0 then head(0, n) else head(1, -1 - n)
        case Term.Bool(b)    => byte(if b then 245 else 244)
        case Term.Arr(values, definite) =>
          (if definite then head(4, values.size) else byte(159)) *> values.traverse_(
            go(_, depth + 1)
          ) *> (if definite then cats.data.StateT.pure[ResultOf, Written, Unit](()) else byte(255))
        case Term.Map(values, definite) =>
          (if definite then head(5, values.size) else byte(191)) *> values.traverse_((k, v) =>
            go(k, depth + 1) *> go(v, depth + 1)
          ) *> (if definite then cats.data.StateT.pure[ResultOf, Written, Unit](()) else byte(255))
        case Term.Text(value, definite) =>
          checked(unicode(value)).flatMap(bytes =>
            (if definite then cats.data.StateT.pure[ResultOf, Written, Unit](())
             else byte(127)) *> raw(
              3,
              bytes
            ) *> (if definite then cats.data.StateT.pure[ResultOf, Written, Unit](())
                  else byte(255))
          )
        case Term.ByteString(bytes)  => raw(2, bytes.value)
        case Term.Tag(number, value) => head(6, number) *> go(value, depth + 1)
        case Term.Simple(n) =>
          if n < 0 || n > 255 || (n >= 20 && n <= 21) || (n >= 24 && n < 32) then
            checked(Left(ProtocolError.InvalidSimpleValue))
          else if n < 24 then byte(224 + n)
          else byte(248) *> byte(n)
        case Term.FloatBits(width, bits) =>
          if !Set(2, 4, 8).contains(width) || bits < 0 || bits >= (BigInt(1) << (width * 8)) then
            checked(Left(ProtocolError.InvalidFloatBits))
          else
            byte(if width == 2 then 249 else if width == 4 then 250 else 251) *> emit(
              number(bits, width)
            )
    }
    Bytes(ProtocolWire.get(go(term, 0).runS(Written(Vector.empty, 0))).bytes)
