// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.{Block, DetachedRecords, Tx}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

/** A transaction of the ledger's era: its parts, each as the bytes it was read from, with its flag,
  * and the transaction they make in that era.
  */
final case class EraTx(parts: Block.Transaction, tx: Tx)

/** A transaction in an era whose transactions the pool takes: one of an era from Shelley to Conway
  * as the flat decoders read it, with the era it is of ([[Flat]]); one of Conway read into the
  * era's types too, in the layout of a Conway ledger ([[InConway]]); or one of Dijkstra in the
  * era's types ([[InDijkstra]]).
  */
enum InEra:
  case Flat(of: Era, transaction: EraTx)
  case InConway(transaction: ConwayRead)
  case InDijkstra(transaction: DijkstraRead)

  /** The era it is of. */
  def era: Era = this match
    case Flat(era, _)  => era
    case InConway(_)   => Era.Conway
    case InDijkstra(_) => Era.Dijkstra

  /** The id, the hash of the body. */
  def id: Bytes = this match
    case Flat(_, t)       => t.parts.id
    case InConway(read)   => read.id
    case InDijkstra(read) => read.id

  /** The transaction as the reference writes it in its era, to relay, to list and to read again in
    * another ([[EraInjection.envelope]]; Dijkstra's mempool form, [[DijkstraRead.raw]]).
    */
  def raw: Bytes = this match
    case Flat(era, t)     => EraInjection.envelope(t.parts, era)
    case InConway(read)   => read.raw
    case InDijkstra(read) => read.raw

/** How the mempool takes a transaction of another era than its ledger's, a submitted one or one it
  * holds when the ledger enters a new era. The two reference builds differ here, and the selected
  * build governs (REF-3, [[of]]):
  *
  *   - [[Translate]], cardano-node 11.1.3: the hard-fork combinator injects a transaction of an
  *     earlier era into each later era in turn (`matchPolyTx'`, `HardFork/Combinator/InjectTxs.hs:
  *     60-102`, ouroboros-consensus 82ecba32) with the era's `translateEra` (`hardForkInjectTxs`,
  *     `Cardano/CanHardFork.hs:182-224`), the same for a held transaction
  *     (`translateValidatedTx…Wrapper`, which keeps it validated). Byron cannot be injected
  *     (`cannotInjectTx`), and a later-era transaction is not injected at all.
  *   - [[Redecode]], cardano-node 11.2.1 and the Leios build: a transaction of any other era,
  *     earlier or later, is serialised in its era and decoded in the ledger's (`matchTx`,
  *     `HardFork/Combinator/InjectTxs.hs:215-247`, ouroboros-consensus 808e4005; the same file at
  *     2416493b); a held one is so too, and loses its validation (`rematchValidatedTxs`, lines
  *     75-193 at 808e4005).
  *
  * A transaction that does not reach the ledger's era is refused with the combinator's era
  * mismatch, which names the transaction's era: under [[Translate]] the era it was last translated
  * into (`ML x (Telescope.tip f)` after the steps that succeeded, `InjectTxs.hs:79-82`), under
  * [[Redecode]] the era it came in (`reject txEraNS`, `InjectTxs.hs:105-111` at 808e4005).
  */
enum EraInjection:
  case Translate
  case Redecode

  /** `t` in the ledger's era `to`; or the era the mismatch names for it. Only [[Redecode]] reaches
    * Dijkstra, or leaves it: [[Translate]] is cardano-node 11.1.3's, whose node never enters it.
    */
  def into(t: InEra, to: Era): Either[Era, InEra] =
    if t.era == to then Right(t)
    else
      (this, t) match
        case (Translate, InEra.Flat(from, flat)) if to != Era.Dijkstra =>
          EraInjection.translate(flat, from, to).map(InEra.Flat(to, _))
        case (Translate, _) => Left(t.era)
        case (Redecode, _)  => EraInjection.redecode(t, to)

  /** How a held transaction brought into a new era is validated there: under [[Translate]] it stays
    * validated and is applied again without the static checks (`InjectValidatedTx`,
    * `HardFork/Combinator/Mempool.hs:182-192`, ouroboros-consensus 82ecba32); under [[Redecode]] it
    * was decoded afresh and is applied with every check, as from a peer (`ApplyTxs`, `applyTx …
    * DoNotIntervene`, `HardFork/Combinator/Mempool.hs:198-222`, ouroboros-consensus 808e4005).
    */
  def revalidation: EraInjection.Revalidation = this match
    case Translate => EraInjection.Revalidation.Reapply
    case Redecode  => EraInjection.Revalidation.Apply

object EraInjection:
  /** How a held transaction that changed era is validated again. */
  enum Revalidation:
    case Reapply
    case Apply

  /** Where the mempool refuses a submitted transaction that does not reach the ledger's era. */
  enum Mismatch:
    /** In its first measure, before any wait: `txMeasurePhase1` throws the mismatch
      * (`HardFork/Combinator/Mempool.hs` 357-362, ouroboros-consensus 808e4005; 390 at 2416493b).
      */
    case InMeasure

    /** By the rules, in the timed step: both measures are nothing (`Measure.zero`,
      * `HardFork/Combinator/Mempool.hs` 371-376 and 416-421, ouroboros-consensus 82ecba32), so the
      * transaction waits for room and for time as any other, and `applyTx` refuses it (501-506).
      */
    case InRules

  object Mismatch:
    /** The place of the selected build (REF-3). */
    def of(build: ReferenceBuild): Mismatch = build match
      case ReferenceBuild.Release11_1 => InRules
      case ReferenceBuild.Release11_2 => InMeasure
      case ReferenceBuild.Leios       => InMeasure

  /** The injection of the selected build (REF-3): 11.1 is cardano-node 11.1.3's; 11.2 is
    * cardano-node 11.2.1's, and the Leios build's is the same code (`InjectTxs.hs` is the same file
    * at ouroboros-consensus 808e4005 and 2416493b).
    */
  def of(build: ReferenceBuild): EraInjection = build match
    case ReferenceBuild.Release11_1 => Translate
    case ReferenceBuild.Release11_2 => Redecode
    case ReferenceBuild.Leios       => Redecode

  /** The whole transaction of `era` as the reference writes it, to relay and to list: before Alonzo
    * the record `[body, witnesses, auxiliary data or null]` (`EncCBOR (ShelleyTx l era)`,
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Tx.hs:247-262`, cardano-ledger f649f975), from
    * Alonzo the four items with the flag ([[Tx.envelope]]). Each part keeps the bytes it was read
    * from, as the ledger's memoized parts do.
    */
  def envelope(parts: Block.Transaction, era: Era): Bytes =
    if era.ordinal >= Era.Alonzo.ordinal then Tx.envelope(parts)
    else
      Bytes.fromArray(Array(0x83.toByte)) ++ parts.body ++ parts.witnesses ++
        parts.auxiliaryData.getOrElse(Bytes.fromArray(Array(0xf6.toByte)))

  /** `translateEra` of `Tx` into each era after `from` up to `to`: the parts are decoded again by
    * the next era's decoders at its lowest version (`translateEraThroughCBOR`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/Translation.hs:115-123`, cardano-ledger
    * f649f975; the instances of `Allegra/Translation.hs:74-76`, `Mary/Translation.hs:63-65`,
    * `Alonzo/Translation.hs:73-88`, `Babbage/Translation.hs:61-78`,
    * `Conway/Translation.hs:89-103`). From Mary to Alonzo the transaction is marked as passing its
    * scripts (`Phase2Valid`); from Alonzo the flag is kept. The id is the hash of the body, whose
    * bytes do not change (`mkShelleyTx`, `ShelleyHFC.hs:401-406`).
    */
  private def translate(t: EraTx, from: Era, to: Era): Either[Era, EraTx] =
    if to.ordinal < from.ordinal then Left(from)
    else
      Era.values.toVector
        .filter(e => e.ordinal > from.ordinal && e.ordinal <= to.ordinal)
        .foldLeft[Either[Era, (Era, EraTx)]](Right(from -> t)) {
          case (Right((at, current)), next) =>
            val parts =
              if next == Era.Alonzo then current.parts.copy(isValid = true) else current.parts
            Tx.fromBlock(parts, next).bimap(_ => at, tx => next -> EraTx(parts, tx))
          case (stopped, _) => stopped
        }
        .map(_._2)

  /** The transaction serialised in its era ([[InEra.raw]]) and decoded whole in `to`, with nothing
    * left over (`deserialiseFromBytes fromCBOR`, `InjectTxs.hs:239-247` at 808e4005; the same file
    * at 2416493b): Dijkstra's by its mempool decoder (`decodeDijkstraTopTx`,
    * [[DijkstraRead.submitted]]).
    */
  private def redecode(t: InEra, to: Era): Either[Era, InEra] =
    val from = t.era
    to match
      case Era.Dijkstra =>
        DijkstraRead.submitted(t.raw).bimap(_ => from, InEra.InDijkstra(_))
      case Era.Byron | Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage |
          Era.Conway =>
        DetachedRecords.read(
          t.raw,
          Cbor.Limits.Unbounded,
          DetachedRecords.Shapes.transaction(to)
        )(_ => from)(root =>
          Tx.envelopeParts(root, to)
            .flatMap(parts => Tx.fromBlock(parts, to).map(EraTx(parts, _)))
            .bimap(_ => from, InEra.Flat(to, _))
        )
