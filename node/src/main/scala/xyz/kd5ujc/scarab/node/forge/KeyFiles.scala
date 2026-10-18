// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import io.circe.Json
import io.circe.parser
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ForgeError, Header}
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover

/** The part of a pool's credentials that rotation replaces as a unit: the KES signing key, the
  * operational certificate that names its verification key, and the cold verification key that
  * signed the certificate.
  */
final case class HotCredentials(
    kes: Sum6KesSigning.SigningKey,
    opCert: Header.OpCert,
    coldVkey: Bytes
)

/** The credentials of a block-producing pool: the VRF signing key, the KES signing key (at period 0
  * as `cardano-cli` writes it, or at the period this node last evolved and wrote it, see
  * [[KesFiles]]), the operational certificate and the cold verification key that issued it. The KES
  * key is mutable: forging evolves it.
  */
final case class PoolCredentials(
    vrf: Draft03Prover.SigningKey,
    kes: Sum6KesSigning.SigningKey,
    opCert: Header.OpCert,
    coldVkey: Bytes
):
  def hot: HotCredentials = HotCredentials(kes, opCert, coldVkey)

  def withHot(h: HotCredentials): PoolCredentials =
    copy(kes = h.kes, opCert = h.opCert, coldVkey = h.coldVkey)

  /** The pool id: Blake2b-224 of the cold verification key. */
  lazy val poolId: Bytes = Blake2b.hash224.hash(coldVkey)

  /** The VRF key hash a pool registration carries. */
  lazy val vrfKeyHash: Bytes = Blake2b.hash256.hash(vrf.publicKey)

object PoolCredentials:
  /** Do the keys belong together: the certificate names this KES key and is signed by the cold key.
    */
  def check(c: PoolCredentials): Either[ForgeError, Unit] =
    for
      _ <- Either.cond(
        c.kes.verificationKey == c.opCert.hotVkey,
        (),
        ForgeError.KeyMismatch("the operational certificate names another KES verification key")
      )
      verdict <- OperationalCertificate
        .verify(c.coldVkey, c.opCert.hotVkey, c.opCert.sequence, c.opCert.kesPeriod, c.opCert.sigma)
        .leftMap(e => ForgeError.KeyMismatch(s"operational certificate: $e"))
      _ <- Either.cond(
        verdict == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
        (),
        ForgeError.KeyMismatch("the operational certificate is not signed by its cold key")
      )
    yield ()

/** The cardano-cli text envelopes: `{"type": ..., "description": ..., "cborHex": ...}` whose
  * `cborHex` is the CBOR encoding of the payload.
  */
object KeyFiles:
  val VrfSigningKeyType = "VrfSigningKey_PraosVRF"
  val KesSigningKeyType = "KesSigningKey_ed25519_kes_2^6"
  val OpCertType = "NodeOperationalCertificate"

  /** The type and the decoded CBOR of an envelope's text. */
  def envelope(file: String, text: String): Either[ForgeError, (String, Bytes)] =
    def bad(reason: String) = ForgeError.KeyFile(file, reason)
    for
      json <- parser.parse(text).leftMap(e => bad(s"not JSON: ${e.message}"))
      cursor = json.hcursor
      typ <- cursor.get[String]("type").leftMap(_ => bad("no \"type\""))
      hex <- cursor.get[String]("cborHex").leftMap(_ => bad("no \"cborHex\""))
      raw <- Bytes.fromHex(hex).leftMap(e => bad(s"cborHex: $e"))
    yield (typ, raw)

  private def expect(file: String, text: String, typ: String): Either[ForgeError, Bytes] =
    envelope(file, text).flatMap { (found, raw) =>
      Either.cond(
        found == typ,
        raw,
        ForgeError.KeyFile(file, s"expected type $typ, found $found")
      )
    }

  private def byteString(file: String, raw: Bytes): Either[ForgeError, Bytes] =
    Cbor.decode(raw).leftMap(ForgeError.KeyFile(file, _)).flatMap { n =>
      n.value match
        case Value.ByteString(b) => Right(b)
        case _ => Left(ForgeError.KeyFile(file, "payload is not a CBOR byte string"))
    }

  def vrfSigningKey(file: String, text: String): Either[ForgeError, Draft03Prover.SigningKey] =
    for
      raw <- expect(file, text, VrfSigningKeyType)
      payload <- byteString(file, raw)
      key <- Draft03Prover.SigningKey
        .fromBytes(payload)
        .leftMap(e => ForgeError.KeyFile(file, e.toString))
    yield key

  /** The KES key as `cardano-cli node key-gen-KES` writes it (the 608-byte period-0 key), or as
    * [[KesFiles]] writes it back after evolving (the same envelope; the period is read off the
    * key).
    */
  def kesSigningKey(file: String, text: String): Either[ForgeError, Sum6KesSigning.SigningKey] =
    for
      raw <- expect(file, text, KesSigningKeyType)
      payload <- byteString(file, raw)
      key <- Sum6KesSigning
        .fromEvolved(payload)
        .leftMap(ForgeError.KesKey(file, _))
    yield key

  /** The text of a KES key file: the cardano-cli envelope around the key at its current period. */
  def kesEnvelope(file: String, key: Sum6KesSigning.SigningKey): Either[ForgeError, String] =
    Cbor
      .encode(Value.ByteString(key.toBytes))
      .leftMap(ForgeError.KeyFile(file, _))
      .map(cbor =>
        Json
          .obj(
            "type" -> KesSigningKeyType.asJson,
            "description" -> "KES Signing Key".asJson,
            "cborHex" -> cbor.hex.asJson
          )
          .spaces2
      )

  /** `[[hotVkey, sequence, kesPeriod, sigma], coldVkey]`. */
  def opCert(file: String, text: String): Either[ForgeError, (Header.OpCert, Bytes)] =
    def bad(reason: String) = ForgeError.KeyFile(file, reason)
    for
      raw <- expect(file, text, OpCertType)
      root <- Cbor.decode(raw).leftMap(bad)
      outer <- root.value match
        case Value.Arr(xs) if xs.size == 2 => Right(xs)
        case _ => Left(bad("expected [certificate, cold verification key]"))
      parts <- outer(0).value match
        case Value.Arr(xs) if xs.size == 4 => Right(xs)
        case _ => Left(bad("expected [hot vkey, counter, KES period, signature]"))
      hot <- fixed(parts(0).value, 32, bad)
      sequence <- uint(parts(1).value, bad)
      period <- uint(parts(2).value, bad)
      sigma <- fixed(parts(3).value, 64, bad)
      cold <- fixed(outer(1).value, 32, bad)
    yield (Header.OpCert(hot, sequence, period, sigma), cold)

  private def fixed(v: Value, size: Int, bad: String => ForgeError): Either[ForgeError, Bytes] =
    v match
      case Value.ByteString(b) if b.size == size => Right(b)
      case _                                     => Left(bad(s"expected a $size-byte string"))

  private def uint(v: Value, bad: String => ForgeError): Either[ForgeError, BigInt] = v match
    case Value.UInt(n) => Right(n)
    case _             => Left(bad("expected an unsigned integer"))

  /** The text of a file, or why it cannot be read. */
  private[forge] def read(p: Path): Either[ForgeError, String] =
    Either
      .catchNonFatal(Files.readString(p))
      .leftMap(e => ForgeError.KeyFile(p.toString, Option(e.getMessage).getOrElse(e.toString)))

  /** Load and cross-check the three files of a pool (a KES key left half-replaced by a crash is put
    * right first, see [[KesFiles.recover]]).
    */
  def load(vrfFile: Path, kesFile: Path, opCertFile: Path): Either[ForgeError, PoolCredentials] =
    for
      vrfText <- read(vrfFile)
      vrf <- vrfSigningKey(vrfFile.toString, vrfText)
      hot <- KesFiles.readHot(kesFile, opCertFile)
      credentials = PoolCredentials(vrf, hot.kes, hot.opCert, hot.coldVkey)
      _ <- PoolCredentials.check(credentials)
    yield credentials
