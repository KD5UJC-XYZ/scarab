// SPDX-License-Identifier: Apache-2.0
package lab.chain

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal

/** Original-byte body hash and size predicate only. Matching binds the body to the supplied header
  * declarations; it does not authenticate that header or validate a ledger transition. Existing
  * structural indexing and fetch/store admission do not call this predicate.
  */
object CardanoBodyCommitment:
  enum Failure:
    case Malformed(reason: String)
    case InternalFailure(kind: String)

  enum ComponentKind(val label: String):
    case TransactionBodies extends ComponentKind("transactionBodies")
    case TransactionWitnesses extends ComponentKind("transactionWitnesses")
    case AuxiliaryData extends ComponentKind("auxiliaryData")
    case InvalidTransactionIndices extends ComponentKind("invalidTransactionIndices")

  final class Component private[CardanoBodyCommitment] (
      val kind: ComponentKind,
      val offset: Int,
      val length: Int,
      val hash: Bytes
  )

  /** Constructor is deliberately unavailable to callers; immutable, with no copy method and no
    * retained full body copies. Offsets are zero-based in the exact supplied disk envelope.
    */
  final class Observation private[CardanoBodyCommitment] (
      val era: CardanoBlockIndex.Era,
      val rawSha256: Bytes,
      val headerHash: Bytes,
      val declaredSize: Long,
      val actualSize: Long,
      val declaredHash: Bytes,
      val actualHash: Bytes,
      val components: Vector[Component]
  ):
    val bodySizeMatched: Boolean = declaredSize == actualSize
    val bodyHashMatched: Boolean = declaredHash == actualHash
    val bodyCommitmentMatched: Boolean = bodySizeMatched && bodyHashMatched

  val MaxDeclaredSize: BigInt = (BigInt(1) << 32) - 1

  /** Uses the indexer's unchanged hard ceilings and shallow schema acceptance. Caller limits can
    * only tighten those ceilings. Word32 is enforced here, not in structural indexing. A
    * well-formed mismatch is an Observation, never conflated with malformed input.
    */
  def inspect(
      raw: Bytes,
      limits: CardanoBlockIndex.Limits = CardanoBlockIndex.Limits()
  ): Either[Failure, Observation] =
    if raw == null || raw.value == null then Left(Failure.Malformed("null block bytes"))
    else if limits == null then Left(Failure.Malformed("null block limits"))
    else
      try
        CardanoBlockIndex
          .parse(raw, limits)
          .left
          .map(Failure.Malformed.apply)
          .flatMap(inspectParsed)
      catch case NonFatal(e) => Left(Failure.InternalFailure(e.getClass.getName))

  /** Owner-preserving integration seam: ParsedBlock can only be created by the index parser. */
  private[chain] def inspectParsed(
      parsed: CardanoBlockIndex.ParsedBlock
  ): Either[Failure, Observation] =
    if parsed == null then Left(Failure.Malformed("null parsed block"))
    else
      try
        if parsed.declaredBodySize > MaxDeclaredSize then
          Left(Failure.Malformed("body size must be unsigned Word32"))
        else
          val indexed = parsed.indexed
          var offset = Math.addExact(indexed.headerOffset, indexed.headerLength)
          var actualSize = 0L
          val offsets = parsed.components.map { original =>
            val start = offset
            offset = Math.addExact(offset, original.size)
            actualSize = Math.addExact(actualSize, original.size.toLong)
            start
          }
          // Sizes and checked span arithmetic are established before new body digest work.
          val components = parsed.components.zipWithIndex.map { case (original, i) =>
            new Component(
              ComponentKind.values(i),
              offsets(i),
              original.size,
              Blake2b.hash256.hash(original)
            )
          }
          // Raw 3 x 32 or 4 x 32 digest bytes. No CBOR heads and no flat body hash.
          val innerHashes = Bytes(components.flatMap(_.hash.value))
          Right(
            new Observation(
              parsed.era,
              indexed.rawSha256,
              indexed.headerHash,
              parsed.declaredBodySize.toLong,
              actualSize,
              parsed.declaredBodyHash,
              Blake2b.hash256.hash(innerHashes),
              components
            )
          )
      catch case NonFatal(e) => Left(Failure.InternalFailure(e.getClass.getName))
