// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes

/** BLAKE2b-256 of the exact raw genesis file bytes consumed by node setup. */
final case class GenesisFileHash(hash: Bytes)

/** The fields known by identity formats 1 and 2. They cannot establish which Alonzo or Conway
  * genesis was used and are therefore never treated as a complete current identity.
  */
final case class LegacyNetworkIdentity(
    magic: Long,
    shelleyGenesis: Bytes,
    byronGenesis: Option[Bytes]
):
  def matches(expected: NetworkIdentity): Boolean =
    magic == expected.magic &&
      shelleyGenesis == expected.shelleyGenesis &&
      byronGenesis == expected.byronGenesis

  def renderAs(version: FormatVersion): String =
    s"""${FileKind.IdentityFile.identifier(version)}
       |magic $magic
       |shelley-genesis ${shelleyGenesis.hex}
       |byron-genesis ${byronGenesis.fold("none")(_.hex)}
       |""".stripMargin

  def describe: String =
    s"magic $magic, shelley genesis ${shelleyGenesis.hex.take(16)}, byron genesis ${byronGenesis
        .fold("none")(_.hex.take(16))}"

/** Complete identity of a database: the protocol magic and the exact genesis inputs from which this
  * node assembled its initial state. The raw Shelley/Alonzo/Conway file bytes are hashed with
  * Blake2b-256; Byron keeps the existing canonical-genesis hash and chain-start semantics.
  */
final case class NetworkIdentity(
    magic: Long,
    shelleyGenesis: Bytes,
    byronGenesis: Option[Bytes],
    alonzoGenesis: GenesisFileHash,
    conwayGenesis: GenesisFileHash
):

  def render: String = renderAs(FileKind.IdentityFile.current)

  def renderAs(version: FormatVersion): String =
    if version.ordinal < FormatVersion.V6.ordinal then legacy.renderAs(version)
    else renderAs(version, IdentityProvenance.Fresh)

  def renderAs(version: FormatVersion, provenance: IdentityProvenance): String =
    s"""${FileKind.IdentityFile.identifier(version)}
       |magic $magic
       |shelley-genesis ${shelleyGenesis.hex}
       |byron-genesis ${byronGenesis.fold("none")(_.hex)}
       |alonzo-genesis ${NetworkIdentity.renderGenesisHash(alonzoGenesis)}
       |conway-genesis ${NetworkIdentity.renderGenesisHash(conwayGenesis)}
       |provenance ${IdentityProvenance.render(provenance)}
       |""".stripMargin

  def legacy: LegacyNetworkIdentity = LegacyNetworkIdentity(magic, shelleyGenesis, byronGenesis)

  def describe: String =
    s"${legacy.describe}, alonzo genesis ${NetworkIdentity.describeBinding(alonzoGenesis)}, " +
      s"conway genesis ${NetworkIdentity.describeBinding(conwayGenesis)}"

object NetworkIdentity:
  def renderGenesisHash(binding: GenesisFileHash): String = s"file:${binding.hash.hex}"

  private[ledger] def describeBinding(binding: GenesisFileHash): String =
    renderGenesisHash(binding)

  private def hash(value: String): Option[Bytes] =
    Bytes.fromHex(value).toOption.filter(_.size == 32)

  private def binding(value: String): Option[GenesisFileHash] =
    Option
      .when(value.startsWith("file:"))(value.drop(5))
      .flatMap(hash)
      .map(GenesisFileHash(_))

  /** Parse v1/v2 as incomplete legacy data; v6 is a complete identity with an adoption record. */
  def parseVersioned(
      what: String,
      text: String
  ): Either[StorageError, (FormatVersion, StoredNetworkIdentity)] =
    def corrupt(
        reason: StorageStructureError
    ): Either[StorageError, (FormatVersion, StoredNetworkIdentity)] =
      Left(StorageError.Corrupt(what, reason))
    text.linesIterator.map(_.trim).filter(_.nonEmpty).toList match
      case header :: body =>
        FileKind.IdentityFile.versionOf(what, header).flatMap { version =>
          if version.ordinal < FormatVersion.V6.ordinal then
            parseLegacyBody(what, body)
              .map(legacy => version -> StoredNetworkIdentity.Legacy(version, legacy))
          else
            parseCurrentBody(what, body).map((identity, provenance) =>
              (version, StoredNetworkIdentity.Complete(identity, provenance))
            )
        }
      case Nil => corrupt(StorageStructureError.EmptyIdentity)

  private def parseLegacyBody(
      what: String,
      body: List[String]
  ): Either[StorageError, LegacyNetworkIdentity] =
    def corrupt(reason: StorageStructureError) = Left(StorageError.Corrupt(what, reason))
    body match
      case magic :: shelley :: byron :: Nil =>
        (magic.split(' ').toList, shelley.split(' ').toList, byron.split(' ').toList) match
          case ("magic" :: m :: Nil, "shelley-genesis" :: s :: Nil, "byron-genesis" :: b :: Nil) =>
            (
              m.toLongOption,
              hash(s),
              if b == "none" then Some(None) else hash(b).map(Some(_))
            ) match
              case (Some(mm), Some(ss), Some(bb)) => Right(LegacyNetworkIdentity(mm, ss, bb))
              case _ => corrupt(StorageStructureError.InvalidIdentityFields)
          case _ => corrupt(StorageStructureError.UnexpectedIdentityLines)
      case _ => corrupt(StorageStructureError.UnexpectedIdentityLines)

  private def parseCurrentBody(
      what: String,
      body: List[String]
  ): Either[StorageError, (NetworkIdentity, IdentityProvenance)] =
    def corrupt(reason: StorageStructureError) = Left(StorageError.Corrupt(what, reason))
    body match
      case magic :: shelley :: byron :: alonzo :: conway :: provenance :: Nil =>
        (
          magic.split(' ').toList,
          shelley.split(' ').toList,
          byron.split(' ').toList,
          alonzo.split(' ').toList,
          conway.split(' ').toList,
          provenance.split(' ').toList
        ) match
          case (
                "magic" :: m :: Nil,
                "shelley-genesis" :: s :: Nil,
                "byron-genesis" :: b :: Nil,
                "alonzo-genesis" :: a :: Nil,
                "conway-genesis" :: c :: Nil,
                "provenance" :: p :: Nil
              ) =>
            val parsedByron = if b == "none" then Some(None) else hash(b).map(Some(_))
            (
              m.toLongOption,
              hash(s),
              parsedByron,
              binding(a),
              binding(c),
              IdentityProvenance.parse(p)
            ) match
              case (Some(mm), Some(ss), Some(bb), Some(aa), Some(cc), Some(pp)) =>
                Right((NetworkIdentity(mm, ss, bb, aa, cc), pp))
              case _ => corrupt(StorageStructureError.InvalidIdentityFields)
          case _ => corrupt(StorageStructureError.UnexpectedIdentityLines)
      case _ => corrupt(StorageStructureError.UnexpectedIdentityLines)

  /** Parse any registered identity format. */
  def parse(what: String, text: String): Either[StorageError, StoredNetworkIdentity] =
    parseVersioned(what, text).map(_._2)

/** Provenance is part of V6 so operator adoption is durable and cannot be mistaken for proof that
  * the historical database used these genesis files.
  */
enum IdentityProvenance derives CanEqual:
  case Fresh
  case OperatorConfirmedMissing
  case OperatorConfirmedLegacy(version: FormatVersion, contentDigest: Bytes)

object IdentityProvenance:
  private[ledger] def parse(value: String): Option[IdentityProvenance] =
    value match
      case "fresh"                      => Some(IdentityProvenance.Fresh)
      case "operator-confirmed-missing" => Some(IdentityProvenance.OperatorConfirmedMissing)
      case s if s.startsWith("operator-confirmed-legacy:") =>
        val parts = s.split(':').toList
        parts match
          case _ :: source :: digest :: Nil =>
            val version = source match
              case "v1" => Some(FormatVersion.V1)
              case "v2" => Some(FormatVersion.V2)
              case _    => None
            val parsedDigest =
              Option
                .when(digest.startsWith("blake2b256="))(digest.drop("blake2b256=".length))
                .flatMap(Bytes.fromHex(_).toOption)
                .filter(_.size == 32)
            (version, parsedDigest).mapN(IdentityProvenance.OperatorConfirmedLegacy.apply)
          case _ => None
      case _ => None

  private[ledger] def render(provenance: IdentityProvenance): String = provenance match
    case IdentityProvenance.Fresh                    => "fresh"
    case IdentityProvenance.OperatorConfirmedMissing => "operator-confirmed-missing"
    case IdentityProvenance.OperatorConfirmedLegacy(version, digest) =>
      s"operator-confirmed-legacy:${version.toString.toLowerCase}:blake2b256=${digest.hex}"

enum StoredNetworkIdentity derives CanEqual:
  case Legacy(version: FormatVersion, identity: LegacyNetworkIdentity)
  case Complete(identity: NetworkIdentity, provenance: IdentityProvenance)
