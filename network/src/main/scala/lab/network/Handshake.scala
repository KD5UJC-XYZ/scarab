// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

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
    case QueryResult(versions: Vector[(Int, Either[String, Data])])
    case Rejected(reason: Reason)
  final case class DecodeError(message: String, incomplete: Boolean = false)
  private final case class Failure(error: DecodeError) extends RuntimeException(error.message)
  private def bad(message: String): Nothing = throw Failure(DecodeError(message))
  private def attempt[A](f: => A): Either[String, A] =
    try Right(f)
    catch case Failure(e) => Left(e.message)

  def recognized(suite: Suite, version: Int): Boolean = suite match
    case Suite.NodeToNode   => version >= 14 && version <= 16
    case Suite.NodeToClient => version >= 16 && version <= 23
  def wireVersion(suite: Suite, version: Int): Either[String, Int] =
    if !recognized(suite, version) then Left("unsupported logical version")
    else Right(if suite == Suite.NodeToClient then version | 0x8000 else version)
  private def logical(suite: Suite, term: Term): Option[Int] = term match
    case Term.Integer(n) if n.isValidInt =>
      val wire = n.toInt
      val v = if suite == Suite.NodeToClient then wire ^ 0x8000 else wire
      Option.when(recognized(suite, v))(v)
    case _ => None
  private def known(suite: Suite, term: Term): Int =
    logical(suite, term).getOrElse(bad("unrecognized selected version"))
  private def versionTerm(suite: Suite, v: Int): Term =
    Term.Integer(wireVersion(suite, v).fold(bad, identity))
  private def arr(t: Term): Vector[Term] = t match
    case Term.Arr(v, true) => v
    case _                 => bad("expected definite array")
  private def bool(t: Term): Boolean = t match
    case Term.Bool(b) => b
    case _            => bad("expected boolean")
  private def magic(t: Term): Long = t match
    case Term.Integer(n) if n >= 0 && n <= BigInt("ffffffff", 16) => n.toLong
    case _ => bad("network magic outside uint32")

  def dataTerm(suite: Suite, version: Int, data: Data): Either[String, Term] = attempt {
    if !recognized(suite, version) then bad("unsupported logical version")
    if data.magic < 0 || data.magic > 0xffffffffL then bad("network magic outside uint32")
    if suite == Suite.NodeToClient then
      if !data.initiatorOnly || data.peerSharing || data.peras then bad("NtC has no NtN features")
      Term.Arr(Vector(Term.Integer(data.magic), Term.Bool(data.query)))
    else
      if version < 16 && data.peras then bad("Peras requires version 16")
      Term.Arr(
        Vector(
          Term.Integer(data.magic),
          Term.Bool(data.initiatorOnly),
          Term.Integer(if data.peerSharing then 1 else 0),
          Term.Bool(data.query)
        ) ++
          (if version == 16 then Vector(Term.Bool(data.peras)) else Vector.empty)
      )
  }
  def decodeData(suite: Suite, version: Int, term: Term): Either[String, Data] = attempt {
    if !recognized(suite, version) then bad("unsupported logical version")
    val fields = arr(term)
    if suite == Suite.NodeToClient then
      if fields.size != 2 then bad("NtC data arity")
      Data(magic(fields(0)), query = bool(fields(1)))
    else
      if fields.size != (if version == 16 then 5 else 4) then bad("NtN data arity")
      val peers = fields(2) match
        case Term.Integer(n) if n == 0 || n == 1 => n == 1
        case _                                   => bad("peer sharing must be 0 or 1")
      Data(
        magic(fields(0)),
        bool(fields(1)),
        peers,
        bool(fields(3)),
        if version == 16 then bool(fields(4)) else false
      )
  }
  private def agree(local: Data, remote: Data): Either[String, Data] =
    if local.magic != remote.magic then Left("network magic mismatch")
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

  private def versionMap(suite: Suite, term: Term): Vector[(Int, Term)] = term match
    case Term.Map(entries, true) =>
      var previous = -1
      entries.flatMap { case (key, data) =>
        logical(suite, key).map { v =>
          if v <= previous then bad("recognized versions must be distinct and increasing")
          previous = v
          (v, data)
        }
      }
    case _ => bad("expected definite version map")
  private def mapTerm(suite: Suite, entries: Vector[(Int, Term)]): Term =
    if entries.map(_._1).distinct.size != entries.size then bad("duplicate version")
    Term.Map(entries.sortBy(_._1).map { case (v, d) => (versionTerm(suite, v), d) })
  private def text(t: Term): String = t match
    case Term.Text(s, true) if s.getBytes(StandardCharsets.UTF_8).length <= MaxRefusalBytes => s
    case _ => bad("invalid or oversized refusal text")
  private def reason(suite: Suite, term: Term): Reason = arr(term) match
    case Vector(Term.Integer(tag), versions) if tag == 0 =>
      val all = arr(versions)
      Reason.VersionMismatch(
        all.flatMap(logical(suite, _)),
        all.collect {
          case t @ Term.Integer(n) if logical(suite, t).isEmpty => n
        }
      )
    case Vector(Term.Integer(tag), v, s) if tag == 1 => Reason.DecodeError(known(suite, v), text(s))
    case Vector(Term.Integer(tag), v, s) if tag == 2 => Reason.Refused(known(suite, v), text(s))
    case _                                           => bad("invalid refusal tag or arity")
  private def reasonTerm(suite: Suite, r: Reason): Term = r match
    case Reason.VersionMismatch(vs, _) =>
      Term.Arr(Vector(Term.Integer(0), Term.Arr(vs.map(versionTerm(suite, _)))))
    case Reason.DecodeError(v, s) =>
      text(Term.Text(s)); Term.Arr(Vector(Term.Integer(1), versionTerm(suite, v), Term.Text(s)))
    case Reason.Refused(v, s) =>
      text(Term.Text(s)); Term.Arr(Vector(Term.Integer(2), versionTerm(suite, v), Term.Text(s)))
  private def message(suite: Suite, state: State, term: Term): Message =
    if state == State.Done then bad("handshake already done")
    arr(term) match
      case Vector(Term.Integer(tag), map) if tag == 0 => Message.Propose(versionMap(suite, map))
      case Vector(Term.Integer(tag), v, d) if tag == 1 && state == State.Confirm =>
        Message.Accept(known(suite, v), d)
      case Vector(Term.Integer(tag), r) if tag == 2 && state == State.Confirm =>
        Message.Refuse(reason(suite, r))
      case Vector(Term.Integer(tag), map) if tag == 3 && state == State.Confirm =>
        Message.QueryReply(versionMap(suite, map))
      case _ => bad("unexpected handshake tag, arity, or state")
  def encode(suite: Suite, msg: Message): Either[String, Bytes] = attempt {
    val term = msg match
      case Message.Propose(vs)    => Term.Arr(Vector(Term.Integer(0), mapTerm(suite, vs)))
      case Message.Accept(v, d)   => Term.Arr(Vector(Term.Integer(1), versionTerm(suite, v), d))
      case Message.Refuse(r)      => Term.Arr(Vector(Term.Integer(2), reasonTerm(suite, r)))
      case Message.QueryReply(vs) => Term.Arr(Vector(Term.Integer(3), mapTerm(suite, vs)))
    encodeTerm(term)
  }
  def decodePrefix(
      suite: Suite,
      state: State,
      bytes: Bytes
  ): Either[DecodeError, (Message, Bytes)] =
    try
      val parser = new Parser(bytes)
      val term = parser.term(0)
      Right((message(suite, state, term), Bytes(bytes.value.drop(parser.position))))
    catch case Failure(e) => Left(e)
  def decode(suite: Suite, state: State, bytes: Bytes): Either[DecodeError, Message] =
    decodePrefix(suite, state, bytes).flatMap { case (m, rest) =>
      if rest.size == 0 then Right(m) else Left(DecodeError("trailing bytes"))
    }

  def proposal(suite: Suite, offers: Vector[(Int, Data)]): Either[String, Message] =
    offers
      .foldLeft[Either[String, Vector[(Int, Term)]]](Right(Vector.empty)) { (acc, entry) =>
        for xs <- acc; d <- dataTerm(suite, entry._1, entry._2) yield xs :+ (entry._1 -> d)
      }
      .flatMap(vs =>
        if vs.map(_._1).distinct.size != vs.size then Left("duplicate version")
        else Right(Message.Propose(vs.sortBy(_._1)))
      )
  def defaultNodeToNode(magic: Long): Vector[(Int, Data)] = Vector(14 -> Data(magic))
  private def queryResult(suite: Suite, vs: Vector[(Int, Term)]): Result =
    Result.QueryResult(vs.map { case (v, d) => v -> decodeData(suite, v, d) })
  private def negotiate(
      suite: Suite,
      offers: Vector[(Int, Data)],
      remote: Vector[(Int, Term)]
  ): Result =
    val common = remote.filter { case (v, _) => offers.exists(_._1 == v) }
    if common.isEmpty then Result.Rejected(Reason.VersionMismatch(offers.map(_._1).sorted))
    else
      val (v, term) = common.maxBy(_._1)
      decodeData(suite, v, term) match
        case Left(e) => Result.Rejected(Reason.DecodeError(v, e))
        case Right(d) =>
          agree(offers.find(_._1 == v).get._2, d) match
            case Left(e)       => Result.Rejected(Reason.Refused(v, e))
            case Right(agreed) => Result.Negotiated(v, agreed)
  final case class Client private[Handshake] (
      suite: Suite,
      offers: Vector[(Int, Data)],
      state: State
  ):
    def receive(msg: Message): Either[String, (Client, Result)] =
      if state != State.Confirm then Left("client is not awaiting confirmation")
      else
        val result: Either[String, Result] = msg match
          case Message.Propose(vs)    => Right(negotiate(suite, offers, vs))
          case Message.QueryReply(vs) => Right(queryResult(suite, vs))
          case Message.Refuse(r)      => Right(Result.Rejected(r))
          case Message.Accept(v, t) =>
            offers.find(_._1 == v) match
              case None => Left("server selected a version not offered")
              case Some((_, local)) =>
                decodeData(suite, v, t).flatMap(agree(local, _)).map(Result.Negotiated(v, _))
        result.map(r => (copy(state = State.Done), r))
  def clientStart(suite: Suite, offers: Vector[(Int, Data)]): Either[String, (Client, Message)] =
    proposal(suite, offers).map(p => (Client(suite, offers, State.Confirm), p))
  def responder(
      suite: Suite,
      offers: Vector[(Int, Data)],
      msg: Message
  ): Either[String, (Message, Result)] =
    proposal(suite, offers).flatMap { local =>
      msg match
        case Message.Propose(vs) =>
          negotiate(suite, offers, vs) match
            case r @ Result.Rejected(reason) => Right((Message.Refuse(reason), r))
            case Result.Negotiated(_, d) if d.query =>
              local match
                case Message.Propose(localVs) =>
                  Right((Message.QueryReply(localVs), queryResult(suite, vs)))
                case _ => Left("invalid local proposal")
            case r @ Result.Negotiated(v, d) =>
              dataTerm(suite, v, d).map(t => (Message.Accept(v, t), r))
            case _ => Left("invalid negotiation result")
        case _ => Left("responder expects proposal")
    }

  private final class Parser(input: Bytes):
    private var pos = 0
    private var items = 0
    def position: Int = pos
    private def read(): Int =
      if pos >= MaxMessageBytes then bad("handshake byte limit exceeded")
      if pos >= input.size then throw Failure(DecodeError("incomplete CBOR", incomplete = true))
      val b = input.value(pos) & 255
      pos += 1
      b
    private def peekBreak: Boolean = pos < input.size && (input.value(pos) & 255) == 255
    private def argument(ai: Int): BigInt =
      if ai < 24 then BigInt(ai)
      else
        val count = ai match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => bad("reserved CBOR argument")
        (0 until count).foldLeft(BigInt(0))((n, _) => (n << 8) | read())
    private def size(n: BigInt, bound: Int): Int =
      if n < 0 || n > bound then bad("CBOR resource limit exceeded")
      n.toInt
    private def utf8(bytes: Vector[Byte]): String =
      try
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toArray))
          .toString
      catch case NonFatal(_) => bad("invalid UTF-8")
    private def string(major: Int, ai: Int): Term =
      val out = Vector.newBuilder[Byte]
      var total = 0
      def chunk(a: Int): Unit =
        val n = size(argument(a), MaxStringBytes - total)
        total += n
        val data = Vector.fill(n)(read().toByte)
        if major == 3 then utf8(data)
        out ++= data
      if ai != 31 then chunk(ai)
      else
        while !peekBreak do
          items += 1
          if items > MaxItems then bad("CBOR item limit exceeded")
          val h = read()
          if h / 32 != major || (h & 31) == 31 then bad("invalid indefinite string chunk")
          chunk(h & 31)
        read()
      val bytes = out.result()
      if major == 2 then Term.ByteString(Bytes(bytes)) else Term.Text(utf8(bytes), ai != 31)
    def term(depth: Int): Term =
      if depth > MaxDepth then bad("CBOR depth limit exceeded")
      items += 1
      if items > MaxItems then bad("CBOR item limit exceeded")
      val h = read()
      val major = h / 32
      val ai = h & 31
      major match
        case 0     => Term.Integer(argument(ai))
        case 1     => Term.Integer(-1 - argument(ai))
        case 2 | 3 => string(major, ai)
        case 4 =>
          val out = Vector.newBuilder[Term]
          if ai == 31 then
            while !peekBreak do out += term(depth + 1)
            read()
          else
            val n = size(argument(ai), MaxItems - items)
            (0 until n).foreach(_ => out += term(depth + 1))
          Term.Arr(out.result(), ai != 31)
        case 5 =>
          val out = Vector.newBuilder[(Term, Term)]
          def pair(): Unit =
            val k = term(depth + 1)
            val v = term(depth + 1)
            out += ((k, v))
          if ai == 31 then
            while !peekBreak do pair()
            read()
          else
            val n = size(argument(ai), (MaxItems - items) / 2)
            (0 until n).foreach(_ => pair())
          Term.Map(out.result(), ai != 31)
        case 6 => Term.Tag(argument(ai), term(depth + 1))
        case 7 =>
          ai match
            case 20 => Term.Bool(false)
            case 21 => Term.Bool(true)
            case 25 | 26 | 27 =>
              Term.FloatBits(if ai == 25 then 2 else if ai == 26 then 4 else 8, argument(ai))
            case 31           => bad("unexpected break")
            case 28 | 29 | 30 => bad("reserved CBOR simple value")
            case 24 =>
              val n = read()
              if n < 32 then bad("invalid extended CBOR simple value")
              Term.Simple(n)
            case n => Term.Simple(n)

  private def encodeTerm(term: Term): Bytes =
    val out = new java.io.ByteArrayOutputStream()
    var items = 0
    def write(b: Int): Unit =
      if out.size() >= MaxMessageBytes then bad("handshake byte limit exceeded")
      out.write(b)
    def number(n: BigInt, width: Int): Unit =
      (0 until width).reverse.foreach(i => write(((n >> (i * 8)) & 255).toInt))
    def head(major: Int, n: BigInt): Unit =
      if n < 0 || n >= (BigInt(1) << 64) then bad("CBOR integer outside uint64")
      if n < 24 then write(major * 32 + n.toInt)
      else
        val width =
          if n <= 255 then 1 else if n <= 65535 then 2 else if n <= 0xffffffffL then 4 else 8
        write(
          major * 32 + (if width == 1 then 24
                        else if width == 2 then 25
                        else if width == 4 then 26
                        else 27)
        )
        number(n, width)
    def raw(major: Int, data: Array[Byte]): Unit =
      if data.length > MaxStringBytes then bad("string byte limit exceeded")
      head(major, data.length)
      data.foreach(b => write(b & 255))
    def go(t: Term, depth: Int): Unit =
      items += 1
      if items > MaxItems || depth > MaxDepth then bad("CBOR resource limit exceeded")
      t match
        case Term.Integer(n) => if n >= 0 then head(0, n) else head(1, -1 - n)
        case Term.Bool(b)    => write(if b then 245 else 244)
        case Term.Arr(vs, definite) =>
          if definite then head(4, vs.size) else write(159)
          vs.foreach(go(_, depth + 1))
          if !definite then write(255)
        case Term.Map(vs, definite) =>
          if definite then head(5, vs.size) else write(191)
          vs.foreach { case (k, v) => go(k, depth + 1); go(v, depth + 1) }
          if !definite then write(255)
        case Term.Text(s, definite) =>
          val bytes =
            try
              val b = StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(s))
              val a = new Array[Byte](b.remaining()); b.get(a); a
            catch case NonFatal(_) => bad("invalid Unicode text")
          if !definite then write(127)
          raw(3, bytes)
          if !definite then write(255)
        case Term.ByteString(b) => raw(2, b.toArray)
        case Term.Tag(n, t)     => head(6, n); go(t, depth + 1)
        case Term.Simple(n) =>
          if n < 0 || n > 255 || (n >= 20 && n <= 21) || (n >= 24 && n < 32) then
            bad("invalid simple value")
          if n < 24 then write(224 + n) else { write(248); write(n) }
        case Term.FloatBits(width, bits) =>
          if !Set(2, 4, 8).contains(width) || bits < 0 || bits >= (BigInt(1) << (width * 8)) then
            bad("invalid float bits")
          write(if width == 2 then 249 else if width == 4 then 250 else 251)
          number(bits, width)
    go(term, 0)
    Bytes.fromArray(out.toByteArray)
