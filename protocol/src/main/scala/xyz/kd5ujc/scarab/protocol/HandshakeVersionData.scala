// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.Eval
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Float16, HaskellFloat, HaskellShow}

/** Cardano version-data codecs at ouroboros-network 2fb69829:
  * NodeToNode/Version.hs:139-148,190-225; NodeToClient/Version.hs:123-152.
  */
object HandshakeVersionData:
  import Handshake.{Data, Suite, Term}

  enum Error:
    case Magic(value: BigInt)
    case PeerSharing(value: BigInt)
    case Peras(version: Int, values: Vector[Term])
    case Encoding(suite: Suite, value: Term)
    case Mismatch(suite: Suite, local: Data, remote: Data)

  def decode(suite: Suite, version: Int, term: Term): Either[Error, Data] =
    def magic(value: BigInt): Either[Error, Long] =
      Either.cond(value >= 0 && value <= 0xffffffffL, value.toLong, Error.Magic(value))
    def peers(value: BigInt): Either[Error, Boolean] =
      Either.cond(value == 0 || value == 1, value == 1, Error.PeerSharing(value))
    def peras(values: Vector[Term]): Either[Error, Boolean] = values match
      case Vector() if version < 16 => Right(false)
      case Vector(Term.Bool(value)) if version >= 16 => Right(value)
      case _ => Left(Error.Peras(version, values))
    (suite, term) match
      case (Suite.NodeToClient, Term.Arr(Vector(Term.Integer(n), Term.Bool(query)), true))
          if n.isValidLong => magic(n).map(Data(_, query = query))
      case (Suite.NodeToNode, Term.Arr(values, true)) => values match
        case Term.Integer(n) +: Term.Bool(initiator) +: Term.Integer(sharing) +: Term.Bool(query) +: rest
            if n.isValidLong && sharing.isValidLong =>
          for
            network <- magic(n)
            peerSharing <- peers(sharing)
            supported <- peras(rest)
          yield Data(network, initiator, peerSharing, query, supported)
        case _ => Left(Error.Encoding(suite, term))
      case _ => Left(Error.Encoding(suite, term))

  def agree(suite: Suite, local: Data, remote: Data): Either[Error, Data] =
    Either.cond(
      local.magic == remote.magic,
      Data(local.magic, local.initiatorOnly || remote.initiatorOnly,
        local.peerSharing && remote.peerSharing, local.query || remote.query, local.peras && remote.peras),
      Error.Mismatch(suite, local, remote)
    )

  private def dataShow(suite: Suite, value: Data): String =
    def bool(value: Boolean): String = if value then "True" else "False"
    suite match
      case Suite.NodeToClient =>
        s"NodeToClientVersionData {networkMagic = ${value.magic}, query = ${bool(value.query)}}"
      case Suite.NodeToNode =>
        val mode = if value.initiatorOnly then "InitiatorOnlyDiffusionMode" else "InitiatorAndResponderDiffusionMode"
        val peers = if value.peerSharing then "PeerSharingEnabled" else "PeerSharingDisabled"
        val peras = if value.peras then "PerasSupported" else "PerasUnsupported"
        s"NodeToNodeVersionData {networkMagic = ${value.magic}, diffusionMode = $mode, peerSharing = $peers, query = ${bool(value.query)}, perasSupport = $peras}"

  def text(error: Error): String = error match
    case Error.Magic(value) => s"networkMagic out of bound: $value"
    case Error.PeerSharing(value) => s"peerSharing is out of bound: $value"
    case Error.Peras(version, values) =>
      s"invalid encoding for perasSupport given the version NodeToNodeV_$version: " +
        values.traverse(term(_, 0)).map(_.mkString("[", ",", "]")).value
    case Error.Encoding(Suite.NodeToClient, value) => "unknown encoding: " + show(value)
    case Error.Encoding(Suite.NodeToNode, value) =>
      "unexpected encoding when decoding NodeToNodeVersionData: " + show(value)
    case Error.Mismatch(suite, local, remote) =>
      "version data mismatch: " + dataShow(suite, local) + " /= " + dataShow(suite, remote)

  /** cborg-0.2.10.0 Codec/CBOR/Term.hs:65-87, derived Show. No provider text is used. */
  def show(value: Term): String = term(value, 0).value

  private def term(value: Term, precedence: Int): Eval[String] = Eval.defer {
    def ctor(name: String, arguments: String*): String =
      val result = (name +: arguments).mkString(" ")
      if arguments.nonEmpty && precedence > 10 then "(" + result + ")" else result
    def number(value: BigInt): String = if value < 0 then s"($value)" else value.toString
    def list(values: Vector[Term]): Eval[String] =
      values.traverse(term(_, 0)).map(_.mkString("[", ",", "]"))
    value match
      case Term.Integer(value) => Eval.now(ctor(if value.isValidLong then "TInt" else "TInteger", number(value)))
      case Term.BigInteger(value) => Eval.now(ctor("TInteger", number(value)))
      case Term.Bool(value) => Eval.now(ctor("TBool", if value then "True" else "False"))
      case Term.Arr(values, definite) => list(values).map(ctor(if definite then "TList" else "TListI", _))
      case Term.Map(values, definite) =>
        values.traverse((key, value) => (term(key, 0), term(value, 0)).mapN((k,v) => s"($k,$v)"))
          .map(values => ctor(if definite then "TMap" else "TMapI", values.mkString("[", ",", "]")))
      case Term.Text(value, definite) => Eval.now(ctor(if definite then "TString" else "TStringI", HaskellShow.text(value)))
      case Term.ByteString(value, definite) => Eval.now(ctor(if definite then "TBytes" else "TBytesI", HaskellShow.bytes(value)))
      case Term.Tag(number, value) => term(value, 11).map(ctor("TTagged", number.toString, _))
      case Term.Simple(22) => Eval.now("TNull")
      case Term.Simple(value) => Eval.now(ctor("TSimple", value.toString))
      case Term.FloatBits(2, bits) => Eval.now(ctor("THalf", HaskellFloat.float(Float16.toFloat(bits.toInt), 11)))
      case Term.FloatBits(4, bits) => Eval.now(ctor("TFloat", HaskellFloat.float(java.lang.Float.intBitsToFloat(bits.toInt), 11)))
      case Term.FloatBits(_, bits) => Eval.now(ctor("TDouble", HaskellFloat.double(java.lang.Double.longBitsToDouble(bits.toLong), 11)))
  }
