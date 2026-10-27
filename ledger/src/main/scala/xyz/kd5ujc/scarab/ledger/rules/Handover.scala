// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronOut}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** `FromByronTranslationContext` (`Shelley/Translation.hs:46`): what the Shelley genesis gives the
  * ledger state that the chain enters Shelley with: the genesis delegations, the protocol
  * parameters and the maximum supply of lovelace.
  */
final case class FromByron(
    genDelegs: Map[Bytes, node.GenDelegPair],
    protocolParams: node.ProtocolParams,
    maxLovelaceSupply: BigInt
)

/** The hand-over from Byron to Shelley (`translateToShelleyLedgerStateFromUtxo`,
  * `Shelley/API/ByronTranslation.hs:98`; consensus' `translateLedgerStateByronToShelleyWrapper`,
  * `CanHardFork.hs:336`): the new-epoch state of the first Shelley epoch, from the Byron UTxO the
  * chain ends with.
  *
  * Nothing else of Byron is carried: no accounts, no pools, empty snapshots, the genesis
  * delegations and the parameters of the Shelley genesis, the reserves what the outputs leave of
  * the supply, and the Byron redeem (AVVM) outputs stashed for the Shelley to Allegra translation
  * ([[Translate.shelleyToAllegra]]). Outputs of no lovelace, which some test networks inject, are
  * not carried (`translateUTxOByronToShelley`).
  */
object Handover:

  def toShelley(
      context: FromByron,
      epoch: EpochNo,
      utxo: Map[node.TxIn, ByronOut]
  ): Either[TickFailure, NewEpochState[Shelley]] =
    (for
      outputs <- utxo.toVector
        .filter((_, out) => out.value != 0)
        .traverse((in, out) =>
          node.Address
            .decode(out.address)
            .leftMap(_ => TickFailure.UndecodableAddress(in))
            .map(a => (in, node.TxOut(a, node.Value.coin(out.value)), redeemer(out.address)))
        )
      all = node.Utxo.fromMap(outputs.map((in, out, _) => in -> out).toMap)
      stash = node.Utxo.fromMap(outputs.collect { case (in, out, true) => in -> out }.toMap)
      reserves = Coin(context.maxLovelaceSupply - all.coin)
      genDelegs <- context.genDelegs.toList
        .traverse((k, p) => Convert.keyHash[Genesis](k).map(_ -> p))
        .map(Sorted.map(_))
        .leftMap(TickFailure.Vocab(_))
    yield
      // the parameters of the Shelley genesis: the flat record holds the later eras' fields too,
      // and the cost per UTxO word is Alonzo's (introduced by `Translate.maryToAlonzo`)
      val params = context.protocolParams.copy(coinsPerUTxOWord = 0)
      val gov = ShelleyGovState(
        Sorted.empty,
        Sorted.empty,
        params,
        params,
        FuturePParams.NoPParamsUpdate
      )
      val base = Empty.epochState[Shelley](params)
      val cert = ShelleyCertState(
        base.ls.cert.pState,
        base.ls.cert.dState.copy(genDelegs = genDelegs)
      )
      NewEpochState[Shelley](
        epoch,
        BlocksMade.empty,
        BlocksMade.empty,
        EpochState[Shelley](
          ChainAccountState(Coin.zero, reserves),
          LedgerState[Shelley](
            cert,
            UTxOState[Shelley](
              UtxoTables(all),
              Coin.zero,
              Coin.zero,
              gov,
              ShelleyInstantStake(Sorted.empty, Sorted.empty),
              Coin.zero
            )
          ),
          SnapShots.empty,
          NonMyopic.empty
        ),
        None,
        PoolDistr.empty,
        UtxoTables(stash)
      )
    )

  /** `isBootstrapRedeemer`: a Byron address that pays to a redeem key. */
  private def redeemer(address: Bytes): Boolean =
    ByronAddress.decode(address).exists(_.isRedeem)
