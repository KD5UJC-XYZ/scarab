// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, Tree, TreeFold}
import xyz.kd5ujc.scarab.codec.Tree.{treeEquals, treeHash, treeString}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import cats.syntax.all.*

/** Handshake-only source conformance to ouroboros-network c45735a56c567fa977969173d18943bac6bb3821.
  * Independently implemented from Protocol/Handshake/{Codec,Client,Server}.hs and Cardano version
  * codecs. A message is bounded as the reference bounds it, at [[MaxMessageBytes]]
  * (`byteLimitsHandshake`, `Codec.hs:54-68`), and by nothing else: the generic terms and strings it
  * reads (`CBOR.decodeTerm` and `CBOR.decodeString`, `Codec.hs:152-195`, `217-243`, `264-288`) have
  * no depth, count or size of their own (cborg's `decodeTerm`,
  * `cborg/src/Codec/CBOR/Term.hs:126-198`, cborg 0.2.10.0, the version ouroboros-network 1.2.0.0
  * builds with). A term is parsed on a stack on the heap, so one nested as deep as the message is
  * long decodes on any thread's stack (ADR 0011). Unknown map entries are parsed as generic CBOR
  * before being skipped. A refusal's reason is read as `decodeRefuseReason` reads it
  * (`Codec.hs:264-288`): its declared length is read and ignored, and its tag names its items.
  */
object Handshake:
  val MaxMessageBytes = 5760
  val DefaultPhaseTimeout: FiniteDuration = 10.seconds

  enum Suite:
    case NodeToNode, NodeToClient
  enum State:
    case Propose, Confirm, Done

  /** A generic CBOR term as cborg's `Term` holds one (`cborg/src/Codec/CBOR/Term.hs:64-82`, cborg
    * 0.2.10.0): an indefinite string keeps its chunks, as `TStringI` and `TBytesI` do, so that it
    * is written again as cborg's `encodeTerm` writes it. Its chunks are never empty: they are read
    * into a lazy `Text` or `ByteString` (`Term.hs:203-216`), whose `chunk` drops an empty one
    * (`Data/Text/Internal/Lazy.hs:91-94`, text 2.1.3; `Data/ByteString/Lazy/Internal.hs:211-213`,
    * bytestring 0.12.2.0). `TextChunks` and `ByteChunks` are the indefinite text and byte strings;
    * two of either are equal when their contents are, whatever their chunks, as two lazy strings
    * are (`Data/Text/Internal/Lazy.hs:138-149`; `Data/ByteString/Lazy/Internal.hs:261-268`).
    */
  enum Term:
    case Integer(value: BigInt)
    case Bool(value: Boolean)
    case Arr(values: Vector[Term], definite: Boolean = true)
    case Map(values: Vector[(Term, Term)], definite: Boolean = true)
    case Text(value: String)
    case TextChunks(chunks: Vector[String])
    case ByteString(value: Bytes)
    case ByteChunks(chunks: Vector[Bytes])
    case Tag(number: BigInt, value: Term)
    case Simple(value: Int)
    case FloatBits(width: Int, bits: BigInt)

    // Structural and stack-safe: a term nests as deep as its message is long.
    override def equals(that: Any): Boolean = that match
      case o: Term => this.treeEquals(o)
      case _       => false
    override def hashCode(): Int = this.treeHash
    override def toString: String = this.treeString

  private def joined(chunks: Vector[Bytes]): Bytes = chunks.foldLeft(Bytes.empty)(_ ++ _)

  private given Tree[Term] with
    def children(t: Term): IndexedSeq[Term] = t match
      case Term.Arr(values, _) => values
      case Term.Map(values, _) => TreeFold.flatten(values)
      case Term.Tag(_, value)  => Vector(value)
      case _                   => Vector.empty
    def sameLabel(x: Term, y: Term): Boolean = (x, y) match
      case (Term.Integer(a), Term.Integer(b))           => a == b
      case (Term.Bool(a), Term.Bool(b))                 => a == b
      case (Term.Arr(_, a), Term.Arr(_, b))             => a == b
      case (Term.Map(_, a), Term.Map(_, b))             => a == b
      case (Term.Text(a), Term.Text(b))                 => a == b
      case (Term.TextChunks(a), Term.TextChunks(b))     => a.mkString == b.mkString
      case (Term.ByteString(a), Term.ByteString(b))     => a == b
      case (Term.ByteChunks(a), Term.ByteChunks(b))     => joined(a) == joined(b)
      case (Term.Tag(a, _), Term.Tag(b, _))             => a == b
      case (Term.Simple(a), Term.Simple(b))             => a == b
      case (Term.FloatBits(w, a), Term.FloatBits(v, b)) => w == v && a == b
      case _                                            => false
    def labelHash(t: Term): Int = t match
      case Term.Integer(a)      => 41 + a.hashCode
      case Term.Bool(a)         => if a then 43 else 47
      case Term.Arr(_, d)       => if d then 53 else 59
      case Term.Map(_, d)       => if d then 61 else 67
      case Term.Text(a)         => 71 + a.hashCode
      case Term.TextChunks(a)   => 97 + a.mkString.hashCode
      case Term.ByteString(a)   => 73 + a.hashCode
      case Term.ByteChunks(a)   => 101 + joined(a).hashCode
      case Term.Tag(a, _)       => 79 + a.hashCode
      case Term.Simple(a)       => 83 + a
      case Term.FloatBits(w, a) => 89 + 31 * w + a.hashCode
    def name(t: Term): String =
      def form(definite: Boolean) = if definite then "" else "(indefinite)"
      t match
        case Term.Integer(a)      => s"Integer($a)"
        case Term.Bool(a)         => s"Bool($a)"
        case Term.Arr(_, d)       => s"Arr${form(d)}"
        case Term.Map(_, d)       => s"Map${form(d)}"
        case Term.Text(a)         => s"Text(${Tree.shown(a)})"
        case Term.TextChunks(a)   => s"TextChunks(${a.size} chunks, ${Tree.shown(a.mkString)})"
        case Term.ByteString(a)   => s"ByteString(${Tree.shown(a)})"
        case Term.ByteChunks(a)   => s"ByteChunks(${a.size} chunks, ${Tree.shown(joined(a))})"
        case Term.Tag(a, _)       => s"Tag($a)"
        case Term.Simple(a)       => s"Simple($a)"
        case Term.FloatBits(w, a) => s"FloatBits($w, $a)"

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
      if fields.sizeIs != 2 then bad(ProtocolError.NtCDataArity)
      Data(magic(fields(0)), query = bool(fields(1)))
    else
      if fields.sizeIs != (if carriesPeras(version) then 5 else 4) then
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
    if entries.map(_._1).distinct.sizeIs != entries.size then bad(ProtocolError.DuplicateVersion)
    Term.Map(entries.sortBy(_._1).map { case (v, d) => (versionTerm(v), d) })

  /** `decodeString` (`Codec.hs:282`, `287`, ouroboros-network c45735a5): a definite text of any
    * length within the message.
    */
  private def text(t: Term)(using ProtocolWire.Scope): String = t match
    case Term.Text(s) => s
    case _            => bad(ProtocolError.InvalidOrOversizedRefusalText)
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
        Term.Arr(Vector(Term.Integer(1), versionTerm(v), Term.Text(s)))
      case WireReason.Refused(v, s) =>
        Term.Arr(Vector(Term.Integer(2), versionTerm(v), Term.Text(s)))
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
      .message(state)
      .flatMap((end, term) => attempt((message[V](state, term), bytes.drop(end))))
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
      .reason(0)
      .flatMap((end, term) =>
        if end != bytes.size then Left(ProtocolError.TrailingBytes) else attempt(reason[V](term))
      )
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
      case Reason.DecodeError(v, s) => version(v).map(WireReason.DecodeError(_, s))
      case Reason.Refused(v, s)     => version(v).map(WireReason.Refused(_, s))
    private def untypedReason(r: WireReason[V]): Reason = r match
      case WireReason.VersionMismatch(vs, unknown) =>
        Reason.VersionMismatch(vs.map(number), unknown)
      case WireReason.DecodeError(v, s) => Reason.DecodeError(number(v), s)
      case WireReason.Refused(v, s)     => Reason.Refused(number(v), s)
    private def typedEntries(vs: Vector[(Int, Term)]): Either[ProtocolError, Vector[(V, Term)]] =
      if vs.map(_._1).distinct.sizeIs != vs.size then Left(ProtocolError.DuplicateVersion)
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
        if vs.map(_._1).distinct.sizeIs != vs.size then Left(ProtocolError.DuplicateVersion)
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
    * Mirrors Handshake/Client.hs:acceptOrRefuse at upstream c45735a5. A failed highest candidate
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

  /** A container the parser has opened and not yet closed: the items read so far, and how many it
    * still needs (a definite one) or that a break closes it (an indefinite one).
    */
  private enum Open:
    case Items(remaining: Int, done: Vector[Term])
    case OpenItems(done: Vector[Term])
    case Entries(remaining: Int, done: Vector[(Term, Term)], key: Option[Term])
    case OpenEntries(done: Vector[(Term, Term)], key: Option[Term])
    case Tagged(number: BigInt)

  /** What one head starts: a whole term, or a container whose items follow it. */
  private enum Start:
    case Whole(term: Term)
    case Container(open: Open)

  /** A container with one more item: closed into its term, or still open. */
  private enum Added:
    case Closed(term: Term)
    case Still(open: Open)

  private def add(open: Open, item: Term): Added = open match
    case Open.Items(1, done)            => Added.Closed(Term.Arr(done :+ item))
    case Open.Items(remaining, done)    => Added.Still(Open.Items(remaining - 1, done :+ item))
    case Open.OpenItems(done)           => Added.Still(Open.OpenItems(done :+ item))
    case Open.Entries(n, done, None)    => Added.Still(Open.Entries(n, done, Some(item)))
    case Open.Entries(1, done, Some(k)) => Added.Closed(Term.Map(done :+ (k -> item)))
    case Open.Entries(n, done, Some(k)) =>
      Added.Still(Open.Entries(n - 1, done :+ (k -> item), None))
    case Open.OpenEntries(done, None)    => Added.Still(Open.OpenEntries(done, Some(item)))
    case Open.OpenEntries(done, Some(k)) => Added.Still(Open.OpenEntries(done :+ (k -> item), None))
    case Open.Tagged(number)             => Added.Closed(Term.Tag(number, item))

  /** Handshake negotiation is infrequent: the parser reads immutable input and keeps its open
    * containers in a list on the heap, so the depth of a term is bounded by the message's bytes (a
    * level takes one) and not by the thread's stack. A position at or past [[MaxMessageBytes]] is
    * refused as the driver refuses a message longer than its limit (`runDecoderWithLimit`,
    * `ouroboros-network/framework/lib/Ouroboros/Network/Driver/Limits.hs:295-331`, c45735a5).
    */
  private final class Parser(input: Bytes):
    private def byte(at: Int): Either[ProtocolError, Int] =
      if at >= MaxMessageBytes then Left(ProtocolError.HandshakeByteLimitExceeded)
      else input.lift(at).toRight(ProtocolError.IncompleteCbor).map(_ & 255)

    private def isBreak(at: Int): Boolean = input.lift(at).contains(0xff.toByte)

    /** The argument of a head whose additional information is `ai`, read from `at`: the position
      * after it and its value.
      */
    private def argument(ai: Int, at: Int): Either[ProtocolError, (Int, BigInt)] =
      if ai < 24 then Right((at, BigInt(ai)))
      else
        val width = ai match
          case 24 => Right(1)
          case 25 => Right(2)
          case 26 => Right(4)
          case 27 => Right(8)
          case _  => Left(ProtocolError.ReservedCBORArgument)
        @tailrec def go(width: Int, i: Int, value: BigInt): Either[ProtocolError, (Int, BigInt)] =
          if i == width then Right((at + width, value))
          else
            byte(at + i) match
              case Left(error) => Left(error)
              case Right(b)    => go(width, i + 1, (value << 8) | b)
        width.flatMap(go(_, 0, BigInt(0)))

    /** A declared count of items of at least `per` bytes each, from `at`: one the message cannot
      * hold could never be read within its bound, and is refused at once.
      */
    private def fits(count: BigInt, per: Int, at: Int): Either[ProtocolError, Int] =
      if count * per > MaxMessageBytes - at then Left(ProtocolError.HandshakeByteLimitExceeded)
      else Right(count.toInt)

    private def utf8(bytes: Bytes): Either[ProtocolError, String] =
      Either
        .catchOnly[CharacterCodingException](
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toArray))
            .toString
        )
        .leftMap(_ => ProtocolError.InvalidUTF8)

    /** A definite string of `major` (each chunk of a text is UTF-8 itself). */
    private def chunk(major: Int, ai: Int, at: Int): Either[ProtocolError, (Int, Bytes)] =
      for
        (start, declared) <- argument(ai, at)
        length <- fits(declared, 1, start)
        _ <- Either.cond(length <= input.size - start, (), ProtocolError.IncompleteCbor)
        bytes = input.slice(start, start + length)
        _ <- if major == 3 then utf8(bytes).void else Right(())
      yield (start + length, bytes)

    /** A definite string, or an indefinite one as its chunks, the empty ones dropped as cborg's
      * `decodeBytesIndefLen` and `decodeStringIndefLen` drop them (`Term.hs:203-216`, cborg
      * 0.2.10.0).
      */
    private def string(major: Int, ai: Int, at: Int): Either[ProtocolError, (Int, Term)] =
      // the chunks of an indefinite string up to its break
      @tailrec def chunks(
          position: Int,
          collected: Vector[Bytes]
      ): Either[ProtocolError, (Int, Vector[Bytes])] =
        if isBreak(position) then byte(position).as((position + 1, collected))
        else
          val next = byte(position).flatMap { head =>
            if head / 32 != major || (head & 31) == 31 then
              Left(ProtocolError.InvalidIndefiniteStringChunk)
            else chunk(major, head & 31, position + 1)
          }
          next match
            case Left(error) => Left(error)
            case Right((after, bytes)) =>
              chunks(after, if bytes.isEmpty then collected else collected :+ bytes)
      if ai != 31 then
        chunk(major, ai, at).flatMap((end, bytes) =>
          if major == 2 then Right((end, Term.ByteString(bytes)))
          else utf8(bytes).map(text => (end, Term.Text(text)))
        )
      else
        chunks(at, Vector.empty).flatMap((end, parts) =>
          if major == 2 then Right((end, Term.ByteChunks(parts)))
          else parts.traverse(utf8).map(texts => (end, Term.TextChunks(texts)))
        )

    /** Major type 7 as cborg's `decodeTerm` reads it (`Term.hs:192-198`, cborg 0.2.10.0, with the
      * token types of `Read.hs:1492-1501`): `0xf8` and the byte after it are a simple value of any
      * value, those below 32 included (`tryConsumeSimple`, `Read.hs:2457`), and `0xf4` and `0xf5`
      * the booleans.
      */
    private def simple(ai: Int, at: Int): Either[ProtocolError, (Int, Term)] = ai match
      case 20 => Right((at, Term.Bool(false)))
      case 21 => Right((at, Term.Bool(true)))
      case 25 | 26 | 27 =>
        argument(ai, at).map((next, bits) =>
          (next, Term.FloatBits(if ai == 25 then 2 else if ai == 26 then 4 else 8, bits))
        )
      case 31           => Left(ProtocolError.UnexpectedBreak)
      case 28 | 29 | 30 => Left(ProtocolError.ReservedCBORSimpleValue)
      case 24           => byte(at).map(n => (at + 1, Term.Simple(n)))
      case n            => Right((at, Term.Simple(n)))

    /** The head at `at` and what it starts. */
    private def start(at: Int): Either[ProtocolError, (Int, Start)] =
      byte(at).flatMap { head =>
        val major = head / 32
        val ai = head & 31
        val after = at + 1
        def whole(read: Either[ProtocolError, (Int, Term)]) =
          read.map((next, term) => (next, Start.Whole(term)))
        major match
          case 0     => whole(argument(ai, after).map((next, n) => (next, Term.Integer(n))))
          case 1     => whole(argument(ai, after).map((next, n) => (next, Term.Integer(-1 - n))))
          case 2 | 3 => whole(string(major, ai, after))
          case 4 if ai == 31 => Right((after, Start.Container(Open.OpenItems(Vector.empty))))
          case 4 =>
            for
              (next, declared) <- argument(ai, after)
              n <- fits(declared, 1, next)
            yield
              if n == 0 then (next, Start.Whole(Term.Arr(Vector.empty)))
              else (next, Start.Container(Open.Items(n, Vector.empty)))
          case 5 if ai == 31 =>
            Right((after, Start.Container(Open.OpenEntries(Vector.empty, None))))
          case 5 =>
            for
              (next, declared) <- argument(ai, after)
              n <- fits(declared, 2, next)
            yield
              if n == 0 then (next, Start.Whole(Term.Map(Vector.empty)))
              else (next, Start.Container(Open.Entries(n, Vector.empty, None)))
          case 6 => argument(ai, after).map((next, n) => (next, Start.Container(Open.Tagged(n))))
          case _ => whole(simple(ai, after))
      }

    /** One term from `at`: the position after it, and the term. */
    def term(at: Int): Either[ProtocolError, (Int, Term)] =
      // `open`: the containers open around the position, innermost first; `read`: a term just
      // read, for the innermost to take
      @tailrec def go(
          position: Int,
          open: List[Open],
          read: Option[Term]
      ): Either[ProtocolError, (Int, Term)] = (read, open) match
        case (Some(term), Nil) => Right((position, term))
        case (Some(term), inner :: outer) =>
          add(inner, term) match
            case Added.Closed(closed) => go(position, outer, Some(closed))
            case Added.Still(still)   => go(position, still :: outer, None)
        case (None, Open.OpenItems(done) :: outer) if isBreak(position) =>
          byte(position) match
            case Left(error) => Left(error)
            case Right(_)    => go(position + 1, outer, Some(Term.Arr(done, false)))
        case (None, Open.OpenEntries(done, None) :: outer) if isBreak(position) =>
          byte(position) match
            case Left(error) => Left(error)
            case Right(_)    => go(position + 1, outer, Some(Term.Map(done, false)))
        case (None, _) =>
          start(position) match
            case Left(error)                             => Left(error)
            case Right((next, Start.Whole(term)))        => go(next, open, Some(term))
            case Right((next, Start.Container(started))) => go(next, started :: open, None)
      go(at, Nil, None)

    /** `count` terms from `at`, in order. */
    @tailrec private def terms(
        at: Int,
        count: Int,
        done: Vector[Term]
    ): Either[ProtocolError, (Int, Vector[Term])] =
      if done.sizeIs == count then Right((at, done))
      else
        term(at) match
          case Left(error)         => Left(error)
          case Right((next, item)) => terms(next, count, done :+ item)

    /** The length of the definite array whose head is at `at`, if the item there is one: the
      * position after its head and the length, read as cborg's `decodeListLen` reads it, into an
      * `Int` (`tryConsumeListLen`, `cborg/src/Codec/CBOR/Read.hs:1925-1964`, with `word64ToInt`,
      * `Codec/CBOR/Magic.hs:506-519`, cborg 0.2.10.0, on a 64-bit machine).
      */
    private def listLength(at: Int): Either[ProtocolError, Option[(Int, Long)]] =
      byte(at).flatMap { head =>
        if head / 32 != 4 || (head & 31) == 31 then Right(None)
        else
          argument(head & 31, at + 1).map((next, n) => Option.when(n.isValidLong)((next, n.toLong)))
      }

    /** A refusal's reason as `decodeRefuseReason` reads it (`Codec.hs:264-288`, ouroboros-network
      * c45735a5): the length of its array is read and ignored (268), then its tag (269), then the
      * items the tag names: the list of versions for 0 (271-277), a version and a text for 1 and 2
      * (278-287). An item the array declares beyond them is left to whatever follows. Any other tag
      * is refused (288) once the items its array declares are read, and an item that is no definite
      * array is read as a term and refused when the reason is checked.
      */
    def reason(at: Int): Either[ProtocolError, (Int, Term)] =
      listLength(at).flatMap {
        case None => term(at)
        case Some((next, declared)) =>
          def named(tag: Term, after: Int): Either[ProtocolError, Int] = tag match
            case Term.Integer(t) if t == 0           => Right(1)
            case Term.Integer(t) if t == 1 || t == 2 => Right(2)
            case _ => fits(BigInt(math.max(declared - 1, 0L)), 1, after)
          for
            (afterTag, tag) <- term(next)
            count <- named(tag, afterTag)
            (end, items) <- terms(afterTag, count, Vector.empty)
          yield (end, Term.Arr(tag +: items))
      }

    /** The message from the start of the input. In `StConfirm` a message of two items whose key is
      * 2 is a refusal (`Codec.hs:177-178`, ouroboros-network c45735a5), and its reason is read as
      * [[reason]] reads it; anything else is one term, checked when it is read as a message.
      */
    def message(state: State): Either[ProtocolError, (Int, Term)] =
      if state != State.Confirm then term(0)
      else
        listLength(0).flatMap {
          case Some((next, length)) if length == 2 =>
            term(next).flatMap {
              case (afterKey, key @ Term.Integer(k)) if k == 2 =>
                reason(afterKey).map((end, r) => (end, Term.Arr(Vector(key, r))))
              case (afterKey, key) =>
                term(afterKey).map((end, item) => (end, Term.Arr(Vector(key, item))))
            }
          case _ => term(0)
        }

  /** One step of writing a term: an item to write, or bytes that close an indefinite container. */
  private enum Pending:
    case Item(term: Term)
    case Raw(bytes: Vector[Byte])

  private def number(value: BigInt, width: Int): Vector[Byte] =
    (0 until width).reverseIterator.map(i => ((value >> (i * 8)) & 255).toByte).toVector

  private def headBytes(major: Int, n: BigInt): Either[ProtocolError, Vector[Byte]] =
    if n < 0 || n >= (BigInt(1) << 64) then Left(ProtocolError.CBORIntegerOutsideUint64)
    else if n < 24 then Right(Vector((major * 32 + n.toInt).toByte))
    else
      val width =
        if n <= 255 then 1 else if n <= 65535 then 2 else if n <= 0xffffffffL then 4 else 8
      val ai = if width == 1 then 24 else if width == 2 then 25 else if width == 4 then 26 else 27
      Right((major * 32 + ai).toByte +: number(n, width))

  private def unicode(value: String): Either[ProtocolError, Vector[Byte]] =
    Either
      .catchOnly[CharacterCodingException](
        StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(java.nio.CharBuffer.wrap(value))
      )
      .bimap(
        _ => ProtocolError.InvalidUnicodeText,
        encoded => Vector.tabulate(encoded.remaining())(i => encoded.get(i))
      )

  /** A definite text, as `encodeString` writes it. */
  private def writtenText(value: String): Either[ProtocolError, Vector[Byte]] =
    unicode(value).flatMap(bytes => headBytes(3, bytes.size).map(_ ++ bytes))

  /** A definite byte string, as `encodeBytes` writes it. */
  private def writtenBytes(bytes: Bytes): Either[ProtocolError, Vector[Byte]] =
    headBytes(2, bytes.size).map(_ ++ bytes.value)

  /** The bytes of one item's head (all of a string or a leaf), and the items under it, in order:
    * cborg's `encodeTerm` (`cborg/src/Codec/CBOR/Term.hs:90-121`, cborg 0.2.10.0). An indefinite
    * string is its chunks between `0x7f` or `0x5f` and a break, an empty one is no chunk at all
    * (`7f ff`), and an indefinite array or map its items between `0x9f` or `0xbf` and a break.
    */
  private def written(term: Term): Either[ProtocolError, (Vector[Byte], List[Pending])] =
    val break = Pending.Raw(Vector(0xff.toByte))
    term match
      case Term.Integer(n) =>
        (if n >= 0 then headBytes(0, n) else headBytes(1, -1 - n)).map(_ -> Nil)
      case Term.Bool(b) => Right(Vector((if b then 245 else 244).toByte) -> Nil)
      case Term.Arr(values, definite) =>
        val items = values.iterator.map(Pending.Item(_)).toList
        if definite then headBytes(4, values.size).map(_ -> items)
        else Right(Vector(0x9f.toByte) -> (items :+ break))
      case Term.Map(values, definite) =>
        val items =
          values.iterator.flatMap((k, v) => Iterator(Pending.Item(k), Pending.Item(v))).toList
        if definite then headBytes(5, values.size).map(_ -> items)
        else Right(Vector(0xbf.toByte) -> (items :+ break))
      case Term.Text(value) => writtenText(value).map(_ -> Nil)
      case Term.TextChunks(chunks) =>
        chunks
          .filter(_.nonEmpty)
          .flatTraverse(writtenText)
          .map(written => ((Vector(0x7f.toByte) ++ written) :+ 0xff.toByte) -> Nil)
      case Term.ByteString(bytes) => writtenBytes(bytes).map(_ -> Nil)
      case Term.ByteChunks(chunks) =>
        chunks
          .filter(_.nonEmpty)
          .flatTraverse(writtenBytes)
          .map(written => ((Vector(0x5f.toByte) ++ written) :+ 0xff.toByte) -> Nil)
      case Term.Tag(number, value) => headBytes(6, number).map(_ -> List(Pending.Item(value)))
      // encodeSimple (`Encoding.hs:335-336`, cborg 0.2.10.0) is written by `simpleMP`
      // (`Write.hs:563-566`): a value of at most 23 in the head, `0xe0 + n`, a larger one after
      // `0xf8`; so 20 and 21 are written as the booleans' heads and 22 and 23 as null's and
      // undefined's, as cborg writes them
      case Term.Simple(n) =>
        if n < 0 || n > 255 then Left(ProtocolError.InvalidSimpleValue)
        else if n <= 23 then Right(Vector((0xe0 + n).toByte) -> Nil)
        else Right(Vector(0xf8.toByte, n.toByte) -> Nil)
      case Term.FloatBits(width, bits) =>
        if !Set(2, 4, 8).contains(width) || bits < 0 || bits >= (BigInt(1) << (width * 8)) then
          Left(ProtocolError.InvalidFloatBits)
        else
          val initial = if width == 2 then 249 else if width == 4 then 250 else 251
          Right((initial.toByte +: number(bits, width)) -> Nil)

  /** A term in pre-order from a stack on the heap. Nothing bounds its bytes: the reference's driver
    * sends what the codec encodes (`sendMessage`,
    * `ouroboros-network/framework/lib/Ouroboros/Network/Driver/Limits.hs:109-117`, c45735a5) and
    * bounds a message on receipt only (`runDecoderWithLimit`, 295-331), as [[Parser]] does.
    */
  private def encodeTerm(term: Term)(using ProtocolWire.Scope): Bytes =
    @tailrec def go(
        pending: List[Pending],
        out: Vector[Byte]
    ): Either[ProtocolError, Vector[Byte]] =
      pending match
        case Nil => Right(out)
        case next :: rest =>
          val step = next match
            case Pending.Raw(bytes) => Right((bytes, Nil))
            case Pending.Item(item) => written(item)
          step match
            case Left(error)           => Left(error)
            case Right((bytes, items)) => go(items ::: rest, out ++ bytes)
    Bytes(ProtocolWire.get(go(List(Pending.Item(term)), Vector.empty)))
