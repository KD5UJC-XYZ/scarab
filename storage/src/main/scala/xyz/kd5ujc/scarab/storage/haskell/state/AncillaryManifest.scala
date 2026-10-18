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
  VerificationResult
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
      .leftMap(e => AncillaryManifest.Error.Verifier(e.toString))
      .flatMap {
        case VerificationResult.SignatureVerified => Right(())
        case VerificationResult.SignatureRejected => Left(AncillaryManifest.Error.SignatureRejected)
      }

object AncillaryManifest:
  final case class File private[state] (path: String, digest: Bytes)

  enum Error:
    case Malformed(detail: String)
    case UnsafePath(path: String)
    case InvalidDigest(path: String)
    case SignatureMissing, SignatureRejected
    case InvalidSignature(detail: String)
    case InvalidKey(detail: String)
    case Verifier(detail: String)
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
        .leftMap(e => Error.Malformed(e.message))
      data <- json.hcursor
        .downField("data")
        .focus
        .flatMap(_.asObject)
        .toRight(Error.Malformed("data is not an object"))
      files <- data.toVector.traverse { (path, value) =>
        for
          _ <- Either.cond(safePath(path), (), Error.UnsafePath(path))
          hex <- value.asString.toRight(Error.InvalidDigest(path))
          digest <- Bytes.fromHex(hex).leftMap(_ => Error.InvalidDigest(path))
          _ <- Either.cond(digest.size == 32 && hex == digest.hex, (), Error.InvalidDigest(path))
        yield File(path, digest)
      }
      sig <- json.hcursor
        .downField("signature")
        .focus
        .flatMap(_.asString)
        .toRight(Error.SignatureMissing)
      bytes <- Bytes.fromHex(sig).leftMap(Error.InvalidSignature(_))
      signature <- Signature64.create(bytes).leftMap(e => Error.InvalidSignature(e.toString))
    yield AncillaryManifest(files.sortBy(f => Bytes.wrap(f.path.getBytes(UTF_8))), signature)

  /** `ProtocolKey`'s published ancillary.vkey is hex-encoded JSON containing 32 octets. */
  def verificationKey(text: String): Either[Error, PublicKey32] =
    for
      bytes <- Bytes.fromHex(text.trim).leftMap(Error.InvalidKey(_))
      json <- io.circe.parser
        .parse(new String(bytes.toArray, UTF_8))
        .leftMap(e => Error.InvalidKey(e.message))
      octets <- json.as[Vector[Int]].leftMap(e => Error.InvalidKey(e.message))
      _ <- Either.cond(
        octets.size == 32 && octets.forall(n => n >= 0 && n <= 255),
        (),
        Error.InvalidKey("expected 32 octets")
      )
      key <- PublicKey32
        .fromArray(octets.map(_.toByte).toArray)
        .leftMap(e => Error.InvalidKey(e.toString))
    yield key
