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

/** Certificate pointer of a pointer address: slot, transaction index, certificate index. */
final case class Ptr(slot: BigInt, txIx: BigInt, certIx: BigInt)

object Ptr:
  given Ordering[Ptr] = Ordering.by(p => (p.slot, p.txIx, p.certIx))

  /** Three variable-length naturals (7 bits per byte, high bit set on all but the last). */
  def decode(bytes: Vector[Byte]): Either[String, Ptr] =
    def nat(rest: Vector[Byte]): Either[String, (BigInt, Vector[Byte])] =
      val (continued, tail) = rest.span(b => (b & 0x80) != 0)
      tail.headOption.toRight("pointer address: truncated natural").map { last =>
        val digits = (continued :+ last).map(b => BigInt(b & 0x7f))
        digits.foldLeft(BigInt(0))((acc, d) => (acc << 7) + d) -> tail.tail
      }
    for
      a <- nat(bytes)
      b <- nat(a._2)
      c <- nat(b._2)
      _ <- Either.cond(c._2.isEmpty, (), "pointer address: trailing bytes")
    yield Ptr(a._1, b._1, c._1)

/** A ledger address with its original bytes. Only the parts the ledger acts on are decoded. */
final case class Address(raw: Bytes, kind: Address.Kind)

object Address:
  enum Kind:
    /** Shelley payment address; `stake` is the delegating credential of a base address and
      * `pointer` the certificate pointer of a pointer address.
      */
    case Shelley(network: Int, payment: Credential, stake: Option[Credential], pointer: Option[Ptr])

    /** Byron bootstrap address; `root` is the 28-byte address root (empty if it did not parse). */
    case Byron(root: Bytes)

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
              None
            )
          )
        case 4 | 5 =>
          for
            p <- payment
            ptr <- Ptr.decode(raw.value.drop(29))
          yield Address(raw, Kind.Shelley(network, p, None, Some(ptr)))
        case 6 | 7 =>
          Either.cond(raw.size == 29, (), "enterprise address length") *>
            payment.map(p => Address(raw, Kind.Shelley(network, p, None, None)))
        case 8 => Right(Address(raw, Kind.Byron(byronRoot(raw))))
        case _ => Left(s"address header $header is not a payment address")

  /** `[#6.24(bytes .cbor [root, attributes, type]), crc32]`: the root, or empty if malformed. */
  private def byronRoot(raw: Bytes): Bytes =
    val parsed = for
      outer <- Cbor.decode(raw)
      parts <- CborRead.arrayN(outer, 2, "byron address")
      payload <- parts(0).value match
        case lab.cbor.Value.Tag(t, inner) if t == 24 => CborRead.bytes(inner, "byron payload")
        case _                                       => Left("byron payload")
      body <- Cbor.decode(payload)
      fields <- CborRead.array(body, "byron address payload")
      root <- fields.headOption.toRight("empty").flatMap(CborRead.bytesN(_, 28, "byron root"))
    yield root
    parsed.getOrElse(Bytes.empty)

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

  /** Bytes of the value as the ledger serialises it (`serialize v` in `validateOutputTooBigUTxO`):
    * a bare unsigned integer for an ada-only value, otherwise `[coin, {policy: {name: quantity}}]`
    * whatever encoding the transaction arrived in. A map of more than 23 entries is written
    * indefinite-length (`encodeMap`, `variableMapLenEncoding`, `lengthThreshold = 23` in
    * cardano-ledger-binary `Encoding/Encoder.hs`, every protocol major from 2): two bytes in total,
    * one byte fewer than the definite head of a map of 256 entries or more.
    */
  def serializedSize: Int =
    import Value.{headSize, mapHead, uintSize}
    if assets.isEmpty then uintSize(coin)
    else
      val inner = assets.valuesIterator.map { names =>
        val entries = names.iterator.map { case (name, q) =>
          headSize(name.size) + name.size + uintSize(q.abs)
        }.sum
        2 + 28 + mapHead(names.size) + entries
      }.sum
      1 + uintSize(coin) + mapHead(assets.size) + inner

object Value:
  /** Size of a CBOR head carrying `n` (a length or an unsigned integer up to 2^32). */
  private def headSize(n: BigInt): Int =
    if n < 24 then 1
    else if n < 256 then 2
    else if n < 65536 then 3
    else if n < (BigInt(1) << 32) then 5
    else 9

  private def uintSize(n: BigInt): Int = headSize(n)

  /** Bytes of a map's length prefix (and break): exact head up to 23 entries, else `0xbf ... 0xff`.
    */
  private[ledger] def mapHead(n: Int): Int = if n <= 23 then headSize(n) else 2

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
            yield assetName -> quantity
          }
        yield policy -> quantities.filter(_._2 != 0).toMap
      }.map(_.filter(_._2.nonEmpty).toMap))

/** The datum of an output. */
enum Datum:
  case NoDatum
  case Hash(hash: Bytes)

  /** Inline datum: the bytes of the plutus data (the content of the `#6.24` wrapper). */
  case Inline(data: Bytes)

/** A transaction output with its original encoding. */
final case class TxOut(
    raw: Bytes,
    address: Address,
    value: Value,
    datum: Datum,
    scriptRef: Option[Script],
    /** Size in bytes of the value as it arrived (informational: the size rules use
      * [[Value.serializedSize]], the canonical re-serialisation).
      */
    valueSize: Int
):
  def hasDatum: Boolean = datum != Datum.NoDatum
  def hasScriptRef: Boolean = scriptRef.isDefined

  /** The reference script with its Plutus bytes, re-read from the output's encoding. */
  def referenceScript: Option[Script] =
    scriptRef.flatMap { kept =>
      val reread = for
        node <- Cbor.decode(raw)
        f <- CborRead.fields(node, "output")
        field <- f.get(3).toRight("output: no reference script")
        script <- Script.decodeWrappedWithBytes(field)
      yield script
      reread.toOption.orElse(Some(kept))
    }

  /** Size in bytes of the reference script as the ledger counts it for the reference-script fee:
    * the Plutus script bytes, or the encoding of a native script.
    */
  def referenceScriptSize: Int =
    if scriptRef.isEmpty then 0
    else
      val size = for
        node <- Cbor.decode(raw)
        f <- CborRead.fields(node, "output")
        field <- f.get(3).toRight("output: no reference script")
        inner <- field.value match
          case lab.cbor.Value.Tag(t, i) if t == 24 => CborRead.bytes(i, "reference script")
          case _                                   => Left("reference script: expected tag 24")
        script <- Cbor.decode(inner)
        parts <- CborRead.arrayN(script, 2, "script")
        tag <- CborRead.uint(parts(0), "script type")
        payload <-
          if tag == 0 then Right(parts(1).original.size)
          else CborRead.bytes(parts(1), "plutus script").map(_.size)
      yield payload
      size.getOrElse(0)

  /** Size of the output as the ledger serialises it: outputs with neither inline datum nor
    * reference script use the legacy array form, shorter by two bytes than the equivalent map.
    */
  def ledgerSize: Int =
    val head = if raw.size == 0 then 0 else raw.value.head & 0xff
    datum match
      case Datum.NoDatum if scriptRef.isEmpty && head == 0xa2 => raw.size - 2
      case Datum.Hash(_) if scriptRef.isEmpty && head == 0xa3 => raw.size - 2
      case _                                                  => raw.size

object TxOut:
  def apply(address: Address, value: Value): TxOut =
    TxOut(Bytes.empty, address, value, Datum.NoDatum, None, 0)

  def decode(n: Node): Either[String, TxOut] = n.value match
    case lab.cbor.Value.Arr(xs) if xs.size == 2 || xs.size == 3 =>
      for
        a <- CborRead.bytes(xs(0), "output address").flatMap(Address.decode)
        v <- Value.decode(xs(1), "output value")
        d <-
          if xs.size == 3 then CborRead.bytesN(xs(2), 32, "datum hash").map(Datum.Hash(_))
          else Right(Datum.NoDatum)
      yield TxOut(n.original, a, v, d, None, xs(1).original.size)
    case lab.cbor.Value.Map(_) =>
      for
        f <- CborRead.fields(n, "output")
        a <- f
          .get(0)
          .toRight("output: missing address")
          .flatMap(CborRead.bytes(_, "output address"))
          .flatMap(Address.decode)
        vn <- f.get(1).toRight("output: missing value")
        v <- Value.decode(vn, "output value")
        _ <- Either.cond(f.keySet.subsetOf(Set(0, 1, 2, 3)), (), "output: unknown field")
        d <- f.get(2).traverse(datumOption)
        s <- f.get(3).traverse(Script.decodeWrapped)
      yield TxOut(n.original, a, v, d.getOrElse(Datum.NoDatum), s, vn.original.size)
    case _ => Left("output: expected legacy array or map")

  /** `datum_option = [0, hash] / [1, #6.24(bytes .cbor plutus_data)]` */
  private def datumOption(n: Node): Either[String, Datum] =
    CborRead.arrayN(n, 2, "datum option").flatMap { xs =>
      CborRead.uint(xs(0), "datum option tag").flatMap {
        case t if t == 0 => CborRead.bytesN(xs(1), 32, "datum hash").map(Datum.Hash(_))
        case t if t == 1 =>
          xs(1).value match
            case lab.cbor.Value.Tag(g, inner) if g == 24 =>
              CborRead.bytes(inner, "inline datum").map(Datum.Inline(_))
            case _ => Left("inline datum: expected tag 24")
        case t => Left(s"datum option tag $t")
      }
    }

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
