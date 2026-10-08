// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as CValue}

enum LedgerError:
  case UnsupportedEra(era: String)
  case UnsupportedProtocol(major: Int)
  case UnsupportedBodyFields(fields: Set[BigInt])
  case UnsupportedCertificates
  case UnsupportedWithdrawals
  case UnsupportedProposals
  case UnsupportedDonation
  case UnsupportedCollateralPath
  case UnsupportedOutputFields(fields: Set[BigInt])
  case UnresolvedInputs(inputs: Set[TxIn])
  case Malformed(detail: String)

final case class AssetId private (policy: Bytes, name: Bytes):
  require(policy.size == 28 && name.size <= 32)
  override def toString: String = s"${policy.hex}.${name.hex}"
object AssetId:
  def create(policy: Bytes, name: Bytes): Either[LedgerError, AssetId] =
    if policy.size == 28 && name.size <= 32 then Right(new AssetId(policy, name))
    else Left(LedgerError.Malformed("policy must be 28 bytes and asset name at most 32 bytes"))

final case class TxIn private (id: Bytes, index: BigInt):
  require(id.size == 32 && index >= 0 && index <= 65535)
  override def toString: String = s"${id.hex}:$index"
object TxIn:
  def create(id: Bytes, index: BigInt): Either[LedgerError, TxIn] =
    if id.size == 32 && index >= 0 && index <= 65535 then Right(new TxIn(id, index))
    else Left(LedgerError.Malformed("input requires a 32-byte txid and uint16 index"))

/** Exact mathematical value. Zero entries are removed at every construction boundary. */
final class Value private (val lovelace: BigInt, val assets: Map[AssetId, BigInt]):
  def +(other: Value): Value = Value(
    lovelace + other.lovelace,
    (assets.keySet ++ other.assets.keySet).iterator
      .map(k => k -> (assets.getOrElse(k, BigInt(0)) + other.assets.getOrElse(k, BigInt(0))))
      .toMap
  )
  def unary_- : Value = Value(-lovelace, assets.view.mapValues(-_).toMap)
  def nonnegative: Boolean = lovelace >= 0 && assets.values.forall(_ >= 0)
  override def equals(other: Any): Boolean = other match
    case that: Value => lovelace == that.lovelace && assets == that.assets
    case _           => false
  override def hashCode(): Int = (lovelace, assets).hashCode
  override def toString: String =
    val tokens = assets.toVector
      .sortBy(_._1.toString)
      .map { case (id, quantity) => s"$id=$quantity" }
      .mkString(",")
    s"Value(lovelace=$lovelace,assets={$tokens})"
object Value:
  def apply(lovelace: BigInt, assets: Map[AssetId, BigInt] = Map.empty): Value =
    new Value(lovelace, assets.filter(_._2 != 0))
  val zero: Value = Value(0)

/** Only the checked complete-envelope decoder can construct this scope evidence. This is a value
  * projection, not proof of transaction validity.
  */
type TransferMintBody = Balance.TransferMintBody

enum BalanceResult:
  case PredicateSatisfied(consumed: Value, produced: Value)
  case ValueNotConserved(consumed: Value, produced: Value, delta: Value)

/** Conway PV9 transfer/mint-only ValueNotConservedUTxO predicate, no effects or ledger transition.
  */
object Balance:
  final class TransferMintBody private[Balance] (
      val inputs: Set[TxIn],
      val outputs: Vector[Value],
      val fee: BigInt,
      val mint: Map[AssetId, BigInt]
  )
  import LedgerError.*
  private type Checked[A] = Either[LedgerError, A]
  private def bad[A](message: String): Checked[A] = Left(Malformed(message))
  private def traverse[A, B](values: Vector[A])(f: A => Checked[B]): Checked[Vector[B]] =
    values.foldLeft[Checked[Vector[B]]](Right(Vector.empty))((acc, a) =>
      for xs <- acc; b <- f(a) yield xs :+ b
    )
  private def arr(n: Node): Checked[Vector[Node]] = n.value match
    case CValue.Arr(xs) => Right(xs)
    case _              => bad("expected array")
  private def uint(n: Node): Checked[BigInt] = n.value match
    case CValue.UInt(x) => Right(x)
    case _              => bad("expected unsigned integer")
  private def integer(n: Node): Checked[BigInt] = n.value match
    case CValue.UInt(x) => Right(x)
    case CValue.NInt(x) => Right(x)
    case _              => bad("expected integer")
  private def bytes(n: Node): Checked[Bytes] = n.value match
    case CValue.ByteString(b) => Right(b)
    case _                    => bad("expected byte string")
  private def map[K](n: Node)(key: Node => Checked[K]): Checked[Map[K, Node]] = n.value match
    case CValue.Map(xs) =>
      traverse(xs) { case (k, v) => key(k).map(_ -> v) }.flatMap { pairs =>
        if pairs.map(_._1).distinct.size != pairs.size then bad("duplicate map key")
        else Right(pairs.toMap)
      }
    case _ => bad("expected map")
  private def assets(n: Node, signed: Boolean): Checked[Map[AssetId, BigInt]] =
    for
      policies <- map(n)(bytes)
      nested <- traverse(policies.toVector) { case (policy, names) =>
        for
          _ <- Either.cond(policy.size == 28, (), Malformed("policy must be 28 bytes"))
          ns <- map(names)(bytes)
          quantities <- traverse(ns.toVector) { case (name, quantity) =>
            for
              id <- AssetId.create(policy, name)
              q <- if signed then integer(quantity) else uint(quantity)
              _ <- Either.cond(
                !signed || (q >= -(BigInt(1) << 63) && q < (BigInt(1) << 63)),
                (),
                Malformed("mint quantity outside int64")
              )
            yield id -> q
          }
        yield quantities
      }
    yield nested.flatten.filter(_._2 != 0).toMap
  private def value(n: Node): Checked[Value] = n.value match
    case CValue.UInt(coin) => Right(Value(coin))
    case CValue.Arr(Vector(coin, tokens)) =>
      for c <- uint(coin); a <- assets(tokens, false) yield Value(c, a)
    case _ => bad("output value must be nonnegative coin or [coin, multiasset]")
  private def output(n: Node): Checked[Value] = n.value match
    case CValue.Arr(Vector(address, amount)) => bytes(address).flatMap(_ => value(amount))
    case CValue.Map(_) =>
      map(n)(uint).flatMap { fields =>
        val extra = fields.keySet -- Set(BigInt(0), BigInt(1))
        if extra.nonEmpty then Left(UnsupportedOutputFields(extra))
        else if fields.keySet != Set(BigInt(0), BigInt(1)) then bad("missing output fields")
        else bytes(fields(0)).flatMap(_ => value(fields(1)))
      }
    case _ => bad("unsupported output shape; only address and value are modeled")
  private def input(n: Node): Checked[TxIn] = n.value match
    case CValue.Arr(Vector(txid, index)) =>
      for id <- bytes(txid); ix <- uint(index); in <- TxIn.create(id, ix) yield in
    case _ => bad("expected [txid, index]")
  private def inputSet(n: Node): Checked[Set[TxIn]] =
    val untagged = n.value match
      case CValue.Tag(number, inner) if number == 258 => Right(inner)
      case CValue.Tag(_, _)                           => bad[Node]("unsupported input-set tag")
      case _                                          => Right(n)
    for
      node <- untagged
      xs <- arr(node)
      inputs <- traverse(xs)(input)
      _ <- Either.cond(
        inputs.distinct.size == inputs.size,
        (),
        Malformed("duplicate spending input")
      )
    yield inputs.toSet
  private def scope(fields: Set[BigInt]): Checked[Unit] =
    if fields.contains(4) then Left(UnsupportedCertificates)
    else if fields.contains(5) then Left(UnsupportedWithdrawals)
    else if fields.contains(20) then Left(UnsupportedProposals)
    else if fields.contains(22) then Left(UnsupportedDonation)
    else if fields.exists(Set(BigInt(13), BigInt(16), BigInt(17)).contains) then
      Left(UnsupportedCollateralPath)
    else
      val extra = fields -- Set(BigInt(0), BigInt(1), BigInt(2), BigInt(9))
      if extra.nonEmpty then Left(UnsupportedBodyFields(extra))
      else
        Either.cond(
          Set(BigInt(0), BigInt(1), BigInt(2)).subsetOf(fields),
          (),
          Malformed("missing required body fields")
        )

  def decode(raw: Bytes): Checked[TransferMintBody] =
    for
      root <- Cbor.decode(raw).left.map(Malformed.apply)
      envelope <- arr(root)
      _ <- Either.cond(envelope.size == 4, (), Malformed("expected four-field Conway transaction"))
      _ <- envelope(2).value match
        case CValue.Bool(true)  => Right(())
        case CValue.Bool(false) => Left(UnsupportedCollateralPath)
        case _                  => bad[Unit]("isValid must be boolean")
      _ <- envelope(1).value match
        case CValue.Map(_) => Right(())
        case _             => bad[Unit]("witness set must be a map (not validated)")
      fields <- map(envelope(0))(uint)
      _ <- scope(fields.keySet)
      inputs <- inputSet(fields(0))
      outNodes <- arr(fields(1))
      outputs <- traverse(outNodes)(output)
      fee <- uint(fields(2))
      mint <- fields.get(9).fold[Checked[Map[AssetId, BigInt]]](Right(Map.empty))(assets(_, true))
    yield new TransferMintBody(inputs, outputs, fee, mint)

  /** Strict value-only UTxO projection; no ledger snapshot or MemPack interpretation. */
  def decodeResolved(raw: Bytes): Checked[Map[TxIn, Value]] =
    for
      root <- Cbor.decode(raw).left.map(Malformed.apply)
      entries <- map(root)(input)
      values <- traverse(entries.toVector) { case (in, amount) => value(amount).map(in -> _) }
    yield values.toMap

  def check(
      era: String,
      protocolMajor: Int,
      utxo: Map[TxIn, Value],
      body: TransferMintBody
  ): Checked[BalanceResult] =
    if era != "Conway" then Left(UnsupportedEra(era))
    else if protocolMajor != 9 then Left(UnsupportedProtocol(protocolMajor))
    else
      val missing = body.inputs -- utxo.keySet
      if missing.nonEmpty then Left(UnresolvedInputs(missing))
      else if body.inputs.exists(i => !utxo(i).nonnegative) then
        bad("negative resolved input value")
      else
        val positive = body.mint.filter(_._2 > 0)
        val negative = body.mint.collect { case (k, q) if q < 0 => k -> -q }
        val consumed = body.inputs.toVector.map(utxo).foldLeft(Value(0, positive))(_ + _)
        val produced = body.outputs.foldLeft(Value(body.fee, negative))(_ + _)
        Right(
          if consumed == produced then BalanceResult.PredicateSatisfied(consumed, produced)
          else BalanceResult.ValueNotConserved(consumed, produced, consumed + -produced)
        )
