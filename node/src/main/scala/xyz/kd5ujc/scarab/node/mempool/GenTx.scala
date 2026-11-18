// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.node.PrefixTokens
import xyz.kd5ujc.scarab.protocol.{Era, LocalTxSubmission}

/** `GenTx (CardanoBlock c)` as a node-to-client client submits it, `[era, transaction]`, judged
  * while it has not all arrived. The reference's codec reads it with the hard-fork combinator's
  * `decodeNodeToClient`, `dispatchDecoder` over the eras' own decoders, from the stream
  * (`decodeWithByteSpan decodeTx`, ouroboros-network c45735a5
  * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxSubmission/Codec.hs:148-154`;
  * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:258-276, 362-367`, every era enabled
  * at every node-to-client version from 16 to 23, `Cardano/Node.hs:298-453`, ouroboros-consensus
  * 82ecba32), so a prefix it fails on ends the connection before the rest arrives.
  */
object GenTx:
  /** Where the reference's decoder of a `GenTx` fails on the part of one that has arrived. */
  enum Refusal extends DomainError:
    /** `decodeNS` reads the era and its transaction with `enforceSize "decodeNS" 2`. */
    case EraEnvelope

    /** `decodeNS` reads the era index with `decodeWord8`: an unsigned integer up to 255. */
    case EraIndex

    /** An era index past the last of `CardanoEras` ("decodeNS: invalid index"). */
    case UnknownEra(index: Int)

    /** Byron's `MempoolPayload` is read with `enforceSize "MempoolPayload" 2`. */
    case ByronEnvelope

    /** Byron's `MempoolPayload` reads its constructor with `decodeWord8`. */
    case ByronTag

    /** A constructor past Byron's four (`DecoderErrorUnknownTag "MempoolPayload"`). */
    case UnknownByronTag(tag: Int)

    /** `unwrapCBORinCBOR` reads a tag with `decodeTag`, and the item is not one. */
    case ExpectedTag(era: Era)

    /** A tag other than 24 ("expected tag 24 (CBOR-in-CBOR)"). */
    case NotCborInCbor(era: Era)

    /** `unwrapCBORinCBOR` reads the transaction's bytes with cborg's `decodeBytes`, a definite byte
      * string, and the item is not one.
      */
    case ExpectedBytes(era: Era)

  /** What the reference's decoder of a `GenTx` makes of `input`, the part of one that has arrived,
    * each token judged once its head has arrived whole ([[PrefixTokens]]):
    *   - `decodeNS`: `enforceSize "decodeNS" 2`, `decodeWord8` and the era's index
    *     (`HardFork/Combinator/Serialisation/Common.hs:420-425`);
    *   - Byron: `decodeByronGenTx`, `fromByronCBOR` of a `MempoolPayload`
    *     (`Byron/Node/Serialisation.hs:176-178`, `Byron/Ledger/Mempool.hs:338-339`), its
    *     `enforceSize "MempoolPayload" 2` and a constructor from 0 to 3
    *     (`eras/byron/ledger/impl/src/Cardano/Chain/MempoolPayload.hs:85-93`, cardano-ledger
    *     f649f975);
    *   - a Shelley-based era: `unwrapCBORinCBOR` (`Shelley/Ledger/Mempool.hs:261-266`), which reads
    *     tag 24 and a definite byte string (`decodeTag`, `decodeBytes`,
    *     `ouroboros-network/api/lib/Ouroboros/Network/Block.hs:501-504`, ouroboros-network
    *     c45735a5); the transaction in it is read only once it has all arrived (`fromSerialised`,
    *     `Block.hs:477-486`), as [[LocalSubmission.unwrap]] reads a whole one.
    *
    * A prefix is refused only where every message that starts with it is refused whole too
    * ([[LocalSubmission.unwrap]], [[MempoolState.read]]), so whether a transaction is admitted does
    * not depend on how its bytes arrive. Three forms the reference fails on wait here, because the
    * whole read admits them in the eras the pool reads: an indefinite envelope, a list where the
    * tag is due (a transaction not wrapped in CBOR-in-CBOR) and a chunked string after tag 24.
    * Byron's and Dijkstra's transactions are never read whole ([[MempoolState.Read.Foreign]]), so
    * in those eras every prefix the reference fails on is refused.
    */
  def prefix(input: Bytes): LocalTxSubmission.TxPrefix =
    val tokens = PrefixTokens(input, LocalTxSubmission.TxPrefix.Short)
    tokens
      .head(0)
      .flatMap(envelope =>
        // an indefinite envelope is read whole; its era is judged as a definite one's
        if envelope.major == 4 && (envelope.argument == 2 || envelope.info == 31) then
          Right(envelope.next)
        else Left(refused(Refusal.EraEnvelope))
      )
      .flatMap(tokens.word8(_, refused(Refusal.EraIndex)))
      .flatMap((index, next) =>
        Era
          .fromIndex(BigInt(index))
          .toRight(refused(Refusal.UnknownEra(index)))
          .flatMap(transaction(tokens, next, _))
      )
      .merge

  /** [[prefix]], as the LocalTxSubmission server asks it. */
  val reader: LocalTxSubmission.TxReader = prefix(_)

  private type Step[A] = Either[LocalTxSubmission.TxPrefix, A]
  private type Tokens = PrefixTokens[LocalTxSubmission.TxPrefix]

  private def refused(refusal: Refusal): LocalTxSubmission.TxPrefix =
    LocalTxSubmission.TxPrefix.Refused(refusal)

  /** How the pool reads a transaction of an era whole, as far as the first tokens decide. */
  private enum Whole:
    /** Never: the era's transactions get the era mismatch, whatever their bytes. */
    case Foreign

    /** Wrapped in tag 24 or not, the wrapped bytes chunked or not ([[LocalSubmission.unwrap]]). */
    case Lenient

  private def transaction(tokens: Tokens, at: Int, era: Era): Step[LocalTxSubmission.TxPrefix] =
    era match
      case Era.Byron =>
        tokens
          .listOf(2, at, refused(Refusal.ByronEnvelope))
          .flatMap(tokens.word8(_, refused(Refusal.ByronTag)))
          .flatMap((constructor, _) =>
            Either.cond(
              constructor <= 3,
              LocalTxSubmission.TxPrefix.Short,
              refused(Refusal.UnknownByronTag(constructor))
            )
          )
      case Era.Dijkstra => wrapped(tokens, at, era, Whole.Foreign)
      case Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway =>
        wrapped(tokens, at, era, Whole.Lenient)

  /** `unwrapCBORinCBOR`'s tag 24 and the head of its byte string. */
  private def wrapped(
      tokens: Tokens,
      at: Int,
      era: Era,
      whole: Whole
  ): Step[LocalTxSubmission.TxPrefix] =
    tokens
      .head(at)
      .flatMap(tag =>
        (tag.major, whole) match
          case (4, Whole.Lenient)           => Right(LocalTxSubmission.TxPrefix.Short)
          case (6, _) if tag.argument == 24 => tokens.head(tag.next).flatMap(bytes(era, whole))
          case (6, _)                       => Left(refused(Refusal.NotCborInCbor(era)))
          case (_, Whole.Foreign | Whole.Lenient) => Left(refused(Refusal.ExpectedTag(era)))
      )

  private def bytes(era: Era, whole: Whole)(
      head: PrefixTokens.Head
  ): Step[LocalTxSubmission.TxPrefix] =
    (head.major, head.info, whole) match
      case (2, info, _) if info < 28             => Right(LocalTxSubmission.TxPrefix.Short)
      case (2, 31, Whole.Lenient)                => Right(LocalTxSubmission.TxPrefix.Short)
      case (_, _, Whole.Foreign | Whole.Lenient) => Left(refused(Refusal.ExpectedBytes(era)))
