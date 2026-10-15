// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}

import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import io.circe.{ACursor, Decoder, Json, parser}
import java.nio.file.{Files, Path}

/** A relay as a topology file writes it: `{"address": "host-or-ip", "port": 3001}`. */
final case class AccessPoint(host: Host, port: Port):
  def socketAddress: SocketAddress[Host] = SocketAddress(host, port)
  override def toString: String = s"$host:$port"

/** A group of `localRoots`: peers the operator names. `hotValency` (`valency` in the older
  * spelling) is how many of them the reference keeps active; this node follows every one of them.
  */
final case class LocalRoots(
    accessPoints: Vector[AccessPoint],
    advertise: Boolean,
    hotValency: Int,
    warmValency: Int,
    trustable: Boolean,
    behindFirewall: Boolean,
    diffusionMode: String
)

/** A group of `publicRoots`. */
final case class PublicRoots(accessPoints: Vector[AccessPoint], advertise: Boolean)

/** `useLedgerAfterSlot`: when the reference starts taking peers from the ledger's stake pool relays
  * (a number above 0: after that slot; 0: always; below 0 or absent: never).
  */
enum LedgerPeers:
  case Disabled
  case Always
  case After(slot: BigInt)

/** A P2P topology (`topology.json`), as `cardano-node` reads it (`Cardano.Node.Configuration.
  * TopologyP2P`): `localRoots` and `publicRoots` are required, `bootstrapPeers` (a list, or null
  * for none), `useLedgerAfterSlot` and `peerSnapshotFile` are optional. The node follows the peers
  * of the local roots and the bootstrap peers (and the public roots when there are none of those);
  * it has no ledger peers, peer snapshot or peer sharing, so those fields are read and recorded.
  */
final case class Topology(
    localRoots: Vector[LocalRoots],
    publicRoots: Vector[PublicRoots],
    /** `None` when the file says `null` or leaves the key out (bootstrap peers off). */
    bootstrapPeers: Option[Vector[AccessPoint]],
    useLedgerAfterSlot: LedgerPeers,
    peerSnapshotFile: Option[String]
):
  /** The peers to follow, in order and without repeats: local roots, bootstrap peers, and, when
    * there are none of either, the public roots.
    */
  def peers: Vector[SocketAddress[Host]] =
    val configured = localRoots.flatMap(_.accessPoints) ++ bootstrapPeers.getOrElse(Vector.empty)
    val all = if configured.nonEmpty then configured else publicRoots.flatMap(_.accessPoints)
    all.map(_.socketAddress).distinct

object Topology:
  private def bad[A](reason: String): Either[ConfigError, A] =
    Left(ConfigError(s"topology: $reason"))

  private def unsupported[A](reason: String): Either[ConfigError, A] =
    Left(ConfigError(s"topology: $reason", ConfigProblem.UnsupportedTopology))

  private def field[A: Decoder](c: ACursor, name: String): Either[ConfigError, A] =
    c.downField(name).as[A].leftMap(e => ConfigError(s"topology $name: ${e.message}"))

  private def optional[A: Decoder](c: ACursor, name: String): Either[ConfigError, Option[A]] =
    c.downField(name).focus.filterNot(_.isNull).traverse { j =>
      j.as[A].leftMap(e => ConfigError(s"topology $name: ${e.message}"))
    }

  /** `{"address": ..., "port": ...}`. An address without a port is a DNS SRV name, which this node
    * does not resolve; the unspecified address (0.0.0.0, ::) is never a destination.
    */
  private[node] def accessPoint(j: Json): Either[ConfigError, AccessPoint] =
    val c = j.hcursor
    for
      address <- field[String](c, "address")
      given_ <- optional[Json](c, "port")
      port <- given_ match
        case None =>
          unsupported(s"$address has no port: DNS SRV access points are not supported")
        case Some(p) =>
          // a number, as for the reference ("The port and valency fields should be numerical")
          p.asNumber
            .flatMap(_.toInt)
            .flatMap(Port.fromInt)
            .toRight(ConfigError(s"topology: port ${p.noSpaces} is not a port number"))
      host <- Host
        .fromString(address.stripSuffix("."))
        .toRight(ConfigError(s"topology: $address is not a host name or an IP address"))
      _ <- host match
        case ip: IpAddress if ip.toBytes.forall(_ == 0) =>
          bad(s"$ip is not a valid destination address")
        case _ => Right(())
    yield AccessPoint(host, port)

  private def accessPoints(c: ACursor): Either[ConfigError, Vector[AccessPoint]] =
    field[Vector[Json]](c, "accessPoints").flatMap(_.traverse(accessPoint))

  private def localRoots(j: Json): Either[ConfigError, LocalRoots] =
    val c = j.hcursor
    for
      points <- accessPoints(c)
      hot <- (optional[Int](c, "valency"), optional[Int](c, "hotValency")).tupled.flatMap {
        case (Some(v), _) => Right(v)
        case (_, Some(v)) => Right(v)
        case _            => bad("a local root group needs valency (or hotValency)")
      }
      advertise <- optional[Boolean](c, "advertise").map(_.getOrElse(false))
      firewall <- optional[Boolean](c, "behindFirewall").map(_.getOrElse(false))
      warm <- optional[Int](c, "warmValency").map(_.getOrElse(hot))
      mode <- optional[String](c, "diffusionMode").map(_.getOrElse("InitiatorAndResponder"))
      _ <- Either.cond(
        Set("InitiatorOnly", "InitiatorAndResponder")(mode),
        (),
        ConfigError(
          s"topology diffusionMode $mode: expected InitiatorOnly or InitiatorAndResponder"
        )
      )
      trustable <- optional[Boolean](c, "trustable").map(_.getOrElse(false))
    yield LocalRoots(points, advertise, hot, warm, trustable, firewall, mode)

  private def publicRoots(j: Json): Either[ConfigError, PublicRoots] =
    val c = j.hcursor
    (accessPoints(c), optional[Boolean](c, "advertise").map(_.getOrElse(false)))
      .mapN(PublicRoots(_, _))

  private def ledgerPeers(c: ACursor): Either[ConfigError, LedgerPeers] =
    optional[BigDecimal](c, "useLedgerAfterSlot").map {
      case None                     => LedgerPeers.Disabled
      case Some(n) if n > 0         => LedgerPeers.After(n.toBigInt)
      case Some(n) if n.signum == 0 => LedgerPeers.Always
      case Some(_)                  => LedgerPeers.Disabled
    }

  /** Read a topology. The legacy format (a `Producers` list, for a node without P2P) is refused as
    * unsupported, as the reference refuses it when P2P is on.
    */
  def parse(text: String): Either[ConfigError, Topology] =
    for
      json <- parser.parse(text).leftMap(e => ConfigError(s"topology is not JSON: ${e.message}"))
      c = json.hcursor
      _ <- Either.cond(json.isObject, (), ConfigError("topology is not a JSON object"))
      _ <- Either.cond(
        c.downField("Producers").focus.isEmpty,
        (),
        ConfigError(
          "topology: the legacy format (Producers) is not supported, expected the P2P format " +
            "(localRoots, publicRoots, bootstrapPeers)",
          ConfigProblem.UnsupportedTopology
        )
      )
      locals <- field[Vector[Json]](c, "localRoots").flatMap(_.traverse(localRoots))
      publics <- field[Vector[Json]](c, "publicRoots").flatMap(_.traverse(publicRoots))
      bootstrap <- optional[Vector[Json]](c, "bootstrapPeers")
        .flatMap(_.traverse(_.traverse(accessPoint)))
      ledger <- ledgerPeers(c)
      snapshot <- optional[String](c, "peerSnapshotFile")
      // as `isValidTrustedPeerConfiguration`: an empty bootstrap list needs a trustable local root
      _ <- Either.cond(
        bootstrap.forall(_.nonEmpty) || locals.exists(_.trustable),
        (),
        ConfigError(
          "topology: bootstrapPeers is empty and no local root is trustable, so there is no " +
            "peer to make progress from"
        )
      )
    yield Topology(locals, publics, bootstrap, ledger, snapshot)

  def read(path: Path): Either[ConfigError, Topology] =
    Either
      .catchNonFatal(new String(Files.readAllBytes(path), "UTF-8"))
      .leftMap(e =>
        ConfigError(
          s"cannot read the topology $path: ${e.getMessage}",
          ConfigProblem.FileMissing("topology", path.toString)
        )
      )
      .flatMap(parse)
