// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** Which network a database belongs to: the protocol magic, the hash of the Shelley genesis file
  * (Blake2b-256 of the file as read, the hash the Haskell node configuration names) and, for a
  * chain that starts in Byron, the Byron genesis hash. The Byron genesis hash is also the
  * predecessor the first block of the chain names; a chain that starts in Shelley has none.
  */
final case class NetworkIdentity(magic: Long, shelleyGenesis: Bytes, byronGenesis: Option[Bytes]):

  /** The text of the identity file, see [[NetworkIdentity.parse]]. */
  def render: String =
    s"""${NetworkIdentity.Header}
       |magic $magic
       |shelley-genesis ${shelleyGenesis.hex}
       |byron-genesis ${byronGenesis.fold("none")(_.hex)}
       |""".stripMargin

  /** One line for messages. */
  def describe: String =
    s"magic $magic, shelley genesis ${shelleyGenesis.hex.take(16)}, byron genesis ${byronGenesis
        .fold("none")(_.hex.take(16))}"

object NetworkIdentity:
  val Header = "cardano-scala-lab network identity v1"

  /** The inverse of [[NetworkIdentity.render]]; a different version line is a
    * [[StorageError.VersionMismatch]], any other deviation is [[StorageError.Corrupt]].
    */
  def parse(what: String, text: String): Either[StorageError, NetworkIdentity] =
    def corrupt(reason: String): Either[StorageError, NetworkIdentity] =
      Left(StorageError.Corrupt(what, reason))
    def hash(value: String): Option[Bytes] =
      Bytes.fromHex(value).toOption.filter(_.size == 32)
    text.linesIterator.map(_.trim).filter(_.nonEmpty).toList match
      case header :: rest if header != Header && header.startsWith("cardano-scala-lab network") =>
        Left(StorageError.VersionMismatch(what, header, Header))
      case Header :: magic :: shelley :: byron :: Nil =>
        (magic.split(' ').toList, shelley.split(' ').toList, byron.split(' ').toList) match
          case (
                "magic" :: m :: Nil,
                "shelley-genesis" :: s :: Nil,
                "byron-genesis" :: b :: Nil
              ) =>
            (
              m.toLongOption,
              hash(s),
              if b == "none" then Some(None) else hash(b).map(Some(_))
            ) match
              case (Some(mm), Some(ss), Some(bb)) => Right(NetworkIdentity(mm, ss, bb))
              case _                              => corrupt("malformed magic or genesis hash")
          case _ => corrupt("unexpected lines")
      case _ => corrupt("not a network identity file")
