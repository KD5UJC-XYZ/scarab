// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.io.Source
import xyz.kd5ujc.scarab.codec.Bytes

import Handshake.{WireMessage as Message, WireReason as Reason, State, Term}

/** Original mock domain from upstream Handshake/Test.hs:125–235, not Cardano versions. */
class GenericHandshakeUpstreamSuite extends munit.FunSuite:
  private case class Data(number: Long, conjunction: Boolean, disjunction: Boolean)
  private enum Version(val number: Int):
    case V0 extends Version(0)
    case V1 extends Version(1)
    case V2 extends Version(2)
  private object Version:
    given Ordering[Version] = Ordering.by(_.number)
    given Handshake.VersionCodec[Version] = versions()

  private def version(number: Int): Version =
    Version.values.find(_.number == number).getOrElse(fail(s"unknown mock version $number"))

  private enum DataError:
    case Unrecognized(version: Version, term: Term)
    case Incompatible

  private def versions(onlyOne: Boolean = false): Handshake.VersionCodec[Version] =
    new Handshake.VersionCodec[Version]:
      def decode(wire: Int): Option[Version] =
        Version.values.find(v => v.number == wire && (!onlyOne || v == Version.V1))
      def encode(v: Version): Either[ProtocolError, Int] =
        decode(v.number).map(_.number).toRight(ProtocolError.UnsupportedLogicalVersion)

  private given codec: Handshake.DataCodec[Version, Data, DataError] with
    def decode(version: Version, term: Term): Either[DataError, Data] = (version, term) match
      case (Version.V0, Term.Integer(n)) if n.isValidLong => Right(Data(n.toLong, false, false))
      case (Version.V1, Term.Arr(Vector(Term.Integer(n), Term.Bool(a)), true)) if n.isValidLong =>
        Right(Data(n.toLong, a, false))
      case (Version.V2, Term.Arr(Vector(Term.Integer(n), Term.Bool(a), Term.Bool(b)), true))
          if n.isValidLong =>
        Right(Data(n.toLong, a, b))
      case _ => Left(DataError.Unrecognized(version, term))
    def agree(local: Data, remote: Data): Either[DataError, Data] =
      Either.cond(
        local.number == remote.number,
        Data(
          local.number,
          local.conjunction && remote.conjunction,
          local.disjunction || remote.disjunction
        ),
        DataError.Incompatible
      )
    def refusalText(error: DataError): String = error match
      case DataError.Incompatible => "incompatible"
      case DataError.Unrecognized(version, term) =>
        val name = if version == Version.V2 then "Data" else "VersionData"
        s"decodeTerm $name: unrecognised tag: ${haskell(term)}"

  private def haskell(term: Term): String = term match
    case Term.Integer(n)    => if n < 0 then s"TInt ($n)" else s"TInt $n"
    case Term.Bool(b)       => s"TBool ${if b then "True" else "False"}"
    case Term.Arr(xs, true) => s"TList [${xs.map(haskell).mkString(",")}]"
    case other              => fail(s"unexpected generated data term $other")

  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def rows(name: String, hash: String): Vector[Vector[String]] =
    val stream = Option(getClass.getResourceAsStream(s"/upstream/generic-handshake/$name.tsv"))
      .getOrElse(fail(s"missing $name"))
    val raw =
      try stream.readAllBytes()
      finally stream.close()
    val actual =
      MessageDigest.getInstance("SHA-256").digest(raw).map(b => f"${b & 255}%02x").mkString
    assertEquals(actual, hash)
    Source
      .fromString(new String(raw, StandardCharsets.UTF_8))
      .getLines()
      .map(_.split("\\t", -1).toVector)
      .toVector

  private def state(value: String): State = value match
    case "propose" => State.Propose
    case "confirm" => State.Confirm
    case other     => fail(s"unknown state $other")

  private def receive(
      state: State,
      chunks: Vector[Bytes]
  ): Either[Handshake.DecodeError, Message[Version]] =
    chunks
      .foldLeft[Either[Handshake.DecodeError, (Bytes, Option[Message[Version]])]](
        Right((Bytes.empty, None))
      ) { (result, chunk) =>
        result.flatMap { case (pending, decoded) =>
          decoded match
            case Some(message) =>
              if chunk.size == 0 then Right((pending, Some(message)))
              else Left(Handshake.DecodeError(ProtocolError.TrailingBytes))
            case None =>
              val all = pending ++ chunk
              Handshake.decodePrefix[Version](state, all) match
                case Left(error) if error.incomplete => Right((all, None))
                case Left(error)                     => Left(error)
                case Right((message, rest)) =>
                  if rest.size == 0 then Right((rest, Some(message)))
                  else Left(Handshake.DecodeError(ProtocolError.TrailingBytes))
        }
      }
      .flatMap { case (_, message) =>
        message.toRight(Handshake.DecodeError(ProtocolError.IncompleteCbor, incomplete = true))
      }

  test("original ordinary Handshake codecs, valid CBOR and exhaustive two/three splits") {
    val cases = rows("codec", "73bee3eca337073174a058bd2edb912ac481241e9afc65235a52d9ea2cb888d9")
    assertEquals(cases.size, 48)
    cases.foreach { row =>
      assertEquals(row.size, 9)
      assert(row.drop(4).forall(_ == "pass"), s"original property verdicts ${row.take(2)}")
      val wire = bytes(row(3))
      val st = state(row(2))
      val decoded = right(Handshake.decode[Version](st, wire))
      assertEquals(right(Handshake.encode(decoded)), wire)
      (0 to wire.size).foreach { i =>
        assertEquals(right(receive(st, Vector(wire.take(i), wire.drop(i)))), decoded)
        (i to wire.size).foreach { j =>
          assertEquals(
            right(receive(st, Vector(wire.take(i), wire.drop(i).take(j - i), wire.drop(j)))),
            decoded
          )
        }
      }
      val sentinel = bytes("00")
      assertEquals(
        right(Handshake.decodePrefix[Version](st, wire ++ sentinel)),
        (decoded, sentinel)
      )
    }
  }

  test("original standalone RefuseReason codec preserves version lists and diagnostic bytes") {
    val cases = rows("refuse", "bf30a0f4f614a9c4e03e0d775a998b19c39ab941361e38f4b7f8d1407c68b667")
    assertEquals(cases.size, 24)
    cases.foreach { row =>
      assertEquals(row.size, 4)
      assertEquals(row(3), "pass")
      val wire = bytes(row(2))
      assertEquals(
        right(Handshake.encodeReason(right(Handshake.decodeReason[Version](wire)))),
        wire
      )
    }
  }

  private def offers(raw: String): Vector[(Version, Data)] =
    raw.split(";", -1).toVector.filter(_.nonEmpty).map { entry =>
      val fields = entry.split(",", -1)
      assertEquals(fields.length, 4)
      version(fields(0).toInt) -> Data(fields(1).toLong, fields(2) == "True", fields(3) == "True")
    }
  private def proposal(hex: String, onlyOne: Boolean = false): Vector[(Version, Term)] =
    given Handshake.VersionCodec[Version] = versions(onlyOne)
    right(Handshake.decode[Version](State.Propose, bytes("8200" + hex))) match
      case Message.Propose(vs) => vs
      case other               => fail(s"unexpected proposal $other")
  private def select(
      local: Vector[(Version, Data)],
      remote: Vector[(Version, Term)]
  ): Either[Reason[Version], (Version, Data)] =
    Handshake.acceptOrRefuse(local, remote).left.map(Handshake.refusal[Version, Data, DataError])

  private def expected(raw: String): Either[Reason[Version], (Version, Data)] =
    if raw.startsWith("refuse:") then
      Left(right(Handshake.decodeReason[Version](bytes(raw.drop(7)))))
    else
      raw.split(":", -1).toVector match
        case Vector("accept", version, n, a, b) =>
          Right(this.version(version.toInt) -> Data(n.toLong, a == "True", b == "True"))
        case other => fail(s"unknown outcome $other")

  test("original generic and asymmetric negotiation judgments use the shared production selector") {
    val cases = rows("behavior", "88dcdf78ce87316efbabb81fd398abb51696b9b14b343e2a7ef1fb77aec3b7fe")
    assertEquals(cases.size, 48)
    cases.foreach { row =>
      assertEquals(row.size, 19)
      assert(row.drop(9).forall(_ == "pass"), s"original peer verdicts ${row.take(2)}")
      val client = offers(row(4))
      val server = offers(row(5))
      // Original full data remains distinct from its lossy version-specific wire projection.
      assertEquals(select(server, proposal(row(2))), expected(row(6)))
      assertEquals(select(client, proposal(row(3))), expected(row(7)))
      // The actual asymmetric server only recognizes version 1, and skips 0/2 on the wire.
      val known = proposal(row(2), onlyOne = true)
      assert(known.forall(_._1 == Version.V1))
      assertEquals(
        select(Vector(Version.V1 -> Data(0, true, true)), known),
        expected(row(8))
      )
    }
    assertEquals(cases.count(_(6).startsWith("accept:")), 17)
    assertEquals(cases.count(_(8).startsWith("accept:")), 1)
  }

  test("reference cross-version data probes preserve typed decode failures and forbid fallback") {
    val cases = rows("probes", "7d1b11d79a063393888c3a7d509399bebc0e22cca8f36fdb48f48006f1ad9872")
    assertEquals(cases.size, 9)
    cases.foreach { row =>
      assertEquals(row.size, 8)
      val target = version(row(2).toInt)
      val d = Data(row(3).toLong, row(4) == "True", row(5) == "True")
      val local =
        (if target == Version.V2 then Vector(Version.V0 -> d) else Vector.empty) :+ (target -> d)
      val actual = select(local, proposal(row(6)))
      assertEquals(actual, expected(row(7)))
      if version(row(1).toInt) != target then
        actual match
          case Left(Reason.DecodeError(version, _)) => assertEquals(version, target)
          case other => fail(s"invalid cross-version data did not refuse highest version: $other")
    }
    assertEquals(cases.count(_(7).startsWith("refuse:")), 6)
  }

  private def encoded(v: Version, d: Data): Term = v match
    case Version.V0 => Term.Integer(d.number)
    case Version.V1 => Term.Arr(Vector(Term.Integer(d.number), Term.Bool(d.conjunction)))
    case Version.V2 =>
      Term.Arr(Vector(Term.Integer(d.number), Term.Bool(d.conjunction), Term.Bool(d.disjunction)))

  test("original acceptable and valid-version selection symmetry properties") {
    val cases = rows("symmetry", "924508ac71976613c3c9e39afeef965e34febf8bc410cd0cbed3344c28ded35d")
    assertEquals(cases.size, 24)
    cases.foreach { row =>
      assertEquals(row.size, 12)
      val a = Data(row(2).toLong, row(3) == "True", row(4) == "True")
      val b = Data(row(5).toLong, row(6) == "True", row(7) == "True")
      assertEquals(row(8), "pass")
      assertEquals(codec.agree(a, b), codec.agree(b, a))
      val client = offers(row(9))
      val server = offers(row(10))
      assertEquals(row(11), "pass")
      val c = select(client, server.map((v, d) => v -> encoded(v, d)))
      val r = select(server, client.map((v, d) => v -> encoded(v, d)))
      (c, r) match
        case (Right(x), Right(y)) => assertEquals(x, y)
        case (Left(Reason.VersionMismatch(vs, _)), Left(Reason.VersionMismatch(ws, _))) =>
          assertEquals(vs, client.map(_._1))
          assertEquals(ws, server.map(_._1))
        case (Left(Reason.DecodeError(_, _)), Left(_))                => ()
        case (Left(_), Left(Reason.DecodeError(_, _)))                => ()
        case (Left(Reason.Refused(v, _)), Left(Reason.Refused(w, _))) => assertEquals(v, w)
        case other => fail(s"selection was not symmetric: $other")
    }
  }

  test(
    "generic codecs do not widen Cardano recognition or fallback after a highest-version refusal"
  ) {
    val original = bytes("8200a30000018200f4028300f4f4")
    assertEquals(
      proposal("a30000018200f4028300f4f4").map(_._1),
      Vector(Version.V0, Version.V1, Version.V2)
    )
    assertEquals(
      right(Handshake.decode(Handshake.Suite.NodeToNode, State.Propose, original)),
      Handshake.Message.Propose(Vector.empty)
    )
    val local = Vector(Version.V0 -> Data(1, false, false), Version.V2 -> Data(1, true, false))
    val remote = Vector(Version.V0 -> Term.Integer(1), Version.V2 -> Term.Integer(1))
    assertEquals(
      Handshake.acceptOrRefuse(local, remote),
      Left(
        Handshake.SelectionError
          .DecodeError(Version.V2, DataError.Unrecognized(Version.V2, Term.Integer(1)))
      )
    )
    assertEquals(
      select(local, remote),
      Left(Reason.DecodeError(Version.V2, "decodeTerm Data: unrecognised tag: TInt 1"))
    )
  }
