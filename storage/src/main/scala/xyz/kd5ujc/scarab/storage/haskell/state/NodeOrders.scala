// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import cats.Order
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Credential, DRep, GovActionId, RewardAccount}

/** The orders of the ledger types that the mirror reuses from the node's own ledger. They are
  * plain values of the node (`Credential.Key` before `Credential.Script`, maps and sets without an
  * order), so the order a map or a set is written in has to be given here, as Haskell's `Ord`.
  * `LedgerEncoders` (the node-to-client answers) has the same orders as `Ordering`s; the overlap
  * suite of the node checks the two agree.
  */
object NodeOrders:
  /** `Hash.Hash`: unsigned, lexicographic. */
  given Order[Bytes] = Order.from(_ compareTo _)

  /** `Credential` (`Credential.hs:98-101`): `ScriptHashObj` before `KeyHashObj`, then by hash. */
  given Order[Credential] = Order.from { (a, b) =>
    (a, b) match
      case (Credential.Script(x), Credential.Script(y)) => x compareTo y
      case (Credential.Key(x), Credential.Key(y))       => x compareTo y
      case (Credential.Script(_), Credential.Key(_))    => -1
      case (Credential.Key(_), Credential.Script(_))    => 1
  }

  /** `DRep` (`DRep.hs:67-72`): key hash, script hash, always-abstain, always-no-confidence. */
  given Order[DRep] = Order.from { (a, b) =>
    def rank(d: DRep): Int = d match
      case DRep.Registered(Credential.Key(_))    => 0
      case DRep.Registered(Credential.Script(_)) => 1
      case DRep.AlwaysAbstain                    => 2
      case DRep.AlwaysNoConfidence               => 3
    (a, b) match
      case (DRep.Registered(x), DRep.Registered(y)) if rank(a) == rank(b) =>
        x.hash compareTo y.hash
      case _ => Integer.compare(rank(a), rank(b))
  }

  /** `GovActionId` (`Procedures.hs:170`, derived `Ord`): the transaction id, then the index. */
  given Order[GovActionId] = Order.from { (a, b) =>
    val byTx = a.txId compareTo b.txId
    if byTx != 0 then byTx else a.index compare b.index
  }

  /** `AccountAddress` (derived `Ord`, `Address.hs:182-187`): the network, then the credential. */
  given Order[RewardAccount] = Order.from { (a, b) =>
    val byNetwork = Integer.compare(a.network, b.network)
    if byNetwork != 0 then byNetwork else summon[Order[Credential]].compare(a.credential, b.credential)
  }
