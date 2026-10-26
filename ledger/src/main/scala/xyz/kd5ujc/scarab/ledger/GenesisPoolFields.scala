// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{ACursor, Json, JsonObject}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Node, Value}

/** The JSON fields of `StakePoolParams` that genesis injects unchanged. JSON names and URLs are
  * unrestricted Text, unlike their protocol-versioned CBOR decoders (cardano-ledger f649f975,
  * `BaseTypes.hs:674-701`, `State/StakePool.hs:293-360,467-483`).
  */
object GenesisPoolFields:
  enum Error extends DomainError:
    case Port(value: Json)
    case Ipv4(value: String)
    case Ipv6(value: String)
    case Encoding(cause: Cbor.Error)
    def message: String = toString

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
    yield PoolRelays.encode(relays) -> metadata

  private def failure(e: Error): ConfigError = ConfigError(ConfigProblem.GenesisPool(e))

  private def relay(j: Json): Either[ConfigError, PoolRelay] =
    def fields(name: String) = GenesisState.get[JsonObject](j.hcursor, name).map(_.toJson.hcursor)
    def port(c: ACursor): Either[ConfigError, Option[RelayPort]] =
      c.downField("port")
        .as[Option[Json]]
        .leftMap(e => ConfigError(ConfigProblem.JsonField("genesis pool relay", "port", e)))
        .flatMap(_.traverse { value =>
          Either.fromOption(
            GenesisPoolParams.relayPort(value),
            failure(Error.Port(value))
          )
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
  private def ipv4(s: String): Option[Ipv4] =
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
  private def ipv6(s: String): Option[Ipv6] =
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
