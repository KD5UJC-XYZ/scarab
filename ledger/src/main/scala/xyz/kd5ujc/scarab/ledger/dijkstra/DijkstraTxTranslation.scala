// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.ledger.{Block, LedgerDecodeError, Tx}

/** The part of a transaction a translation through CBOR names when it fails: the labels
  * `translateEraThroughCBOR` is given (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/
  * Translation.hs:60-62`, cardano-ledger 9df9f98c).
  */
enum DijkstraTxPart:
  case TxBody
  case TxWits
  case TxAuxData

/** Why a Conway transaction does not carry into Dijkstra. */
enum DijkstraTxTranslationError:
  /** A part does not decode at Dijkstra's version: the `DecoderError` of `TranslateEra DijkstraEra
    * (Tx TopTx)` (`Translation.hs:54`).
    */
  case Undecodable(part: DijkstraTxPart, cause: LedgerDecodeError)

  /** The whole transaction, as Conway writes it, is not one Dijkstra decodes: the hard-fork
    * mempool's `MismatchEraInfo` for a transaction it cannot carry into the ledger's era
    * (`HardFork/Combinator/InjectTxs.hs`, `rematchValidatedTxs`, ouroboros-consensus 2416493b).
    */
  case WrongEra(cause: LedgerDecodeError)

/** A Conway transaction carried into Dijkstra, in the two ways the build of the public Leios
  * testnet has. Both are pure and neither is wired yet: the node does not enter Dijkstra (#54).
  *
  * The two differ only on a transaction whose validity flag is false, which [[translateEra]]
  * carries and [[rematch]] refuses, since Dijkstra's mempool form can no longer say it.
  */
object DijkstraTxTranslation:

  /** `TranslateEra DijkstraEra (Tx TopTx)` (`Translation.hs:53-68`): the body, the witness set and
    * the auxiliary data, in that order, each written as Conway wrote it (`toCBOR` of a memoized
    * part is its original bytes) and read whole at Dijkstra's version (`translateEraThroughCBOR`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/Translation.hs:115-123`, through
    * `translateViaCBORAnnotator`, `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary.hs:60-70`),
    * the validity flag kept. Each part keeps its bytes, so the id is the same.
    *
    * The reference keeps no bytes of the whole transaction (`Translation.hs:57-59`, `Dijkstra/
    * Tx.hs:483-490`); the result's are those Dijkstra writes it in, the mempool form
    * `[body, witnesses, auxiliary data / nil]` with no flag (`toCBORForMempoolSubmission`,
    * `Tx.hs:500-514`). Nothing hashes them.
    */
  def translateEra(conway: Block.Transaction): Either[DijkstraTxTranslationError, DijkstraTx] =
    for
      body <- part(DijkstraTxPart.TxBody, conway.body)(DijkstraTx.body(_, DijkstraLevel.Top))
      witnesses <- part(DijkstraTxPart.TxWits, conway.witnesses)(DijkstraWitnesses.decode)
      auxiliary <- conway.auxiliaryData.traverse(
        part(DijkstraTxPart.TxAuxData, _)(DijkstraAuxData.decode)
      )
    yield DijkstraTx(
      mempoolForm(body, witnesses, auxiliary),
      body,
      witnesses,
      auxiliary,
      conway.isValid
    )

  /** What the hard-fork mempool does with a Conway transaction it holds when the ledger enters
    * Dijkstra (`rematchValidatedTxs`, `HardFork/Combinator/InjectTxs.hs`, ouroboros-consensus
    * 2416493b), and with one submitted in Conway's era to a ledger in Dijkstra (`matchTx`, same
    * file): the transaction written by Conway's `toCBOR`, the definite list of four whose parts
    * keep their bytes ([[Tx.envelope]]), and read whole by Dijkstra's `fromCBOR`
    * (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:299-309`):
    * `decodeDijkstraTopTx` at version 12 ([[DijkstraTx.fromSubmission]]), which refuses a flag of
    * false. The `CBOR-in-CBOR` wrapping on both sides changes no byte of the transaction. The
    * result's bytes are, as [[translateEra]]'s, those Dijkstra writes it in.
    */
  def rematch(conway: Block.Transaction): Either[DijkstraTxTranslationError, DijkstraTx] =
    DijkstraTx
      .fromSubmission(Tx.envelope(conway))
      .bimap(
        DijkstraTxTranslationError.WrongEra(_),
        tx => tx.copy(raw = mempoolForm(tx.body, tx.witnesses, tx.auxiliary))
      )

  private def part[A](which: DijkstraTxPart, raw: Bytes)(
      decode: Node => Either[LedgerDecodeError, A]
  ): Either[DijkstraTxTranslationError, A] =
    Cbor
      .decode(raw, Cbor.Limits.Unbounded)
      .leftMap(LedgerDecodeError.Syntax(_))
      .flatMap(decode)
      .leftMap(DijkstraTxTranslationError.Undecodable(which, _))

  private def mempoolForm(
      body: DijkstraTxBody,
      witnesses: DijkstraWitnesses,
      auxiliary: Option[DijkstraAuxData]
  ): Bytes =
    Bytes.fromArray(Array(0x83.toByte)) ++ body.raw ++ witnesses.raw ++
      auxiliary.fold(Bytes.fromArray(Array(0xf6.toByte)))(_.raw)
