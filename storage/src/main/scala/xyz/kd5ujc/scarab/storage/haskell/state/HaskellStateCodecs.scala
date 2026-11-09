// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.protocol.StateLayout

/** Every codec of the Haskell ledger state in the 11.1 layout, as givens:
  * `import HaskellStateCodecs.given`.
  */
object HaskellStateCodecs extends LedgerFileCodecs:
  def stateLayout: StateLayout = StateLayout.Node11_1

/** Every codec of the Haskell ledger state in the layout of the Leios build
  * ([[StateLayout.Leios]]), as givens: `import LeiosStateCodecs.given`.
  */
object LeiosStateCodecs extends LedgerFileCodecs:
  def stateLayout: StateLayout = StateLayout.Leios
