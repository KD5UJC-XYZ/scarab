// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import io.circe.Json
import io.circe.parser
import io.circe.syntax.*
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.leios.VotingKey
import xyz.kd5ujc.scarab.crypto.bls.SecretKey
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem, Header, KeyShape}
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
  * [[KesFiles]]), the operational certificate and the cold verification key that issued it, and the
  * Leios voting keys of `--shelley-bls-key` (`praosCanBeLeaderSignKeyBLS`, cardano-node b004fe0c
  * `Cardano/Node/Protocol/Shelley.hs:303-322`), none without the flag. The KES key is mutable:
  * forging evolves it.
  */
final case class PoolCredentials(
    vrf: Draft03Prover.SigningKey,
    kes: Sum6KesSigning.SigningKey,
    opCert: Header.OpCert,
    coldVkey: Bytes,
    voting: Vector[VotingKey] = Vector.empty
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
        ForgeError.KesKeyNotCertified(c.opCert.hotVkey, c.kes.verificationKey)
      )
      verdict <- OperationalCertificate
        .verify(c.coldVkey, c.opCert.hotVkey, c.opCert.sequence, c.opCert.kesPeriod, c.opCert.sigma)
        .leftMap(ForgeError.CertificateFailure(_))
      _ <- Either.cond(
        verdict == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
        (),
        ForgeError.CertificateSignatureRejected
      )
    yield ()

/** The cardano-cli text envelopes: `{"type": ..., "description": ..., "cborHex": ...}` whose
  * `cborHex` is the CBOR encoding of the payload.
  */
object KeyFiles:
  val VrfSigningKeyType = "VrfSigningKey_PraosVRF"
  val KesSigningKeyType = "KesSigningKey_ed25519_kes_2^6"
  val OpCertType = "NodeOperationalCertificate"

  /** The envelope type `cardano-cli dijkstra node key-gen-BLS` writes for a BLS signing key
    * (cardano-cli 11.3.1.0 of cardano-node b004fe0c, the Leios testnet's release).
    */
  val BlsSigningKeyType = "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size"

  /** The type and the decoded CBOR of an envelope's text. */
  def envelope(file: String, text: String): Either[ForgeError, (String, Bytes)] =
    def bad(reason: ForgeKeyProblem) = ForgeError.KeyFile(file, reason)
    for
      json <- parser.parse(text).leftMap(e => bad(ForgeKeyProblem.Json(e)))
      cursor = json.hcursor
      typ <- cursor.get[String]("type").leftMap(e => bad(ForgeKeyProblem.Field("type", e)))
      hex <- cursor.get[String]("cborHex").leftMap(e => bad(ForgeKeyProblem.Field("cborHex", e)))
      raw <- Bytes.fromHex(hex).leftMap(e => bad(ForgeKeyProblem.Hex(e)))
    yield (typ, raw)

  private def expect(file: String, text: String, typ: String): Either[ForgeError, Bytes] =
    envelope(file, text).flatMap { (found, raw) =>
      Either.cond(
        found == typ,
        raw,
        ForgeError.KeyFile(file, ForgeKeyProblem.EnvelopeType(typ, found))
      )
    }

  private def byteString(file: String, raw: Bytes): Either[ForgeError, Bytes] =
    Cbor.decode(raw).leftMap(e => ForgeError.KeyFile(file, ForgeKeyProblem.Cbor(e))).flatMap { n =>
      n.value match
        case Value.ByteString(b) => Right(b)
        case _ =>
          Left(ForgeError.KeyFile(file, ForgeKeyProblem.ExpectedByteString(KeyShape.of(n.value))))
    }

  def vrfSigningKey(file: String, text: String): Either[ForgeError, Draft03Prover.SigningKey] =
    for
      raw <- expect(file, text, VrfSigningKeyType)
      payload <- byteString(file, raw)
      key <- Draft03Prover.SigningKey
        .fromBytes(payload)
        .leftMap(e => ForgeError.KeyFile(file, ForgeKeyProblem.Vrf(e)))
    yield key

  /** The voting keys of `--shelley-bls-key` (`readBlsSigningKeys`, cardano-node b004fe0c
    * `Cardano/Node/Protocol/Shelley.hs:324-339`): a JSON array of text envelopes, or else one text
    * envelope, each a BLS signing key. When the text is not an array of envelopes the failure is
    * the single envelope's, as there. An empty array is no key, so the node does not vote.
    */
  def blsSigningKeys(file: String, text: String): Either[ForgeError, Vector[SecretKey]] =
    val asArray = parser.parse(text).toOption.flatMap(_.asArray).flatMap { items =>
      items.traverse(item => envelope(file, item.noSpaces).toOption)
    }
    asArray
      .fold(envelope(file, text).map(Vector(_)))(Right(_))
      .flatMap(_.traverse((typ, raw) => blsSigningKey(file, typ, raw)))

  private def blsSigningKey(file: String, typ: String, raw: Bytes): Either[ForgeError, SecretKey] =
    for
      _ <- Either.cond(
        typ == BlsSigningKeyType,
        (),
        ForgeError.KeyFile(file, ForgeKeyProblem.EnvelopeType(BlsSigningKeyType, typ))
      )
      payload <- byteString(file, raw)
      key <- SecretKey
        .fromBytes(payload)
        .leftMap(e => ForgeError.KeyFile(file, ForgeKeyProblem.Bls(e)))
    yield key

  /** The voting keys of a `--shelley-bls-key` file, read. */
  def loadBlsSigningKeys(file: Path): Either[ForgeError, Vector[SecretKey]] =
    read(file).flatMap(blsSigningKeys(file.toString, _))

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
      .leftMap(e => ForgeError.KeyFile(file, ForgeKeyProblem.Cbor(e)))
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
    def bad(reason: ForgeKeyProblem) = ForgeError.KeyFile(file, reason)
    for
      raw <- expect(file, text, OpCertType)
      root <- Cbor.decode(raw).leftMap(e => bad(ForgeKeyProblem.Cbor(e)))
      outer <- root.value match
        case Value.Arr(xs) if xs.sizeIs == 2 => Right(xs)
        case _ => Left(bad(ForgeKeyProblem.CertificateEnvelope(KeyShape.of(root.value))))
      parts <- outer(0).value match
        case Value.Arr(xs) if xs.sizeIs == 4 => Right(xs)
        case _ => Left(bad(ForgeKeyProblem.CertificateFields(KeyShape.of(outer(0).value))))
      hot <- fixed(parts(0).value, 32, bad)
      sequence <- uint(parts(1).value, bad)
      period <- uint(parts(2).value, bad)
      sigma <- fixed(parts(3).value, 64, bad)
      cold <- fixed(outer(1).value, 32, bad)
    yield (Header.OpCert(hot, sequence, period, sigma), cold)

  private def fixed(
      v: Value,
      size: Int,
      bad: ForgeKeyProblem => ForgeError
  ): Either[ForgeError, Bytes] =
    v match
      case Value.ByteString(b) if b.size == size => Right(b)
      case _ => Left(bad(ForgeKeyProblem.FixedBytes(size, KeyShape.of(v))))

  private def uint(v: Value, bad: ForgeKeyProblem => ForgeError): Either[ForgeError, BigInt] =
    v match
      case Value.UInt(n) => Right(n)
      case _             => Left(bad(ForgeKeyProblem.UnsignedInteger(KeyShape.of(v))))

  /** The text of a file, or why it cannot be read. */
  private[forge] def read(p: Path): Either[ForgeError, String] =
    Either
      .catchNonFatal(Files.readString(p))
      .leftMap(e => ForgeError.KeyFile(p.toString, ForgeKeyProblem.Io(e)))

  /** Load and cross-check the three files of a pool (a KES key left half-replaced by a crash is put
    * right first, see [[KesFiles.recover]]).
    */
  def load(
      vrfFile: Path,
      kesFile: Path,
      opCertFile: Path,
      blsFile: Option[Path] = None
  ): Either[ForgeError, PoolCredentials] =
    for
      vrfText <- read(vrfFile)
      vrf <- vrfSigningKey(vrfFile.toString, vrfText)
      // the voting keys after the VRF key and before the KES key and the certificate, as
      // `readLeaderCredentialsSingleton` reads them (`Cardano/Node/Protocol/Shelley.hs:187-201`,
      // cardano-node b004fe0c)
      voting <- blsFile.traverse(loadBlsSigningKeys).map(_.getOrElse(Vector.empty))
      keys <- voting.traverse(k =>
        VotingKey
          .of(k)
          .leftMap(e => ForgeError.KeyFile(blsFile.fold("")(_.toString), ForgeKeyProblem.Bls(e)))
      )
      hot <- KesFiles.readHot(kesFile, opCertFile)
      credentials = PoolCredentials(vrf, hot.kes, hot.opCert, hot.coldVkey, keys)
      _ <- PoolCredentials.check(credentials)
    yield credentials
