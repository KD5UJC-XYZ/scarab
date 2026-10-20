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

  /** Generic wire values retain their version domain; Cardano's integer API is mapped once through
    * its closed node-to-node or node-to-client profile below.
    */
  enum WireReason[V]:
    case VersionMismatch(versions: Vector[V], unknown: Vector[BigInt] = Vector.empty)
    case DecodeError(version: V, text: String)
    case Refused(version: V, text: String)
  enum WireMessage[V]:
    case Propose(versions: Vector[(V, Term)])
    case Accept(version: V, data: Term)
    case Refuse(reason: WireReason[V])
    case QueryReply(versions: Vector[(V, Term)])
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

  /** The wire representation of one version domain. Unknown map keys are skipped, while selected
    * versions must belong to that same domain.
    */
  trait VersionCodec[V]:
    def encode(version: V): Either[ProtocolError, Int]
    def decode(wire: Int): Option[V]

  object VersionCodec:
    given VersionCodec[NtnVersion] with
      def encode(version: NtnVersion): Either[ProtocolError, Int] = Right(version.number)
      def decode(wire: Int): Option[NtnVersion] = NtnVersion.fromNumber(wire)
    given VersionCodec[NtcVersion] with
      def encode(version: NtcVersion): Either[ProtocolError, Int] = Right(version.number | 0x8000)
      def decode(wire: Int): Option[NtcVersion] = NtcVersion.fromNumber(wire ^ 0x8000)

  private def logical[V](term: Term)(using codec: VersionCodec[V]): Option[V] = term match
    case Term.Integer(n) if n.isValidInt => codec.decode(n.toInt)
    case _                               => None
  private def known[V: VersionCodec](term: Term)(using ProtocolWire.Scope): V =
    logical[V](term).getOrElse(bad(ProtocolError.UnrecognizedSelectedVersion))
  private def versionTerm[V](v: V)(using codec: VersionCodec[V], scope: ProtocolWire.Scope): Term =
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

  private def versionMap[V: VersionCodec](term: Term)(using
      ordering: Ordering[V],
      scope: ProtocolWire.Scope
  ): Vector[(V, Term)] = term match
    case Term.Map(entries, true) =>
      val recognized = entries.flatMap { case (key, data) => logical[V](key).map(_ -> data) }
      if recognized.zip(recognized.drop(1)).exists((a, b) => ordering.gteq(a._1, b._1)) then
        bad(ProtocolError.RecognizedVersionsMustBeDistinctAndIncreasing)
      recognized
    case _ => bad(ProtocolError.ExpectedDefiniteVersionMap)
  private def mapTerm[V: VersionCodec: Ordering](entries: Vector[(V, Term)])(using
      ProtocolWire.Scope
  ): Term =
    if entries.map(_._1).distinct.size != entries.size then bad(ProtocolError.DuplicateVersion)
    Term.Map(entries.sortBy(_._1).map { case (v, d) => (versionTerm(v), d) })
  private def text(t: Term)(using ProtocolWire.Scope): String = t match
    case Term.Text(s, true) if s.getBytes(StandardCharsets.UTF_8).length <= MaxRefusalBytes => s
    case _ => bad(ProtocolError.InvalidOrOversizedRefusalText)
  private def reason[V: VersionCodec](term: Term)(using ProtocolWire.Scope): WireReason[V] =
    arr(term) match
      case Vector(Term.Integer(tag), versions) if tag == 0 =>
        val all = arr(versions)
        WireReason.VersionMismatch(
          all.flatMap(logical[V]),
          all.collect { case t @ Term.Integer(n) if logical[V](t).isEmpty => n }
        )
      case Vector(Term.Integer(tag), v, s) if tag == 1 =>
        WireReason.DecodeError(known[V](v), text(s))
      case Vector(Term.Integer(tag), v, s) if tag == 2 =>
        WireReason.Refused(known[V](v), text(s))
      case _ => bad(ProtocolError.InvalidRefusalTagOrArity)
  private def reasonTerm[V: VersionCodec](r: WireReason[V])(using ProtocolWire.Scope): Term =
    r match
      case WireReason.VersionMismatch(vs, _) =>
        Term.Arr(Vector(Term.Integer(0), Term.Arr(vs.map(versionTerm(_)))))
      case WireReason.DecodeError(v, s) =>
        text(Term.Text(s)); Term.Arr(Vector(Term.Integer(1), versionTerm(v), Term.Text(s)))
      case WireReason.Refused(v, s) =>
        text(Term.Text(s)); Term.Arr(Vector(Term.Integer(2), versionTerm(v), Term.Text(s)))
  private def message[V: VersionCodec: Ordering](state: State, term: Term)(using
      ProtocolWire.Scope
  ): WireMessage[V] =
    if state == State.Done then bad(ProtocolError.HandshakeAlreadyDone)
    arr(term) match
      case Vector(Term.Integer(tag), map) if tag == 0 => WireMessage.Propose(versionMap[V](map))
      case Vector(Term.Integer(tag), v, d) if tag == 1 && state == State.Confirm =>
        WireMessage.Accept(known[V](v), d)
      case Vector(Term.Integer(tag), r) if tag == 2 && state == State.Confirm =>
        WireMessage.Refuse(reason[V](r))
      case Vector(Term.Integer(tag), map) if tag == 3 && state == State.Confirm =>
        WireMessage.QueryReply(versionMap[V](map))
      case _ => bad(ProtocolError.UnexpectedHandshakeTagArityOrState)
  def encode[V: VersionCodec: Ordering](msg: WireMessage[V]): Either[ProtocolError, Bytes] =
    attempt {
      val term = msg match
        case WireMessage.Propose(vs)    => Term.Arr(Vector(Term.Integer(0), mapTerm(vs)))
        case WireMessage.Accept(v, d)   => Term.Arr(Vector(Term.Integer(1), versionTerm(v), d))
        case WireMessage.Refuse(r)      => Term.Arr(Vector(Term.Integer(2), reasonTerm(r)))
        case WireMessage.QueryReply(vs) => Term.Arr(Vector(Term.Integer(3), mapTerm(vs)))
      encodeTerm(term)
    }
  def decodePrefix[V: VersionCodec: Ordering](
      state: State,
      bytes: Bytes
  ): Either[DecodeError, (WireMessage[V], Bytes)] =
    new Parser(bytes)
      .term(0)
      .run(Cursor(0, 0))
      .flatMap { case (cursor, term) =>
        attempt((message[V](state, term), bytes.drop(cursor.position)))
      }
      .left
      .map(e => DecodeError(e, e.incomplete))
  def decode[V: VersionCodec: Ordering](
      state: State,
      bytes: Bytes
  ): Either[DecodeError, WireMessage[V]] =
    decodePrefix[V](state, bytes).flatMap { case (m, rest) =>
      if rest.size == 0 then Right(m) else Left(DecodeError(ProtocolError.TrailingBytes))
    }

  def encode(suite: Suite, msg: Message): Either[ProtocolError, Bytes] = profile(suite).encode(msg)
  def decodePrefix(
      suite: Suite,
      state: State,
      bytes: Bytes
  ): Either[DecodeError, (Message, Bytes)] =
    profile(suite).decodePrefix(state, bytes)
  def decode(suite: Suite, state: State, bytes: Bytes): Either[DecodeError, Message] =
    decodePrefix(suite, state, bytes).flatMap { case (m, rest) =>
      if rest.size == 0 then Right(m) else Left(DecodeError(ProtocolError.TrailingBytes))
    }

  def encodeReason[V: VersionCodec](value: WireReason[V]): Either[ProtocolError, Bytes] =
    attempt(encodeTerm(reasonTerm(value)))
  def decodeReason[V: VersionCodec](bytes: Bytes): Either[DecodeError, WireReason[V]] =
    new Parser(bytes)
      .term(0)
      .run(Cursor(0, 0))
      .flatMap { case (cursor, term) =>
        if cursor.position != bytes.size then Left(ProtocolError.TrailingBytes)
        else attempt(reason[V](term))
      }
      .left
      .map(e => DecodeError(e, e.incomplete))

  private trait CardanoProfile:
    def encode(msg: Message): Either[ProtocolError, Bytes]
    def decodePrefix(state: State, bytes: Bytes): Either[DecodeError, (Message, Bytes)]
    def negotiate(offers: Vector[(Int, Data)], remote: Vector[(Int, Term)]): Result

  private def cardanoProfile[V: VersionCodec: Ordering](
      suite: Suite,
      fromNumber: Int => Option[V],
      number: V => Int
  ): CardanoProfile = new CardanoProfile:
    private def version(n: Int): Either[ProtocolError, V] =
      fromNumber(n).toRight(ProtocolError.UnsupportedLogicalVersion)
    private def typedReason(r: Reason): Either[ProtocolError, WireReason[V]] = r match
      case Reason.VersionMismatch(vs, unknown) =>
        vs.traverse(version).map(WireReason.VersionMismatch(_, unknown))
      case Reason.DecodeError(v, s) =>
        attempt(text(Term.Text(s))).flatMap(_ => version(v)).map(WireReason.DecodeError(_, s))
      case Reason.Refused(v, s) =>
        attempt(text(Term.Text(s))).flatMap(_ => version(v)).map(WireReason.Refused(_, s))
    private def untypedReason(r: WireReason[V]): Reason = r match
      case WireReason.VersionMismatch(vs, unknown) =>
        Reason.VersionMismatch(vs.map(number), unknown)
      case WireReason.DecodeError(v, s) => Reason.DecodeError(number(v), s)
      case WireReason.Refused(v, s)     => Reason.Refused(number(v), s)
    private def typedEntries(vs: Vector[(Int, Term)]): Either[ProtocolError, Vector[(V, Term)]] =
      if vs.map(_._1).distinct.size != vs.size then Left(ProtocolError.DuplicateVersion)
      else vs.traverse((v, t) => version(v).map(_ -> t))
    private def typed(msg: Message): Either[ProtocolError, WireMessage[V]] = msg match
      case Message.Propose(vs)    => typedEntries(vs).map(WireMessage.Propose(_))
      case Message.Accept(v, d)   => version(v).map(WireMessage.Accept(_, d))
      case Message.Refuse(r)      => typedReason(r).map(WireMessage.Refuse(_))
      case Message.QueryReply(vs) => typedEntries(vs).map(WireMessage.QueryReply(_))
    private def untyped(msg: WireMessage[V]): Message = msg match
      case WireMessage.Propose(vs)    => Message.Propose(vs.map((v, t) => number(v) -> t))
      case WireMessage.Accept(v, d)   => Message.Accept(number(v), d)
      case WireMessage.Refuse(r)      => Message.Refuse(untypedReason(r))
      case WireMessage.QueryReply(vs) => Message.QueryReply(vs.map((v, t) => number(v) -> t))
    def encode(msg: Message): Either[ProtocolError, Bytes] = typed(msg).flatMap(Handshake.encode[V])
    def decodePrefix(state: State, bytes: Bytes): Either[DecodeError, (Message, Bytes)] =
      Handshake.decodePrefix[V](state, bytes).map((msg, rest) => (untyped(msg), rest))
    private given DataCodec[V, Data, ProtocolError] with
      def decode(version: V, term: Term): Either[ProtocolError, Data] =
        decodeData(suite, number(version), term)
      def agree(local: Data, remote: Data): Either[ProtocolError, Data] =
        Handshake.agree(local, remote)
      def refusalText(error: ProtocolError): String = error.message
    def negotiate(offers: Vector[(Int, Data)], remote: Vector[(Int, Term)]): Result =
      // Preserve the integer facade's explicit diagnostic for a manually supplied unsupported
      // highest version. Normal network input already belongs to the closed profile.
      val common = remote.filter((v, _) => offers.exists(_._1 == v))
      common.maxByOption(_._1) match
        case None => Result.Rejected(Reason.VersionMismatch(offers.map(_._1).sorted))
        case Some((v, _)) if fromNumber(v).isEmpty =>
          Result.Rejected(Reason.DecodeError(v, ProtocolError.UnsupportedLogicalVersion.message))
        case Some(_) =>
          val local = offers.flatMap((v, d) => fromNumber(v).map(_ -> d))
          val proposed = remote.flatMap((v, t) => fromNumber(v).map(_ -> t))
          acceptOrRefuse(local, proposed).fold(
            error => Result.Rejected(untypedReason(refusal(error))),
            (v, d) => Result.Negotiated(number(v), d)
          )

  private val nodeToNodeProfile = cardanoProfile(Suite.NodeToNode, NtnVersion.fromNumber, _.number)
  private val nodeToClientProfile =
    cardanoProfile(Suite.NodeToClient, NtcVersion.fromNumber, _.number)
  private def profile(suite: Suite): CardanoProfile = suite match
    case Suite.NodeToNode   => nodeToNodeProfile
    case Suite.NodeToClient => nodeToClientProfile

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

  /** Version-data policy for one version domain. Errors stay typed until wire presentation. */
  trait DataCodec[V, A, E]:
    def decode(version: V, term: Term): Either[E, A]
    def agree(local: A, remote: A): Either[E, A]
    def refusalText(error: E): String

  enum SelectionError[V, +E]:
    case VersionMismatch(versions: Vector[V])
    case DecodeError(version: V, cause: E)
    case Refused(version: V, cause: E)

  /** Convert a typed selection failure into its wire diagnostic at the handshake boundary. */
  def refusal[V, A, E](error: SelectionError[V, E])(using
      codec: DataCodec[V, A, E]
  ): WireReason[V] =
    error match
      case SelectionError.VersionMismatch(vs) => WireReason.VersionMismatch(vs)
      case SelectionError.DecodeError(v, cause) =>
        WireReason.DecodeError(v, codec.refusalText(cause))
      case SelectionError.Refused(v, cause) => WireReason.Refused(v, codec.refusalText(cause))

  /** Highest-common-version selection, shared by the Cardano facade and mock-domain adapters.
    * Mirrors Handshake/Client.hs:acceptOrRefuse at upstream 2fb69829. A failed highest candidate
    * never falls back to a lower version.
    */
  def acceptOrRefuse[V: Ordering, A, E](
      offers: Vector[(V, A)],
      remote: Vector[(V, Term)]
  )(using codec: DataCodec[V, A, E]): Either[SelectionError[V, E], (V, A)] =
    val common = remote.filter { case (v, _) => offers.exists(_._1 == v) }
    common.maxByOption(_._1) match
      case None => Left(SelectionError.VersionMismatch(offers.map(_._1).sorted))
      case Some((v, term)) =>
        codec.decode(v, term).left.map(e => SelectionError.DecodeError(v, e)).flatMap { decoded =>
          offers.find(_._1 == v) match
            case None => Left(SelectionError.VersionMismatch(offers.map(_._1).sorted))
            case Some((_, own)) =>
              codec.agree(own, decoded).left.map(e => SelectionError.Refused(v, e)).map(v -> _)
        }

  private def negotiate(
      suite: Suite,
      offers: Vector[(Int, Data)],
      remote: Vector[(Int, Term)]
  ): Result = profile(suite).negotiate(offers, remote)
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
