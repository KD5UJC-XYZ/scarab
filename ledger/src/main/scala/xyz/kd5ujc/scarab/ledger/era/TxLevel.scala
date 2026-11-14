// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

/** The level of a transaction (cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxLevel.hs:34`, `TopTx | SubTx`): a top-level
  * transaction, or a sub-transaction of one (Dijkstra).
  */
sealed trait TxLevel

object TxLevel:
  sealed abstract class Top extends TxLevel
  sealed abstract class Sub extends TxLevel

/** The levels of an era that has top-level transactions only (`STxTopLevel`, whose one constructor
  * is `STopTxOnly`, `Core/TxLevel.hs:36-37`, cardano-ledger f649f975): every era up to Conway.
  * Matching on it shows the level is `Top`.
  */
enum TopOnly[L <: TxLevel]:
  case Top extends TopOnly[TxLevel.Top]

/** The levels of an era with sub-transactions (`STxBothLevels`, `Core/TxLevel.hs:47-49`,
  * cardano-ledger f649f975): Dijkstra.
  */
enum BothLevels[L <: TxLevel]:
  case Top extends BothLevels[TxLevel.Top]
  case Sub extends BothLevels[TxLevel.Sub]

/** `B` at the top level, and no type at another level: the bodies of an era with top-level
  * transactions only.
  */
type AtTop[L <: TxLevel, B] = L match
  case TxLevel.Top => B
