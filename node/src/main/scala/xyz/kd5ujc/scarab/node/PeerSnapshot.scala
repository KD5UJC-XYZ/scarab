// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.codec.DomainError
import cats.data.NonEmptyVector
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import io.circe.{ACursor, Json, parser}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{LedgerRelay, RelayIp, RelayPort}
import xyz.kd5ujc.scarab.network.selection.{
  AccPoolStake,
  AfterSlot,
  ConsensusMode,
  LedgerPeerSnapshot,
  PoolStake,
  SnapshotPoint,
  SnapshotPool,
  UseLedgerPeers,
  WithOrigin
}

/** Why a peer snapshot file could not be read. `where` is the JSON path of the part at fault. */
enum PeerSnapshotError extends DomainError:
  case Unreadable(path: String, cause: Throwable)
  case NotJson(cause: io.circe.ParsingFailure)

  /** A required field is absent. */
  case Missing(where: String)

  /** A field is present and of the wrong type or outside its range. */
  case Malformed(where: String)

  /** `version` / `NodeToClientVersion` names a format this node does not read. */
  case UnsupportedVersion(version: BigInt)

  /** Pool `index` has no relay (the reference reads `relays` as a `NonEmpty`). */
  case NoRelays(index: Int)

/** Why the node does not start with the peer snapshot it was given (`updateLedgerPeerSnapshot`,
  * `cardano-node/src/Cardano/Node/Run.hs` 705-760, at startup: the reference stops with each of
  * these).
  */
enum PeerSnapshotRefusal extends DomainError:
  /** A version 1 or 2 file: "Unsupported legacy peer snapshot version." */
  case LegacyVersion

  /** The snapshot is at the origin: "GenesisPoint is not a valid value in the peer snapshot file".
    */
  case AtOrigin

  /** The snapshot is of another network. */
  case WrongMagic(expected: Long, found: Long)

  /** The snapshot is older than `useLedgerAfterSlot` (`LedgerPeerSnapshotTooOld`). */
  case TooOld(after: BigInt, snapshot: BigInt)

  /** Genesis mode cannot make a safe ledger-peer set from an unreadable configured snapshot. */
  case UnreadableSnapshot(error: PeerSnapshotError)

  def message: String = this match
    case LegacyVersion => "the peer snapshot file is of a legacy version, which is not supported"
    case AtOrigin      => "the peer snapshot is at the origin, which is not a valid point"
    case WrongMagic(expected, found) =>
      s"the network magic $expected differs from the peer snapshot's $found"
    case TooOld(after, snapshot) =>
      s"the peer snapshot slot $snapshot is older than useLedgerAfterSlot $after: update the file"
    case UnreadableSnapshot(error) =>
      s"the Genesis peer snapshot could not be read: ${error.toString}"

/** What the node does with the peer snapshot file of its topology. */
enum SnapshotUse:
  /** No file is named, or ledger peers are off (the reference ignores it, with a warning). */
  case Unused

  /** The file could not be read; as in the reference's Praos mode the node carries on without it
    * and says so.
    */
  case Ignored(error: PeerSnapshotError)

  case Loaded(file: LedgerPeerSnapshot[LedgerRelay])

  def snapshot: Option[LedgerPeerSnapshot[LedgerRelay]] = this match
    case Loaded(file) => Some(file)
    case _            => None

/** The big ledger peer snapshot file named by `peerSnapshotFile` in the topology: the JSON
  * `FromJSON (LedgerPeerSnapshot BigLedgerPeers)` reads
  * (`Ouroboros.Network.PeerSelection.LedgerPeers.Type`, ouroboros-network 1.2.0.0,
  * `Type.hs:254-299`), with the relays of `FromJSON LedgerRelayAccessPoint`
  * (`RelayAccessPoint.hs:191-210`).
  *
  *   - The format is named by `version` (1, 2) or, in the newer files, `NodeToClientVersion` (23).
  *   - Versions 1 and 2 carry `slotNo` (a number, or `"origin"`); version 23 carries `Point`
  *     (`"origin"` or `{"blockPointSlot", "blockPointHash"}`) and `NetworkMagic`.
  *   - `bigLedgerPools` is a list of `{accumulatedStake, relativeStake, relays}`; the stakes are
  *     JSON numbers and are read as the exact decimal they are written as (the reference writes
  *     them from a `Double`, `fromRational @Double`, and reads them as a `Rational`).
  *   - A relay is `{"address", "port"}`. With a port that is an integer, an address that is an IP
  *     address is an address and any other is a domain, written with a trailing dot added; without
  *     a port, or with a port that is not an integer, it is an SRV domain, written with the dot
  *     added too. (The ledger's own relays do not get the dot: `PeerSelection.hs:50-57` of
  *     ouroboros-consensus.) A port outside 0 to 65535, which the reference wraps into a `Word16`,
  *     is refused here.
  */
object PeerSnapshot:
  type Result[A] = Either[PeerSnapshotError, A]

  def read(path: Path): Result[LedgerPeerSnapshot[LedgerRelay]] =
    Either
      .catchNonFatal(new String(Files.readAllBytes(path), "UTF-8"))
      .leftMap(e => PeerSnapshotError.Unreadable(path.toString, e))
      .flatMap(decode)

  /** The snapshot the node starts with, as `updateLedgerPeerSnapshot` decides it (Praos mode): none
    * unless ledger peers are on and a file is named; a file that cannot be read is ignored; one
    * that reads but is of a legacy version, at the origin, of another network, or older than
    * `useLedgerAfterSlot` stops the node.
    */
  def use(
      file: Option[Path],
      networkMagic: Long,
      ledger: UseLedgerPeers,
      consensusMode: ConsensusMode = ConsensusMode.PraosMode
  ): Either[PeerSnapshotRefusal, SnapshotUse] =
    (file, ledger) match
      case (Some(path), UseLedgerPeers.Use(after)) =>
        read(path) match
          case Left(error) if consensusMode == ConsensusMode.GenesisMode =>
            Left(PeerSnapshotRefusal.UnreadableSnapshot(error))
          case Left(error)                        => Right(SnapshotUse.Ignored(error))
          case Right(LedgerPeerSnapshot.V2(_, _)) => Left(PeerSnapshotRefusal.LegacyVersion)
          case Right(s @ LedgerPeerSnapshot.V23(point, magic, _)) =>
            point match
              case SnapshotPoint.Origin => Left(PeerSnapshotRefusal.AtOrigin)
              case SnapshotPoint.Block(slot, _) =>
                if magic != networkMagic then
                  Left(PeerSnapshotRefusal.WrongMagic(networkMagic, magic))
                else
                  after match
                    case AfterSlot.After(n) if slot < n =>
                      Left(PeerSnapshotRefusal.TooOld(n, slot))
                    case _ => Right(SnapshotUse.Loaded(s))
      case _ => Right(SnapshotUse.Unused)

  def decode(text: String): Result[LedgerPeerSnapshot[LedgerRelay]] =
    parser.parse(text).leftMap(e => PeerSnapshotError.NotJson(e)).flatMap { json =>
      val root = json.hcursor
      for
        version <- version(root)
        snapshot <- version.toInt match
          case 1 | 2 => v2(root)
          case 23    => v23(root)
          case _     => Left(PeerSnapshotError.UnsupportedVersion(version))
      yield snapshot
    }

  // ---------------------------------------------------------------------------------- fields

  private def version(root: ACursor): Result[BigInt] =
    // `v .: "version" <|> v .: "NodeToClientVersion"`
    val named = root.downField("version").focus.orElse(root.downField("NodeToClientVersion").focus)
    named match
      case None => Left(PeerSnapshotError.Missing("version"))
      case Some(j) =>
        j.asNumber.flatMap(_.toBigInt).toRight(PeerSnapshotError.Malformed("version"))

  private def required(c: ACursor, name: String): Result[Json] =
    c.downField(name).focus.toRight(PeerSnapshotError.Missing(name))

  private def nonNegativeInteger(j: Json, where: String): Result[BigInt] =
    j.asNumber
      .flatMap(_.toBigInt)
      .filter(_.signum >= 0)
      .toRight(PeerSnapshotError.Malformed(where))

  private def v2(root: ACursor): Result[LedgerPeerSnapshot[LedgerRelay]] =
    for
      slot <- required(root, "slotNo").flatMap(slotOrOrigin(_, "slotNo"))
      pools <- pools(root)
    yield LedgerPeerSnapshot.V2(slot, pools)

  private def v23(root: ACursor): Result[LedgerPeerSnapshot[LedgerRelay]] =
    for
      point <- required(root, "Point").flatMap(point)
      magic <- required(root, "NetworkMagic").flatMap(nonNegativeInteger(_, "NetworkMagic"))
      _ <- Either.cond(magic <= 0xffffffffL, (), PeerSnapshotError.Malformed("NetworkMagic"))
      pools <- pools(root)
    yield LedgerPeerSnapshot.V23(point, magic.toLong, pools)

  /** `WithOrigin SlotNo`: the string `"origin"` or a slot number (`Cardano.Slotting.Slot`). */
  private def slotOrOrigin(j: Json, where: String): Result[WithOrigin[BigInt]] =
    if j.asString.contains("origin") then Right(WithOrigin.Origin)
    else nonNegativeInteger(j, where).map(WithOrigin.At(_))

  private def point(j: Json): Result[SnapshotPoint] =
    if j.asString.contains("origin") then Right(SnapshotPoint.Origin)
    else
      val c = j.hcursor
      for
        slot <- required(c, "blockPointSlot").flatMap(nonNegativeInteger(_, "Point.blockPointSlot"))
        hashText <- required(c, "blockPointHash")
          .flatMap(_.asString.toRight(PeerSnapshotError.Malformed("Point.blockPointHash")))
        hash <- Bytes
          .fromHex(hashText)
          .leftMap(_ => PeerSnapshotError.Malformed("Point.blockPointHash"))
      yield SnapshotPoint.Block(slot, hash)

  private def pools(root: ACursor): Result[Vector[SnapshotPool[LedgerRelay]]] =
    required(root, "bigLedgerPools")
      .flatMap(_.asArray.toRight(PeerSnapshotError.Malformed("bigLedgerPools")))
      .flatMap(_.zipWithIndex.traverse((j, i) => pool(j, i)))

  private def stake(c: ACursor, name: String, index: Int): Result[Rational] =
    val where = s"bigLedgerPools[$index].$name"
    c.downField(name).focus match
      case None => Left(PeerSnapshotError.Missing(where))
      case Some(j) =>
        j.asNumber
          .flatMap(_.toBigDecimal)
          .map(Rational.fromDecimal)
          .filter(_ >= Rational.zero)
          .toRight(PeerSnapshotError.Malformed(where))

  private def pool(j: Json, index: Int): Result[SnapshotPool[LedgerRelay]] =
    val c = j.hcursor
    for
      accumulated <- stake(c, "accumulatedStake", index)
      relative <- stake(c, "relativeStake", index)
      listed <- required(c, "relays")
        .flatMap(_.asArray.toRight(PeerSnapshotError.Malformed(s"bigLedgerPools[$index].relays")))
      relays <- listed.zipWithIndex.traverse((r, k) =>
        relay(r, s"bigLedgerPools[$index].relays[$k]")
      )
      nonEmpty <- NonEmptyVector.fromVector(relays).toRight(PeerSnapshotError.NoRelays(index))
    yield SnapshotPool(AccPoolStake(accumulated), PoolStake(relative), nonEmpty)

  private def fullyQualified(name: String): String = if name.endsWith(".") then name else name + "."

  private def relay(j: Json, where: String): Result[LedgerRelay] =
    val c = j.hcursor
    for
      address <- required(c, "address")
        .flatMap(_.asString.toRight(PeerSnapshotError.Malformed(s"$where.address")))
      // `parseMaybe (o .: "port")`: a port that is not an integer is no port
      port = c.downField("port").focus.flatMap(_.asNumber).flatMap(_.toBigInt)
      relay <- port match
        case None => Right(LedgerRelay.Srv(fullyQualified(address)))
        case Some(p) =>
          Option
            .when(p.isValidInt)(p.toInt)
            .flatMap(RelayPort.fromInt)
            .toRight(PeerSnapshotError.Malformed(s"$where.port"))
            .map { port =>
              IpAddress
                .fromString(address)
                .flatMap(ip => RelayIp.fromOctets(Bytes.fromArray(ip.toBytes))) match
                case Some(ip) => LedgerRelay.Address(ip, port)
                case None     => LedgerRelay.Domain(fullyQualified(address), port)
            }
    yield relay
