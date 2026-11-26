// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import io.circe.parser
import java.nio.ByteBuffer
import java.nio.charset.MalformedInputException
import java.nio.file.{Files, Path}
import scala.util.control.Exception.allCatch
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.leios.VotingKey
import xyz.kd5ujc.scarab.crypto.bls.SecretKey
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem, Header, KeyShape}
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.secret.{KeyText, Public}
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
  *
  * The VRF and KES signing keys are read from their files as bytes ([[KeyText]]): a flat envelope
  * of the expected type is read in place, and its key decoded and copied out of storage that is
  * zeroed once the key is made, so that no `String`, `Json` or `Bytes` holds a copy of it. An
  * envelope that is not flat is read as text, by the readers below that take a text, with the
  * results and failures they always had.
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

  /** The VRF key of the file `file`, read as bytes. */
  def vrfSigningKey(file: Path): Either[ForgeError, Draft03Prover.SigningKey] =
    vrfSigningKeyRead(file.toString, KeyText.file(file)).leftMap(unreadable(file.toString)).flatten

  /** The VRF key of the envelope `source` gives, named `file`: none if its bytes cannot be read. */
  private[forge] def vrfSigningKeyRead(
      file: String,
      source: KeyText.Source
  ): Either[Throwable, Either[ForgeError, Draft03Prover.SigningKey]] =
    keyRead(file, source, VrfSigningKeyType)(
      Draft03Prover.SigningKey.fromPayload,
      e => ForgeError.KeyFile(file, ForgeKeyProblem.Vrf(e)),
      vrfSigningKey(file, _)
    )

  /** The VRF key of an envelope's text. */
  private[forge] def vrfSigningKey(
      file: String,
      text: String
  ): Either[ForgeError, Draft03Prover.SigningKey] =
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

  /** The bytes of a `--shelley-bls-key` file (`BS.readFile`,
    * `Cardano/Node/Protocol/Shelley.hs:332`, cardano-node b004fe0c): any failure to read them is
    * the reference's `CredentialsReadError`.
    */
  def readBls(file: Path): Either[ForgeError, Array[Byte]] =
    Either
      .catchNonFatal(Files.readAllBytes(file))
      .leftMap(e => ForgeError.KeyFile(file.toString, ForgeKeyProblem.Io(e)))

  /** The voting keys of the bytes of a `--shelley-bls-key` file, each with its verification key.
    * Bytes that are not UTF-8 are no JSON, as `eitherDecodeStrict'` finds them.
    */
  def blsVotingKeys(file: Path, bytes: Array[Byte]): Either[ForgeError, Vector[VotingKey]] =
    val name = file.toString
    for
      text <- Either
        .catchNonFatal(
          java.nio.charset.StandardCharsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString
        )
        .leftMap(e =>
          ForgeError.KeyFile(name, ForgeKeyProblem.Json(io.circe.ParsingFailure("not UTF-8", e)))
        )
      secrets <- blsSigningKeys(name, text)
      keys <- secrets.traverse(k =>
        VotingKey.of(k).leftMap(e => ForgeError.KeyFile(name, ForgeKeyProblem.Bls(e)))
      )
    yield keys

  /** The voting keys of a `--shelley-bls-key` file, read. */
  def loadBlsSigningKeys(file: Path): Either[ForgeError, Vector[VotingKey]] =
    readBls(file).flatMap(blsVotingKeys(file, _))

  /** The KES key as `cardano-cli node key-gen-KES` writes it (the 608-byte period-0 key), or as
    * [[KesFiles]] writes it back after evolving (the same envelope; the period is read off the
    * key), read as bytes.
    */
  def kesSigningKey(file: Path): Either[ForgeError, Sum6KesSigning.SigningKey] =
    kesSigningKeyRead(file.toString, KeyText.file(file)).leftMap(unreadable(file.toString)).flatten

  /** The KES key of the envelope `source` gives, named `file`: none if its bytes cannot be read. */
  private[forge] def kesSigningKeyRead(
      file: String,
      source: KeyText.Source
  ): Either[Throwable, Either[ForgeError, Sum6KesSigning.SigningKey]] =
    keyRead(file, source, KesSigningKeyType)(
      Sum6KesSigning.fromEvolvedPayload,
      ForgeError.KesKey(file, _),
      kesSigningKey(file, _)
    )

  /** The KES key of an envelope's text. */
  private[forge] def kesSigningKey(
      file: String,
      text: String
  ): Either[ForgeError, Sum6KesSigning.SigningKey] =
    for
      raw <- expect(file, text, KesSigningKeyType)
      payload <- byteString(file, raw)
      key <- Sum6KesSigning
        .fromEvolved(payload)
        .leftMap(ForgeError.KesKey(file, _))
    yield key

  /** The text of a KES key file, the cardano-cli envelope around the key at its current period,
    * handed to `use` in native memory that is zeroed when `use` ends; its bytes are those circe's
    * `spaces2` printed of `{"type", "description", "cborHex"}` before (`KeyFilesBytesSuite`).
    */
  def kesEnvelope[A](key: Sum6KesSigning.SigningKey)(use: ByteBuffer => A): A =
    KeyText.written(KesSigningKeyType, KesDescription, Sum6KesSigning.KeyBytes, key.payloadIn(_))(
      use
    )

  private val KesDescription = "KES Signing Key"

  /** What [[KeyText.read]] found in the envelope of a key: the key, made by `make` from the read's
    * buffer, which it copies what it keeps from and the read then zeroes; or the failure the
    * readers of an envelope's text give, through `text` when the byte reader does not take it;
    * outside, a failure to read it.
    */
  private def keyRead[F: Public, K: Public](
      file: String,
      source: KeyText.Source,
      typ: String
  )(
      make: Array[Byte] => Either[F, K],
      failure: F => ForgeError,
      text: String => Either[ForgeError, K]
  ): Either[Throwable, Either[ForgeError, K]] =
    KeyText.read(source, typ)(make) match
      case KeyText.Read.Unreadable(cause) => Left(cause)
      case KeyText.Read.NotUtf8(cause) =>
        Right(Left(ForgeError.KeyFile(file, ForgeKeyProblem.NotUtf8(cause))))
      case KeyText.Read.Payload(made) => Right(made.leftMap(failure))
      case KeyText.Read.WrongType(found) =>
        Right(Left(ForgeError.KeyFile(file, ForgeKeyProblem.EnvelopeType(typ, found))))
      case KeyText.Read.Other(envelope) => Right(text(envelope))

  private def unreadable(file: String)(cause: Throwable): ForgeError =
    ForgeError.KeyFile(file, ForgeKeyProblem.Io(cause))

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

  /** The text of a file, or why it is none: bytes that are not UTF-8, which are the file's content
    * (`NotUtf8`), or a failure to read it (`Io`).
    */
  private[forge] def read(p: Path): Either[ForgeError, String] =
    Either
      .catchNonFatal(Files.readString(p))
      .leftMap {
        case malformed: MalformedInputException =>
          ForgeError.KeyFile(p.toString, ForgeKeyProblem.NotUtf8(malformed))
        case other => ForgeError.KeyFile(p.toString, ForgeKeyProblem.Io(other))
      }

  /** Load and cross-check the three files of a pool (a KES key left half-replaced by a crash is put
    * right first, see [[KesFiles.recover]]).
    */
  def load(
      vrfFile: Path,
      kesFile: Path,
      opCertFile: Path,
      blsFile: Option[Path] = None
  ): Either[ForgeError, PoolCredentials] =
    loadWith(vrfFile, kesFile, opCertFile, blsFile, KesFiles.readHot)

  /** [[load]] with the KES key and certificate read by `readHot`, a seam for the suites. */
  private[forge] def loadWith(
      vrfFile: Path,
      kesFile: Path,
      opCertFile: Path,
      blsFile: Option[Path],
      readHot: (Path, Path) => Either[ForgeError, HotCredentials]
  ): Either[ForgeError, PoolCredentials] =
    vrfSigningKey(vrfFile).flatMap { vrf =>
      erasedUnless(
        vrf,
        for
          // the voting keys after the VRF key and before the KES key and the certificate, as
          // `readLeaderCredentialsSingleton` reads them (`Cardano/Node/Protocol/Shelley.hs:187-201`,
          // cardano-node b004fe0c)
          keys <- blsFile.traverse(loadBlsSigningKeys).map(_.getOrElse(Vector.empty))
          hot <- readHot(kesFile, opCertFile)
          credentials = PoolCredentials(vrf, hot.kes, hot.opCert, hot.coldVkey, keys)
          // credentials that do not belong together are not taken: their KES key is erased too
          _ <- erasedUnless(hot.kes, PoolCredentials.check(credentials))(_ => true)
        yield credentials
      )(_ => true)
    }

  /** `result`, computed with the VRF key `vrf` read for it, which nothing else holds: the key is
    * erased unless `result` is a value that `keeps` it, so that credentials that are not taken
    * leave no copy of its seed. A computation that throws, whatever it throws, erases the key too,
    * and its `Throwable` is passed on as it came (ERR-4); that is the reason for the suppression.
    */
  private[forge] def erasedUnless[A](
      vrf: Draft03Prover.SigningKey,
      result: => Either[ForgeError, A]
  )(
      keeps: A => Boolean
  ): Either[ForgeError, A] =
    erasing(() => vrf.forget(), result)(keeps)

  /** [[erasedUnless]] for a KES key read for `result`: erased unless `result` keeps it. */
  private[forge] def erasedUnless[A](
      kes: Sum6KesSigning.SigningKey,
      result: => Either[ForgeError, A]
  )(
      keeps: A => Boolean
  ): Either[ForgeError, A] =
    erasing(() => kes.forget(), result)(keeps)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def erasing[A](forget: () => Unit, result: => Either[ForgeError, A])(
      keeps: A => Boolean
  ): Either[ForgeError, A] =
    allCatch.either(result) match
      case Right(computed) =>
        if !computed.exists(keeps) then forget()
        computed
      case Left(thrown) =>
        forget()
        throw thrown
