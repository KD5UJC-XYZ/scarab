// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.{Eq, Show}

/** The layout of the reference's `state` file that a reference build writes (#826). Several builds
  * can share one ([[StateLayout.of]]): a build names what the operator runs, a layout what the
  * bytes are.
  */
enum StateLayout derives CanEqual:
  /** The cardano-node 11.1 line (cardano-ledger f649f975, ouroboros-consensus 82ecba32): an
    * `ExtLedgerState` of two parts (`Ledger/Extended.hs:233-248`).
    */
  case Node11_1

  /** The Leios build (cardano-ledger 9df9f98c, ouroboros-consensus 2416493b): an `ExtLedgerState`
    * of three parts, the third the Peras state (`Ledger/Extended.hs:420-442`), and era states that
    * differ from 11.1's in every Shelley-based era.
    */
  case Leios

object StateLayout:
  /** The layout `build` writes. */
  def of(build: ReferenceBuild): StateLayout = build match
    case ReferenceBuild.Release11_1   => Node11_1
    case ReferenceBuild.LeiosB004fe0c => Leios

  given Eq[StateLayout] = Eq.fromUniversalEquals
  given Show[StateLayout] = Show.fromToString

/** A part of a record of the ledger or consensus state that the layout decides: a closed enum with
  * a case per layout that holds that layout's fields. A value says which layout it is in, and a
  * codec writes only parts of its own layout: there is no conversion between layouts.
  */
trait LayoutPart:
  def stateLayout: StateLayout
