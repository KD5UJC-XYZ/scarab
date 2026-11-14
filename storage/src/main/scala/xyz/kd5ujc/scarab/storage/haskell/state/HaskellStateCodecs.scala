// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.protocol.StateLayout

/** Every codec of the Haskell ledger state in the 11.1 layout, as givens:
  * `import HaskellStateCodecs.given`.
  */
object HaskellStateCodecs extends LedgerFileCodecs:
  def stateLayout: StateLayout = StateLayout.Node11_1
  def proposalsReading: ProposalsReading = ProposalsReading.Reference

/** Every codec of the Haskell ledger state in the layout of the Leios build
  * ([[StateLayout.Leios]]), as givens: `import LeiosStateCodecs.given`.
  */
object LeiosStateCodecs extends LedgerFileCodecs:
  def stateLayout: StateLayout = StateLayout.Leios
  def proposalsReading: ProposalsReading = ProposalsReading.Reference

/** The codecs of the state section of a native snapshot in the 11.1 layout: those of
  * [[HaskellStateCodecs]], but for the governance proposals, read as the snapshot wrote them
  * ([[ProposalsReading.AsWritten]]).
  */
object NativeStateCodecs extends LedgerFileCodecs:
  def stateLayout: StateLayout = StateLayout.Node11_1
  def proposalsReading: ProposalsReading = ProposalsReading.AsWritten

/** The codecs of the state section of a native snapshot in the layout of the Leios build: those of
  * [[LeiosStateCodecs]], but for the governance proposals, read as the snapshot wrote them.
  */
object NativeLeiosStateCodecs extends LedgerFileCodecs:
  def stateLayout: StateLayout = StateLayout.Leios
  def proposalsReading: ProposalsReading = ProposalsReading.AsWritten
