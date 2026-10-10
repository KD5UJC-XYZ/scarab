// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node}

/** A 28-byte key or script hash identifying who may spend or who owns stake. */
enum Credential:
  case Key(keyHash: Bytes)
  case Script(scriptHash: Bytes)

  def hash: Bytes = this match
    case Key(h)    => h
    case Script(h) => h

  /** The ledger's JSON rendering, e.g. `keyHash-ab12…`. */
  def render: String = this match
    case Key(h)    => s"keyHash-${h.hex}"
    case Script(h) => s"scriptHash-${h.hex}"

object Credential:
  given Ordering[Credential] = Ordering.by(c => (c.ordinal, c.hash.hex))

  /** Conway credential encoding `[0, keyhash] / [1, scripthash]`. */
  def decode(n: Node, what: String): Either[String, Credential] =
    CborRead.arrayN(n, 2, what).flatMap { xs =>
      (CborRead.uint(xs(0), what).map(_.toInt), CborRead.bytesN(xs(1), 28, what)).tupled.flatMap {
        case (0, h) => Right(Key(h))
        case (1, h) => Right(Script(h))
        case (t, _) => Left(s"$what: credential tag $t")
      }
    }

/** A ledger address with its original bytes. Only the parts the ledger acts on are decoded. */
final case class Address(raw: Bytes, kind: Address.Kind)

object Address:
  enum Kind:
    /** Shelley payment address; `stake` is the delegating credential of a base address. */
    case Shelley(network: Int, payment: Credential, stake: Option[Credential], pointer: Boolean)
    case Byron

  def decode(raw: Bytes): Either[String, Address] =
    if raw.size == 0 then Left("empty address")
    else
      val header = raw.value(0) & 0xff
      val kind = header >> 4
      val network = header & 0x0f
      def hash(offset: Int) =
        Either.cond(
          raw.size >= offset + 28,
          Bytes(raw.value.slice(offset, offset + 28)),
          "short address"
        )
      def payment =
        hash(1).map(h => if (kind & 1) == 0 then Credential.Key(h) else Credential.Script(h))
      kind match
        case 0 | 1 | 2 | 3 =>
          for
            p <- payment
            s <- hash(29)
            _ <- Either.cond(raw.size == 57, (), "base address length")
          yield Address(
            raw,
            Kind.Shelley(
              network,
              p,
              Some(if (kind & 2) == 0 then Credential.Key(s) else Credential.Script(s)),
              false
            )
          )
        case 4 | 5 => payment.map(p => Address(raw, Kind.Shelley(network, p, None, pointer = true)))
        case 6 | 7 =>
          Either.cond(raw.size == 29, (), "enterprise address length") *>
            payment.map(p => Address(raw, Kind.Shelley(network, p, None, false)))
        case 8 => Right(Address(raw, Kind.Byron))
        case _ => Left(s"address header $header is not a payment address")

  /** Byron bootstrap address root for a genesis non-AVVM balance is the address itself. */
  def byronGenesisTxId(raw: Bytes): Bytes = Blake2b.hash256.hash(raw)

/** Reward (stake) address: header 0xe0/0xf0 | network, then a 28-byte credential hash. */
final case class RewardAccount(network: Int, credential: Credential)

object RewardAccount:
  def decode(raw: Bytes): Either[String, RewardAccount] =
    if raw.size != 29 then Left("reward account length")
    else
      val header = raw.value(0) & 0xff
      val h = Bytes(raw.value.drop(1))
      header >> 4 match
        case 14 => Right(RewardAccount(header & 0x0f, Credential.Key(h)))
        case 15 => Right(RewardAccount(header & 0x0f, Credential.Script(h)))
        case _  => Left(s"reward account header $header")

/** Lovelace plus native assets. Mint values may be negative; UTxO values never are. */
final case class Value(coin: BigInt, assets: Map[Bytes, Map[Bytes, BigInt]]):
  def +(o: Value): Value = Value(coin + o.coin, Value.mergeAssets(assets, o.assets, _ + _))
  def -(o: Value): Value = Value(coin - o.coin, Value.mergeAssets(assets, o.assets, _ - _))
  def isZero: Boolean = coin == 0 && assets.isEmpty

object Value:
  val zero: Value = Value(0, Map.empty)
  def coin(c: BigInt): Value = Value(c, Map.empty)

  private def mergeAssets(
      a: Map[Bytes, Map[Bytes, BigInt]],
      b: Map[Bytes, Map[Bytes, BigInt]],
      f: (BigInt, BigInt) => BigInt
  ): Map[Bytes, Map[Bytes, BigInt]] =
    (a.keySet ++ b.keySet).iterator
      .map { policy =>
        val x = a.getOrElse(policy, Map.empty)
        val y = b.getOrElse(policy, Map.empty)
        policy -> (x.keySet ++ y.keySet).iterator
          .map(n => n -> f(x.getOrElse(n, 0), y.getOrElse(n, 0)))
          .filter(_._2 != 0)
          .toMap
      }
      .filter(_._2.nonEmpty)
      .toMap

  /** `coin / [coin, multiasset<uint>]`; zero quantities are rejected as in Conway. */
  def decode(n: Node, what: String): Either[String, Value] = n.value match
    case lab.cbor.Value.UInt(c) => Right(coin(c))
    case _ =>
      for
        xs <- CborRead.arrayN(n, 2, what)
        c <- CborRead.uint(xs(0), what)
        assets <- multiasset(xs(1), what, signed = false)
      yield Value(c, assets)

  def multiasset(
      n: Node,
      what: String,
      signed: Boolean
  ): Either[String, Map[Bytes, Map[Bytes, BigInt]]] =
    CborRead
      .map(n, what)
      .flatMap(_.traverse { case (p, inner) =>
        for
          policy <- CborRead.bytesN(p, 28, s"$what policy")
          names <- CborRead.map(inner, what)
          quantities <- names.traverse { case (name, q) =>
            for
              assetName <- CborRead.bytes(name, s"$what asset name")
              _ <- Either.cond(assetName.size <= 32, (), s"$what: asset name too long")
              quantity <- if signed then CborRead.int(q, what) else CborRead.uint(q, what)
              _ <- Either.cond(quantity != 0, (), s"$what: zero quantity")
            yield assetName -> quantity
          }
        yield policy -> quantities.toMap
      }.map(_.toMap))

/** A transaction output with its original encoding. */
final case class TxOut(
    raw: Bytes,
    address: Address,
    value: Value,
    hasDatum: Boolean,
    hasScriptRef: Boolean
)

object TxOut:
  def decode(n: Node): Either[String, TxOut] = n.value match
    case lab.cbor.Value.Arr(xs) if xs.size == 2 || xs.size == 3 =>
      for
        a <- CborRead.bytes(xs(0), "output address").flatMap(Address.decode)
        v <- Value.decode(xs(1), "output value")
      yield TxOut(n.original, a, v, hasDatum = xs.size == 3, hasScriptRef = false)
    case lab.cbor.Value.Map(_) =>
      for
        f <- CborRead.fields(n, "output")
        a <- f
          .get(0)
          .toRight("output: missing address")
          .flatMap(CborRead.bytes(_, "output address"))
          .flatMap(Address.decode)
        v <- f.get(1).toRight("output: missing value").flatMap(Value.decode(_, "output value"))
        _ <- Either.cond(f.keySet.subsetOf(Set(0, 1, 2, 3)), (), "output: unknown field")
      yield TxOut(n.original, a, v, f.contains(2), f.contains(3))
    case _ => Left("output: expected legacy array or map")

  def fromBytes(raw: Bytes): Either[String, TxOut] = Cbor.decode(raw).flatMap(decode)

/** `[transaction_id, index]` */
final case class TxIn(id: Bytes, index: BigInt):
  def render: String = s"${id.hex}#$index"

object TxIn:
  given Ordering[TxIn] = Ordering.by(i => (i.id.hex, i.index))
  def decode(n: Node): Either[String, TxIn] =
    CborRead
      .arrayN(n, 2, "input")
      .flatMap(xs =>
        (CborRead.bytesN(xs(0), 32, "input id"), CborRead.uint(xs(1), "input index"))
          .mapN(TxIn.apply)
      )
