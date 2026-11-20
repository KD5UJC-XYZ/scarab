// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{NonEmptyList, NonEmptySet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{RewardAccount, TxOutSource}
import xyz.kd5ujc.scarab.ledger.era.{
  AsIx,
  ContextError,
  Language,
  PlutusPurpose,
  PlutusTxInfoResult
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayContextError
import xyz.kd5ujc.scarab.ledger.state.{Coin, Credential, Dijkstra, EraTag, Guard, ScriptHash}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

// Dijkstra's script contexts' types (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs`,
// cardano-ledger 9df9f98c), the ones the annotated transaction names. The translations that give
// them, Dijkstra's `EraPlutusContext` and `EraPlutusTxInfo` instances, come with Dijkstra's script
// contexts (#85, https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6981185123).

/** Why a Dijkstra transaction has no script context (`data DijkstraContextError era`,
  * `TxInfo.hs:155-172`, cardano-ledger 9df9f98c): Conway's failures, a sub-transaction's failure at
  * the top level, and what Dijkstra adds. A transaction id is its body's hash, the account balance
  * intervals and the direct deposits the body's fields.
  */
enum DijkstraContextError[E <: EraTag]:
  case ConwayContextError(error: xyz.kd5ujc.scarab.ledger.era.conway.ConwayContextError[E])
  case SubTxContextError(txId: Bytes, error: ContextError[E])
  case PointerPresentInOutput(txOutSource: TxOutSource)
  case UnsupportedScriptInSubTx(language: Lang, txId: Bytes)
  case DirectDepositsNotSupported(directDeposits: Map[RewardAccount, Coin])
  case AccountBalanceIntervalsNotSupported(
      accountBalanceIntervals: Map[RewardAccount, AccountBalanceInterval]
  )
  case GuardScriptHashesNotSupported(scriptHashes: NonEmptyList[ScriptHash])
  case RequiredTopLevelGuardsNotSupported(guards: NonEmptySet[Credential[Guard]])
  case ScriptHashNotFoundForPurpose(purpose: PlutusPurpose[AsIx, E])

/** Dijkstra's memoised transaction information (`data TxInfoResult DijkstraEra =
  * DijkstraTxInfoResult`, `TxInfo.hs:302-307`, cardano-ledger 9df9f98c): Plutus V1's to V4's, each
  * computed when first looked up, as the reference keeps its fields lazy. Each slot is the shared
  * [[PlutusTxInfoResult]] (`Alonzo/Plutus/Context.hs:193-196`, cardano-ledger 9df9f98c); V4's holds
  * V3's `TxInfo` where 9df9f98c's holds `PV4.TxInfo` (`Context.hs:280`) until the Plutus V4 types
  * come with Dijkstra's script contexts (#85).
  */
final class DijkstraTxInfoResult(
    plutusV1: => PlutusTxInfoResult[Language.PlutusV1, Dijkstra],
    plutusV2: => PlutusTxInfoResult[Language.PlutusV2, Dijkstra],
    plutusV3: => PlutusTxInfoResult[Language.PlutusV3, Dijkstra],
    plutusV4: => PlutusTxInfoResult[Language.PlutusV4, Dijkstra]
):
  lazy val tirPlutusV1: PlutusTxInfoResult[Language.PlutusV1, Dijkstra] = plutusV1
  lazy val tirPlutusV2: PlutusTxInfoResult[Language.PlutusV2, Dijkstra] = plutusV2
  lazy val tirPlutusV3: PlutusTxInfoResult[Language.PlutusV3, Dijkstra] = plutusV3
  lazy val tirPlutusV4: PlutusTxInfoResult[Language.PlutusV4, Dijkstra] = plutusV4
