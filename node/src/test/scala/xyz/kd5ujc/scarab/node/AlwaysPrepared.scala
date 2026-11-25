// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.Phase2
import xyz.kd5ujc.scarab.plutus.ExUnits

/** A body program the node's workers ([[ParallelPlutus]]) prepare wherever it may be prepared
  * (`preparable`: more than one transaction with redeemers, within the block's units), below the
  * threshold of declared work the node prepares above (`Phase2.pays`, #85, ADR 0018) too: for
  * checking the prepared path on blocks of cheap scripts, which the node itself runs serially, and
  * for timing it there. Its serial run and its preparation are the program's own.
  */
object AlwaysPrepared:
  def apply[A](p: Phase2.Program[A]): Phase2.Program[A] = new Phase2.Program[A]:
    def serial: A = p.serial
    def hasScripts: Boolean = p.hasScripts
    def preparable: Boolean = p.preparable
    def declared: ExUnits = p.declared
    override def worthwhile: Boolean = p.preparable
    def prepare: Phase2.Plan[A] = p.prepare
