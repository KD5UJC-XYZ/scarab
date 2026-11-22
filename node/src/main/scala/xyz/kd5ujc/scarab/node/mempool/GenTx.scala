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
    * Only these envelopes are read here: a prefix the reference fails on at a deeper token waits
    * until the message has all arrived. A Shelley-based era's transaction is then read whole in its
    * era, and Byron's payload by the decoder of its kind ([[MempoolState.read]]), so a Byron
    * transaction of three items after constructor 0 (`82 00 82 00 82 00 83`), which the reference
    * refuses at once (`enforceSize "TxAux" 2`,
    * `eras/byron/ledger/impl/src/Cardano/Chain/UTxO/TxAux.hs:108-114`, cardano-ledger f649f975),
    * waits and then ends the connection; Dijkstra's is read whole in the Leios build only. Every
    * message a prefix refused here starts is refused whole too ([[LocalSubmission.unwrap]],
    * [[MempoolState.read]]).
    */
  def prefix(input: Bytes): LocalTxSubmission.TxPrefix =
    val tokens = PrefixTokens(input, LocalTxSubmission.TxPrefix.Short)
    tokens
      .listOf(2, 0, refused(Refusal.EraEnvelope))
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
      case Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway |
          Era.Dijkstra =>
        wrapped(tokens, at, era)

  /** `unwrapCBORinCBOR`'s tag 24 and the head of its definite byte string. */
  private def wrapped(tokens: Tokens, at: Int, era: Era): Step[LocalTxSubmission.TxPrefix] =
    tokens
      .head(at)
      .flatMap(tag =>
        if tag.major != 6 then Left(refused(Refusal.ExpectedTag(era)))
        else if tag.argument != 24 then Left(refused(Refusal.NotCborInCbor(era)))
        else
          tokens
            .head(tag.next)
            .flatMap(bytes =>
              Either.cond(
                bytes.major == 2 && bytes.info < 28,
                LocalTxSubmission.TxPrefix.Short,
                refused(Refusal.ExpectedBytes(era))
              )
            )
      )
