// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Address
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  ConwayInstantStake,
  Dijkstra,
  Credential,
  EraTag,
  InstantStake,
  Staking,
  VocabError
}

/** The stake the outputs of the UTxO set give their credentials as the set changes (`class EraStake
  * era`, `libs/cardano-ledger-core/src/Cardano/Ledger/State/Stake.hs`, cardano-ledger f649f975),
  * with the members the UTxO rules use. An output's lovelace and stake credential are read into the
  * state's vocabulary, which refuses what the era's output cannot hold
  * ([[xyz.kd5ujc.scarab.ledger.state.VocabError]]); the reference reads them with no refusal.
  */
trait EraStake[E <: EraTag]:
  /** `instantStakeCredentialsL` (`State/Stake.hs:191`, cardano-ledger 9df9f98c): the stake of each
    * credential.
    */
  def instantStakeCredentialsL: Lens[InstantStake[E], SortedMap[Credential[Staking], CompactCoin]]

  /** `addInstantStake`: the stake of the outputs of `utxo` added to `stake`. */
  def addInstantStake(utxo: UTxO[E], stake: InstantStake[E]): Either[VocabError, InstantStake[E]]

  /** `deleteInstantStake`: the stake of the outputs of `utxo` taken out of `stake`. */
  def deleteInstantStake(
      utxo: UTxO[E],
      stake: InstantStake[E]
  ): Either[VocabError, InstantStake[E]]

/** The instances of each era and the functions of each era's `State/Stake.hs` over the class. */
object EraStake:
  given conway: EraStake[Conway] with
    val instantStakeCredentialsL
        : Lens[InstantStake[Conway], SortedMap[Credential[Staking], CompactCoin]] =
      conwayInstantStakeCredentialsL
    def addInstantStake(
        utxo: UTxO[Conway],
        stake: InstantStake[Conway]
    ): Either[VocabError, InstantStake[Conway]] =
      addConwayInstantStake[Conway](utxo, stake)
    def deleteInstantStake(
        utxo: UTxO[Conway],
        stake: InstantStake[Conway]
    ): Either[VocabError, InstantStake[Conway]] =
      deleteConwayInstantStake[Conway](utxo, stake)

  /** Dijkstra's (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/State/Stake.hs:18-23`,
    * cardano-ledger 9df9f98c): Conway's instant stake and functions.
    */
  given dijkstra: EraStake[Dijkstra] with
    val instantStakeCredentialsL
        : Lens[InstantStake[Dijkstra], SortedMap[Credential[Staking], CompactCoin]] =
      conwayInstantStakeCredentialsL
    def addInstantStake(
        utxo: UTxO[Dijkstra],
        stake: InstantStake[Dijkstra]
    ): Either[VocabError, InstantStake[Dijkstra]] =
      addConwayInstantStake[Dijkstra](utxo, stake)
    def deleteInstantStake(
        utxo: UTxO[Dijkstra],
        stake: InstantStake[Dijkstra]
    ): Either[VocabError, InstantStake[Dijkstra]] =
      deleteConwayInstantStake[Dijkstra](utxo, stake)

  /** `conwayInstantStakeCredentialsL` (`Conway/State/Stake.hs:83-85`, cardano-ledger 9df9f98c). */
  private val conwayInstantStakeCredentialsL
      : Lens[ConwayInstantStake, SortedMap[Credential[Staking], CompactCoin]] =
    Lens[ConwayInstantStake, SortedMap[Credential[Staking], CompactCoin]](_.credentialStake)(v =>
      _ => ConwayInstantStake(v)
    )

  /** `addConwayInstantStake` (`eras/conway/impl/src/Cardano/Ledger/Conway/State/Stake.hs:87-89`,
    * cardano-ledger f649f975): `(+) @Word64`, which wraps.
    */
  def addConwayInstantStake[E <: EraTag](utxo: UTxO[E], stake: ConwayInstantStake)(using
      EraTxOut[E]
  ): Either[VocabError, ConwayInstantStake] =
    applyUTxOConwayInstantStake[E]((old, out) => old.add(out))(utxo, stake)

  /** `deleteConwayInstantStake` (`Conway/State/Stake.hs:91-93`, cardano-ledger f649f975):
    * `(-) @Word64`, which wraps.
    */
  def deleteConwayInstantStake[E <: EraTag](utxo: UTxO[E], stake: ConwayInstantStake)(using
      EraTxOut[E]
  ): Either[VocabError, ConwayInstantStake] =
    applyUTxOConwayInstantStake[E]((old, out) => old.subtract(out))(utxo, stake)

  /** `applyUTxOConwayInstantStake` (`Conway/State/Stake.hs:95-121`, cardano-ledger f649f975): a
    * left fold over the outputs in the order of their inputs (`Map.foldl'`), each output of a base
    * address (`StakeRefBase`) altering its stake credential's entry: an absent entry becomes the
    * output's lovelace, a present one `f` of it and the lovelace, and an entry that is zero is
    * removed (`keepOrDeleteCompact`). An output of any other address leaves the stake as it is.
    */
  def applyUTxOConwayInstantStake[E <: EraTag](f: (CompactCoin, CompactCoin) => CompactCoin)(
      utxo: UTxO[E],
      instantInstantStake: ConwayInstantStake
  )(using o: EraTxOut[E]): Either[VocabError, ConwayInstantStake] =
    def keepOrDeleteCompact(
        cc: CompactCoin
    )(entry: Option[CompactCoin]): Option[CompactCoin] =
      val next = entry.fold(cc)(f(_, cc))
      Option.unless(next.word.isZero)(next)
    utxo.unUTxO.toVector
      .sortBy(_._1)
      .foldLeftM(instantInstantStake.credentialStake) { case (ans, (_, out)) =>
        o.address(out).kind match
          case Address.Kind.Shelley(_, _, Some(stakingKeyHash), _) =>
            for
              credential <- Convert.credential[Staking](stakingKeyHash)
              cc <- CompactCoin.fromCoin(o.valueOps.coin(o.value(out)))
            yield alter(ans, credential)(keepOrDeleteCompact(cc))
          case _ => Right(ans)
      }
      .map(ConwayInstantStake(_))

  /** `Map.alter`. */
  private def alter(
      m: SortedMap[Credential[Staking], CompactCoin],
      k: Credential[Staking]
  )(f: Option[CompactCoin] => Option[CompactCoin]): SortedMap[Credential[Staking], CompactCoin] =
    f(m.get(k)).fold(m.removed(k))(m.updated(k, _))
