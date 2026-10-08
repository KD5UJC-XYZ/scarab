// SPDX-License-Identifier: Apache-2.0
package lab.witness

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}
import scala.util.control.NonFatal

final case class VKeyWitness(publicKey: PublicKey32, signature: Signature64)

/** Structural map evidence only. Original bytes, not a re-encoding, are retained. */
final class ExactBodyCbor private (val bytes: Bytes):
  def hash: BodyHash32 = BodyHash32.create(Blake2b.hash256.hash(bytes)).toOption.get
object ExactBodyCbor:
  def create(bytes: Bytes): Either[WitnessInputError, ExactBodyCbor] =
    Cbor.decode(bytes).left.map(WitnessInputError.MalformedCbor.apply).flatMap { node =>
      CardanoWitness.uniqueUIntMap(node, "body").map(_ => new ExactBodyCbor(node.original))
    }

/** No transaction-validity Boolean is exposed. This is only a parsed vkey-only envelope. */
final case class WitnessEnvelope(body: ExactBodyCbor, witnesses: Vector[VKeyWitness])

object CardanoWitness:
  import WitnessInputError.*
  private type Checked[A] = Either[WitnessInputError, A]
  private def malformed[A](detail: String): Checked[A] = Left(MalformedCbor(detail))
  private def traverse[A, B](xs: Vector[A])(f: A => Checked[B]): Checked[Vector[B]] =
    xs.foldLeft[Checked[Vector[B]]](Right(Vector.empty)) { (acc, x) =>
      for out <- acc; next <- f(x) yield out :+ next
    }

  private[witness] def uniqueUIntMap(node: Node, label: String): Checked[Map[BigInt, Node]] =
    node.value match
      case Value.Map(entries) =>
        traverse(entries) { case (k, v) =>
          k.value match
            case Value.UInt(n) => Right(n -> v)
            case _             => malformed(s"$label keys must be unsigned integers")
        }.flatMap { pairs =>
          if pairs.map(_._1).distinct.size != pairs.size then malformed(s"duplicate $label map key")
          else Right(pairs.toMap)
        }
      case _ => malformed(s"$label must be a CBOR map")

  private def bytes(node: Node): Checked[Bytes] = node.value match
    case Value.ByteString(value) => Right(value)
    case _                       => malformed("vkey and signature must be byte strings")

  private def witness(node: Node): Checked[VKeyWitness] = node.value match
    case Value.Arr(Vector(key, sig)) =>
      for
        keyBytes <- bytes(key)
        sigBytes <- bytes(sig)
        publicKey <- PublicKey32.create(keyBytes)
        signature <- Signature64.create(sigBytes)
      yield VKeyWitness(publicKey, signature)
    case _ => malformed("vkey witness must be a two-element array")

  private def witnessSet(node: Node): Checked[Vector[VKeyWitness]] = node.value match
    case Value.Tag(n, inner) if n == 258 => witnessArray(inner)
    case _                               => witnessArray(node)

  private def witnessArray(node: Node): Checked[Vector[VKeyWitness]] = node.value match
    case Value.Arr(items) =>
      traverse(items)(witness).flatMap { ws =>
        if ws.isEmpty then Left(UnsupportedShape("empty vkey set has no signature to check"))
        else if ws.map(_.publicKey).distinct.size != ws.size then
          malformed("duplicate vkey witness public key")
        else Right(ws)
      }
    case _ => malformed("vkey set must be an array, optionally tagged 258")

  /** Narrow research parser: four fields, structural body map, only witness key 0, Boolean validity
    * flag, null auxiliary data. Unsupported containers fail before crypto. This intentionally does
    * not implement Cardano's complete CBOR acceptance rules.
    */
  def decodeEnvelope(transaction: Bytes): Checked[WitnessEnvelope] =
    Cbor.decode(transaction).left.map(MalformedCbor.apply).flatMap { node =>
      node.value match
        case Value.Arr(Vector(body, witnesses, validity, auxiliary)) =>
          for
            exactBody <- ExactBodyCbor.create(body.original)
            _ <- validity.value match
              case Value.Bool(_) => Right(())
              case _             => malformed("validity flag must be Boolean")
            _ <- auxiliary.value match
              case Value.Null => Right(())
              case _ => Left(UnsupportedShape("auxiliary data is outside this parser's scope"))
            fields <- uniqueUIntMap(witnesses, "witness")
            _ <-
              if fields.keySet == Set(BigInt(0)) then Right(())
              else
                Left(UnsupportedShape("only a nonempty vkey-only witness map (key 0) is supported"))
            ws <- witnessSet(fields(BigInt(0)))
          yield WitnessEnvelope(exactBody, ws)
        case _ => malformed("expected four-element Conway transaction envelope")
    }

  /** Plain Ed25519 over the 32 raw BLAKE2b-256 body-hash bytes. Not Ed25519ph. */
  def verifyVKeyWitness(
      bodyHash: BodyHash32,
      witness: VKeyWitness
  ): Either[VerificationError, VerificationResult] =
    try StrictEd25519.verifyEd25519(witness.publicKey, witness.signature, bodyHash.bytes)
    catch case NonFatal(e) => Left(VerificationError.ImplementationFailure(e.getClass.getName))

  def verifyVKeyWitness(
      body: ExactBodyCbor,
      witness: VKeyWitness
  ): Either[VerificationError, VerificationResult] =
    try verifyVKeyWitness(body.hash, witness)
    catch case NonFatal(e) => Left(VerificationError.ImplementationFailure(e.getClass.getName))
