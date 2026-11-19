// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Block,
  CborRead,
  Credential as FlatCredential,
  DecodeField,
  GovActionId,
  LedgerDecodeError,
  Metadatum,
  PreprodDeepTx,
  RewardAccount,
  TxIn,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraAuxData,
  DijkstraBalanceInterval,
  DijkstraCert,
  DijkstraDatum,
  DijkstraDelegatee,
  DijkstraGovAction,
  DijkstraGovActionId,
  DijkstraLanguage,
  DijkstraProposal,
  DijkstraPurpose,
  DijkstraPurposes,
  DijkstraScript as FlatScript,
  DijkstraTx as FlatTx,
  DijkstraTxBody as FlatBody,
  DijkstraTxIn,
  DijkstraTxOut as FlatTxOut,
  DijkstraVoter,
  DijkstraWitnesses,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{
  AsIxItem,
  BothLevels,
  ConwayGovCert,
  Datum,
  Delegatee,
  EraTxBody,
  EraTxError,
  EraTxWits,
  EraTxAuxData,
  GovAction,
  GovActionPurpose,
  GovPurposeId,
  IsPhase2Valid,
  PParamsUpdate,
  PoolCert,
  ToPlutusData,
  TxLevel,
  ValidityInterval
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Credential, Dijkstra, FlatView, KeyRole}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** Dijkstra's transactions on the era types ([[DijkstraTx]], [[DijkstraTxBody]],
  * [[DijkstraTxWits]], [[DijkstraTxAuxData]], cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/{Tx,TxBody,TxWits,TxAuxData}.hs`). Every
  * Dijkstra transaction the repository holds is read by the flat decoder, which decides what is
  * refused, and by the era decoder: the era decoder refuses exactly what the flat one refuses, with
  * its error, and reads what it accepts with every field equal to the flat decoder's, its
  * sub-transactions at the sub-transaction level in the order and with the ids read; the one other
  * refusal is a pool registration, whose voting key the era types do not hold yet (#85).
  *
  * The transactions are those of `fixtures/reference/leios-testnet-blocks` (the corpus of the Leios
  * build's verdicts, its blocks and its transactions alone, recorded and synthetic), the public
  * Leios testnet's blocks (`fixtures/leios/testnet`), the reference cluster's Conway to Dijkstra
  * chain and its submitted transaction (`fixtures/dijkstra-devnet`), and the transactions of the
  * script-context fixtures (`fixtures/reference/leios-testnet-contexts` and
  * `leios-testnet-chain-contexts`, the latter recorded on the testnet's chain).
  */
class DijkstraTxSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private def node(raw: Bytes): Node =
    CborRead.decode(raw, V12.nestedLimits).fold(e => fail(e.toString), identity)

  private def jsonLines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  private def json(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)

  private def str(j: Json, name: String): String =
    j.hcursor.get[String](name).fold(e => fail(s"$name: ${e.message}"), identity)

  private def tsv(path: String): Vector[Array[String]] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .drop(1)
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))

  private val bodies = EraTxBody.dijkstra
  private val wits = EraTxWits.dijkstra
  private val auxes = EraTxAuxData.dijkstra

  // ---- the flat decoder's fields, read independently of the projection

  private def txIn(i: DijkstraTxIn): TxIn = TxIn(i.id, BigInt(i.index))

  private def anchor(a: DijkstraAnchor): Anchor = Anchor(a.url, a.hash)

  private def voter(v: DijkstraVoter): Voter = v match
    case DijkstraVoter.Committee(c) => Voter.CommitteeHot(c)
    case DijkstraVoter.DRep(c)      => Voter.DRepVoter(c)
    case DijkstraVoter.StakePool(p) => Voter.Pool(p)

  private def interval(i: AccountBalanceInterval): DijkstraBalanceInterval = i match
    case AccountBalanceInterval.AccountBalanceLowerBound(l) =>
      DijkstraBalanceInterval.Lower(l.unInclusive.value)
    case AccountBalanceInterval.AccountBalanceUpperBound(u) =>
      DijkstraBalanceInterval.Upper(u.unExclusive.value)
    case AccountBalanceInterval.AccountBalanceBothBounds(l, u) =>
      DijkstraBalanceInterval.Both(l.unInclusive.value, u.unExclusive.value)
    case AccountBalanceInterval.AccountBalanceExact(c) => DijkstraBalanceInterval.Exact(c.value)

  private def keyOf(c: FlatCredential): Option[Bytes] = c match
    case FlatCredential.Key(h)    => Some(h)
    case FlatCredential.Script(_) => None

  /** The key that signs a certificate (`getVKeyWitnessDijkstraTxCert`, `TxCert.hs:342-351`,
    * cardano-ledger 9df9f98c).
    */
  private def certKey(c: DijkstraCert): Option[Bytes] = c match
    case DijkstraCert.Register(c, _)                 => keyOf(c)
    case DijkstraCert.Unregister(c, _)               => keyOf(c)
    case DijkstraCert.Delegate(c, _)                 => keyOf(c)
    case DijkstraCert.RegisterDelegate(c, _, _)      => keyOf(c)
    case DijkstraCert.RegisterPool(p)                => Some(p.operator)
    case DijkstraCert.RetirePool(p, _)               => Some(p)
    case DijkstraCert.AuthorizeCommitteeHotKey(c, _) => keyOf(c)
    case DijkstraCert.ResignCommitteeColdKey(c, _)   => keyOf(c)
    case DijkstraCert.RegisterDRep(c, _, _)          => keyOf(c)
    case DijkstraCert.UnregisterDRep(c, _)           => keyOf(c)
    case DijkstraCert.UpdateDRep(c, _)               => keyOf(c)

  private def language(l: DijkstraLanguage): PlutusLanguage = l match
    case DijkstraLanguage.PlutusV1 => PlutusLanguage.V1
    case DijkstraLanguage.PlutusV2 => PlutusLanguage.V2
    case DijkstraLanguage.PlutusV3 => PlutusLanguage.V3
    case DijkstraLanguage.PlutusV4 => PlutusLanguage.V4

  private def scriptBytes(s: FlatScript): Bytes = s match
    case FlatScript.Native(raw)     => raw
    case FlatScript.Plutus(_, code) => code

  // ---- what an era value must equal

  /** The size of `[body, witnesses, auxiliary data / null]` with a one-byte list head, the parts
    * written as they arrived: the bytes of `toCBORForSizeComputation` (`Tx.hs:297-308`,
    * cardano-ledger 9df9f98c).
    */
  private def partsSize(body: Bytes, wits: Bytes, aux: Option[Bytes]): Long =
    (Bytes.fromArray(Array(0x83.toByte)) ++ body ++ wits ++
      aux.getOrElse(Bytes.fromArray(Array(0xf6.toByte)))).size.toLong

  /** An output against the block decoder's, field by field. */
  private def sameOutput(era: DijkstraTxOut, flat: FlatTxOut, what: String): Unit =
    val r = era.rep
    assertEquals(r.raw, flat.raw, what)
    assertEquals(r.address, flat.address, what)
    // the value's own size: the length of its encoding in the output (key 1 of the map form, the
    // second item of the list form), which the minimum-coin rule reads
    val valueBytes = node(flat.raw).value match
      case CborValue.Map(kvs) =>
        kvs.collectFirst { case (k, v) if k.value == CborValue.UInt(BigInt(1)) => v.original }
      case CborValue.Arr(xs) => xs.lift(1).map(_.original)
      case other             => fail(s"$what: an output of $other")
    assertEquals(r.valueSize.toLong, valueBytes.fold(fail(s"$what: no value"))(_.size.toLong), what)
    assertEquals(
      r.value.stored,
      xyz.kd5ujc.scarab.ledger.Value(flat.value.coin, flat.value.assets),
      what
    )
    (r.datum, flat.datum) match
      case (Datum.NoDatum, DijkstraDatum.NoDatum)      => ()
      case (Datum.DatumHash(h), DijkstraDatum.Hash(f)) => assertEquals(h, f, what)
      case (Datum.Datum(d), DijkstraDatum.Inline(f))   => assertEquals(d.bytes, f, what)
      case other                                       => fail(s"$what: $other")
    val s = DijkstraScript.instance
    assertEquals(
      r.scriptRef.map(x => (s.hashScript(x), s.originalBytes(x))),
      flat.referenceScript.map(f => (f.hash, scriptBytes(f))),
      what
    )

  /** A metadatum against the bytes the block decoder kept of it, as their CBOR reads, walked on the
    * heap (the corpus nests metadata deeper than the stack).
    */
  private def sameMetadatum(m: Metadatum, raw: Bytes): Boolean =
    @annotation.tailrec
    def go(todo: List[(Metadatum, Node)]): Boolean = todo match
      case Nil => true
      case (m, n) :: rest =>
        (m, n.value) match
          case (Metadatum.Num(v), CborValue.UInt(u))       => if v == u then go(rest) else false
          case (Metadatum.Num(v), CborValue.NInt(u))       => if v == u then go(rest) else false
          case (Metadatum.Bin(b), CborValue.ByteString(c)) => if b == c then go(rest) else false
          case (Metadatum.Str(a), CborValue.Text(t))       => if a == t then go(rest) else false
          case (Metadatum.Items(xs), CborValue.Arr(ys)) =>
            if xs.size == ys.size then go(xs.zip(ys).toList ++ rest) else false
          case (Metadatum.Entries(ps), CborValue.Map(qs)) =>
            if ps.size == qs.size then
              go(
                ps.zip(qs).toList.flatMap { case ((a, b), (c, d)) => List(a -> c, b -> d) } ++ rest
              )
            else false
          case _ => false
    go(List(m -> node(raw)))

  /** The bytes of each key of a witness set, read from its bytes. */
  private def keyedFields(raw: Bytes): Map[BigInt, Bytes] =
    CborRead
      .map(node(raw), DecodeField.WitnessSet)
      .fold(e => fail(e.toString), identity)
      .map((k, v) =>
        CborRead.uint(k, DecodeField.WitnessSet).fold(e => fail(e.toString), identity) -> v.original
      )
      .toMap

  /** The block decoder's certificate of an era certificate, every field read back. */
  private def asFlat(cert: DijkstraTxCert): DijkstraCert =
    import DijkstraTxCert.*
    import DijkstraDelegCert.*
    import ConwayGovCert.*
    def to(d: Delegatee): DijkstraDelegatee = d match
      case Delegatee.DelegStake(p) => DijkstraDelegatee.Stake(p.bytes)
      case Delegatee.DelegVote(v)  => DijkstraDelegatee.Vote(FlatView.drepBack(v))
      case Delegatee.DelegStakeVote(p, v) =>
        DijkstraDelegatee.StakeVote(p.bytes, FlatView.drepBack(v))
    def a(x: Option[Anchor]): Option[DijkstraAnchor] = x.map(y => DijkstraAnchor(y.url, y.hash))
    def back[R <: KeyRole](cr: Credential[R]) = FlatView.credBack(cr)
    cert match
      case DijkstraTxCertDeleg(DijkstraRegCert(cr, d)) => DijkstraCert.Register(back(cr), d.value)
      case DijkstraTxCertDeleg(DijkstraUnRegCert(cr, r)) =>
        DijkstraCert.Unregister(back(cr), r.value)
      case DijkstraTxCertDeleg(DijkstraDelegCert(cr, d)) => DijkstraCert.Delegate(back(cr), to(d))
      case DijkstraTxCertDeleg(DijkstraRegDelegCert(cr, d, dep)) =>
        DijkstraCert.RegisterDelegate(back(cr), to(d), dep.value)
      case DijkstraTxCertPool(PoolCert.RetirePool(p, e)) =>
        DijkstraCert.RetirePool(p.bytes, e.toBigInt)
      case DijkstraTxCertPool(PoolCert.RegPool(params)) => fail(s"a pool registration: $params")
      case DijkstraTxCertGov(ConwayAuthCommitteeHotKey(cold, hot)) =>
        DijkstraCert.AuthorizeCommitteeHotKey(back(cold), back(hot))
      case DijkstraTxCertGov(ConwayResignCommitteeColdKey(cold, x)) =>
        DijkstraCert.ResignCommitteeColdKey(back(cold), a(x))
      case DijkstraTxCertGov(ConwayRegDRep(cr, d, x)) =>
        DijkstraCert.RegisterDRep(back(cr), d.value, a(x))
      case DijkstraTxCertGov(ConwayUnRegDRep(cr, r)) =>
        DijkstraCert.UnregisterDRep(back(cr), r.value)
      case DijkstraTxCertGov(ConwayUpdateDRep(cr, x)) => DijkstraCert.UpdateDRep(back(cr), a(x))

  private def actionIds[P <: GovActionPurpose](
      era: Option[GovPurposeId[P]],
      flat: Option[DijkstraGovActionId],
      what: String
  ): Unit =
    assertEquals(
      era.map(_.unGovPurposeId),
      flat.map(i => GovActionId(i.txId, BigInt(i.index))),
      what
    )

  /** An era action against the block decoder's, field by field. */
  private def sameAction(era: GovAction[Dijkstra], flat: DijkstraGovAction, what: String): Unit =
    (era, flat) match
      case (GovAction.ParameterChange(p, u, g), DijkstraGovAction.ParameterChange(fp, fu, fg)) =>
        actionIds(p, fp, what)
        assertEquals(g.map(_.bytes), fg, what)
        // the update as Plutus data, against the flat path's reading of the decoder's update
        assertEquals(
          ToPlutusData[PParamsUpdate[Dijkstra]].toPlutusData(u),
          xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScriptContexts.changedParameters(fu),
          what
        )
      case (
            GovAction.HardForkInitiation(p, v),
            DijkstraGovAction.HardForkInitiation(fp, major, minor)
          ) =>
        actionIds(p, fp, what)
        assertEquals(
          (BigInt(v.pvMajor.value), v.pvMinor.value),
          (BigInt(major), BigInt(minor)),
          what
        )
      case (GovAction.TreasuryWithdrawals(ws, g), DijkstraGovAction.TreasuryWithdrawals(fws, fg)) =>
        assertEquals(ws.map((a, c) => a -> c.value), fws, what)
        assertEquals(g.map(_.bytes), fg, what)
      case (GovAction.NoConfidence(p), DijkstraGovAction.NoConfidence(fp)) => actionIds(p, fp, what)
      case (
            GovAction.UpdateCommittee(p, r, a, q),
            DijkstraGovAction.UpdateCommittee(fp, fr, fa, fq)
          ) =>
        actionIds(p, fp, what)
        assertEquals(r.map(FlatView.credBack), fr, what)
        assertEquals(a.map((c, e) => FlatView.credBack(c) -> e.toBigInt), fa, what)
        assertEquals(q.rational, fq, what)
      case (GovAction.NewConstitution(p, c), DijkstraGovAction.NewConstitution(fp, fa, fpol)) =>
        actionIds(p, fp, what)
        assertEquals((c.anchor, c.script), (anchor(fa), fpol), what)
      case (GovAction.InfoAction(), DijkstraGovAction.InfoAction) => ()
      case other                                                  => fail(s"$what: $other")

  /** What a redeemer key names, as both resolutions can say it. */
  private enum Item:
    case Input(in: TxIn)
    case Policy(policy: Bytes)
    case Cert(key: Option[Bytes], script: Option[Bytes])
    case Account(account: RewardAccount)
    case ByVoter(voter: Voter)
    case Proposal(proposal: DijkstraProposal)
    case Guard(script: Bytes)

  /** The item a redeemer key names, by the flat decoder's own resolution
    * ([[DijkstraPurposes.inverse]]): its tag, index and item.
    */
  private def flatItem(p: DijkstraPurpose): (Int, BigInt, Item) = p match
    case DijkstraPurpose.Spending(ix, in) => (0, BigInt(ix), Item.Input(txIn(in)))
    case DijkstraPurpose.Minting(ix, pol) => (1, BigInt(ix), Item.Policy(pol))
    case DijkstraPurpose.Certifying(ix, c) =>
      (2, BigInt(ix), Item.Cert(certKey(c), DijkstraPurposes.certificateScript(c)))
    case DijkstraPurpose.Withdrawing(ix, a)   => (3, BigInt(ix), Item.Account(a))
    case DijkstraPurpose.Voting(ix, v)        => (4, BigInt(ix), Item.ByVoter(voter(v)))
    case DijkstraPurpose.Proposing(ix, p)     => (5, BigInt(ix), Item.Proposal(p))
    case DijkstraPurpose.Guarding(ix, script) => (6, BigInt(ix), Item.Guard(script))

  private def eraItem(p: DijkstraPlutusPurpose[AsIxItem]): (Int, BigInt, Item) =
    import DijkstraPlutusPurpose.*
    val c = DijkstraTxCert.instance
    p match
      case DijkstraSpending(AsIxItem(ix, in))    => (0, ix.value, Item.Input(in))
      case DijkstraMinting(AsIxItem(ix, policy)) => (1, ix.value, Item.Policy(policy))
      case DijkstraCertifying(AsIxItem(ix, cert)) =>
        (2, ix.value, Item.Cert(c.getVKeyWitnessTxCert(cert), c.getScriptWitnessTxCert(cert)))
      case DijkstraWithdrawing(AsIxItem(ix, a)) => (3, ix.value, Item.Account(a))
      case DijkstraVoting(AsIxItem(ix, v))      => (4, ix.value, Item.ByVoter(v))
      case DijkstraProposing(AsIxItem(ix, p))   => (5, ix.value, Item.Proposal(p.read))
      case DijkstraGuarding(AsIxItem(ix, h))    => (6, ix.value, Item.Guard(h.bytes))

  /** Every field of a body of either level equal to the flat decoder's. */
  private def sameBody[L <: TxLevel](era: DijkstraTxBody[L], flat: FlatBody, what: String)(using
      l: BothLevels[L]
  ): Unit =
    val b = bodies
    assertEquals(b.id(era), flat.id, what)
    assertEquals(era.raw, flat.raw, what)
    assertEquals(
      b.inputs(era).toVector,
      flat.inputs.sorted(using DijkstraPurposes.inputOrder).map(txIn),
      what
    )
    assertEquals(
      b.referenceInputs(era).toVector,
      flat.referenceInputs.sorted(using DijkstraPurposes.inputOrder).map(txIn),
      what
    )
    assertEquals(b.sizedOutputs(era).map(_.size), flat.outputs.map(_.raw.size.toLong), what)
    b.sizedOutputs(era).zip(flat.outputs).foreach((o, f) => sameOutput(o.value, f, what))
    assertEquals(b.sizedOutputs(era).size, flat.outputs.size, what)
    val certs = b.certs(era)
    assertEquals(certs.size, flat.certificates.size, what)
    assertEquals(certs.map(asFlat), flat.certificates, what)
    certs.zip(flat.certificates).foreach { (c, f) =>
      assertEquals(b.certsOf.getVKeyWitnessTxCert(c), certKey(f), what)
      assertEquals(b.certsOf.getScriptWitnessTxCert(c), DijkstraPurposes.certificateScript(f), what)
    }
    assertEquals(b.withdrawals(era), flat.withdrawals.map((a, c) => a -> Coin(c)).toMap, what)
    assertEquals(b.vldt(era), ValidityInterval(flat.validityStart, flat.ttl), what)
    assertEquals(b.guards(era).toVector.map(FlatView.credBack), flat.guards, what)
    assertEquals(b.reqSignerHashes(era).toVector, flat.guards.flatMap(keyOf).distinct.sorted, what)
    assertEquals(b.mint(era), flat.mint, what)
    assertEquals(b.scriptIntegrityHash(era), flat.scriptIntegrityHash, what)
    assertEquals(b.auxDataHash(era), flat.auxDataHash, what)
    assertEquals(b.networkId(era).map(_.ordinal), flat.networkId, what)
    val votes = b.votingProcedures(era).unVotingProcedures
    assertEquals(
      votes.toVector.map((v, vs) => v -> vs.toVector),
      flat.votingProcedures
        .sortBy(_._1)(using DijkstraPurposes.voterOrder)
        .map((v, vs) =>
          voter(v) -> vs
            .sortBy(_._1)(using DijkstraPurposes.govActionOrder)
            .map((id, p) =>
              GovActionId(id.txId, BigInt(id.index)) -> VotingProcedure(
                p.vote,
                p.anchor.map(anchor)
              )
            )
        ),
      what
    )
    val proposals = b.proposalProcedures(era).toVector
    assertEquals(proposals.map(_.read), flat.proposals, what)
    proposals.zip(flat.proposals).foreach((p, f) => sameAction(p.pProcGovAction, f.action, what))
    assertEquals(
      proposals.map(p => (p.pProcDeposit, p.pProcReturnAddr, p.pProcAnchor)),
      flat.proposals.map(p => (Coin(p.deposit), p.returnAccount, anchor(p.anchor))),
      what
    )
    assertEquals(b.currentTreasuryValue(era), flat.currentTreasuryValue.map(Coin(_)), what)
    assertEquals(b.treasuryDonation(era), Coin(flat.donation), what)
    assertEquals(
      b.requiredTopLevelGuards(era).map((c, d) => FlatView.credBack(c) -> d.map(_.originalBytes)),
      flat.requiredTopLevelGuards.toMap,
      what
    )
    assertEquals(b.directDeposits(era), flat.directDeposits.map((a, c) => a -> Coin(c)).toMap, what)
    assertEquals(
      b.accountBalanceIntervals(era).map((a, i) => a -> interval(i)),
      flat.accountBalanceIntervals.toMap,
      what
    )
    // every redeemer key of each purpose, to one past the last item, names what the flat
    // resolution names
    val counts = Vector(
      flat.inputs.size,
      flat.mint.size,
      flat.certificates.size,
      flat.withdrawals.size,
      flat.votingProcedures.size,
      flat.proposals.size,
      flat.guards.size
    )
    for
      (count, tag) <- counts.zipWithIndex
      ix <- 0 to count
    do
      val ptr = DijkstraPlutusPurpose
        .asIx(
          tag,
          xyz.kd5ujc.scarab.ledger.Unsigned
            .from[xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32](BigInt(ix))
            .getOrElse(fail("index"))
        )
        .getOrElse(fail(s"tag $tag"))
      assertEquals(
        b.redeemerPointerInverse(era, ptr).map(eraItem),
        DijkstraPurposes.inverse(flat, tag, ix.toLong).map(flatItem),
        s"$what: purpose $tag at $ix"
      )

  private def sameWits(era: DijkstraTxWits, flat: DijkstraWitnesses, what: String): Unit =
    assertEquals(era.raw, flat.raw, what)
    assertEquals(wits.addrTxWits(era).map(w => (w.vkey, w.signature)).toSet, flat.vkeys.toSet, what)
    // every bootstrap witness in full, one per address root, the last read of a root kept, in
    // the roots' order
    val boots = flat.bootstraps.map(b =>
      xyz.kd5ujc.scarab.ledger.BootstrapWitness(b.key, b.signature, b.chainCode, b.attributes)
    )
    val kept = wits.bootAddrTxWits(era)
    assertEquals(
      kept.map(b => (b.vkey, b.signature, b.chainCode, b.attributes)).toSet,
      boots.reverse
        .distinctBy(_.addressRoot)
        .map(b => (b.vkey, b.signature, b.chainCode, b.attributes))
        .toSet,
      what
    )
    assertEquals(kept.map(_.addressRoot), kept.map(_.addressRoot).sorted, what)
    assertEquals(wits.scriptTxWitsArrival(era), flat.scriptHashes, what)
    val byHash = flat.scripts.map(s => s.hash -> scriptBytes(s)).toMap
    assertEquals(
      wits.scriptTxWits(era).map((h, s) => h -> wits.scriptsOf.originalBytes(s)),
      flat.scriptHashes.map(h => h -> byHash(h)).toMap,
      what
    )
    val datums = wits.datsTxWits(era)
    assertEquals(
      datums.datums.map((h, d) => h -> d.originalBytes),
      flat.datumHashes.zip(flat.datums).toMap,
      what
    )
    assertEquals(datums.raw.isDefined, flat.datums.nonEmpty, what)
    val redeemers = wits.rdmrsTxWits(era)
    assertEquals(
      redeemers.entries.toVector.map((p, r) =>
        (p.tag, p.ix.value, r._1.originalBytes, r._2.mem, r._2.steps)
      ),
      flat.redeemers.map(r => (r.tag, BigInt(r.index), r.data, r.memory, r.steps)),
      what
    )
    assertEquals(redeemers.raw.isDefined, flat.redeemers.nonEmpty, what)
    // keys 4 and 5 keep the bytes the datums and redeemers arrived as
    val keys = keyedFields(flat.raw)
    assertEquals(datums.raw, keys.get(BigInt(4)), what)
    assertEquals(redeemers.raw, keys.get(BigInt(5)), what)

  private def sameAux(era: DijkstraTxAuxData, flat: DijkstraAuxData, what: String): Unit =
    assertEquals(auxes.originalBytes(era), flat.raw, what)
    assertEquals(auxes.metadata(era).keySet.map(_.value), flat.metadata.map(_._1).toSet, what)
    val metadata = auxes.metadata(era).map((k, m) => k.value -> m)
    flat.metadata.foreach((label, raw) =>
      assert(metadata.get(label).exists(sameMetadatum(_, raw)), s"$what: metadatum $label")
    )
    assertEquals(
      auxes.nativeScripts(era).map(_.raw),
      flat.scripts.collect { case FlatScript.Native(raw) => raw },
      what
    )
    val plutus = flat.scripts.collect { case FlatScript.Plutus(l, code) => language(l) -> code }
    assertEquals(auxes.plutusScriptsArrival(era), plutus, what)
    assertEquals(
      auxes.plutusScripts(era).toVector.flatMap((l, cs) => cs.toVector.map(l -> _)),
      plutus,
      what
    )

  /** A top-level transaction equal to the flat decoder's, its sub-transactions in order. */
  private def sameTx(era: DijkstraTx[TxLevel.Top], flat: FlatTx, what: String): Int =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    sameBody(era.body, flat.body, what)
    sameWits(era.wits, flat.witnesses, what)
    assertEquals(era.auxData.isDefined, flat.auxiliary.isDefined, what)
    era.auxData.zip(flat.auxiliary).foreach((a, f) => sameAux(a, f, what))
    assertEquals(DijkstraTx.isValid(era), IsPhase2Valid.of(flat.isValid), what)
    val top = DijkstraTxBody.topBody(era.body)
    assertEquals(bodies.fee(era.body), Coin(flat.body.fee.getOrElse(fail(s"$what: no fee"))), what)
    assertEquals(
      bodies.collateralInputs(era.body).toVector,
      flat.body.collateral.sorted(using DijkstraPurposes.inputOrder).map(txIn),
      what
    )
    assertEquals(
      bodies.sizedCollateralReturn(era.body).map(_.size),
      flat.body.collateralReturn.map(_.raw.size.toLong),
      what
    )
    bodies
      .sizedCollateralReturn(era.body)
      .zip(flat.body.collateralReturn)
      .foreach((o, f) => sameOutput(o.value, f, what))
    assertEquals(bodies.totalCollateral(era.body), flat.body.totalCollateral.map(Coin(_)), what)
    assertEquals(
      bodies.startingAccountBalanceIntervals(era.body).map((a, i) => a -> interval(i)),
      flat.body.startingAccountBalanceIntervals.toMap,
      what
    )
    // the sub-transactions in the order read, by their ids, each at the sub-transaction level
    val subs = bodies.subTransactions(era.body)
    assertEquals(subs.keys, flat.body.subTransactions.map(_.id), what)
    assertEquals(subs.toVector.map(_.id), flat.body.subTransactions.map(_.id), what)
    // each id looks its sub-transaction up, and an id of none looks up nothing
    subs.keys.foreach(k => assertEquals(subs.get(k).map(_.id), Some(k), what))
    assertEquals(subs.get(flat.body.id), None, what)
    subs.toVector.zip(flat.body.subTransactions).foreach { (s, f) =>
      given BothLevels[TxLevel.Sub] = BothLevels.Sub
      sameBody(s.body, f.body, s"$what, sub-transaction ${f.id.hex}")
      sameWits(s.wits, f.witnesses, s"$what, sub-transaction ${f.id.hex}")
      s.auxData.zip(f.auxiliary).foreach((a, x) => sameAux(a, x, what))
      assertEquals(s.sizeTxF, partsSize(f.body.raw, f.witnesses.raw, f.auxiliary.map(_.raw)), what)
      assertEquals(s.auxData.isDefined, f.auxiliary.isDefined, what)
      assertEquals(
        bodies.spendableInputs(s.body)(using BothLevels.Sub),
        bodies.inputs(s.body)(using BothLevels.Sub)
      )
    }
    // `dijkstraAllInputsTxBodyF`: the top level's spent, collateral and reference inputs and
    // each sub-transaction's spent and reference inputs
    assertEquals(
      bodies.allInputs(era.body),
      (flat.body.inputs ++ flat.body.collateral ++ flat.body.referenceInputs ++
        flat.body.subTransactions.flatMap(s => s.body.inputs ++ s.body.referenceInputs))
        .map(txIn)
        .to(scala.collection.immutable.SortedSet),
      what
    )
    assertEquals(top.subTransactions.size, flat.body.subTransactions.size, what)
    // `sizeDijkstraTxF`: the list of three of the parts as they arrived
    assertEquals(
      era.sizeTxF,
      partsSize(flat.body.raw, flat.witnesses.raw, flat.auxiliary.map(_.raw)),
      what
    )
    flat.body.subTransactions.size

  private def registersPool(flat: FlatTx): Boolean =
    def in(b: FlatBody) = b.certificates.exists {
      case DijkstraCert.RegisterPool(_) => true
      case _                            => false
    }
    in(flat.body) || flat.body.subTransactions.exists(s => in(s.body))

  /** The era decoder against the flat one on `n`: the flat refusal, the same transaction, or the
    * refusal of a pool registration. The result counts sub-transactions and pool refusals.
    */
  private final case class Tally(txs: Int, subs: Int, pools: Int, refused: Int):
    def +(o: Tally): Tally = Tally(txs + o.txs, subs + o.subs, pools + o.pools, refused + o.refused)

  private val none = Tally(0, 0, 0, 0)

  private def compare(
      flat: Either[LedgerDecodeError, FlatTx],
      era: Either[EraTxError, DijkstraTx[TxLevel.Top]],
      what: String
  ): Tally =
    (flat, era) match
      case (Left(e), Left(EraTxError.Decode(f))) =>
        assertEquals(f, e, what)
        Tally(0, 0, 0, 1)
      case (Right(f), Right(t)) =>
        assert(!registersPool(f), s"$what: a pool registration read")
        Tally(1, sameTx(t, f, what), 0, 0)
      case (Right(f), Left(EraTxError.PoolVotingKeyNotYetTyped)) =>
        assert(registersPool(f), s"$what: refused as a pool registration")
        Tally(1, 0, 1, 0)
      case other => fail(s"$what: $other")

  // the bytes read as CBOR first, by the one reader both decoders are given, as a block body reads
  // each transaction; bytes that are not one item of CBOR are refused before either decoder
  private def inBlock(raw: Bytes, what: String): Tally =
    CborRead.decode(raw, V12.nestedLimits) match
      case Right(n) => compare(FlatTx.inBlock(n), DijkstraTx.inBlock(n), what)
      case Left(_)  => Tally(0, 0, 0, 1)

  private def ofBlock(raw: Bytes, what: String): Tally =
    Block.decodeShelley(raw) match
      case Right(b: Block.Dijkstra) =>
        b.block.transactions.zipWithIndex
          .map((t, i) => compare(Right(t), DijkstraTx.inBlock(node(t.raw)), s"$what tx $i"))
          .foldLeft(none)(_ + _)
      case other => fail(s"$what: $other")

  private lazy val corpus = jsonLines("fixtures/reference/leios-testnet-blocks/cases.jsonl")

  test(
    "every transaction of the Leios corpus reads as the flat decoder reads it, or is refused as it"
  ) {
    val txs = corpus.filter(j => str(j, "kind") == "tx")
    val tally = txs.map(j => inBlock(hex(str(j, "bytes")), str(j, "name"))).foldLeft(none)(_ + _)
    // the corpus's 536 transactions alone: the 273 the build accepts, 16 of which register a pool,
    // with 20 sub-transactions among them, and the 263 it refuses
    assertEquals(tally, Tally(txs = 273, subs = 20, pools = 16, refused = 263))
    // among them a parameter change, whose proposal keeps the flat Dijkstra proposal it was read as
    val changes = txs
      .flatMap(j => CborRead.decode(hex(str(j, "bytes")), V12.nestedLimits).toOption)
      .flatMap(n => DijkstraTx.inBlock(n).toOption)
      .flatMap(t => bodies.proposalProcedures(t.body)(using BothLevels.Top).toVector)
      .count(_.pProcGovAction match
        case xyz.kd5ujc.scarab.ledger.era.GovAction.ParameterChange(_, _, _) => true
        case _                                                               => false)
    assert(changes > 0, "no parameter change in the corpus")
  }

  test(
    "every transaction of the corpus's accepted Leios blocks reads as the flat decoder reads it"
  ) {
    val blocks = corpus.filter(j => str(j, "kind") == "block")
    val tally = blocks
      .map(j => LeiosBlockRead(hex(str(j, "bytes")), str(j, "name")))
      .foldLeft(none)(_ + _)
    // the transactions of every block the corpus's blocks decode as
    assertEquals(tally, Tally(txs = 63, subs = 0, pools = 0, refused = 0))
  }

  private def LeiosBlockRead(raw: Bytes, what: String): Tally =
    xyz.kd5ujc.scarab.ledger.LeiosBlock.decode(raw) match
      case Right(b) =>
        b.transactions.zipWithIndex
          .map((t, i) => compare(Right(t), DijkstraTx.inBlock(node(t.raw)), s"$what tx $i"))
          .foldLeft(none)(_ + _)
      case Left(_) => none

  test(
    "every transaction of the public Leios testnet's blocks reads as the flat decoder reads it"
  ) {
    val rows = tsv("fixtures/leios/testnet/blocks-rc-2026w44.tsv")
    assert(rows.nonEmpty)
    val tally = rows.map(p => ofBlock(hex(p(5)), p(0))).foldLeft(none)(_ + _)
    // the 40 transactions of the announcing block, none with sub-transactions
    assertEquals(tally, Tally(txs = 40, subs = 0, pools = 0, refused = 0))
  }

  test(
    "the devnet chain's Dijkstra transactions and its submitted one read as the flat decoder reads them"
  ) {
    val rows = tsv("fixtures/dijkstra-devnet/blocks.tsv").filter(_(0) == "8")
    val tally = rows.map(p => ofBlock(hex(p(4)), s"devnet block ${p(1)}")).foldLeft(none)(_ + _)
    val submitted = """"cborHex"\s*:\s*"([0-9a-f]+)"""".r
      .findFirstMatchIn(Files.readString(Path.of("fixtures/dijkstra-devnet/transaction.json")))
      .map(m => hex(m.group(1)))
      .getOrElse(fail("no cborHex"))
    val mempool = compare(
      FlatTx.fromSubmission(submitted),
      DijkstraTx.fromSubmission(submitted),
      "the submitted transaction"
    )
    assertEquals((tally, mempool), (Tally(1, 0, 0, 0), Tally(1, 0, 0, 0)))
  }

  test("the transactions of the script-context fixtures read as the flat decoder reads them") {
    val contexts = json("fixtures/reference/leios-testnet-contexts/cases.json").asArray
      .getOrElse(fail("not an array"))
      .map(c => inBlock(hex(str(c, "bytes")), str(c, "name")))
      .foldLeft(none)(_ + _)
    val chain = json("fixtures/reference/leios-testnet-chain-contexts/cases.json").hcursor
      .get[Vector[Json]]("cases")
      .fold(e => fail(e.message), identity)
      .map(c => inBlock(hex(str(c, "bytes")), str(c, "name")))
      .foldLeft(none)(_ + _)
    // the 200 transactions recorded on the testnet's chain hold 38 of its 280 sub-transactions
    assertEquals((contexts, chain), (Tally(11, 3, 1, 0), Tally(200, 38, 0, 0)))
    // some arrived in an order that is not their ids', which the ordered map keeps
    val unsorted = (json("fixtures/reference/leios-testnet-contexts/cases.json").asArray
      .getOrElse(fail("not an array")) ++ json(
      "fixtures/reference/leios-testnet-chain-contexts/cases.json"
    ).hcursor
      .get[Vector[Json]]("cases")
      .fold(e => fail(e.message), identity))
      .flatMap(c => DijkstraTx.inBlock(node(hex(str(c, "bytes")))).toOption)
      .map(t => bodies.subTransactions(t.body).keys)
      .count(ids => ids.sizeIs >= 2 && ids != ids.sorted)
    assert(unsorted > 0, "every recorded sub-transaction list is in its ids' order")
  }

  test(
    "the distinct transactions of the reference's UTXOW cases read as the flat decoder reads them"
  ) {
    // `fixtures/reference/dijkstra-rules`: the devnet's transaction and its variants, each a
    // transaction of a block body
    val txs = jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
      .filter(j => str(j, "kind") == "utxow")
      .map(j => str(j, "tx"))
      .distinct
    val tally = txs.zipWithIndex.map((t, i) => inBlock(hex(t), s"utxow $i")).foldLeft(none)(_ + _)
    assertEquals(tally, Tally(11, 0, 0, 0))
  }

  test("the Dijkstra mempool forms of the crossing corpus read as the flat decoder reads them") {
    // `fixtures/reference/dijkstra-crossing`: each Conway transaction the reference carried into
    // Dijkstra, in Dijkstra's mempool form (`toCBORForMempoolSubmission`), as a client or the
    // hard-fork mempool submits it; that form is also the size the reference computes for the fee
    val forms = jsonLines("fixtures/reference/dijkstra-crossing/cases.jsonl")
      .flatMap(c =>
        Vector("rematch", "translateEra").flatMap(k =>
          c.hcursor.downField("reference").downField(k).get[String]("mempool").toOption
        )
      )
      .distinct
    val tally = forms.zipWithIndex
      .map { (f, i) =>
        val raw = hex(f)
        val era = DijkstraTx.fromSubmission(raw)
        era.foreach(t => assertEquals(t.sizeTxF, raw.size.toLong, s"crossing $i: size"))
        compare(FlatTx.fromSubmission(raw), era, s"crossing $i")
      }
      .foldLeft(none)(_ + _)
    // all 149 the flat decoder accepts, 9 of them registering a pool
    assertEquals(forms.size, 149)
    assertEquals(tally, Tally(149, 0, 9, 0))
  }

  test("a body and a transaction are made only by the decoders, from what they read from bytes") {
    // the body projections and the flat witness set's take a proof only the decoders hold
    val proof =
      "No given instance of type xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.FromBytes was found"
    Vector(
      compileErrors(
        "def f(b: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxBody) = DijkstraTxBody.subOf(b)"
      ),
      compileErrors(
        "def f(b: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxBody, s: xyz.kd5ujc.scarab.ledger.era.OMap[Bytes, DijkstraTx[TxLevel.Sub]]) = DijkstraTxBody.topOf(b, s)"
      ),
      compileErrors(
        "def f(w: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraWitnesses) = DijkstraTxWits.fromFlat(w)"
      ),
      compileErrors(
        "def f(a: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraAuxData) = DijkstraTxAuxData.fromFlat(a)"
      )
    ).foreach(e => assert(e.contains(proof), e))
    // nor can the proof, a body or a transaction be made outside the objects that make them
    Vector(
      "DijkstraTx" -> compileErrors("val p = new DijkstraTx.FromBytes()"),
      "DijkstraTxBody" -> compileErrors(
        "def f(x: DijkstraBodyFields) = new DijkstraTxBody.SubTxBody(x)"
      ),
      "DijkstraTx" -> compileErrors(
        "def f(b: DijkstraTxBody[TxLevel.Sub], w: DijkstraTxWits) = new DijkstraTx.SubTx(b, w, None)"
      ),
      "DijkstraTxBody" -> compileErrors(
        "def f(x: DijkstraBodyFields, c: scala.collection.immutable.SortedSet[TxIn], r: Option[xyz.kd5ujc.scarab.ledger.era.Sized[DijkstraTxOut]], t: Option[Coin], fee: Coin, s: xyz.kd5ujc.scarab.ledger.era.OMap[Bytes, DijkstraTx[TxLevel.Sub]], i: Map[RewardAccount, AccountBalanceInterval]) = new DijkstraTxBody.TopTxBody(x, c, r, t, fee, s, i)"
      ),
      "DijkstraTx" -> compileErrors(
        "def f(b: DijkstraTxBody[TxLevel.Top], w: DijkstraTxWits, v: IsPhase2Valid) = new DijkstraTx.TopTx(b, w, v, None)"
      )
    ).foreach((owner, e) => assert(e.contains(s"can only be accessed from object $owner"), e))
  }

  test("a sub-transaction id given twice is refused with the flat decoder's error") {
    val cases = corpus.filter(j =>
      Set(
        "tx sub-transactions with one twice",
        "tx sub-transactions with one body and two witness sets"
      )
        .contains(str(j, "name"))
    )
    assertEquals(cases.size, 2)
    cases.foreach { j =>
      val n = node(hex(str(j, "bytes")))
      assertEquals(
        FlatTx.inBlock(n),
        Left(LedgerDecodeError.DuplicateElement(DecodeField.SubTransactions))
      )
      assertEquals(
        DijkstraTx.inBlock(n),
        Left(EraTxError.Decode(LedgerDecodeError.DuplicateElement(DecodeField.SubTransactions)))
      )
    }
  }

  test("a sub-transaction body has no fee or collateral; a top-level body has them") {
    val top =
      "def f(b: DijkstraTxBody[TxLevel.Top]) = (EraTxBody.dijkstra.fee(b), EraTxBody.dijkstra.collateralInputs(b), EraTxBody.dijkstra.totalCollateral(b), EraTxBody.dijkstra.sizedCollateralReturn(b))"
    assertEquals(compileErrors(top), "")
    // each top-level member of the class refuses a sub-transaction's body: found Sub, required Top
    val mismatch =
      "Found: (b : xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxBody[ xyz.kd5ujc.scarab.ledger.era.TxLevel.Sub] ) " +
        "Required: xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxBody[ xyz.kd5ujc.scarab.ledger.era.TxLevel.Top]"
    Vector(
      compileErrors("def f(b: DijkstraTxBody[TxLevel.Sub]) = EraTxBody.dijkstra.fee(b)"),
      compileErrors(
        "def f(b: DijkstraTxBody[TxLevel.Sub]) = EraTxBody.dijkstra.collateralInputs(b)"
      ),
      compileErrors(
        "def f(b: DijkstraTxBody[TxLevel.Sub]) = EraTxBody.dijkstra.totalCollateral(b)"
      ),
      compileErrors(
        "def f(b: DijkstraTxBody[TxLevel.Sub]) = EraTxBody.dijkstra.sizedCollateralReturn(b)"
      )
    ).foreach(e => assert(e.replaceAll("\\s+", " ").contains(mismatch), e))
    // a sub-transaction's body has no such field, a top-level body has
    Vector(
      "fee" -> compileErrors("def f(b: DijkstraTxBody.SubTxBody) = b.fee"),
      "collateralInputs" -> compileErrors(
        "def f(b: DijkstraTxBody.SubTxBody) = b.collateralInputs"
      ),
      "collateralReturn" -> compileErrors(
        "def f(b: DijkstraTxBody.SubTxBody) = b.collateralReturn"
      ),
      "totalCollateral" -> compileErrors("def f(b: DijkstraTxBody.SubTxBody) = b.totalCollateral"),
      "subTransactions" -> compileErrors("def f(b: DijkstraTxBody.SubTxBody) = b.subTransactions")
    ).foreach { (field, e) =>
      assert(
        e.contains(
          s"value $field is not a member of xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxBody.SubTxBody"
        ),
        e
      )
    }
    assertEquals(
      compileErrors(
        "def f(b: DijkstraTxBody.TopTxBody) = (b.fee, b.collateralInputs, b.collateralReturn, b.totalCollateral, b.subTransactions)"
      ),
      ""
    )
  }

  test("Dijkstra has no class of the required signers' field; its getter is its guards' keys") {
    val missing = compileErrors(
      "summon[xyz.kd5ujc.scarab.ledger.era.AlonzoEraTxBodyToConway[xyz.kd5ujc.scarab.ledger.state.Dijkstra]]"
    ).replaceAll("\\s+", " ")
    assert(
      missing.contains(
        "No given instance of type xyz.kd5ujc.scarab.ledger.era.AlonzoEraTxBodyToConway[ xyz.kd5ujc.scarab.ledger.state.Dijkstra]"
      ) || missing.contains(
        "No given instance of type xyz.kd5ujc.scarab.ledger.era.AlonzoEraTxBodyToConway[xyz.kd5ujc.scarab.ledger.state.Dijkstra]"
      ),
      missing
    )
    assertEquals(
      compileErrors(
        "summon[xyz.kd5ujc.scarab.ledger.era.DijkstraEraTxBody[xyz.kd5ujc.scarab.ledger.state.Dijkstra]]"
      ),
      ""
    )
  }

  test(
    "the cardano-cbor-dataset's Dijkstra witness and auxiliary samples read as the flat decoder reads them"
  ) {
    // `CborDatasetSuite`'s redeemers, indefinite, as key 5 of a witness set: their bytes are kept
    val redeemers = "bf820000829f01ff821903e81b0000000100000000ff"
    val set = hex("a105" + redeemers)
    val flat = DijkstraWitnesses.decode(node(set)).fold(e => fail(e.toString), identity)
    val era = DijkstraTxWits.decode(node(set)).fold(e => fail(e.toString), identity)
    sameWits(era, flat, "redeemers")
    assertEquals(era.redeemers.raw, Some(hex(redeemers)))
    assertEquals(era.datums.raw, None)
    // its auxiliary data with a native script that requires a guard
    val guard = "82068200581c" + "00" * 28
    val aux = hex("82a081" + guard)
    val flatAux = DijkstraAuxData.decode(node(aux)).fold(e => fail(e.toString), identity)
    val eraAux = DijkstraTxAuxData.decode(node(aux)).fold(e => fail(e.toString), identity)
    sameAux(eraAux, flatAux, "guard")
    assertEquals(eraAux.nativeScripts.map(_.raw), Vector(hex(guard)))
  }

  test("a witness set's datums tagged 258 and redeemers in an indefinite map keep their bytes") {
    val datums = "d9010281d87980"
    val redeemers = "bf82000082d87980820101ff"
    val set = hex(s"a204${datums}05$redeemers")
    val flat = DijkstraWitnesses.decode(node(set)).fold(e => fail(e.toString), identity)
    val era = DijkstraTxWits.decode(node(set)).fold(e => fail(e.toString), identity)
    sameWits(era, flat, "tagged and indefinite")
    assertEquals((era.datums.raw, era.redeemers.raw), (Some(hex(datums)), Some(hex(redeemers))))
    assertEquals(era.datums.datums.values.map(_.originalBytes).toVector, Vector(hex("d87980")))
  }

  test(
    "auxiliary Plutus scripts arrive by language, as the block decoder lists them, in any map order"
  ) {
    // the tagged map with Plutus V4 (key 5) before Plutus V1 (key 2)
    val aux = hex("d90103a2058141aa028141bb")
    val flat = DijkstraAuxData.decode(node(aux)).fold(e => fail(e.toString), identity)
    val era = DijkstraTxAuxData.decode(node(aux)).fold(e => fail(e.toString), identity)
    sameAux(era, flat, "tagged")
    assertEquals(
      era.plutusArrival,
      Vector(PlutusLanguage.V1 -> hex("bb"), PlutusLanguage.V4 -> hex("aa"))
    )
  }

  test("a transaction nested deeper than the stack reads on a small stack") {
    val depth = 50000
    def nested(inner: String, open: String) = open * depth + inner
    val native = nested("8200581c" + "33" * 28, "820181")
    val input = "81825820" + "11" * 32 + "00"
    def tx(output: String, witnesses: String, aux: String) =
      hex(s"84a300${input}01${output}0201${witnesses}${aux}f5")
    val script = s"8200$native"
    val wrapped = s"d8185a${f"${script.length / 2}%08x"}$script"
    Vector(
      tx("80", s"a10181$native", "f6"),
      tx(s"81a300581d61${"22" * 28}010503$wrapped", "a0", "f6"),
      tx("80", "a0", s"a101${nested("01", "81")}")
    ).zipWithIndex.foreach { (raw, i) =>
      val tally = PreprodDeepTx.onSmallStack(inBlock(raw, s"deep $i"))
      assertEquals(tally, Tally(1, 0, 0, 0))
    }
  }
