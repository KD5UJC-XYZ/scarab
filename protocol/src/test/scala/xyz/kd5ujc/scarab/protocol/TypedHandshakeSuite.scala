// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import Handshake.{Message, Reason, State, Term, WireMessage, WireReason}

class TypedHandshakeSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  test("version-domain codecs cannot be omitted or substituted") {
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.Handshake
      enum MissingVersion:
        case V0
      given Ordering[MissingVersion] = Ordering.by(_.ordinal)
      Handshake.decode[MissingVersion](Handshake.State.Propose, xyz.kd5ujc.scarab.codec.Bytes.empty)
    """).contains("VersionCodec"))
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion, NtcVersion}
      Handshake.encode[ NtnVersion ](
        Handshake.WireMessage.Accept(NtnVersion.V15, Handshake.Term.Integer(0))
      )(using summon[Handshake.VersionCodec[NtcVersion]], summon[Ordering[NtnVersion]])
    """).contains("VersionCodec"))
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion}
      Handshake.acceptOrRefuse[NtnVersion, Int, Unit](
        Vector(NtnVersion.V15 -> 1), Vector(NtnVersion.V15 -> Handshake.Term.Integer(1))
      )
    """).contains("DataCodec"))
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion, NtcVersion}
      given wrong: Handshake.DataCodec[NtcVersion, Int, Unit] with
        def decode(version: NtcVersion, term: Handshake.Term): Either[Unit, Int] = Right(0)
        def agree(local: Int, remote: Int): Either[Unit, Int] = Right(local)
        def refusalText(error: Unit): String = "refused"
      Handshake.acceptOrRefuse[NtnVersion, Int, Unit](
        Vector(NtnVersion.V15 -> 1), Vector(NtnVersion.V15 -> Handshake.Term.Integer(1))
      )(using summon[Ordering[NtnVersion]], wrong)
    """).contains("DataCodec"))
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion, NtcVersion}
      Handshake.encode[NtnVersion](
        Handshake.WireMessage.Accept(NtcVersion.V23, Handshake.Term.Integer(0))
      )
    """).contains("NtcVersion"))
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion, NtcVersion}
      Handshake.encodeReason[NtnVersion](Handshake.WireReason.Refused(NtcVersion.V23, "refused"))
    """).contains("NtcVersion"))
    assert(compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion, NtcVersion}
      given Handshake.DataCodec[NtnVersion, Int, Unit] with
        def decode(version: NtnVersion, term: Handshake.Term): Either[Unit, Int] = Right(version.number)
        def agree(local: Int, remote: Int): Either[Unit, Int] = Right(local)
        def refusalText(error: Unit): String = "refused"
      Handshake.acceptOrRefuse[NtnVersion, Int, Unit](
        Vector(NtnVersion.V15 -> 1), Vector(NtcVersion.V23 -> Handshake.Term.Integer(1))
      )
    """).contains("NtcVersion"))
    assertEquals(
      compileErrors("""
      import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion}
      given Handshake.DataCodec[NtnVersion, Int, Unit] with
        def decode(version: NtnVersion, term: Handshake.Term): Either[Unit, Int] = Right(version.number)
        def agree(local: Int, remote: Int): Either[Unit, Int] = Right(local)
        def refusalText(error: Unit): String = "refused"
      Handshake.acceptOrRefuse[NtnVersion, Int, Unit](
        Vector(NtnVersion.V15 -> 1), Vector(NtnVersion.V15 -> Handshake.Term.Integer(1))
      )
    """),
      ""
    )
  }

  test("closed Cardano domains use their distinct logical and wire version representations") {
    val ntn = WireMessage.Accept(NtnVersion.V15, Term.Integer(0))
    val ntc = WireMessage.Accept(NtcVersion.V23, Term.Integer(0))
    assertEquals(Handshake.encode(ntn), Right(bytes("83010f00")))
    assertEquals(Handshake.encode(ntc), Right(bytes("830119801700")))
    assertEquals(Handshake.decode[NtnVersion](State.Confirm, bytes("83010f00")), Right(ntn))
    assertEquals(Handshake.decode[NtcVersion](State.Confirm, bytes("830119801700")), Right(ntc))
    assert(Handshake.decode[NtnVersion](State.Confirm, bytes("830119801700")) match {
      case Left(
            xyz.kd5ujc.scarab.protocol.Handshake
              .DecodeError(ProtocolError.UnrecognizedSelectedVersion, _)
          ) =>
        true
      case _ => false
    })
    assert(Handshake.decode[NtcVersion](State.Confirm, bytes("83011700")) match {
      case Left(
            xyz.kd5ujc.scarab.protocol.Handshake
              .DecodeError(ProtocolError.UnrecognizedSelectedVersion, _)
          ) =>
        true
      case _ => false
    })
    assertEquals(
      Handshake.encode(Handshake.Suite.NodeToClient, Message.Accept(23, Term.Integer(0))),
      Handshake.encode(ntc)
    )
  }

  test("typed refusals preserve unknown wire versions under the existing re-encoding policy") {
    val wire = bytes("8200830e18111863")
    val expected =
      WireReason.VersionMismatch(Vector(NtnVersion.V14), Vector(BigInt(17), BigInt(99)))
    assertEquals(Handshake.decodeReason[NtnVersion](wire), Right(expected))
    assertEquals(Handshake.encodeReason(expected), Right(bytes("8200810e")))
  }

  test("integer facades retain duplicate validation; a refusal text has no cap of its own") {
    val duplicate = Message.Propose(Vector(99 -> Term.Integer(0), 99 -> Term.Integer(1)))
    assertEquals(
      Handshake.encode(Handshake.Suite.NodeToNode, duplicate),
      Left(ProtocolError.DuplicateVersion)
    )
    // encodeRefuseReason writes any text (Codec.hs:252-261, ouroboros-network c45735a5): only the
    // message's 5760 bytes bound it, on receipt
    val long = "x" * 2000
    Vector(Reason.DecodeError(14, long), Reason.Refused(14, long)).foreach { reason =>
      val message = Message.Refuse(reason)
      val written = Handshake.encode(Handshake.Suite.NodeToNode, message)
      assertEquals(
        written.flatMap(w =>
          Handshake.decode(Handshake.Suite.NodeToNode, State.Confirm, w).left.map(_.cause)
        ),
        Right(message)
      )
    }
    Vector(Reason.DecodeError(99, long), Reason.Refused(99, long)).foreach { reason =>
      assertEquals(
        Handshake.encode(Handshake.Suite.NodeToNode, Message.Refuse(reason)),
        Left(ProtocolError.UnsupportedLogicalVersion)
      )
    }
  }

  test("decodeReason reads a reason's declared length and ignores it (#1029)") {
    assertEquals(
      Handshake.decodeReason[NtnVersion](bytes("85010e6178")),
      Right(WireReason.DecodeError(NtnVersion.V14, "x"))
    )
    assertEquals(
      Handshake.decodeReason[NtnVersion](bytes("8100820e0f")),
      Right(WireReason.VersionMismatch(Vector(NtnVersion.V14, NtnVersion.V15)))
    )
  }
