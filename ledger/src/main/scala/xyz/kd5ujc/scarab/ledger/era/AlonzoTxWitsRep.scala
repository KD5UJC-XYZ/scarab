// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.BootstrapWitness
import xyz.kd5ujc.scarab.ledger.state.EraTag

/** The witness set the reference shares from Alonzo (`newtype AlonzoTxWits era = MkAlonzoTxWits
  * (MemoBytes (AlonzoTxWitsRaw era))` over `data AlonzoTxWitsRaw`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:249-255, 266`), with the bytes it arrived
  * as and those of its datums and redeemers, which the script integrity hash is over. Parameterised
  * by what differs between the eras: the script `S` and the purpose `P` its redeemers are keyed by,
  * [[conway.ConwayScript]] and [[conway.ConwayPlutusPurpose]] behind Conway's witness set,
  * Dijkstra's behind Dijkstra's. Each era's witness set is an opaque type over it, so the
  * representation is shared and the types are not; it has no `CanEqual`, so under strict equality
  * two of its values are compared only as the same era's.
  *
  * @param bootstrap
  *   the bootstrap witnesses, one per address root ([[BootstrapWitness.set]])
  * @param scripts
  *   the era's scripts, by hash
  * @param scriptArrival
  *   the scripts' hashes in the order they arrived (native, then each Plutus language's, each list
  *   in its order), each once
  */
final class AlonzoTxWitsRep[S, P] private[era] (
    val raw: Bytes,
    val vkeys: SortedSet[WitVKey],
    val bootstrap: Vector[BootstrapWitness],
    val scripts: SortedMap[Bytes, S],
    val scriptArrival: Vector[Bytes],
    val datums: TxDats,
    val redeemers: Redeemers[P]
):
  // Not a case class: its derived `Mirror` would make one from any fields, whatever its bytes.
  private def fields: Any = (raw, vkeys, bootstrap, scripts, scriptArrival, datums, redeemers)
  override def equals(that: Any): Boolean = that match
    case o: AlonzoTxWitsRep[?, ?] => fields == o.fields
    case _                        => false
  override def hashCode(): Int = fields.hashCode
  override def toString: String = s"AlonzoTxWitsRep($fields)"

object AlonzoTxWitsRep:
  /** The companion of an era's witness set `W`, an opaque type over this representation: its
    * fields, read only, written once for every era. Only the file that defines `W` can give the
    * evidence that `W` is the representation.
    */
  abstract class Fields[W, S, P](using is: W =:= AlonzoTxWitsRep[S, P]):
    extension (w: W)
      /** The shared representation, read only. */
      def rep: AlonzoTxWitsRep[S, P] = is(w)
      def raw: Bytes = is(w).raw
      def vkeys: SortedSet[WitVKey] = is(w).vkeys
      def bootstrap: Vector[BootstrapWitness] = is(w).bootstrap
      def scripts: SortedMap[Bytes, S] = is(w).scripts
      def scriptArrival: Vector[Bytes] = is(w).scriptArrival
      def datums: TxDats = is(w).datums
      def redeemers: Redeemers[P] = is(w).redeemers

  /** `EraTxWits` and `AlonzoEraTxWits` of every era whose witness set is this representation,
    * written once as the reference writes Alonzo's lenses once over `AlonzoTxWits era`
    * (`addrAlonzoTxWitsL` to `rdmrsAlonzoTxWitsL`, `Alonzo/TxWits.hs:421-464`, cardano-ledger
    * f649f975). An era's instance names its scripts' class; only its own file can give the evidence
    * that its witness set is the representation.
    */
  final class Instance[E <: EraTag](scripts: AlonzoEraScript[E])(using
      rep: TxWits[E] =:= AlonzoTxWitsRep[Script[E], PlutusPurpose[AsIx, E]]
  ) extends AlonzoEraTxWits[E]:
    override def scriptsOf: AlonzoEraScript[E] = scripts
    override def addrTxWits(wits: TxWits[E]): SortedSet[WitVKey] = rep(wits).vkeys
    override def bootAddrTxWits(wits: TxWits[E]): Vector[BootstrapWitness] = rep(wits).bootstrap
    override def scriptTxWits(wits: TxWits[E]): SortedMap[Bytes, Script[E]] = rep(wits).scripts
    override def scriptTxWitsArrival(wits: TxWits[E]): Vector[Bytes] = rep(wits).scriptArrival
    override def datsTxWits(wits: TxWits[E]): TxDats = rep(wits).datums
    override def rdmrsTxWits(wits: TxWits[E]): Redeemers[PlutusPurpose[AsIx, E]] =
      rep(wits).redeemers
