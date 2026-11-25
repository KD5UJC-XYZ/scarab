// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.era.{
  GovAction,
  Mismatch,
  PParamsUpdate,
  ProposalProcedure,
  Relation,
  StoredTxOut
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.AccountBalanceInterval
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Coin,
  Conway,
  Credential,
  Dijkstra,
  EpochNo,
  KeyRole,
  ProtVer,
  ShelleyBasedEra
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, NodeOrders, Versions}

/** Dijkstra's era failures as the reference writes them (the `EncCBOR` instances of the Dijkstra
  * failure types, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/`, cardano-ledger
  * 9df9f98c), at decoder version 12, for the suites that compare the era rules with the Leios
  * build's recorded refusals. Test code: the node answers a Dijkstra refusal with no encoding
  * (`DijkstraNoEncoding`), and this does not change that.
  *
  * Where a Dijkstra failure is Conway's constructor with the same encoding, and its fields do not
  * depend on the era, it is written by [[ConwayFailureCbor]] (the failure rebuilt at Conway and the
  * constructors around it taken off, or its tag replaced where Dijkstra numbers it anew). Conway's
  * encoders of DELEG, UTXOS and GOV are unchanged from f649f975, the pin [[ConwayFailureCbor]]
  * cites, to 9df9f98c (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/` `Deleg.hs:128-146`,
  * `Utxos.hs:110-120`, `Gov.hs:258-298`, cardano-ledger 9df9f98c). The rest is written here from
  * the leaves [[FailureCbor]] shares. Dijkstra writes every `Mismatch` as its record (`To mm`,
  * `[supplied, expected]`, `libs/cardano-ledger-core/src/Cardano/Ledger/` `BaseTypes.hs:861-866`,
  * cardano-ledger 9df9f98c), where Conway writes several in line.
  */
object DijkstraEraFailureCbor:
  import FailureCbor.{govActionId, list, map, maybe, rewardAccount, set, txIn, txOut}
  import NodeOrders.given

  private type W = CborOut

  /** Sets and maps are written at Dijkstra's version. */
  private given ProtVer = Versions.eraLow(ShelleyBasedEra.SDijkstra)

  /** What this encoder does not write: values whose writer the node has only at Conway, or not at
    * all. Each is a failure no recorded case reaches.
    */
  enum NotEncoded:
    /** `MissingRedeemers` ' purposes as items, whose certificates and proposals are Dijkstra's. */
    case Purposes(failure: DijkstraUtxowPredFailure[Dijkstra])

    /** UTXOS's `CollectErrors`, whose contexts are Dijkstra's. */
    case Collection(failure: ConwayUtxosPredFailure[Dijkstra])

    /** BBODY's `PerasCertValidationFailed`. */
    case PerasCertificate(failure: DijkstraBbodyPredFailure[Dijkstra])

    /** LEDGER's `DijkstraSubLedgersFailure`: the SUB rules' failures. */
    case SubLedgers(failure: DijkstraLedgerPredFailure[Dijkstra])

  type Unencodable = NotEncoded | ConwayFailureCbor.Unencodable

  type Result[A] = Either[Unencodable, A]

  // ---- helpers

  private def build(body: W => Unit): Bytes = CborOut.build(body)

  private def raw(w: W, b: Bytes): Unit = { w.raw(b); () }

  /** `Sum c tag !> To x`: `[tag, x]`, `x` already written. */
  private def sum(tag: Int)(payload: Bytes): Bytes = build(w => raw(w.array(2).uint(tag), payload))

  /** The items of a constructor `[tag, ...]` the reference writes with a list. */
  private def items(bytes: Bytes): Vector[Bytes] = Cbor.decode(bytes) match
    case Right(n) =>
      n.value match
        case CborValue.Arr(xs) => xs.map(_.original)
        case _                 => Vector(n.original)
    case Left(_) => Vector(bytes)

  /** The one field of `[tag, field]`. */
  private def field(bytes: Bytes): Bytes = items(bytes).drop(1).headOption.getOrElse(bytes)

  /** Conway's constructor `[tag, fields...]` under Dijkstra's tag. */
  private def retag(tag: Int)(bytes: Bytes): Bytes =
    val fields = items(bytes).drop(1)
    build { w =>
      w.array(fields.size + 1).uint(tag); fields.foreach(raw(w, _))
    }

  /** `To mm`: `[supplied, expected]`. */
  private def mismatch[R <: Relation, A](w: W, m: Mismatch[R, A])(each: (W, A) => Unit): Unit =
    w.array(2); each(w, m.supplied); each(w, m.expected)

  private def tagged(tag: Int)(body: W => Unit): Bytes = build { w =>
    w.array(2).uint(tag); body(w)
  }

  private def coin(w: W, c: Coin): Unit = { w.integer(c.value); () }

  private def credential[R <: KeyRole](w: W, c: Credential[R]): Unit =
    FailureCbor.put(w, c)(using HaskellStateCodecs.credential[R]); ()

  private def accounts(w: W, as: Iterable[AccountAddress]): Unit =
    set(w, as.map(Convert.rewardAccountBack).toSet)(rewardAccount); ()

  private def accountMap[V](w: W, m: Map[AccountAddress, V])(each: (W, V) => Unit): Unit =
    map(w, m.map((a, v) => Convert.rewardAccountBack(a) -> v))(rewardAccount, each); ()

  private def epoch(w: W, e: EpochNo): Unit =
    FailureCbor.put(w, e)(using HaskellStateCodecs.epochNo); ()

  private val stored = StoredTxOut.dijkstra

  private def output(w: W, o: xyz.kd5ujc.scarab.ledger.era.TxOut[Dijkstra]): Unit =
    txOut(w, stored.toStored(o)); ()

  private def value(w: W, v: xyz.kd5ujc.scarab.ledger.era.Value[Dijkstra]): Unit =
    FailureCbor.value(w, stored.storedValue(v))

  // ---- BBODY and LEDGERS

  /** `EncCBOR (DijkstraBbodyPredFailure era)` (`Dijkstra/Rules/Bbody.hs:113-128`, cardano-ledger
    * 9df9f98c): tags 0 to 6, each mismatch `To mm`.
    */
  def bbody(f: DijkstraBbodyPredFailure[Dijkstra]): Result[Bytes] =
    import DijkstraBbodyPredFailure.*
    f match
      case WrongBlockBodySizeBBODY(m) =>
        Right(tagged(0)(mismatch(_, m)((k, n) => { k.int(n); () })))
      case InvalidBodyHashBBODY(m) =>
        Right(tagged(1)(mismatch(_, m)((k, h) => { k.bytes(h.bytes); () })))
      case LedgersFailure(l) => ledgers(l).map(sum(2))
      case TooManyExUnits(m) =>
        Right(tagged(3)(mismatch(_, m)((k, u) => {
          k.array(2).integer(u.mem).integer(u.steps); ()
        })))
      case BodyRefScriptsSizeTooBig(m) =>
        Right(tagged(4)(mismatch(_, m)((k, n) => { k.int(n); () })))
      case PerasCertValidationFailed(_, _) => Left(NotEncoded.PerasCertificate(f))
      case HeaderProtVerTooLow(m) =>
        Right(tagged(6)(mismatch(_, m)((k, v) => { k.uint(v.value); () })))

  /** `EncCBOR (ShelleyLedgersPredFailure era)` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/`
    * `Rules/Ledgers.hs:158-164`, cardano-ledger 9df9f98c): LEDGER's failure itself.
    */
  def ledgers(f: ShelleyLedgersPredFailure[Dijkstra]): Result[Bytes] = f match
    case ShelleyLedgersPredFailure.LedgerFailure(l) => ledger(l)

  // ---- LEDGER

  /** `EncCBOR (DijkstraLedgerPredFailure era)` (`Dijkstra/Rules/Ledger.hs:243-259`, cardano-ledger
    * 9df9f98c): tags 1 to 6, the treasury's and the reference scripts' mismatches `To mm`.
    */
  def ledger(f: DijkstraLedgerPredFailure[Dijkstra]): Result[Bytes] =
    import DijkstraLedgerPredFailure.*
    f match
      case DijkstraUtxowFailure(u)          => utxow(u).map(sum(1))
      case DijkstraEntitiesFailure(e)       => entities(e).map(sum(2))
      case DijkstraGovFailure(g)            => gov(g).map(sum(3))
      case DijkstraTreasuryValueMismatch(m) => Right(tagged(4)(mismatch(_, m)(coin)))
      case DijkstraTxRefScriptsSizeTooBig(m) =>
        Right(tagged(5)(mismatch(_, m)((k, n) => { k.int(n); () })))
      case DijkstraSubLedgersFailure(_) => Left(NotEncoded.SubLedgers(f))

  // ---- UTXOW

  /** `EncCBOR (DijkstraUtxowPredFailure era)` (`Dijkstra/Rules/Utxow.hs:373-401`, cardano-ledger
    * 9df9f98c): Conway's tags 0 to 18 and payloads (`Conway/Rules/Utxow.hs:230-256`), but for the
    * metadata hashes' and the view hashes' mismatches, `To mm`, which Conway writes in line
    * (`ToGroup mm`, `:245, 251`, cardano-ledger 9df9f98c); the guards' credentials sets, 19 and 20.
    */
  def utxow(f: DijkstraUtxowPredFailure[Dijkstra]): Result[Bytes] =
    import DijkstraUtxowPredFailure.*
    def conway(c: ConwayUtxowPredFailure[Conway]): Result[Bytes] = ConwayFailureCbor.utxow(c)
    def guards(
        tag: Int,
        cs: cats.data.NonEmptySet[Credential[xyz.kd5ujc.scarab.ledger.state.Guard]]
    ) =
      Right(tagged(tag)(set(_, cs.toSortedSet)(credential)))
    f match
      case UtxoFailure(u)            => utxo(u).map(sum(0))
      case InvalidWitnessesUTXOW(ks) => conway(ConwayUtxowPredFailure.InvalidWitnessesUTXOW(ks))
      case MissingVKeyWitnessesUTXOW(hs) =>
        conway(ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(hs))
      case MissingScriptWitnessesUTXOW(hs) =>
        conway(ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(hs))
      case ScriptWitnessNotValidatingUTXOW(hs) =>
        conway(ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(hs))
      case MissingTxBodyMetadataHash(h) =>
        conway(ConwayUtxowPredFailure.MissingTxBodyMetadataHash(h))
      case MissingTxMetadata(h) => conway(ConwayUtxowPredFailure.MissingTxMetadata(h))
      case ConflictingMetadataHash(m) =>
        Right(tagged(7)(mismatch(_, m)((k, h) => { k.bytes(h); () })))
      case InvalidMetadata() => conway(ConwayUtxowPredFailure.InvalidMetadata())
      case ExtraneousScriptWitnessesUTXOW(hs) =>
        conway(ConwayUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(hs))
      case MissingRedeemers(_) => Left(NotEncoded.Purposes(f))
      case MissingRequiredDatums(missing, received) =>
        conway(ConwayUtxowPredFailure.MissingRequiredDatums(missing, received))
      case NotAllowedSupplementalDatums(unallowed, acceptable) =>
        conway(ConwayUtxowPredFailure.NotAllowedSupplementalDatums(unallowed, acceptable))
      case PPViewHashesDontMatch(m) =>
        Right(tagged(13)(mismatch(_, m)((k, h) => { FailureCbor.maybeHash(k, h); () })))
      case UnspendableUTxONoDatumHash(ins) =>
        conway(ConwayUtxowPredFailure.UnspendableUTxONoDatumHash(ins))
      case ExtraRedeemers(ps) =>
        Right(tagged(15)(list(_, ps.toList)((k, p) => {
          k.array(2).uint(p.tag).uint(p.ix.value); ()
        })))
      case MalformedScriptWitnesses(hs) =>
        conway(ConwayUtxowPredFailure.MalformedScriptWitnesses(hs))
      case MalformedReferenceScripts(hs) =>
        conway(ConwayUtxowPredFailure.MalformedReferenceScripts(hs))
      case ScriptIntegrityHashMismatch(m, expected) =>
        conway(ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(m, expected))
      case MissingRequiredGuards(cs) => guards(19, cs)
      case MalformedGuardDatums(cs)  => guards(20, cs)

  // ---- UTXO and UTXOS

  /** `EncCBOR (DijkstraUtxoPredFailure era)` (`Dijkstra/Rules/Utxo.hs:488-520`, cardano-ledger
    * 9df9f98c): tags 0 to 23, 8 unused, each mismatch `To mm`. A constructor that is Conway's with
    * the same payload is Conway's encoding under Dijkstra's tag.
    */
  def utxo(f: DijkstraUtxoPredFailure[Dijkstra]): Result[Bytes] =
    import DijkstraUtxoPredFailure.*
    def conway(tag: Int, c: ConwayUtxoPredFailure[Conway]): Result[Bytes] =
      ConwayFailureCbor.utxo(c).map(retag(tag))
    def outs(tag: Int, os: NonEmptyList[xyz.kd5ujc.scarab.ledger.era.TxOut[Dijkstra]]) =
      Right(tagged(tag)(list(_, os.toList)(output)))
    f match
      case UtxosFailure(u)    => utxos(u).map(sum(0))
      case BadInputsUTxO(ins) => conway(1, ConwayUtxoPredFailure.BadInputsUTxO(ins))
      case OutsideValidityIntervalUTxO(vi, slot) =>
        conway(2, ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slot))
      case MaxTxSizeUTxO(m)         => Right(tagged(3)(mismatch(_, m)((k, n) => { k.uint(n); () })))
      case InputSetEmptyUTxO()      => conway(4, ConwayUtxoPredFailure.InputSetEmptyUTxO())
      case FeeTooSmallUTxO(m)       => Right(tagged(5)(mismatch(_, m)(coin)))
      case ValueNotConservedUTxO(m) => Right(tagged(6)(mismatch(_, m)(value)))
      case WrongNetwork(n, addrs)   => conway(7, ConwayUtxoPredFailure.WrongNetwork(n, addrs))
      case OutputBootAddrAttrsTooBig(os) => outs(9, os)
      case OutputTooBigUTxO(os) =>
        Right(tagged(10)(list(_, os.toList) { case (k, (size, max, o)) =>
          k.array(3).int(size).int(max); output(k, o)
        }))
      case InsufficientCollateral(balance, required) =>
        conway(11, ConwayUtxoPredFailure.InsufficientCollateral(balance, required))
      case ScriptsNotPaidUTxO(os) =>
        Right(tagged(12)(map(_, os.toSortedMap.toMap)(txIn(_, _), output)))
      case ExUnitsTooBigUTxO(m) =>
        Right(tagged(13)(mismatch(_, m)((k, u) => {
          k.array(2).integer(u.mem).integer(u.steps); ()
        })))
      case CollateralContainsNonADA(v) => Right(tagged(14)(value(_, v)))
      case WrongNetworkInTxBody(m) =>
        Right(tagged(15)(mismatch(_, m)((k, n) => { k.uint(n.wire); () })))
      case OutsideForecast(slot) => conway(16, ConwayUtxoPredFailure.OutsideForecast(slot))
      case TooManyCollateralInputs(m) =>
        Right(tagged(17)(mismatch(_, m)((k, n) => { k.uint(n.value); () })))
      case NoCollateralInputs() => conway(18, ConwayUtxoPredFailure.NoCollateralInputs())
      case IncorrectTotalCollateralField(provided, declared) =>
        conway(19, ConwayUtxoPredFailure.IncorrectTotalCollateralField(provided, declared))
      case BabbageOutputTooSmallUTxO(os) =>
        Right(tagged(20)(list(_, os.toList) { case (k, (o, least)) =>
          k.array(2); output(k, o); coin(k, least)
        }))
      case BabbageNonDisjointRefInputs(ins) =>
        conway(21, ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(ins))
      case PtrPresentInCollateralReturn(o)  => Right(tagged(22)(output(_, o)))
      case ValueNotConservedInLegacyMode(m) => Right(tagged(23)(mismatch(_, m)(value)))

  /** Dijkstra's UTXOS failure is Conway's (`Dijkstra/Rules/Utxos.hs:18`, cardano-ledger 9df9f98c),
    * written by Conway's encoder; its collection errors are not encoded.
    */
  def utxos(f: ConwayUtxosPredFailure[Dijkstra]): Result[Bytes] = f match
    case ConwayUtxosPredFailure.ValidationTagMismatch(valid, description) =>
      ConwayFailureCbor.utxos(ConwayUtxosPredFailure.ValidationTagMismatch(valid, description))
    case ConwayUtxosPredFailure.CollectErrors(_) => Left(NotEncoded.Collection(f))

  // ---- ENTITIES

  /** `EncCBOR AccountBalanceInterval` (`Dijkstra/Scripts.hs:677-682`, cardano-ledger 9df9f98c): a
    * bound's pair with a null for the missing bound, or the exact balance alone.
    */
  private def interval(w: W, i: AccountBalanceInterval): Unit = i match
    case AccountBalanceInterval.AccountBalanceLowerBound(l) =>
      w.array(2); coin(w, l.unInclusive); w.nul; ()
    case AccountBalanceInterval.AccountBalanceUpperBound(u) =>
      w.array(2).nul; coin(w, u.unExclusive)
    case AccountBalanceInterval.AccountBalanceBothBounds(l, u) =>
      w.array(2); coin(w, l.unInclusive); coin(w, u.unExclusive)
    case AccountBalanceInterval.AccountBalanceExact(c) => coin(w, c)

  /** `EncCBOR (EntitiesPredFailure era)` (`Dijkstra/Rules/Entities.hs:148-169`, cardano-ledger
    * 9df9f98c): tags 0 to 13; each network the node's, each account set a set, each map by its
    * account, a mismatch `To mm` and a balance with its interval a pair.
    */
  def entities(f: EntitiesPredFailure[Dijkstra]): Result[Bytes] =
    import EntitiesPredFailure.*
    def wrong(
        tag: Int,
        n: xyz.kd5ujc.scarab.ledger.state.Network,
        as: cats.data.NonEmptySet[AccountAddress]
    ) =
      Right(build { w =>
        w.array(3).uint(tag).uint(n.wire); accounts(w, as.toSortedSet)
      })
    def coins(tag: Int, m: Map[AccountAddress, Coin]) = Right(tagged(tag)(accountMap(_, m)(coin)))
    def outside(
        tag: Int,
        m: cats.data.NonEmptyMap[AccountAddress, (Coin, AccountBalanceInterval)]
    ) =
      Right(tagged(tag)(accountMap(_, m.toSortedMap.toMap) { case (k, (c, i)) =>
        k.array(2); coin(k, c); interval(k, i)
      }))
    f match
      case CertsFailure(c)                            => certs(c).map(sum(0))
      case WithdrawalAddressesWithWrongNetwork(n, as) => wrong(1, n, as)
      case WithdrawalAccountsMissing(ws)              => coins(2, ws)
      case WithdrawalAccountsMissingFromOriginal(ws)  => coins(3, ws)
      case WithdrawalAmountsInexactInLegacyMode(ms) =>
        Right(tagged(4)(accountMap(_, ms.toSortedMap.toMap)((k, m) => mismatch(k, m)(coin))))
      case WithdrawalAmountsExceedingOriginalBalance(ms) =>
        Right(tagged(5)(accountMap(_, ms.toSortedMap.toMap)((k, m) => mismatch(k, m)(coin))))
      case DirectDepositAddressesWithWrongNetwork(n, as) => wrong(6, n, as)
      case DirectDepositAccountsMissing(ds)              => coins(7, ds)
      case WrongNetworkInAccountBalanceIntervals(n, as)  => wrong(8, n, as)
      case MissingAccountsInAccountBalanceIntervals(is) =>
        Right(tagged(9)(accountMap(_, is.toSortedMap.toMap)(interval)))
      case BalancesOutsideAccountBalanceIntervals(os)           => outside(10, os)
      case WrongNetworkInStartingAccountBalanceIntervals(n, as) => wrong(11, n, as)
      case MissingAccountsInStartingAccountBalanceIntervals(is) =>
        Right(tagged(12)(accountMap(_, is.toSortedMap.toMap)(interval)))
      case BalancesOutsideStartingAccountBalanceIntervals(os) => outside(13, os)

  // ---- CERTS, CERT, DELEG, POOL, GOVCERT

  /** Dijkstra's CERTS and CERT failures are Conway's (`EncCBOR (ConwayCertsPredFailure era)`,
    * `Conway/Rules/Certs.hs:157-165`, and `EncCBOR (ConwayCertPredFailure era)`,
    * `Conway/Rules/Cert.hs:270-281`, cardano-ledger 9df9f98c): `[1, cert]`, and DELEG's `[1, _]`,
    * POOL's `[2, _]`, GOVCERT's `[3, _]`. Dijkstra's CERTS has no withdrawals' failure.
    */
  def certs(f: ConwayCertsPredFailure[Dijkstra]): Result[Bytes] = f match
    case ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(ws) => ws
    case ConwayCertsPredFailure.CertFailure(c) =>
      (c match
        case ConwayCertPredFailure.DelegFailure(d)   => deleg(d).map(sum(1))
        case ConwayCertPredFailure.PoolFailure(p)    => Right(sum(2)(pool(p)))
        case ConwayCertPredFailure.GovCertFailure(g) => Right(sum(3)(govCert(g)))
      ).map(sum(1))

  /** Dijkstra's DELEG failure is Conway's (`Conway/Rules/Deleg.hs:128-146`, cardano-ledger
    * 9df9f98c), written by Conway's encoder.
    */
  private def deleg(f: ConwayDelegPredFailure[Dijkstra]): Result[Bytes] =
    import ConwayDelegPredFailure as D
    val conway: ConwayDelegPredFailure[Conway] = f match
      case D.IncorrectDepositDELEG(c)                 => D.IncorrectDepositDELEG(c)
      case D.StakeKeyRegisteredDELEG(c)               => D.StakeKeyRegisteredDELEG(c)
      case D.StakeKeyNotRegisteredDELEG(c)            => D.StakeKeyNotRegisteredDELEG(c)
      case D.StakeKeyHasNonZeroAccountBalanceDELEG(c) => D.StakeKeyHasNonZeroAccountBalanceDELEG(c)
      case D.DelegateeDRepNotRegisteredDELEG(c)       => D.DelegateeDRepNotRegisteredDELEG(c)
      case D.DelegateeStakePoolNotRegisteredDELEG(p)  => D.DelegateeStakePoolNotRegisteredDELEG(p)
      case D.DepositIncorrectDELEG(m)                 => D.DepositIncorrectDELEG(m)
      case D.RefundIncorrectDELEG(m)                  => D.RefundIncorrectDELEG(m)
    // Conway's CERTS and CERT around DELEG's failure, `[1, [1, failure]]`, taken off
    ConwayFailureCbor
      .certs(ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.DelegFailure(conway)))
      .map(b => field(field(b)))

  /** `EncCBOR (DijkstraPoolPredFailure era)` (`Dijkstra/Rules/Pool.hs:87-105`, cardano-ledger
    * 9df9f98c): tags 0 to 7, 2 unused; each mismatch `To mm` (`encCBOR m`), the BLS key `[key,
    * proof]` (`EncCBOR BlsKey`, `libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:`
    * `532-536`).
    */
  private def pool(f: DijkstraPoolPredFailure[Dijkstra]): Bytes = build { w =>
    import DijkstraPoolPredFailure.*
    f match
      case StakePoolNotRegisteredOnKeyPOOL(p) => w.array(2).uint(0).bytes(p.bytes)
      case StakePoolRetirementWrongEpochPOOL(current, latest) =>
        w.array(3).uint(1); mismatch(w, current)(epoch); mismatch(w, latest)(epoch)
      case StakePoolCostTooLowPOOL(m) => w.array(2).uint(3); mismatch(w, m)(coin)
      case WrongNetworkPOOL(m, p) =>
        w.array(3).uint(4); mismatch(w, m)((k, n) => { k.uint(n.wire); () }); w.bytes(p.bytes)
      case PoolMedataHashTooBig(p, size)     => w.array(3).uint(5).bytes(p.bytes).int(size)
      case VRFKeyHashAlreadyRegistered(p, v) => w.array(3).uint(6).bytes(p.bytes).bytes(v.bytes)
      case BlsKeyInvalidProofOfPossession(p, key) =>
        w.array(3).uint(7).bytes(p.bytes).array(2).bytes(key.key).bytes(key.proofOfPossession)
    ()
  }

  /** `EncCBOR (DijkstraGovCertPredFailure era)` (`Dijkstra/Rules/GovCert.hs:76-84`, cardano-ledger
    * 9df9f98c): tags 0 to 5, the deposit's and the refund's mismatches `To mm`.
    */
  private def govCert(f: DijkstraGovCertPredFailure[Dijkstra]): Bytes = build { w =>
    import DijkstraGovCertPredFailure.*
    f match
      case DijkstraDRepAlreadyRegistered(c)          => w.array(2).uint(0); credential(w, c)
      case DijkstraDRepNotRegistered(c)              => w.array(2).uint(1); credential(w, c)
      case DijkstraDRepIncorrectDeposit(m)           => w.array(2).uint(2); mismatch(w, m)(coin)
      case DijkstraCommitteeHasPreviouslyResigned(c) => w.array(2).uint(3); credential(w, c)
      case DijkstraDRepIncorrectRefund(m)            => w.array(2).uint(4); mismatch(w, m)(coin)
      case DijkstraCommitteeIsUnknown(c)             => w.array(2).uint(5); credential(w, c)
  }

  // ---- GOV

  /** `EncCBOR (GovAction era)` (`eras/conway/impl/src/Cardano/Ledger/Conway/Governance/`
    * `Procedures.hs:987-1006`, cardano-ledger 9df9f98c), as Conway's encoder writes it, with
    * Dijkstra's parameter update (`EncCBOR (PParamsUpdate DijkstraEra)`).
    */
  private def govAction(w: W, a: GovAction[Dijkstra]): Unit =
    def parent(id: Option[xyz.kd5ujc.scarab.ledger.GovActionId]): Unit =
      id.fold({ w.nul; () })(i => { govActionId(w, i); () })
    a match
      case GovAction.ParameterChange(prev, update, guardrails) =>
        w.array(4).uint(0); parent(prev.map(_.unGovPurposeId))
        raw(w, PParamsUpdate.encCBOR[Dijkstra].encCBOR(update).bytes)
        guardrails.fold({ w.nul; () })(h => { w.bytes(h.bytes); () })
      case GovAction.HardForkInitiation(prev, version) =>
        w.array(3).uint(1); parent(prev.map(_.unGovPurposeId))
        raw(w, xyz.kd5ujc.scarab.ledger.era.EncCBOR.protVer.encCBOR(version).bytes)
      case GovAction.TreasuryWithdrawals(withdrawals, guardrails) =>
        w.array(3).uint(2); map(w, withdrawals)(rewardAccount(_, _), coin)
        guardrails.fold({ w.nul; () })(h => { w.bytes(h.bytes); () })
      case GovAction.NoConfidence(prev) => w.array(2).uint(3); parent(prev.map(_.unGovPurposeId))
      case GovAction.UpdateCommittee(prev, removed, added, threshold) =>
        w.array(5).uint(4); parent(prev.map(_.unGovPurposeId))
        set(w, removed)(credential)
        map(w, added)(credential, epoch)
        raw(w, xyz.kd5ujc.scarab.ledger.era.EncCBOR.unitInterval.encCBOR(threshold).bytes)
      case GovAction.NewConstitution(prev, constitution) =>
        w.array(3).uint(5); parent(prev.map(_.unGovPurposeId))
        FailureCbor.put(w, constitution)(using HaskellStateCodecs.constitution); ()
      case GovAction.InfoAction() => w.array(1).uint(6); ()

  /** `EncCBOR (ProposalProcedure era)` (`Procedures.hs:582-590`, cardano-ledger 9df9f98c):
    * `[deposit, return account, action, anchor]`.
    */
  private def proposal(w: W, p: ProposalProcedure[Dijkstra]): Unit =
    w.array(4); coin(w, p.pProcDeposit); rewardAccount(w, p.pProcReturnAddr)
    govAction(w, p.pProcGovAction)
    FailureCbor.put(w, p.pProcAnchor)(using HaskellStateCodecs.anchor); ()

  /** `EncCBOR (DijkstraGovPredFailure era)` (`Dijkstra/Rules/Gov.hs:157-197`, cardano-ledger
    * 9df9f98c): Conway's tags 0 to 18 and payloads, but for the deposit's and the hard fork's
    * mismatches, `To mm`. A constructor whose fields do not depend on the era is Conway's encoding.
    */
  def gov(f: DijkstraGovPredFailure[Dijkstra]): Result[Bytes] =
    import DijkstraGovPredFailure.*
    def conway(c: ConwayGovPredFailure[Conway]): Result[Bytes] = ConwayFailureCbor.gov(c)
    f match
      case GovActionsDoNotExist(ids) => conway(ConwayGovPredFailure.GovActionsDoNotExist(ids))
      case MalformedProposal(a)      => Right(tagged(1)(govAction(_, a)))
      case ProposalProcedureNetworkIdMismatch(a, n) =>
        conway(ConwayGovPredFailure.ProposalProcedureNetworkIdMismatch(a, n))
      case TreasuryWithdrawalsNetworkIdMismatch(as, n) =>
        conway(ConwayGovPredFailure.TreasuryWithdrawalsNetworkIdMismatch(as, n))
      case ProposalDepositIncorrect(m) => Right(tagged(4)(mismatch(_, m)(coin)))
      case DisallowedVoters(vs)        => conway(ConwayGovPredFailure.DisallowedVoters(vs))
      case ConflictingCommitteeUpdate(ms) =>
        conway(ConwayGovPredFailure.ConflictingCommitteeUpdate(ms))
      case ExpirationEpochTooSmall(ms) => conway(ConwayGovPredFailure.ExpirationEpochTooSmall(ms))
      case InvalidPrevGovActionId(p)   => Right(tagged(8)(proposal(_, p)))
      case VotingOnExpiredGovAction(vs) =>
        conway(ConwayGovPredFailure.VotingOnExpiredGovAction(vs))
      case ProposalCantFollow(parent, m) =>
        Right(build { w =>
          w.array(3).uint(10)
          maybe(w, parent.map(_.unGovPurposeId))(govActionId(_, _))
          mismatch(w, m)((k, v) =>
            raw(k, xyz.kd5ujc.scarab.ledger.era.EncCBOR.protVer.encCBOR(v).bytes)
          )
        })
      case InvalidGuardrailsScriptHash(proposed, constitution) =>
        conway(ConwayGovPredFailure.InvalidGuardrailsScriptHash(proposed, constitution))
      case DisallowedProposalDuringBootstrap(p) => Right(tagged(12)(proposal(_, p)))
      case DisallowedVotesDuringBootstrap(vs) =>
        conway(ConwayGovPredFailure.DisallowedVotesDuringBootstrap(vs))
      case VotersDoNotExist(vs)       => conway(ConwayGovPredFailure.VotersDoNotExist(vs))
      case ZeroTreasuryWithdrawals(a) => Right(tagged(15)(govAction(_, a)))
      case ProposalReturnAccountDoesNotExist(a) =>
        conway(ConwayGovPredFailure.ProposalReturnAccountDoesNotExist(a))
      case TreasuryWithdrawalReturnAccountsDoNotExist(as) =>
        conway(ConwayGovPredFailure.TreasuryWithdrawalReturnAccountsDoNotExist(as))
      case UnelectedCommitteeVoters(cs) =>
        conway(ConwayGovPredFailure.UnelectedCommitteeVoters(cs))
