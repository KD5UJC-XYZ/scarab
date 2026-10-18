// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.syntax.all.*
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{
  PublicKey32,
  Signature64,
  StrictEd25519,
  VerificationResult,
  VerificationError,
  WitnessInputError
}

/** Mithril 769d862 `ancillary_files_manifest.rs:153-160`: SHA256 of the ordered UTF-8 path and
  * ASCII digest pairs, signed as its raw 32 bytes. The JSON serialization is not signed.
  */
final case class AncillaryManifest private (
    files: Vector[AncillaryManifest.File],
    signature: Signature64
):
  def hash: Bytes =
    val digest = MessageDigest.getInstance("SHA-256")
    files.foreach { file =>
      digest.update(file.path.getBytes(UTF_8))
      digest.update(file.digest.hex.getBytes(UTF_8))
    }
    Bytes.fromArray(digest.digest())

  def verify(key: PublicKey32): Either[AncillaryManifest.Error, Unit] =
    StrictEd25519
      .verifyEd25519(key, signature, hash)
      .leftMap(e => AncillaryManifest.Error.Verifier(e))
      .flatMap {
        case VerificationResult.SignatureVerified => Right(())
        case VerificationResult.SignatureRejected => Left(AncillaryManifest.Error.SignatureRejected)
      }

object AncillaryManifest:
  final case class File private[state] (path: String, digest: Bytes)

  enum DigestFailure:
    case ExpectedString(actual: io.circe.Json)
    case Hex(cause: Bytes.HexError)
    case WrongLength(actual: Int)
    case NonCanonical
  enum SignatureFailure:
    case Hex(cause: Bytes.HexError)
    case Input(cause: WitnessInputError)
  enum KeyFailure:
    case Hex(cause: Bytes.HexError)
    case JsonSyntax(cause: io.circe.ParsingFailure)
    case JsonDecode(cause: io.circe.DecodingFailure)
    case WrongLength(actual: Int)
    case OctetOutOfRange(index: Int, actual: Int)
    case Input(cause: WitnessInputError)
  enum Error:
    case JsonSyntax(cause: io.circe.ParsingFailure)
    case DataMissing
    case DataNotObject(actual: io.circe.Json)
    case UnsafePath(path: String)
    case InvalidDigest(path: String, cause: DigestFailure)
    case SignatureMissing, SignatureRejected
    case SignatureNotString(actual: io.circe.Json)
    case InvalidSignature(cause: SignatureFailure)
    case InvalidKey(cause: KeyFailure)
    case Verifier(cause: VerificationError)
    case Unlisted(path: String)
    case DigestMismatch(path: String, expected: Bytes, actual: Bytes)

  def encode(manifest: AncillaryManifest): String =
    io.circe.Json
      .obj(
        "data" -> io.circe.Json
          .obj(manifest.files.map(f => f.path -> io.circe.Json.fromString(f.digest.hex))*),
        "signature" -> io.circe.Json.fromString(manifest.signature.bytes.hex)
      )
      .noSpaces

  /** Relative regular-file names only. Unlike the remote archive, a local source can contain
    * symlinks; materialization also refuses those before opening a file.
    */
  def safePath(path: String): Boolean =
    path.nonEmpty && !path.startsWith("/") && !path.contains('\\') &&
      !path.contains('\u0000') && new String(path.getBytes(UTF_8), UTF_8) == path &&
      !path.take(2).endsWith(":") &&
      path.split("/", -1).forall(p => p.nonEmpty && p != "." && p != "..")

  def parse(text: String): Either[Error, AncillaryManifest] =
    for
      json <- io.circe.jawn
        .JawnParser(allowDuplicateKeys = false)
        .parse(text)
        .leftMap(e => Error.JsonSyntax(e))
      dataNode <- json.hcursor.downField("data").focus.toRight(Error.DataMissing)
      data <- dataNode.asObject.toRight(Error.DataNotObject(dataNode))
      files <- data.toVector.traverse { (path, value) =>
        for
          _ <- Either.cond(safePath(path), (), Error.UnsafePath(path))
          hex <- value.asString.toRight(Error.InvalidDigest(path, DigestFailure.ExpectedString(value)))
          digest <- Bytes.fromHex(hex).leftMap(cause => Error.InvalidDigest(path, DigestFailure.Hex(cause)))
          _ <- Either.cond(digest.size == 32, (), Error.InvalidDigest(path, DigestFailure.WrongLength(digest.size)))
          _ <- Either.cond(hex == digest.hex, (), Error.InvalidDigest(path, DigestFailure.NonCanonical))
        yield File(path, digest)
      }
      sigNode <- json.hcursor.downField("signature").focus.toRight(Error.SignatureMissing)
      sig <- sigNode.asString.toRight(Error.SignatureNotString(sigNode))
      bytes <- Bytes.fromHex(sig).leftMap(cause => Error.InvalidSignature(SignatureFailure.Hex(cause)))
      signature <- Signature64.create(bytes).leftMap(cause => Error.InvalidSignature(SignatureFailure.Input(cause)))
    yield AncillaryManifest(files.sortBy(f => Bytes.wrap(f.path.getBytes(UTF_8))), signature)

  /** `ProtocolKey`'s published ancillary.vkey is hex-encoded JSON containing 32 octets. */
  def verificationKey(text: String): Either[Error, PublicKey32] =
    for
      bytes <- Bytes.fromHex(text.trim).leftMap(cause => Error.InvalidKey(KeyFailure.Hex(cause)))
      json <- io.circe.parser
        .parse(new String(bytes.toArray, UTF_8))
        .leftMap(e => Error.InvalidKey(KeyFailure.JsonSyntax(e)))
      octets <- json.as[Vector[Int]].leftMap(e => Error.InvalidKey(KeyFailure.JsonDecode(e)))
      _ <- Either.cond(octets.size == 32, (), Error.InvalidKey(KeyFailure.WrongLength(octets.size)))
      _ <- octets.zipWithIndex.find { (n, _) => n < 0 || n > 255 }
        .map { (n, i) => Error.InvalidKey(KeyFailure.OctetOutOfRange(i, n)) }.toLeft(())
      key <- PublicKey32
        .fromArray(octets.map(_.toByte).toArray)
        .leftMap(e => Error.InvalidKey(KeyFailure.Input(e)))
    yield key
