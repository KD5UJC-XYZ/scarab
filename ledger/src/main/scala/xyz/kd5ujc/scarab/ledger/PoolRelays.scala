// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyVector
import cats.syntax.all.*
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** A TCP port as a pool registers it: the reference's `Port`, a `Word16`
  * (`Cardano.Ledger.BaseTypes`, `newtype Port = Port {portToWord16 :: Word16}`,
  * `BaseTypes.hs:699`).
  */
opaque type RelayPort = Int

object RelayPort:
  val MaxValue: Int = 0xffff

  def fromInt(n: Int): Option[RelayPort] = Option.when(n >= 0 && n <= MaxValue)(n)

  extension (p: RelayPort) def toInt: Int = p

/** An IP address of a relay, kept as the bytes the registration carries, in network order: four
  * octets, or sixteen. The reference holds `Cardano.Base.IP.IPv4/IPv6`; here the value is the
  * address and nothing else, so two spellings of one address are one value.
  */
sealed trait RelayIp:
  /** The address in network order (4 or 16 bytes). */
  def octets: Bytes

  /** The textual form: dotted decimal, or eight colon-separated groups with the longest run of zero
    * groups (two or more) elided, as RFC 5952 writes it.
    */
  def render: String

/** The four octets, most significant first (`192.0.2.1` is `c0 00 02 01`). */
final case class Ipv4(word: Int) extends RelayIp:
  def octets: Bytes = Bytes.fromArray(RelayIp.be(word.toLong & 0xffffffffL, 4))
  def render: String = (3 to 0 by -1).map(i => (word >>> (8 * i)) & 0xff).mkString(".")

/** The sixteen octets, most significant first, as two big-endian 64-bit halves.
  *
  * A registration does *not* hold them in this order: it holds the four 32-bit words of the address
  * (the first four octets as one number, and so on), each written little-endian (`ipv6ToBytes`,
  * cardano-ledger-binary `Encoder.hs:569-575`, `putWord32le` of each word of `fromIPv6w`). So
  * `2a02:c206:2242:2138::1` is `06c2022a 38214222 00000000 01000000` there, as the registrations of
  * mainnet pool1zj4h7kc6v53q9r8x3fwzfuayf2frwme8u5k86gu0uzeeuvqxgul show
  * (`fixtures/mainnet/pool-relays-ipv6.json`). [[wire]] and [[Ipv6.fromWire]] are that form.
  */
final case class Ipv6(high: Long, low: Long) extends RelayIp:
  def octets: Bytes = Bytes.fromArray(RelayIp.be(high, 8) ++ RelayIp.be(low, 8))

  /** The registration's form: each four octets reversed. */
  def wire: Bytes = Bytes.fromArray(Ipv6.swapWords(octets.toArray))
  def render: String =
    val groups = (0 until 8).map { i =>
      val word = if i < 4 then high else low
      ((word >>> (16 * (3 - i % 4))) & 0xffff).toInt
    }.toVector
    RelayIp.compress(groups)

object Ipv6:
  private[ledger] def swapWords(raw: Array[Byte]): Array[Byte] =
    Array.tabulate(raw.length)(i => raw((i / 4) * 4 + 3 - i % 4))

  /** The address of the 16 bytes of a registration (four little-endian words); none for any other
    * length.
    */
  def fromWire(b: Bytes): Option[Ipv6] =
    if b.size != 16 then None
    else RelayIp.fromOctets(Bytes.fromArray(swapWords(b.toArray))).collect { case v6: Ipv6 => v6 }

object RelayIp:
  /** `n` big-endian bytes of the low bits of `value`. */
  private[ledger] def be(value: Long, n: Int): Array[Byte] =
    Array.tabulate(n)(i => ((value >>> (8 * (n - 1 - i))) & 0xff).toByte)

  private def long(b: Bytes, from: Int, n: Int): Long =
    (from until from + n).foldLeft(0L)((acc, i) => (acc << 8) | (b(i) & 0xffL))

  /** The address of 4 or 16 bytes in network order; none for any other length. */
  def fromOctets(b: Bytes): Option[RelayIp] = b.size match
    case 4  => Some(Ipv4(long(b, 0, 4).toInt))
    case 16 => Some(Ipv6(long(b, 0, 8), long(b, 8, 8)))
    case _  => None

  private[ledger] def compress(groups: Vector[Int]): String =
    // the longest run of zero groups, the first one on a tie, if it has two or more
    val runs = groups.indices
      .filter(i => groups(i) == 0 && (i == 0 || groups(i - 1) != 0))
      .map(start => (start, groups.drop(start).takeWhile(_ == 0).size))
    val best = runs.foldLeft(Option.empty[(Int, Int)]) {
      case (None, r)                   => Some(r)
      case (Some(b), r) if r._2 > b._2 => Some(r)
      case (kept, _)                   => kept
    }
    best.filter(_._2 >= 2) match
      case None => groups.map(_.toHexString).mkString(":")
      case Some((start, length)) =>
        val before = groups.take(start).map(_.toHexString).mkString(":")
        val after = groups.drop(start + length).map(_.toHexString).mkString(":")
        s"$before::$after"

/** A relay of a stake pool as a registration holds it: the reference's `StakePoolRelay`
  * (`Cardano.Ledger.State.StakePool`, `State/StakePool.hs:328-337`).
  */
enum PoolRelay:
  /** One or both of an IPv4 and an IPv6 address, and a port (each may be absent). */
  case SingleHostAddr(port: Option[RelayPort], ipv4: Option[Ipv4], ipv6: Option[Ipv6])

  /** An `A` or `AAAA` record, with a port when the registration gives one. */
  case SingleHostName(port: Option[RelayPort], dnsName: String)

  /** An `SRV` record. */
  case MultiHostName(dnsName: String)

  /** What the consensus layer makes of this relay for the ledger peers
    * (`relayToLedgerRelayAccessPoint`, `Ouroboros.Consensus.Shelley.Ledger.PeerSelection`,
    * ouroboros- consensus 4.2.1.0, `PeerSelection.hs:37-57`): an address needs a port, and with
    * both an IPv4 and an IPv6 address the IPv4 one is the relay; a name with a port is a domain; a
    * name without one, and every `MultiHostName`, is an SRV domain. A `SingleHostAddr` with no port
    * or with no address is unusable and gives none.
    */
  def ledgerRelay: Option[LedgerRelay] = this match
    case SingleHostAddr(Some(port), Some(v4), _)    => Some(LedgerRelay.Address(v4, port))
    case SingleHostAddr(Some(port), None, Some(v6)) => Some(LedgerRelay.Address(v6, port))
    case SingleHostAddr(_, _, _)                    => None
    case SingleHostName(Some(port), name)           => Some(LedgerRelay.Domain(name, port))
    case SingleHostName(None, name)                 => Some(LedgerRelay.Srv(name))
    case MultiHostName(name)                        => Some(LedgerRelay.Srv(name))

/** A relay as the ledger peers see it (the reference's `LedgerRelayAccessPoint`,
  * `Ouroboros.Network.PeerSelection.RelayAccessPoint`, ouroboros-network 1.2.0.0,
  * `RelayAccessPoint.hs:165-170`).
  */
enum LedgerRelay:
  case Address(ip: RelayIp, port: RelayPort)
  case Domain(name: String, port: RelayPort)

  /** An SRV domain, not resolved by this node (design decision D9, docs/design/peer-selection.md):
    * decoded and kept, so the stake weights and the 90% set are the reference's, and set aside
    * where the peers are connected to.
    */
  case Srv(name: String)

  /** True for the relays this node does not connect to (D9). */
  def skipped: Boolean = this match
    case Srv(_) => true
    case _      => false

/** Why the relays of a registration could not be read. Every case names what was wrong and, where
  * it can, which relay of the sequence.
  */
enum PoolRelayError:
  /** The bytes are not one CBOR item (the message of the CBOR decoder). */
  case NotCbor(cause: LedgerDecodeError)

  /** The item is not an array: the relays of a pool are a sequence. */
  case NotASequence

  /** Relay `index` is not an array of a tag and its fields. */
  case NotARelay(index: Int)

  /** Relay `index` has a tag other than 0 (address), 1 (name) or 2 (SRV). */
  case UnknownTag(index: Int, tag: BigInt)

  /** Relay `index` has `length` elements, but its tag takes 4, 3 or 2. */
  case WrongLength(index: Int, tag: Int, length: Int)

  /** The port of relay `index` is neither null nor an integer from 0 to 65535. */
  case BadPort(index: Int)

  /** The IPv4 address of relay `index` is neither null nor four bytes. */
  case BadIpv4(index: Int)

  /** The IPv6 address of relay `index` is neither null nor sixteen bytes. */
  case BadIpv6(index: Int)

  /** The DNS name of relay `index` is not text. */
  case BadDnsName(index: Int)

  /** The DNS name of relay `index` is an indefinite (chunked) string. The reference reads it with
    * `textDecCBOR`, which is `decodeString`: a definite string only, in every decoder version
    * (`BaseTypes.hs:644-668, 691-697`).
    */
  case IndefiniteDnsName(index: Int)

  /** The DNS name of relay `index` is longer than 128 bytes (`textSizeN 128`,
    * `BaseTypes.hs:691-697`).
    */
  case DnsNameTooLong(index: Int, bytes: Int)

/** The relays of a pool registration (`PoolParams.relays`, kept as the registration came).
  *
  * Reference: `Cardano.Ledger.State.StakePool` of cardano-ledger-core 1.21.0.0 (rev f649f97):
  * `instance EncCBOR StakePoolRelay` at `State/StakePool.hs:389-404` and `instance DecCBOR
  * StakePoolRelay` at `State/StakePool.hs:406-421` (`decodeRecordSum`): a relay is
  *
  *   - `[0, port / null, ipv4 / null, ipv6 / null]` (`SingleHostAddr`),
  *   - `[1, port / null, dns_name]` (`SingleHostName`),
  *   - `[2, dns_name]` (`MultiHostName`),
  *
  * the pool's relays are a `StrictSeq` of them, an array of any of the definite and indefinite
  * forms. `port` is a `Word16`; `dns_name` is text of at most 128 bytes (64 before protocol version
  * 9, a registration is checked when it is made, so the larger bound reads both).
  *
  * An address is `bytes .size 4` or `bytes .size 16` (`shelley.cddl:187-188`). An IPv4 address is
  * in network order, the first octet first; an IPv6 address is not (below). cardano-ledger-binary
  * 1.9.1.0 (rev 048b78d) writes the IPv4 word big-endian (`ipv4ToBytes`,
  * `Encoding/Encoder.hs:563-564`, changelog "Fix IPv4 encoding order to use network order instead
  * of swapped"; rev f649f97 still has the swapped little-endian write, which the release corrects),
  * and the real registration in `fixtures/mainnet/shelley-pool-network-block.hex` carries
  * `03652f85` for a relay of 3.101.47.133. The IPv6 address is the four 32-bit words of the
  * address, each written little-endian (`ipv6ToBytes`, `Encoder.hs:569-575`; `getHostAddress6`,
  * `Decoder.hs:1363-1368`), so within each four octets the order is reversed, which three real
  * registrations show (`fixtures/mainnet/pool-relays-ipv6.json`,
  * `fixtures/preview/pool-relays-ipv6.json`: for `2a02:c206:2242:2138::1` the bytes
  * `06c2022a 38214222 00000000 01000000`). Reading them as network order, as G1.3a did, gives
  * `6c2:22a:3821:4222::1`, not an address.
  *
  * No relays at all is the empty `Bytes` (as `NodeTypeCodecs.relays` keeps it) or an empty array.
  */
object PoolRelays:
  /** Longest DNS name in a registration, in bytes of UTF-8 (`textDecCBOR 128`). */
  val MaxDnsNameBytes: Int = 128

  /** The relays of a registration, in order. */
  def decode(relays: Bytes): Either[PoolRelayError, Vector[PoolRelay]] =
    decode(relays, Cbor.Limits())

  def decode(relays: Bytes, limits: Cbor.Limits): Either[PoolRelayError, Vector[PoolRelay]] =
    if relays.isEmpty then Right(Vector.empty)
    else
      CborRead.decode(relays, limits).leftMap(PoolRelayError.NotCbor(_)).flatMap { node =>
        node.value match
          case Value.Arr(items) => items.zipWithIndex.traverse((n, i) => relay(n, i))
          case _                => Left(PoolRelayError.NotASequence)
      }

  /** The usable relays as the ledger peers see them, in order (see [[PoolRelay.ledgerRelay]]). */
  def ledgerRelays(relays: Vector[PoolRelay]): Vector[LedgerRelay] = relays.flatMap(_.ledgerRelay)

  /** The relays of a pool the node can connect to: the ledger relays without the SRV ones (D9). */
  def connectable(relays: Vector[PoolRelay]): Vector[LedgerRelay] =
    ledgerRelays(relays).filterNot(_.skipped)

  /** The relays of a pool as the ledger peers take them: none when the pool has no usable relay,
    * and then the pool is not a ledger peer (`NE.nonEmpty`, `PeerSelection.hs:62-66`).
    */
  def peerRelays(relays: Vector[PoolRelay]): Option[NonEmptyVector[LedgerRelay]] =
    NonEmptyVector.fromVector(ledgerRelays(relays))

  private def relay(node: Node, index: Int): Either[PoolRelayError, PoolRelay] =
    node.value match
      case Value.Arr(fields) =>
        fields.headOption.map(_.value) match
          case Some(Value.UInt(tag)) if tag == 0 =>
            sized(fields, index, 0, 4).flatMap(addr(_, index))
          case Some(Value.UInt(tag)) if tag == 1 =>
            sized(fields, index, 1, 3).flatMap(named(_, index))
          case Some(Value.UInt(tag)) if tag == 2 =>
            sized(fields, index, 2, 2).flatMap(multi(_, index))
          case Some(Value.UInt(tag)) => Left(PoolRelayError.UnknownTag(index, tag))
          case _                     => Left(PoolRelayError.NotARelay(index))
      case _ => Left(PoolRelayError.NotARelay(index))

  private def sized(
      fields: Vector[Node],
      index: Int,
      tag: Int,
      expected: Int
  ): Either[PoolRelayError, Vector[Node]] =
    Either.cond(
      fields.size == expected,
      fields,
      PoolRelayError.WrongLength(index, tag, fields.size)
    )

  private def addr(f: Vector[Node], index: Int): Either[PoolRelayError, PoolRelay] =
    (port(f(1), index), ipv4(f(2), index), ipv6(f(3), index))
      .mapN(PoolRelay.SingleHostAddr(_, _, _))

  private def named(f: Vector[Node], index: Int): Either[PoolRelayError, PoolRelay] =
    (port(f(1), index), dns(f(2), index)).mapN(PoolRelay.SingleHostName(_, _))

  private def multi(f: Vector[Node], index: Int): Either[PoolRelayError, PoolRelay] =
    dns(f(1), index).map(PoolRelay.MultiHostName(_))

  private def port(n: Node, index: Int): Either[PoolRelayError, Option[RelayPort]] =
    n.value match
      case Value.Null => Right(None)
      case Value.UInt(v) =>
        Either
          .fromOption(
            Option.when(v <= RelayPort.MaxValue)(v.toInt).flatMap(RelayPort.fromInt),
            PoolRelayError.BadPort(index)
          )
          .map(Some(_))
      case _ => Left(PoolRelayError.BadPort(index))

  private def ipv4(n: Node, index: Int): Either[PoolRelayError, Option[Ipv4]] =
    n.value match
      case Value.Null => Right(None)
      case Value.ByteString(b) =>
        RelayIp.fromOctets(b) match
          case Some(v4: Ipv4) => Right(Some(v4))
          case _              => Left(PoolRelayError.BadIpv4(index))
      case _ => Left(PoolRelayError.BadIpv4(index))

  private def ipv6(n: Node, index: Int): Either[PoolRelayError, Option[Ipv6]] =
    n.value match
      case Value.Null => Right(None)
      case Value.ByteString(b) =>
        Ipv6.fromWire(b) match
          case Some(v6) => Right(Some(v6))
          case None     => Left(PoolRelayError.BadIpv6(index))
      case _ => Left(PoolRelayError.BadIpv6(index))

  private def dns(n: Node, index: Int): Either[PoolRelayError, String] =
    n.value match
      case Value.Text(s) =>
        if CborRead.indefinite(n) then Left(PoolRelayError.IndefiniteDnsName(index))
        else
          val size = s.getBytes(StandardCharsets.UTF_8).length
          Either.cond(size <= MaxDnsNameBytes, s, PoolRelayError.DnsNameTooLong(index, size))
      case _ => Left(PoolRelayError.BadDnsName(index))

  // ---------------------------------------------------------------------------------- encoding

  /** The relays as a registration holds them (definite-length arrays, the form the reference
    * writes); no relays is the empty array. `decode(encode(rs)) == Right(rs)` for every `rs` whose
    * names fit 128 bytes.
    */
  def encode(relays: Vector[PoolRelay]): Bytes =
    val out = new ByteArrayOutputStream()
    head(out, 4, relays.size.toLong)
    relays.foreach {
      case PoolRelay.SingleHostAddr(p, v4, v6) =>
        head(out, 4, 4); head(out, 0, 0)
        optional(out, p)(x => head(out, 0, x.toInt.toLong))
        optional(out, v4)(x => octets(out, x.octets))
        optional(out, v6)(x => octets(out, x.wire))
      case PoolRelay.SingleHostName(p, name) =>
        head(out, 4, 3); head(out, 0, 1)
        optional(out, p)(x => head(out, 0, x.toInt.toLong))
        text(out, name)
      case PoolRelay.MultiHostName(name) =>
        head(out, 4, 2); head(out, 0, 2)
        text(out, name)
    }
    Bytes.fromArray(out.toByteArray)

  private def optional[A](out: ByteArrayOutputStream, a: Option[A])(write: A => Unit): Unit =
    a.fold(out.write(0xf6))(write)

  private def octets(out: ByteArrayOutputStream, b: Bytes): Unit =
    head(out, 2, b.size.toLong)
    out.write(b.toArray)

  private def text(out: ByteArrayOutputStream, s: String): Unit =
    val raw = s.getBytes(StandardCharsets.UTF_8)
    head(out, 3, raw.length.toLong)
    out.write(raw)

  /** A CBOR head: major type `major` and argument `n` in the shortest form. */
  private def head(out: ByteArrayOutputStream, major: Int, n: Long): Unit =
    val m = major << 5
    if n < 24 then out.write(m | n.toInt)
    else if n < 0x100 then { out.write(m | 24); out.write(n.toInt) }
    else if n < 0x10000 then { out.write(m | 25); out.write((n >> 8).toInt); out.write(n.toInt) }
    else
      out.write(m | 26)
      (3 to 0 by -1).foreach(i => out.write(((n >> (8 * i)) & 0xff).toInt))
