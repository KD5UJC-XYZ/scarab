// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as CValue}
import lab.witness.{ExactBodyCbor, PublicKey32, Signature64, VKeyWitness}

enum CoverageError:
  case TypedUnsupported(reason: String)
  case Malformed(detail: String)
  case EmptySpendingInputs
  case UnknownSpendingInputs(inputs: Set[TxIn])

final class KeyHash28 private (val bytes: Bytes):
  override def equals(other: Any): Boolean = other match
    case that: KeyHash28 => bytes == that.bytes
    case _               => false
  override def hashCode(): Int = bytes.hashCode()
  override def toString: String = bytes.hex
object KeyHash28:
  def create(bytes: Bytes): Either[CoverageError, KeyHash28] =
    if bytes.size == 28 then Right(new KeyHash28(bytes))
    else Left(CoverageError.Malformed("payment key hash must be 28 bytes"))

/** Set diagnostics are reconstructed from owned transaction/UTxO bytes, never supplied as evidence.
  * This result says nothing about signatures, balance, or full transaction validity.
  */
final class CoverageResult private[ledger] (
    val required: Set[KeyHash28],
    val provided: Set[KeyHash28]
):
  val missing: Set[KeyHash28] = required -- provided
  def covered: Boolean = missing.isEmpty
  override def toString: String =
    def render(xs: Set[KeyHash28]): String =
      xs.toVector.map(_.toString).sorted.mkString("[", ",", "]")
    s"${if covered then "RequiredKeysCovered" else "MissingRequiredKeys"}(required=${render(required)},provided=${render(provided)},missing=${render(missing)})"

/** Closed Conway PV9 transfer key-coverage predicate. Only body fields 0/1/2 and Shelley address
  * kinds 0/6 are supported. No scripts, explicit signers, certificates, mint or ledger transition.
  * Signature verification remains a separate core predicate; empty witness sets are meaningful
  * here.
  */
object Coverage:
  final class Output private[Coverage] (
      val address: Bytes,
      val paymentKey: KeyHash28,
      val value: Value,
      val original: Bytes
  )
  final class Projection private[Coverage] (
      val inputs: Set[TxIn],
      val outputs: Vector[Output],
      val fee: BigInt,
      val body: ExactBodyCbor,
      val witnesses: Vector[VKeyWitness],
      val originalWitnessMap: Bytes,
      val original: Bytes
  )
  import CoverageError.*
  private type Checked[A] = Either[CoverageError, A]
  private def bad[A](message: String): Checked[A] = Left(Malformed(message))
  private def unsupported[A](message: String): Checked[A] = Left(TypedUnsupported(message))
  private def traverse[A, B](xs: Vector[A])(f: A => Checked[B]): Checked[Vector[B]] =
    xs.foldLeft[Checked[Vector[B]]](Right(Vector.empty)) { (acc, x) =>
      for out <- acc; next <- f(x) yield out :+ next
    }
  private def arr(n: Node): Checked[Vector[Node]] = n.value match
    case CValue.Arr(xs) => Right(xs)
    case _              => bad("expected array")
  private def uint(n: Node): Checked[BigInt] = n.value match
    case CValue.UInt(x) => Right(x)
    case _              => bad("expected unsigned integer")
  private def bytes(n: Node): Checked[Bytes] = n.value match
    case CValue.ByteString(b) => Right(b)
    case _                    => bad("expected byte string")
  private def uniqueMap[K](n: Node)(key: Node => Checked[K]): Checked[Map[K, Node]] = n.value match
    case CValue.Map(xs) =>
      traverse(xs) { case (k, v) => key(k).map(_ -> v) }.flatMap { pairs =>
        if pairs.map(_._1).distinct.size != pairs.size then bad("duplicate map key")
        else Right(pairs.toMap)
      }
    case _ => bad("expected map")
  private def setArray(n: Node): Checked[Vector[Node]] = n.value match
    case CValue.Tag(number, inner) if number == 258 => arr(inner)
    case CValue.Tag(_, _)                           => bad("unsupported set tag")
    case _                                          => arr(n)
  private def input(n: Node): Checked[TxIn] = n.value match
    case CValue.Arr(Vector(id, index)) =>
      for
        b <- bytes(id)
        i <- uint(index)
        ref <- TxIn.create(b, i).left.map(e => Malformed(e.toString))
      yield ref
    case _ => bad("expected [txid, uint16 index]")
  private def payment(address: Bytes): Checked[KeyHash28] =
    if address.size == 0 then bad("empty address")
    else
      val header = address.value.head & 0xff
      val kind = header >>> 4
      if kind != 0 && kind != 6 then unsupported(s"address kind $kind; only 0 and 6 are supported")
      else if (header & 15) > 1 then bad("address network must be 0 or 1")
      else if address.size != (if kind == 0 then 57 else 29) then bad("incorrect address length")
      else KeyHash28.create(Bytes(address.value.slice(1, 29)))
  private def amount(n: Node): Checked[Value] = n.value match
    case CValue.UInt(coin) => Right(Value(coin))
    case CValue.Arr(Vector(coin, tokens)) =>
      for
        c <- uint(coin)
        policies <- uniqueMap(tokens)(bytes)
        assets <- traverse(policies.toVector) { case (policy, names) =>
          for
            _ <- Either.cond(policy.size == 28, (), Malformed("policy must be 28 bytes"))
            ns <- uniqueMap(names)(bytes)
            values <- traverse(ns.toVector) { case (name, quantity) =>
              for
                id <- AssetId.create(policy, name).left.map(e => Malformed(e.toString))
                q <- uint(quantity)
              yield id -> q
            }
          yield values
        }
      yield Value(c, assets.flatten.toMap)
    case _ => bad("output value must be unsigned coin or [coin, multiasset]")
  private def output(n: Node): Checked[Output] =
    val parts = n.value match
      case CValue.Arr(Vector(address, value)) => Right((address, value))
      case CValue.Map(_) =>
        uniqueMap(n)(uint).flatMap { fields =>
          val extra = fields.keySet -- Set(BigInt(0), BigInt(1))
          if extra.nonEmpty then
            unsupported(s"output fields ${extra.toVector.sorted.mkString(",")}")
          else if fields.keySet != Set(BigInt(0), BigInt(1)) then bad("missing output fields")
          else Right((fields(0), fields(1)))
        }
      case CValue.Arr(_) => unsupported("output fields beyond address/value")
      case _             => bad("expected output array or map")
    for
      pair <- parts
      address <- bytes(pair._1)
      key <- payment(address)
      value <- amount(pair._2)
    yield new Output(address, key, value, n.original)
  private def witness(n: Node): Checked[VKeyWitness] = n.value match
    case CValue.Arr(Vector(key, signature)) =>
      for
        k <- bytes(key)
        s <- bytes(signature)
        publicKey <- PublicKey32.create(k).left.map(e => Malformed(e.toString))
        sig <- Signature64.create(s).left.map(e => Malformed(e.toString))
      yield VKeyWitness(publicKey, sig)
    case _ => bad("expected [vkey, signature]")

  def keyHash(key: PublicKey32): KeyHash28 =
    KeyHash28.create(Blake2b.hash224.hash(key.bytes)).toOption.get

  def decode(raw: Bytes): Checked[Projection] =
    for
      root <- Cbor.decode(raw).left.map(Malformed.apply)
      envelope <- arr(root)
      _ <- Either.cond(envelope.size == 4, (), Malformed("expected four-field Conway envelope"))
      _ <- envelope(2).value match
        case CValue.Bool(true)  => Right(())
        case CValue.Bool(false) => unsupported[Unit]("isValid=false collateral path")
        case _                  => bad[Unit]("isValid must be Boolean")
      _ <- envelope(3).value match
        case CValue.Null => Right(())
        case _           => unsupported[Unit]("auxiliary data")
      fields <- uniqueMap(envelope(0))(uint)
      extra = fields.keySet -- Set(BigInt(0), BigInt(1), BigInt(2))
      _ <-
        if extra.nonEmpty then
          unsupported[Unit](s"body fields ${extra.toVector.sorted.mkString(",")}")
        else Right(())
      _ <- Either.cond(
        fields.keySet == Set(BigInt(0), BigInt(1), BigInt(2)),
        (),
        Malformed("missing body fields")
      )
      inputNodes <- setArray(fields(0))
      inputs <- traverse(inputNodes)(input)
      _ <- Either.cond(
        inputs.distinct.size == inputs.size,
        (),
        Malformed("duplicate spending input")
      )
      _ <- Either.cond(inputs.nonEmpty, (), EmptySpendingInputs)
      outputNodes <- arr(fields(1))
      _ <- Either.cond(outputNodes.size <= 65536, (), Malformed("output index exceeds uint16"))
      outputs <- traverse(outputNodes)(output)
      fee <- uint(fields(2))
      witnessFields <- uniqueMap(envelope(1))(uint)
      _ <-
        if (witnessFields.keySet -- Set(BigInt(0))).nonEmpty then
          unsupported[Unit]("witness fields other than VKeys 0")
        else Right(())
      witnessNodes <- witnessFields
        .get(0)
        .fold[Checked[Vector[Node]]](Right(Vector.empty))(setArray)
      witnesses <- traverse(witnessNodes)(witness)
      _ <- Either.cond(
        witnesses.map(_.publicKey).distinct.size == witnesses.size,
        (),
        Malformed("duplicate vkey witness public key")
      )
      body <- ExactBodyCbor.create(envelope(0).original).left.map(e => Malformed(e.toString))
    yield new Projection(
      inputs.toSet,
      outputs,
      fee,
      body,
      witnesses,
      envelope(1).original,
      root.original
    )

  /** Address-preserving resolved-output map, deliberately distinct from Balance's value-only map.
    * Every entry is checked against this closed profile, even if not spent by this transaction.
    */
  def decodeResolved(raw: Bytes): Checked[Map[TxIn, Output]] =
    for
      root <- Cbor.decode(raw).left.map(Malformed.apply)
      entries <- uniqueMap(root)(input)
      outputs <- traverse(entries.toVector) { case (ref, node) => output(node).map(ref -> _) }
    yield outputs.toMap

  def check(
      era: String,
      protocolMajor: Int,
      utxo: Map[TxIn, Output],
      projection: Projection
  ): Checked[CoverageResult] =
    if era != "Conway" then unsupported(s"era $era")
    else if protocolMajor != 9 then unsupported(s"protocol major $protocolMajor")
    else if projection.inputs.isEmpty then Left(EmptySpendingInputs)
    else
      val unknown = projection.inputs -- utxo.keySet
      if unknown.nonEmpty then Left(UnknownSpendingInputs(unknown))
      else
        Right(
          new CoverageResult(
            projection.inputs.map(utxo(_).paymentKey),
            projection.witnesses.map(w => keyHash(w.publicKey)).toSet
          )
        )
