// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoScriptsNeeded,
  CollectError,
  PlutusWithContext,
  ScriptsProvided,
  SupportedPlutusRunnable,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

/** Whether a top-level Dijkstra transaction uses a Plutus language up to V3, whose scripts run in
  * the legacy mode (`dsattPlutusLegacyMode`, a `Bool` in the reference, `Tx.hs:540`, cardano-ledger
  * 9df9f98c): a mode, so an enum here (TYP-4).
  */
enum PlutusLegacyMode:
  case Legacy, NotLegacy

object PlutusLegacyMode:
  given CanEqual[PlutusLegacyMode, PlutusLegacyMode] = CanEqual.derived

/** A Dijkstra transaction of level `L` annotated with what the ledger state gives it (`data
  * DijkstraStAnnTx l era`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:535-557`,
  * cardano-ledger 9df9f98c): the top level's ([[DijkstraStAnnTx.TopTx]]) with its sub-transactions
  * annotated, a sub-transaction's ([[DijkstraStAnnTx.SubTx]]) with the hashes of the scripts it
  * needs and its memoised transaction information. Its maker, `mkDijkstraStAnnTopTx`
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:108-219`), needs Dijkstra's script contexts
  * and comes with them (#85,
  * https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6981185123).
  */
sealed abstract class DijkstraStAnnTx[L <: TxLevel]

object DijkstraStAnnTx:
  /** `DijkstraStAnnTopTx` (`Tx.hs:536-546`, cardano-ledger 9df9f98c). */
  final case class TopTx(
      dsattTx: DijkstraTx[TxLevel.Top],
      dsattScriptsNeeded: AlonzoScriptsNeeded[Dijkstra],
      dsattScriptsProvided: ScriptsProvided[Dijkstra],
      dsattPlutusLegacyMode: PlutusLegacyMode,
      dsattPlutusLanguagesUsed: Set[Lang],
      dsattPlutusRunnableCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
      dsattPlutusScriptsWithContext: Either[
        NonEmptyList[CollectError[Dijkstra]],
        List[PlutusWithContext]
      ],
      dsattSubTransactions: List[DijkstraStAnnTx[TxLevel.Sub]]
  ) extends DijkstraStAnnTx[TxLevel.Top]

  /** `DijkstraStAnnSubTx` (`Tx.hs:547-557`, cardano-ledger 9df9f98c). */
  final case class SubTx(
      dsastTx: DijkstraTx[TxLevel.Sub],
      dsastScriptsNeeded: AlonzoScriptsNeeded[Dijkstra],
      dsastScriptsHashesNeeded: Set[Bytes],
      dsastScriptsProvided: ScriptsProvided[Dijkstra],
      dsastTxInfoResult: DijkstraTxInfoResult,
      dsastPlutusLanguagesUsed: Set[Lang],
      dsastPlutusRunnableCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
      dsastPlutusScriptsWithContext: Either[
        NonEmptyList[CollectError[Dijkstra]],
        List[PlutusWithContext]
      ]
  ) extends DijkstraStAnnTx[TxLevel.Sub]
