// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Json, JsonObject}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Node, Value}
import xyz.kd5ujc.scarab.ledger.json.{BoundedRatio, FromJson, NumberError, Scientific}

/** The genesis `StakePoolParams` as the reference's `FromJSON` reads it (cardano-ledger f649f975,
  * `State/StakePool.hs:293-360,467-483`). JSON names and URLs are unrestricted Text, unlike their
  * protocol-versioned CBOR decoders (`BaseTypes.hs:674-701`).
  */
object GenesisPoolFields:
  /** A pool field this reader refuses. The genesis pools were accepted at start-up by the
    * reference's `FromJSON StakePoolParams` ([[GenesisSchema]]), so a refusal here is a defect of
    * the reader, with no reference text of its own.
    */
  enum Error extends DomainError:
    case Port(value: Json, cause: NumberError)
    case Ipv4(value: String)
    case Ipv6(value: String)
    case Encoding(cause: Cbor.Error)
    case Number(field: String, cause: NumberError)

    def message: String = this match
      case Port(value, cause) =>
        s"defect: genesis pool relay port ${value.noSpaces}: ${cause.message}"
      case Ipv4(value)     => s"defect: genesis pool relay IPv4 $value is not an address"
      case Ipv6(value)     => s"defect: genesis pool relay IPv6 $value is not an address"
      case Encoding(cause) => s"defect: genesis pool metadata does not encode: ${cause.message}"
      case Number(field, cause) => s"defect: genesis pool $field: ${cause.message}"

  /** The parameters of the pool under genesis key `id`. The operator is the inner `poolId` (or its
    * old name `publicKey`), which `mkStakePoolState` drops; the state is keyed by `id`
    * (`Shelley/Transition.hs:526-543`). `publicKey` and `rewardAccount` are tried after any failure
    * of `poolId` and `accountAddress`, not only an absent one (`<|>`,
    * `State/StakePool.hs:467-481`). Coins are aeson's `Integer` (`Coin.hs:93-103`): any sign, and
    * no refusal of a negative pledge or cost anywhere on the genesis path (`mkStakePoolState`,
    * `State/StakePool.hs:256-270`).
    */
  def pool(id: String, j: Json): Either[ConfigError, (Bytes, PoolParams)] =
    val c = j.hcursor
    for
      key <- GenesisState.hex28(id)
      operator <- hash(c, "poolId", 28).orElse(hash(c, "publicKey", 28))
      vrf <- hash(c, "vrf", 32)
      pledge <- number(c, "pledge")(FromJson.integer)
      cost <- number(c, "cost")(FromJson.integer)
      margin <- number(c, "margin")(BoundedRatio.Unit.fromJson)
      account <- rewardAccount(c.downField("accountAddress"))
        .orElse(rewardAccount(c.downField("rewardAccount")))
      owners <- GenesisState.get[Vector[Json]](c, "owners").flatMap(_.traverse(hex(_, 28)))
      fields <- read(c)
    yield key -> PoolParams(
      operator,
      vrf,
      pledge,
      cost,
      margin,
      account,
      owners.toSet,
      fields._1,
      fields._2
    )

  private def number[A](c: ACursor, field: String)(
      read: Json => Either[NumberError, A]
  ): Either[ConfigError, A] =
    GenesisState.get[Json](c, field).flatMap(read(_).leftMap(e => failure(Error.Number(field, e))))

  /** A `Hash` from hex text of exactly `size` bytes, either case (`hashFromTextAsHex`). */
  private def hex(j: Json, size: Int): Either[ConfigError, Bytes] =
    for
      text <- j
        .as[String]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis pool", "hash", e)))
      bytes <- Bytes.fromHex(text).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
      _ <- Either.cond(
        bytes.size == size,
        (),
        ConfigError(ConfigProblem.ByteLength("hash", size, bytes.size))
      )
    yield bytes

  private def hash(c: ACursor, field: String, size: Int): Either[ConfigError, Bytes] =
    GenesisState.get[Json](c, field).flatMap(hex(_, size))

  /** `FromJSON AccountAddress` (`Address.hs:231-237`): a `Network` (`Testnet` or `Mainnet`) and a
    * credential, whose script forms are tried before its key forms, each after any failure of the
    * one before (`Credential.hs:159-165`).
    */
  private def rewardAccount(c: ACursor): Either[ConfigError, RewardAccount] =
    val cred = c.downField("credential")
    for
      network <- GenesisState.get[String](c, "network").flatMap {
        case "Testnet" => Right(0)
        case "Mainnet" => Right(1)
        case other =>
          Left(ConfigError(ConfigProblem.Selection("network", other, Set("Testnet", "Mainnet"))))
      }
      _ <- GenesisState.get[JsonObject](c, "credential")
      credential <- hash(cred, "scriptHash", 28)
        .orElse(hash(cred, "script hash", 28))
        .map(Credential.Script(_))
        .orElse(hash(cred, "keyHash", 28).orElse(hash(cred, "key hash", 28)).map(Credential.Key(_)))
    yield RewardAccount(network, credential)

  /** The encoded relays and optional metadata of a genesis pool. No relays is `Bytes.empty`, the
    * form the snapshot codec reads an empty sequence back as (`NodeTypeCodecs.relays`).
    */
  def read(c: ACursor): Either[ConfigError, (Bytes, Option[Bytes])] =
    for
      relays <- GenesisState.get[Vector[Json]](c, "relays").flatMap(_.traverse(relay))
      metadata <- GenesisState.get[Json](c, "metadata").flatMap { j =>
        if j.isNull then Right(None)
        else
          for
            url <- GenesisState.get[String](j.hcursor, "url")
            hex <- GenesisState.get[String](j.hcursor, "hash")
            hash <- Bytes.fromHex(hex).leftMap(e => ConfigError(ConfigProblem.Hex(e)))
            encoded <- Cbor
              .encode(
                Value.Arr(
                  Vector(
                    Node(Value.Text(url), Bytes.empty),
                    Node(Value.ByteString(hash), Bytes.empty)
                  )
                ),
                Cbor.Limits(
                  maxInputBytes = Int.MaxValue,
                  maxItems = Int.MaxValue,
                  maxStringBytes = Int.MaxValue
                )
              )
              .leftMap(e => failure(Error.Encoding(e)))
          yield Some(encoded)
      }
    yield (if relays.isEmpty then Bytes.empty else PoolRelays.encode(relays)) -> metadata

  private def failure(e: Error): ConfigError = ConfigError(ConfigProblem.GenesisPool(e))

  private def relay(j: Json): Either[ConfigError, PoolRelay] =
    def fields(name: String) = GenesisState.get[JsonObject](j.hcursor, name).map(_.toJson.hcursor)
    def port(c: ACursor): Either[ConfigError, Option[RelayPort]] =
      c.downField("port")
        .as[Option[Json]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis pool relay", "port", e)))
        .flatMap(_.traverse { value =>
          // `FromJSON Word16`: `toBoundedInteger` of the Scientific as written.
          FromJson
            .word(16)(value)
            .flatMap(n =>
              RelayPort.fromInt(n.toInt).toRight(NumberError.NotWord(16, Scientific(n, 0)))
            )
            .leftMap(e => failure(Error.Port(value, e)))
        })
    def ip[A](c: ACursor, name: String, parse: String => Option[A], error: String => Error) =
      c.downField(name)
        .as[Option[String]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis pool relay", name, e)))
        .flatMap(_.traverse(s => Either.fromOption(parse(s), failure(error(s)))))
    val address = fields("single host address").flatMap { c =>
      (port(c), ip(c, "IPv4", ipv4, Error.Ipv4(_)), ip(c, "IPv6", ipv6, Error.Ipv6(_)))
        .mapN(PoolRelay.SingleHostAddr(_, _, _))
    }
    // The reference's `asum` tries the next variant after any failure, including a malformed
    // value under an earlier variant's key. Extra object fields do not select a different sum.
    address
      .orElse {
        fields("single host name").flatMap(c =>
          (port(c), GenesisState.get[String](c, "dnsName")).mapN(PoolRelay.SingleHostName(_, _))
        )
      }
      .orElse {
        fields("multi host name")
          .flatMap(GenesisState.get[String](_, "dnsName"))
          .map(PoolRelay.MultiHostName(_))
      }

  /** cardano-base 0.1.6.0 `IP.hs` delegates to iproute 1.7.15 `Addr.hs:513-546`: four decimal
    * octets, no leading zeros, and ASCII spaces before/after the address.
    */
  private[ledger] def ipv4(s: String): Option[Ipv4] =
    val parts = s.dropWhile(_ == ' ').reverse.dropWhile(_ == ' ').reverse.split("\\.", -1).toVector
    Option
      .when(parts.size == 4)(parts)
      .flatMap(_.traverse { p =>
        Option
          .when(
            p.nonEmpty && p.length <= 3 && p.forall(c => c >= '0' && c <= '9') &&
              (p.length == 1 || p.head != '0')
          )(p)
          .flatMap(_.toIntOption)
          .filter(_ <= 255)
      })
      .map(_.foldLeft(0)((acc, octet) => (acc << 8) | octet))
      .map(Ipv4(_))

  /** The IPv6 alternatives in iproute `Addr.hs:553-640`, including the IPv4 tail. `readMaybe`
    * consumes trailing whitespace for the hex form; the embedded IPv4 parser terminates itself.
    */
  private[ledger] def ipv6(s: String): Option[Ipv6] =
    def whitespace(c: Char): Boolean =
      c == ' ' || (c >= '\t' && c <= '\r') || Character.getType(c) == Character.SPACE_SEPARATOR
    val text = s.dropWhile(_ == ' ')
    val expanded =
      if text.contains('.') then
        val colon = text.lastIndexOf(':')
        Option.when(colon >= 0)(colon).flatMap { i =>
          ipv4(text.drop(i + 1)).filter(_ => !text.drop(i + 1).startsWith(" ")).map { v4 =>
            text.take(
              i + 1
            ) + ((v4.word >>> 16) & 0xffff).toHexString + ":" + (v4.word & 0xffff).toHexString
          }
        }
      else Some(text.reverse.dropWhile(whitespace).reverse)
    def words(part: String): Option[Vector[Int]] =
      if part.isEmpty then Some(Vector.empty)
      else
        part.split(":", -1).toVector.traverse { p =>
          Option
            .when(
              p.nonEmpty && p.length <= 4 && p.forall(c =>
                (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
              )
            )(p)
            .map(Integer.parseInt(_, 16))
        }
    expanded
      .flatMap { value =>
        value.split("::", -1).toVector match
          case Vector(full) => words(full).filter(_.size == 8)
          case Vector(before, after) =>
            (words(before), words(after)).tupled
              .filter((a, b) => a.size + b.size < 8)
              .map((a, b) => a ++ Vector.fill(8 - a.size - b.size)(0) ++ b)
          case _ => None
      }
      .map { groups =>
        def half(xs: Vector[Int]) = xs.foldLeft(0L)((acc, n) => (acc << 16) | n.toLong)
        Ipv6(half(groups.take(4)), half(groups.drop(4)))
      }
