// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.witness.{CardanoWitness, ExactBodyCbor, VKeyWitness, VerificationResult}

/** Bounded Conway PV9 native-script predicate, not script-credential or ledger admission. */
object NativeScript:
  val ProfileId = "conway-pv9-native-script-predicate-v1"
  val MaxBytes = 65536
  val MaxNodes = 1024
  val MaxDepth = 16
  val MaxWitnesses = 128
  private enum Term:
    case Signature(hash: Bytes)
    case All(children: Vector[Term])
    case Any(children: Vector[Term])
    case Threshold(required: BigInt, children: Vector[Term])
    case Start(slot: BigInt)
    case Expire(slot: BigInt)

  final class Script private[NativeScript] (
      val original: Bytes,
      private[NativeScript] val term: Term
  ):
    val hash: Bytes = Blake2b.hash224.hash(Bytes(Vector(0.toByte) ++ original.value))

  /** Can only be obtained by verifying every supplied witness against the exact original body. */
  final class VerifiedKeys private[NativeScript] (
      val originalBody: Bytes,
      val hashes: Set[Bytes]
  )
  final case class Evaluation(scriptHash: Bytes, transactionId: Bytes, satisfied: Boolean):
    val profileId = ProfileId
    val credentialBound = false
    val fullLedgerValidated = false

  private def uint64(node: Node): Either[String, BigInt] = node.value match
    case V.UInt(x) if x <= ValidityInterval.MaxSlot => Right(x)
    case _                                          => Left("native timelock requires uint64 slot")

  private def signed64(node: Node): Either[String, BigInt] = node.value match
    case V.UInt(x) if x <= BigInt(Long.MaxValue) => Right(x)
    case V.NInt(x) if x >= BigInt(Long.MinValue) => Right(x)
    case _ => Left("threshold requires signed 64-bit reference Int")

  def decode(original: Bytes): Either[String, Script] =
    def children(node: Node, depth: Int): Either[String, Vector[Term]] = node.value match
      case V.Arr(xs) =>
        xs.foldLeft[Either[String, Vector[Term]]](Right(Vector.empty)) { (acc, child) =>
          for previous <- acc; term <- parse(child, depth + 1) yield previous :+ term
        }
      case _ => Left("native children must be an untagged array")
    def parse(node: Node, depth: Int): Either[String, Term] =
      if depth > MaxDepth then Left("native script depth limit exceeded")
      else
        node.value match
          case V.Arr(Vector(tag, hash)) if tag.value == V.UInt(0) =>
            hash.value match
              case V.ByteString(bytes)
                  if bytes.size == 28 && (hash.original.value.head & 31) != 31 =>
                Right(Term.Signature(bytes))
              case _ => Left("signature requirement needs a definite 28-byte key hash")
          case V.Arr(Vector(tag, nested)) if tag.value == V.UInt(1) =>
            children(nested, depth).map(Term.All.apply)
          case V.Arr(Vector(tag, nested)) if tag.value == V.UInt(2) =>
            children(nested, depth).map(Term.Any.apply)
          case V.Arr(Vector(tag, count, nested)) if tag.value == V.UInt(3) =>
            for required <- signed64(count); terms <- children(nested, depth)
            yield Term.Threshold(required, terms)
          case V.Arr(Vector(tag, slot)) if tag.value == V.UInt(4) =>
            uint64(slot).map(Term.Start.apply)
          case V.Arr(Vector(tag, slot)) if tag.value == V.UInt(5) =>
            uint64(slot).map(Term.Expire.apply)
          case _ => Left("unsupported native constructor, tag, or arity")
    for
      root <- Cbor.decode(original, Cbor.Limits(MaxBytes, 2 * MaxDepth + 2, MaxNodes, MaxBytes))
      term <- parse(root, 0)
    yield new Script(root.original, term)

  /** Duplicate identical witnesses contribute one key. Different signatures for the same public key
    * are all verified; no invalid signature is discarded by key-only deduplication.
    */
  def verifyKeys(
      interval: ValidityInterval.Interval,
      witnesses: Vector[VKeyWitness]
  ): Either[String, VerifiedKeys] =
    for
      _ <- Either.cond(witnesses.size <= MaxWitnesses, (), "witness count limit exceeded")
      body <- ExactBodyCbor.create(interval.originalBody).left.map(_.toString)
      hashes <- witnesses.distinct.foldLeft[Either[String, Set[Bytes]]](Right(Set.empty)) {
        (acc, witness) =>
          for
            previous <- acc
            verified <- CardanoWitness.verifyVKeyWitness(body, witness).left.map(_.toString)
            _ <- Either.cond(
              verified == VerificationResult.SignatureVerified,
              (),
              "native witness signature rejected"
            )
          yield previous + Blake2b.hash224.hash(witness.publicKey.bytes)
      }
    yield new VerifiedKeys(interval.originalBody, hashes)

  def evaluate(
      script: Script,
      keys: VerifiedKeys,
      interval: ValidityInterval.Interval
  ): Either[String, Evaluation] =
    def go(term: Term): Boolean = term match
      case Term.Signature(hash) => keys.hashes.contains(hash)
      case Term.All(xs)         => xs.forall(go)
      case Term.Any(xs)         => xs.exists(go)
      case Term.Threshold(required, xs) =>
        if required <= 0 then true
        else if required > xs.size then false
        else xs.iterator.filter(go).take(required.toInt).size == required.toInt
      case Term.Start(slot)  => interval.lower.exists(slot <= _)
      case Term.Expire(slot) => interval.upper.exists(_ <= slot)
    Either.cond(
      keys.originalBody == interval.originalBody,
      Evaluation(script.hash, interval.transactionId, go(script.term)),
      "verified witness body differs from interval body"
    )
