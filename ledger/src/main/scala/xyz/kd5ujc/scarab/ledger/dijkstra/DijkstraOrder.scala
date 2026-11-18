// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import scala.math.Ordering.Implicits.seqOrdering
import xyz.kd5ujc.scarab.ledger.IdentityOrder.{map, rational, set}

/** Orders of the Dijkstra values whose repeats a set, a map or an ordered set of the era refuses.
  * The reference finds them through `Data.Set`, `Data.Map` and `OSet` with the derived `Ord` of
  * each type (`decodeListLikeEnforceNoDuplicates`,
  * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:1033-1053`,
  * cardano-ledger 29a7d656), so [[V12.unique]] sorts with these. Each compares the fields its
  * type's equality compares, so two values compare equal exactly when they are equal; which of two
  * unequal values comes first has no meaning.
  */
private[ledger] object DijkstraOrder:
  private def by[A](a: A, b: A)(using o: Ordering[A]): Int = o.compare(a, b)

  given txIn: Ordering[DijkstraTxIn] = Ordering.by(i => (i.id, i.index))
  given actionId: Ordering[DijkstraGovActionId] = Ordering.by(i => (i.txId, i.index))
  given anchor: Ordering[DijkstraAnchor] = Ordering.by(a => (a.url, a.hash))
  given blsKey: Ordering[DijkstraBlsKey] = Ordering.by(k => (k.key, k.proofOfPossession))
  given poolMetadata: Ordering[DijkstraPoolMetadata] = Ordering.by(m => (m.url, m.hash))

  given voter: Ordering[DijkstraVoter] = (a, b) =>
    (a, b) match
      case (DijkstraVoter.Committee(x), DijkstraVoter.Committee(y)) => by(x, y)
      case (DijkstraVoter.DRep(x), DijkstraVoter.DRep(y))           => by(x, y)
      case (DijkstraVoter.StakePool(x), DijkstraVoter.StakePool(y)) => by(x, y)
      case _ => Integer.compare(a.ordinal, b.ordinal)

  given relay: Ordering[DijkstraRelay] = (a, b) =>
    import DijkstraRelay.*
    (a, b) match
      case (SingleHostAddr(p, v4, v6), SingleHostAddr(q, w4, w6)) => by((p, v4, v6), (q, w4, w6))
      case (SingleHostName(p, d), SingleHostName(q, e))           => by((p, d), (q, e))
      case (MultiHostName(d), MultiHostName(e))                   => by(d, e)
      case _ => Integer.compare(a.ordinal, b.ordinal)

  given poolParams: Ordering[DijkstraPoolParams] = (a, b) =>
    by(
      (a.operator, a.vrf, a.blsKey, a.pledge, a.cost, a.margin, a.account, a.owners, a.relays),
      (b.operator, b.vrf, b.blsKey, b.pledge, b.cost, b.margin, b.account, b.owners, b.relays)
    ) match
      case 0      => by(a.metadata, b.metadata)
      case result => result

  given delegatee: Ordering[DijkstraDelegatee] = (a, b) =>
    import DijkstraDelegatee.*
    (a, b) match
      case (Stake(p), Stake(q))               => by(p, q)
      case (Vote(d), Vote(e))                 => by(d, e)
      case (StakeVote(p, d), StakeVote(q, e)) => by((p, d), (q, e))
      case _                                  => Integer.compare(a.ordinal, b.ordinal)

  given certificate: Ordering[DijkstraCert] = (a, b) =>
    import DijkstraCert.*
    (a, b) match
      case (Register(c, d), Register(e, f))                       => by((c, d), (e, f))
      case (Unregister(c, d), Unregister(e, f))                   => by((c, d), (e, f))
      case (Delegate(c, d), Delegate(e, f))                       => by((c, d), (e, f))
      case (RegisterDelegate(c, d, x), RegisterDelegate(e, f, y)) => by((c, d, x), (e, f, y))
      case (RegisterPool(p), RegisterPool(q))                     => by(p, q)
      case (RetirePool(p, e), RetirePool(q, f))                   => by((p, e), (q, f))
      case (AuthorizeCommitteeHotKey(c, h), AuthorizeCommitteeHotKey(e, k)) => by((c, h), (e, k))
      case (ResignCommitteeColdKey(c, x), ResignCommitteeColdKey(e, y))     => by((c, x), (e, y))
      case (RegisterDRep(c, d, x), RegisterDRep(e, f, y)) => by((c, d, x), (e, f, y))
      case (UnregisterDRep(c, d), UnregisterDRep(e, f))   => by((c, d), (e, f))
      case (UpdateDRep(c, x), UpdateDRep(e, y))           => by((c, x), (e, y))
      case _                                              => Integer.compare(a.ordinal, b.ordinal)

  given paramValue: Ordering[DijkstraParamValue] = (a, b) =>
    import DijkstraParamValue.*
    (a, b) match
      case (Number(x), Number(y))                 => by(x, y)
      case (Ratio(x), Ratio(y))                   => by(x, y)
      case (OptionalRatio(x), OptionalRatio(y))   => by(x, y)
      case (OptionalNumber(x), OptionalNumber(y)) => by(x, y)
      case (CostModels(x), CostModels(y))         => by(x, y)
      case (Pair(p, q), Pair(r, s))               => by((p, q), (r, s))
      case (ExUnits(m, s), ExUnits(n, t))         => by((m, s), (n, t))
      case (Thresholds(x), Thresholds(y))         => by(x, y)
      case _                                      => Integer.compare(a.ordinal, b.ordinal)

  given paramUpdate: Ordering[DijkstraParamUpdate] = Ordering.by(_.fields)

  given action: Ordering[DijkstraGovAction] = (a, b) =>
    import DijkstraGovAction.*
    (a, b) match
      case (ParameterChange(p, u, s), ParameterChange(q, v, t))       => by((p, u, s), (q, v, t))
      case (HardForkInitiation(p, m, n), HardForkInitiation(q, o, r)) => by((p, m, n), (q, o, r))
      case (TreasuryWithdrawals(w, s), TreasuryWithdrawals(x, t))     => by((w, s), (x, t))
      case (NoConfidence(p), NoConfidence(q))                         => by(p, q)
      case (UpdateCommittee(p, r, d, u), UpdateCommittee(q, s, e, v)) =>
        by((p, r, d, u), (q, s, e, v))
      case (NewConstitution(p, x, s), NewConstitution(q, y, t)) => by((p, x, s), (q, y, t))
      case _ => Integer.compare(a.ordinal, b.ordinal)

  given proposal: Ordering[DijkstraProposal] =
    Ordering.by(p => (p.deposit, p.returnAccount, p.action, p.anchor))
