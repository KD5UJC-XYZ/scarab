// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ByteStrings,
  CborRead,
  Certificate,
  ConwayParamUpdate,
  DecodeField,
  GovAction as FlatGovAction,
  GovActionId,
  LedgerDecodeError,
  Metadatum,
  NativeScript as FlatNativeScript,
  PoolParams,
  PreprodDeepTx,
  Proposal,
  ReferenceCorpus,
  Purpose,
  RewardAccount,
  Script as FlatScript,
  ScriptKind,
  Unsigned,
  TxBody as FlatTxBody,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayPParams,
  ConwayPlutusPurpose,
  ConwayProcedures,
  ConwayTxOut,
  ConwayTx,
  ConwayTxAuxData,
  ConwayTxBody,
  ConwayTxCert,
  ConwayTxWits
}
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  EpochNo,
  HotCommittee,
  KeyHash,
  KeyRole,
  Network,
  PoolParamsBlsKey,
  ScriptHash,
  StakePool,
  Staking
}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Conway's types (stage 2 of `docs/design/era-typed-transactions.md`): every recorded Conway body
  * read as a `ConwayTxBody` has, through Conway's classes, every field the flat decoder reads; and
  * what only the projection from the flat body refuses, the flat Conway decoder never produces.
  */
class EraConwaySuite extends munit.FunSuite:
  // every block of the fixtures from Shelley on (`[era, block]`, era 2 to 7), each of which must
  // decode, so a regression that loses one fails here rather than shrinking the corpus
  private lazy val conwayBlocks: Vector[Block.Shelley] =
    EraCorpus
      .shelleyBlocks(
        Vector(
          "fixtures/eras/blocks.hex",
          "fixtures/preview/conway.hex",
          "fixtures/preview/conway-governance.hex"
        )
      )
      .filter(_.era == Era.Conway)

  private lazy val transactions: Vector[Block.Transaction] = conwayBlocks.flatMap(_.transactions)

  test(
    "every recorded Conway witness set reads with the flat decoder's witnesses, its scripts typed"
  ) {
    val w = EraTxWits.conway
    assert(transactions.nonEmpty)
    val counts = transactions.map { t =>
      val flat = Witnesses.decode(t.witnesses, Era.Conway).toOption.get
      val c = ConwayTxWits.decode(t.witnesses).toOption.get
      assertEquals(
        w.addrTxWits(c).toVector.map(k => (k.vkey, k.signature)),
        flat.vkeys.distinct.sortBy((vk, sig) => WitVKey(vk, sig))
      )
      assertEquals(w.bootAddrTxWits(c), flat.bootstrap)
      val scripts = w.scriptTxWits(c)
      assertEquals(scripts.keySet, flat.scriptsByHash.keySet)
      scripts.foreach((hash, s) => assertEquals(w.scriptsOf.hashScript(s), hash))
      assertEquals(w.datsTxWits(c).datums.values.map(_.originalBytes).toSet, flat.datums.toSet)
      assertEquals(w.datsTxWits(c).raw, flat.datumsRaw)
      assertEquals(w.rdmrsTxWits(c).raw, flat.redeemersRaw)
      assertEquals(
        w.rdmrsTxWits(c)
          .entries
          .toVector
          .map((p, v) => (p.tag, BigInt(p.ix.value.toString), (v._1.originalBytes, v._2))),
        flat.redeemers.map(r => (r.tag, r.index, (r.data, r.exUnits))).sortBy(r => (r._1, r._2))
      )
      (scripts.size, w.rdmrsTxWits(c).entries.size)
    }
    assert(counts.map(_._1).sum > 0, "no recorded Conway witness set has a script")
    assert(counts.map(_._2).sum > 0, "no recorded Conway witness set has a redeemer")
  }

  test("key witnesses are ordered by the key's hash, then the signature's") {
    // `Ord (WitVKey kr)`: `comparing wvkKeyHash <> comparing (hashTxBodySignature . wvkSignature)`
    // (`Keys/WitVKey.hs:58-68`, cardano-ledger f649f975), from the hashes directly
    val keys = (1 to 6).map(i => Bytes(Vector.fill(32)(i.toByte))).toVector
    val sigs = (1 to 3).map(i => Bytes(Vector.fill(64)((0x40 + i).toByte))).toVector
    val all = for k <- keys; s <- sigs yield WitVKey(k, s)
    val expected = all
      .map(w =>
        (
          xyz.kd5ujc.scarab.crypto.Blake2b.hash224.hash(w.vkey).hex,
          xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(w.signature).hex,
          w
        )
      )
      .sortBy(t => (t._1, t._2))
      .map(_._3)
    assertEquals(scala.collection.immutable.SortedSet.from(all.reverse).toVector, expected)
    // the order of the keys' hashes is not the order of the keys
    assertNotEquals(expected.map(_.vkey).distinct, keys)
  }

  test("the deep preprod witness set reads on a small stack, its native script typed") {
    val witnesses = CborRead.decode(PreprodDeepTx.bytes).toOption.get.value match
      case xyz.kd5ujc.scarab.codec.Value.Arr(xs) => xs(1).original
      case other => fail(s"not a transaction: ${other.toString.take(80)}")
    val c = PreprodDeepTx.onSmallStack(ConwayTxWits.decode(witnesses))
    val flat = Witnesses.decode(witnesses, Era.Conway).toOption.get
    assertEquals(
      c.map(EraTxWits.conway.scriptTxWits(_).keySet.toSet),
      Right(flat.scriptsByHash.keySet)
    )
  }

  private lazy val bodies: Vector[Bytes] = transactions.map(_.body)

  private val body = EraTxBody.conway
  private given body.Level[TxLevel.Top] = body.top

  test("every recorded Conway body reads with every field of the flat decoder's") {
    assert(bodies.nonEmpty)
    val certs = bodies.map { raw =>
      val flat = FlatTxBody.decode(raw, Era.Conway).toOption.get
      val c = ConwayTxBody.decode(raw, StateLayout.Node11_1).toOption.get
      assertEquals(body.id[TxLevel.Top](c), flat.id)
      assertEquals(body.inputs[TxLevel.Top](c).toVector, flat.inputs.sorted)
      assertEquals(body.outputs[TxLevel.Top](c).map(body.outputsOf.raw), flat.outputs.map(_.raw))
      assertEquals(body.fee(c).value, flat.fee)
      assertEquals(body.withdrawals[TxLevel.Top](c), flat.withdrawals)
      assertEquals(body.auxDataHash[TxLevel.Top](c), flat.auxDataHash)
      assertEquals(body.vldt[TxLevel.Top](c), ValidityInterval(flat.validityStart, flat.ttl))
      assertEquals(body.mint[TxLevel.Top](c), flat.mint)
      assertEquals(body.collateralInputs(c).toVector, flat.collateral.sorted)
      assertEquals(body.reqSignerHashes[TxLevel.Top](c).toVector, flat.requiredSigners.sorted)
      assertEquals(body.scriptIntegrityHash[TxLevel.Top](c), flat.scriptDataHash)
      assertEquals(body.networkId[TxLevel.Top](c).map(n => BigInt(n.ordinal)), flat.networkId)
      assertEquals(body.referenceInputs[TxLevel.Top](c).toVector, flat.referenceInputs.sorted)
      assertEquals(body.totalCollateral(c).map(_.value), flat.totalCollateral)
      assertEquals(
        body.collateralReturn(c).map(body.outputsOf.raw),
        flat.collateralReturn.map(_.raw)
      )
      // each output with the size of its encoding, the collateral return last
      assertEquals(
        body.allSizedOutputs[TxLevel.Top](c).map(o => (body.outputsOf.raw(o.value), o.size)),
        (flat.outputs ++ flat.collateralReturn).map(o => (o.raw, o.ledgerSize.toLong))
      )
      assertEquals(body.currentTreasuryValue[TxLevel.Top](c).map(_.value), flat.currentTreasury)
      assertEquals(
        body
          .votingProcedures[TxLevel.Top](c)
          .unVotingProcedures
          .toVector
          .map((v, m) => v -> m.toVector),
        flat.votes.map((v, m) => v -> m.sortBy(_._1)).sortBy(_._1)
      )
      // each proposal at the reference's types, with the flat proposal it was read as
      val proposals = body.proposalProcedures[TxLevel.Top](c).toVector
      assertEquals(proposals.map(_.read), flat.proposals)
      assertEquals(
        proposals.map(pp => (pp.pProcDeposit.value, pp.pProcReturnAddr, pp.pProcAnchor)),
        flat.proposals.map(fp => (fp.deposit, fp.returnAccount, fp.anchor))
      )
      assertEquals(proposals.size, flat.proposals.size)
      proposals.zip(flat.proposals).foreach((pp, fp) => agrees(pp.pProcGovAction, fp.action))
      assertEquals(body.treasuryDonation[TxLevel.Top](c).value, flat.donation)
      // a certificate's script witness is the one the flat rules ask for
      val typed = body.certs[TxLevel.Top](c).toVector
      assertEquals(typed.size, flat.certificates.size)
      assertEquals(
        typed.map(body.certsOf.getScriptWitnessTxCert),
        flat.certificates.map(Purpose.certificateScript)
      )
      // and each reads back as the flat certificate, a pool's parameters with their bytes
      assertEquals(typed.map(ConwayTxCert.toStored), flat.certificates)
      typed.size
    }.sum
    assert(certs > 0, "no recorded Conway body has a certificate")
  }

  /** The Conway bodies of the reference's decoding corpora, with their pool registrations. */
  private lazy val referenceBodies: Vector[(String, Bytes)] =
    Vector(
      ReferenceCorpus.Corpus.ConwayDecoding,
      ReferenceCorpus.Corpus.PoolRelays,
      ReferenceCorpus.Corpus.UrlLength,
      ReferenceCorpus.Corpus.DefiniteText,
      ReferenceCorpus.Corpus.DefiniteBytes
    ).flatMap(c => ReferenceCorpus.load(c).fold(e => fail(e.toString), identity))
      .filter(c => c.era == Era.Conway && c.kind == ReferenceCorpus.Kind.Body)
      .map(c => c.id -> c.raw)

  test(
    "every Conway pool registration, recorded and of the reference's corpora, reads at the state's types in the layout of each build"
  ) {
    val all = bodies.map(raw => raw.hex.take(16) -> raw) ++ referenceBodies
    val registrations = all.map { (what, raw) =>
      val layouts = Vector(StateLayout.Node11_1, StateLayout.Leios)
      FlatTxBody.decode(raw, Era.Conway) match
        case Left(e) =>
          // what the flat decoder refuses, the era decoder refuses with its error, in either layout
          layouts.foreach(l =>
            assertEquals(ConwayTxBody.decode(raw, l).map(_ => ()), Left(EraTxError.Decode(e)), what)
          )
          0
        case Right(flat) =>
          val pools = flat.certificates.collect { case Certificate.RegisterPool(p) => p }
          layouts.zip(Vector(PoolParamsBlsKey.Node11_1, PoolParamsBlsKey.Leios(None))).foreach {
            (layout, key) =>
              val c = ConwayTxBody.decode(raw, layout).fold(e => fail(s"$what: $e"), identity)
              val certs = body.certs[TxLevel.Top](c).toVector
              // each certificate reads back as the flat one, a pool's parameters with their bytes
              assertEquals(certs.map(ConwayTxCert.toStored), flat.certificates, what)
              val params = certs.flatMap(body.certsOf.getRegPoolTxCert)
              assertEquals(params.size, pools.size, what)
              params.zip(pools).foreach { (typed, p) =>
                // the 11.1 build's parameters have no voting key's place; the Leios build's read
                // none in a Conway body (`decodeStakePoolParamsFlat`, 9df9f98c `:746-762`)
                assertEquals(typed.blsKey, key, what)
                assertEquals(typed.id.bytes, p.operator, what)
                assertEquals(typed.vrf.bytes, p.vrfKeyHash, what)
                assertEquals((typed.pledge.value, typed.cost.value), (p.pledge, p.cost), what)
                assertEquals(typed.margin.rational, p.margin, what)
                assertEquals(
                  (typed.accountAddress.networkId.wire, typed.accountAddress.id.credential),
                  (
                    p.rewardAccount.network,
                    Convert.credential[Staking](p.rewardAccount.credential).toOption.get
                  ),
                  what
                )
                assertEquals(typed.owners.toSet.map(_.bytes), p.owners, what)
                assertEquals(typed.relays, p.relays, what)
                assertEquals(typed.metadata.map(_.bytes), p.metadata, what)
                assertEquals(
                  typed.metadata.map(_.hash),
                  p.metadata
                    .map(PoolParams.metadataHash(_)(using ByteStrings.Chunked).toOption.get),
                  what
                )
              }
          }
          pools.size
    }
    // the recorded blocks' bodies and the corpora's Conway bodies, and the pool registrations of
    // those the decoder accepts
    assertEquals((all.size, registrations.sum), (396, 32))
  }

  private def node(hex: String) =
    (Bytes.fromHex(hex): Either[Bytes.HexError | LedgerDecodeError, Bytes])
      .flatMap(CborRead.decode(_))
      .toOption
      .get

  test(
    "Conway's decoder reads no genesis delegation or instantaneous rewards, so neither projects"
  ) {
    val genesis = node(s"8405581c${"11" * 28}581c${"22" * 28}5820${"33" * 32}")
    val mir = node("82068200a0")
    Vector(
      genesis -> LedgerDecodeError.Retired.GenesisDelegation,
      mir -> LedgerDecodeError.Retired.MoveInstantaneousRewards
    ).foreach { (n, retired) =>
      val refused = LedgerDecodeError.RetiredCertificate(retired)
      assertEquals(Certificate.decode(n, Era.Conway), Left(refused))
      assertEquals(ConwayTxCert.decode(n, StateLayout.Node11_1), Left(EraTxError.Decode(refused)))
      // Babbage's decoder reads them, and the projection into Conway refuses them
      assert(Certificate.decode(n, Era.Babbage).isRight)
    }
  }

  test("Conway's decoder reads no protocol-parameter update, so none projects") {
    // `{0: [input], 1: [], 2: 0, 6: [{}, 0]}`
    val withUpdate = Bytes.fromHex(s"a400818258${"20"}${"44" * 32}00018002000682a000").toOption.get
    val flat = FlatTxBody.decode(withUpdate, Era.Conway)
    assert(flat.isLeft, flat)
    assertEquals(
      ConwayTxBody.decode(withUpdate, StateLayout.Node11_1).left.toOption,
      flat.left.toOption.map(EraTxError.Decode(_))
    )
  }

  // a script credential, a key credential, a pool and the DRep `AlwaysAbstain`
  private val s = "51" * 28
  private val k = "4b" * 28
  private val p = "50" * 28
  private val script = s"8201581c$s"
  private val key = s"8200581c$k"
  private val pool = s"581c$p"
  private val abstain = "8102"

  test("each Conway certificate reads into its constructor with the reference's witnesses") {
    val sh = Bytes.fromHex(s).toOption.get
    val kh = Bytes.fromHex(k).toOption.get
    val ph = Bytes.fromHex(p).toOption.get
    val pk = KeyHash.fromBytes[StakePool](ph).toOption.get
    def cred[R <: KeyRole]: Credential[R] =
      Credential.ScriptHashObj[R](ScriptHash.fromBytes(sh).toOption.get)
    def keyCred[R <: KeyRole]: Credential[R] =
      Credential.KeyHashObj[R](KeyHash.fromBytes[R](kh).toOption.get)
    val drep = xyz.kd5ujc.scarab.ledger.state.DRep.DRepAlwaysAbstain
    import ConwayTxCert.*
    import ConwayDelegCert.*
    import ConwayGovCert.*
    // (bytes, the certificate, its script witness, its key witness), from
    // `getScriptWitnessConwayTxCert` and `getVKeyWitnessConwayTxCert` (`Conway/TxCert.hs:763-804`,
    // cardano-ledger f649f975); the pool registration (tag 3) is checked below
    val cases = Vector(
      (s"8200$script", ConwayTxCertDeleg(ConwayRegCert(cred, None)), None, None),
      (s"8307${script}00", ConwayTxCertDeleg(ConwayRegCert(cred, Some(Coin(0)))), Some(sh), None),
      (s"8201$script", ConwayTxCertDeleg(ConwayUnRegCert(cred, None)), Some(sh), None),
      (s"8308${script}00", ConwayTxCertDeleg(ConwayUnRegCert(cred, Some(Coin(0)))), Some(sh), None),
      (
        s"8302$script$pool",
        ConwayTxCertDeleg(ConwayDelegCert(cred, Delegatee.DelegStake(pk))),
        Some(sh),
        None
      ),
      (
        s"8309$script$abstain",
        ConwayTxCertDeleg(ConwayDelegCert(cred, Delegatee.DelegVote(drep))),
        Some(sh),
        None
      ),
      (
        s"840a$script$pool$abstain",
        ConwayTxCertDeleg(ConwayDelegCert(cred, Delegatee.DelegStakeVote(pk, drep))),
        Some(sh),
        None
      ),
      (
        s"840b$script${pool}00",
        ConwayTxCertDeleg(ConwayRegDelegCert(cred, Delegatee.DelegStake(pk), Coin(0))),
        Some(sh),
        None
      ),
      (
        s"840c$script${abstain}00",
        ConwayTxCertDeleg(ConwayRegDelegCert(cred, Delegatee.DelegVote(drep), Coin(0))),
        Some(sh),
        None
      ),
      (
        s"850d$script$pool${abstain}00",
        ConwayTxCertDeleg(ConwayRegDelegCert(cred, Delegatee.DelegStakeVote(pk, drep), Coin(0))),
        Some(sh),
        None
      ),
      (
        s"8304${pool}0a",
        ConwayTxCertPool(PoolCert.RetirePool(pk, EpochNo.fromUnsigned(10))),
        None,
        Some(ph)
      ),
      (
        s"830e$script$key",
        ConwayTxCertGov(
          ConwayAuthCommitteeHotKey(cred, keyCred)
        ),
        Some(sh),
        None
      ),
      (
        s"830e$key$script",
        ConwayTxCertGov(
          ConwayAuthCommitteeHotKey(keyCred, cred)
        ),
        None,
        Some(kh)
      ),
      (
        s"830f${script}f6",
        ConwayTxCertGov(ConwayResignCommitteeColdKey(cred, None)),
        Some(sh),
        None
      ),
      (s"8410${script}00f6", ConwayTxCertGov(ConwayRegDRep(cred, Coin(0), None)), Some(sh), None),
      (s"8311${script}00", ConwayTxCertGov(ConwayUnRegDRep(cred, Coin(0))), Some(sh), None),
      (s"8312${script}f6", ConwayTxCertGov(ConwayUpdateDRep(cred, None)), Some(sh), None)
    )
    val c = EraTxCert.conway
    // `[3, operator, vrf, pledge, cost, margin, reward account, owners, relays, metadata]`
    val regPool = node(
      s"8a03$pool${"5820" + "76" * 32}0000d81e820001581de0${"72" * 28}8080f6"
    )
    ConwayTxCert.decode(regPool, StateLayout.Node11_1) match
      case Right(cert @ ConwayTxCertPool(PoolCert.RegPool(params))) =>
        assertEquals(params.id.bytes, ph)
        assertEquals(params.vrf.bytes, Bytes.fromHex("76" * 32).toOption.get)
        assertEquals(params.blsKey, PoolParamsBlsKey.Node11_1)
        assertEquals(params.margin.rational, Rational(0, 1))
        assertEquals(params.accountAddress.networkId, Network.Testnet)
        assertEquals(params.relays, Bytes.empty)
        assertEquals(params.metadata, None)
        assertEquals(c.getVKeyWitnessTxCert(cert), Some(ph))
        assertEquals(c.getScriptWitnessTxCert(cert), None)
        assertEquals(c.getRegPoolTxCert(cert), Some(params))
      case other => fail(s"not a pool registration: $other")
    // in the Leios build's layout, the same parameters with the voting key's place empty
    assertEquals(
      ConwayTxCert
        .decode(regPool, StateLayout.Leios)
        .toOption
        .flatMap(c.getRegPoolTxCert)
        .map(_.blsKey),
      Some(PoolParamsBlsKey.Leios(None))
    )
    assertEquals(cases.map(_._1.take(4)).distinct.size + 1, 17, "every Conway form but tag 3")
    cases.foreach { (hex, expected, scriptWitness, keyWitness) =>
      val n = node(hex)
      val cert = ConwayTxCert.decode(n, StateLayout.Node11_1)
      assertEquals(cert, Right(expected), hex)
      assertEquals(c.getScriptWitnessTxCert(expected), scriptWitness, hex)
      assertEquals(c.getVKeyWitnessTxCert(expected), keyWitness, hex)
      // and the script witness is the one the flat rules ask for
      assertEquals(
        Certificate.decode(n, Era.Conway).map(Purpose.certificateScript),
        Right(scriptWitness),
        hex
      )
    }
  }

  test("each Conway certificate builder makes the certificate its bytes read as") {
    val sh = Bytes.fromHex(s).toOption.get
    val kh = Bytes.fromHex(k).toOption.get
    val ph = Bytes.fromHex(p).toOption.get
    val pk = KeyHash.fromBytes[StakePool](ph).toOption.get
    def cred[R <: KeyRole]: Credential[R] =
      Credential.ScriptHashObj[R](ScriptHash.fromBytes(sh).toOption.get)
    def keyCred[R <: KeyRole]: Credential[R] =
      Credential.KeyHashObj[R](KeyHash.fromBytes[R](kh).toOption.get)
    val drep = xyz.kd5ujc.scarab.ledger.state.DRep.DRepAlwaysAbstain
    val hot = keyCred[HotCommittee]
    val c = EraTxCert.conway
    val built = Vector(
      s"8200$script" -> c.mkRegTxCert(cred),
      s"8307${script}05" -> c.mkRegDepositTxCert(cred, Coin(5)),
      s"8201$script" -> c.mkUnRegTxCert(cred),
      s"8308${script}05" -> c.mkUnRegDepositTxCert(cred, Coin(5)),
      s"8302$script$pool" -> c.mkDelegStakeTxCert(cred, pk),
      s"8309$script$abstain" -> c.mkDelegTxCert(cred, Delegatee.DelegVote(drep)),
      s"840b$script${pool}05" -> c.mkRegDepositDelegTxCert(cred, Delegatee.DelegStake(pk), Coin(5)),
      s"8304${pool}0a" -> c.mkRetirePoolTxCert(pk, EpochNo.fromUnsigned(10)),
      s"830e$script$key" -> c.mkAuthCommitteeHotKeyTxCert(cred, hot),
      s"830f${script}f6" -> c.mkResignCommitteeColdTxCert(cred, None),
      s"8410${script}05f6" -> c.mkRegDRepTxCert(cred, Coin(5), None),
      s"8311${script}05" -> c.mkUnRegDRepTxCert(cred, Coin(5)),
      s"8312${script}f6" -> c.mkUpdateDRepTxCert(cred, None)
    )
    built.foreach((hex, cert) =>
      assertEquals(ConwayTxCert.decode(node(hex), StateLayout.Node11_1), Right(cert), hex)
    )
    // and each reader reads back what its builder took, and nothing from another form
    assertEquals(
      c.getRegDepositTxCert(c.mkRegDepositTxCert(cred, Coin(5))),
      Some((cred[Staking], Coin(5)))
    )
    assertEquals(c.getRegDepositTxCert(c.mkRegTxCert(cred)), None)
    assertEquals(c.getRegTxCert(c.mkRegDepositTxCert(cred, Coin(5))), None)
    assertEquals(c.getUnRegTxCert(c.mkUnRegDepositTxCert(cred, Coin(5))), None)
    assertEquals(c.getDelegStakeTxCert(c.mkDelegTxCert(cred, Delegatee.DelegVote(drep))), None)
    assertEquals(c.getDelegStakeTxCert(c.mkDelegStakeTxCert(cred, pk)), Some((cred[Staking], pk)))
    assertEquals(
      c.getRetirePoolTxCert(c.mkRetirePoolTxCert(pk, EpochNo.fromUnsigned(10))),
      Some((pk, EpochNo.fromUnsigned(10)))
    )
    assertEquals(c.getUnRegDRepTxCert(c.mkRegDRepTxCert(cred, Coin(5), None)), None)
    assertEquals(
      c.getUpdateDRepTxCert(c.mkUpdateDRepTxCert(cred, None)),
      Some((cred[DRepRole], None))
    )
  }

  test("Conway's voting and proposing purposes build and read back in each form, and only those") {
    val scripts = EraScript.conway
    val ix = Unsigned.from[Unsigned.Width.W32](BigInt(3)).get
    val voter = xyz.kd5ujc.scarab.ledger.Voter.Pool(Bytes.fromHex(p).toOption.get)
    val voting = scripts.mkVotingPurpose[AsIx](AsIx(ix))
    assertEquals(voting, ConwayPlutusPurpose.ConwayVoting[AsIx](AsIx(ix)))
    assertEquals(scripts.toVotingPurpose[AsIx](voting), Some(AsIx(ix)))
    assertEquals(scripts.toProposingPurpose[AsIx](voting), None)
    val proposing = scripts.mkProposingPurpose[AsIx](AsIx(ix))
    assertEquals(proposing, ConwayPlutusPurpose.ConwayProposing[AsIx](AsIx(ix)))
    assertEquals(scripts.toProposingPurpose[AsIx](proposing), Some(AsIx(ix)))
    assertEquals(scripts.toVotingPurpose[AsIx](proposing), None)
    val spending = ConwayPlutusPurpose.ConwaySpending[AsIx](AsIx(ix))
    assertEquals(scripts.toVotingPurpose[AsIx](spending), None)
    assertEquals(scripts.toProposingPurpose[AsIx](spending), None)
    val item = scripts.mkVotingPurpose[AsItem](AsItem(voter))
    assertEquals(scripts.toVotingPurpose[AsItem](item), Some(AsItem(voter)))
    // the body's scripts are the class its outputs name
    assert(EraTxBody.conway.scriptsOf eq EraTxBody.conway.outputsOf.scripts)
  }

  test(
    "Conway's spending, minting, certifying and withdrawing purposes build and read back, and only those"
  ) {
    // Alonzo's builders of Conway's instance (`Conway/Scripts.hs:119-137`, cardano-ledger f649f975)
    import ConwayPlutusPurpose.*
    val scripts = EraScript.conway
    val ix = Unsigned.from[Unsigned.Width.W32](BigInt(2)).get
    val all = Vector(
      scripts.mkSpendingPurpose[AsIx](AsIx(ix)),
      scripts.mkMintingPurpose[AsIx](AsIx(ix)),
      scripts.mkCertifyingPurpose[AsIx](AsIx(ix)),
      scripts.mkWithdrawingPurpose[AsIx](AsIx(ix)),
      scripts.mkVotingPurpose[AsIx](AsIx(ix)),
      scripts.mkProposingPurpose[AsIx](AsIx(ix))
    )
    assertEquals(
      all,
      Vector(
        ConwaySpending[AsIx](AsIx(ix)),
        ConwayMinting[AsIx](AsIx(ix)),
        ConwayCertifying[AsIx](AsIx(ix)),
        ConwayWithdrawing[AsIx](AsIx(ix)),
        ConwayVoting[AsIx](AsIx(ix)),
        ConwayProposing[AsIx](AsIx(ix))
      )
    )
    all.foreach { purpose =>
      val read = Vector(
        scripts.toSpendingPurpose[AsIx](purpose),
        scripts.toMintingPurpose[AsIx](purpose),
        scripts.toCertifyingPurpose[AsIx](purpose),
        scripts.toWithdrawingPurpose[AsIx](purpose)
      )
      assertEquals(read.indexWhere(_.isDefined), if purpose.tag < 4 then purpose.tag else -1)
      assertEquals(read.flatten, if purpose.tag < 4 then Vector(AsIx(ix)) else Vector.empty)
    }
    val in = xyz.kd5ujc.scarab.ledger.TxIn(Bytes.fromHex("33" * 32).toOption.get, BigInt(1))
    val spending = scripts.mkSpendingPurpose[AsItem](AsItem(in))
    assertEquals(scripts.toSpendingPurpose[AsItem](spending), Some(AsItem(in)))
  }

  test("a registration whose VRF key hash is 31 bytes is refused by the certificate's reader") {
    // `decodeStakePoolParamsFlat` reads `sppVrf` as a 32-byte hash (`State/StakePool.hs:578-589`,
    // cardano-ledger f649f975): the state's VRFVerKeyHash is never given another length
    val n = node(s"8a03$pool${"581f" + "76" * 31}0000d81e820001581de0${"72" * 28}8080f6")
    val refused = LedgerDecodeError.ByteLength(DecodeField.PoolVrf, 32, 31)
    assertEquals(Certificate.decode(n, Era.Conway), Left(refused))
    StateLayout.values.foreach(layout =>
      assertEquals(
        ConwayTxCert.decode(n, layout),
        Left(EraTxError.Decode(refused)),
        layout.toString
      )
    )
  }

  test("pool registrations are equal when their relays mean the same, as the reference compares") {
    // the empty relay list as `80`, and as `98 00`: one `StrictSeq StakePoolRelay`, so one
    // `StakePoolParams` under the derived `Eq` (`State/StakePool.hs:424-435`, cardano-ledger
    // f649f975), while the bytes kept for each differ
    def registration(relays: String) =
      node(s"8a03$pool${"5820" + "76" * 32}0000d81e820001581de0${"72" * 28}80${relays}f6")
    val flat = Vector("80", "9800").map(r => Certificate.decode(registration(r), Era.Conway))
    flat.foreach(f => assert(f.isRight, f))
    assertNotEquals(flat(0), flat(1), "the flat certificates keep the relay bytes they read")
    val typed =
      Vector("80", "9800").map(r => ConwayTxCert.decode(registration(r), StateLayout.Node11_1))
    assertEquals(typed(0), typed(1))
    val params = typed.flatMap(_.toOption).flatMap(EraTxCert.conway.getRegPoolTxCert)
    assertEquals(params.size, 2)
    assertEquals(params(0).hashCode, params(1).hashCode)
    // a registration with another relay is another certificate
    val other = ConwayTxCert.decode(registration(s"81${"8400" + "f6" * 3}"), StateLayout.Node11_1)
    assert(other.isRight, other)
    assertNotEquals(other, typed(0))
  }

  test("every recorded Conway auxiliary data reads, keeping the bytes its hash is over") {
    val aux = transactions.flatMap(_.auxiliaryData)
    assert(aux.nonEmpty, "no recorded Conway transaction has auxiliary data")
    aux.foreach { raw =>
      val a = ConwayTxAuxData.decode(raw).toOption.get
      assertEquals(a.raw, raw)
      // a metadata map: one entry per label it has
      CborRead.decode(raw).toOption.get.value match
        case xyz.kd5ujc.scarab.codec.Value.Map(kvs) =>
          assertEquals(EraTxAuxData.conway.metadata(a).size, kvs.size, raw.hex)
        case _ => ()
    }
  }

  test("Conway's auxiliary data reads in each of its three forms") {
    val c = EraTxAuxData.conway
    val label = Unsigned.from[Unsigned.Width.W64](BigInt(674)).get
    // `{674: 7}`: metadata alone
    val byMap = ConwayTxAuxData.decode(Bytes.fromHex("a11902a207").toOption.get).toOption.get
    assertEquals(c.metadata(byMap).toVector, Vector(label -> Metadatum.Num(7)))
    // `[{674: 7}, [[0, h]]]`: metadata and native scripts
    val byList =
      ConwayTxAuxData.decode(Bytes.fromHex(s"82a11902a207818200581c$k").toOption.get).toOption.get
    assertEquals(c.metadata(byList).toVector, Vector(label -> Metadatum.Num(7)))
    assertEquals(
      c.nativeScripts(byList)
        .map(t => EraScript.conway.hashScript(EraScript.conway.fromNativeScript(t))),
      Vector(
        FlatScript
          .native(node(s"8200581c$k"), FlatNativeScript.Form.Timelock)(using ByteStrings.Definite)
          .toOption
          .get
          .hash
      )
    )
    // `259({0: {674: 7}, 2: [h'0102'], 4: [h'03']})`: metadata and Plutus V1 and V3 scripts
    val byTag = ConwayTxAuxData
      .decode(Bytes.fromHex("d90103a300a11902a207028142010204814103").toOption.get)
      .toOption
      .get
    assertEquals(c.metadata(byTag).toVector, Vector(label -> Metadatum.Num(7)))
    assertEquals(
      c.plutusScripts(byTag)
        .toVector
        .map((language, scripts) => language -> scripts.toVector.map(_.hex)),
      Vector(
        xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1 -> Vector("0102"),
        xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3 -> Vector("03")
      )
    )
    // a record whose head is detached, `[_ ]` then its two items, as the flat decoder reads it
    // (`RecD`), with empty metadata and no scripts
    val detached = ConwayTxAuxData.decode(Bytes.fromHex("9fffa080").toOption.get)
    assert(
      xyz.kd5ujc.scarab.ledger.AuxiliaryScripts
        .read(Bytes.fromHex("9fffa080").toOption.get, Era.Conway, 9)(using ByteStrings.Definite)
        .isRight
    )
    assertEquals(detached.map(c.metadata(_).size), Right(0))
    // what Conway's decoder refuses, the era's decoder refuses with the same error: key 6 of the
    // tagged form is no field
    val unknown = Left(
      EraTxError.Decode(
        LedgerDecodeError.UnknownTag(xyz.kd5ujc.scarab.ledger.DecodeField.AuxiliaryField, 6)
      )
    )
    assertEquals(ConwayTxAuxData.decode(Bytes.fromHex("d90103a10680").toOption.get), unknown)
  }

  test("every recorded Conway transaction reads with the flat transaction's size and flag") {
    val tx = EraTx.conway
    given tx.bodyOf.Level[TxLevel.Top] = tx.bodyOf.top
    transactions.foreach { t =>
      val flat = xyz.kd5ujc.scarab.ledger.Tx.fromBlock(t, Era.Conway).toOption.get
      val c = ConwayTx.decode(t, StateLayout.Node11_1).toOption.get
      assertEquals(tx.sizeTxForFeeCalculation[TxLevel.Top](c), flat.size)
      assertEquals(tx.isValid(c), IsPhase2Valid.of(flat.isValid))
      assertEquals(tx.bodyOf.id[TxLevel.Top](tx.body[TxLevel.Top](c)), flat.body.id)
      assertEquals(tx.auxData[TxLevel.Top](c).map(_.raw), flat.auxiliary)
      // the Alonzo classes through the transaction's: collateral, redeemers, Plutus scripts
      assertEquals(
        tx.bodyOf.collateralInputs(tx.body[TxLevel.Top](c)).toVector,
        flat.body.collateral.sorted
      )
      assertEquals(tx.witsOf.rdmrsTxWits(tx.wits[TxLevel.Top](c)).raw, flat.witnesses.redeemersRaw)
      assertEquals(tx.witsOf.scriptsOf.eraMaxLanguage, xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3)
    }
  }

  test("a Conway output written back to the store is the output it was read from") {
    val outputs = bodies.flatMap(FlatTxBody.decode(_, Era.Conway).toOption.get.outputs)
    val withScripts = Vector(
      storedWith("8201420102"),
      storedWith("8202420102"),
      storedWith(s"82008200581c$k"),
      storedWith("8203420102")
    )
    assert(outputs.nonEmpty)
    (outputs ++ withScripts).foreach { out =>
      assertEquals(ConwayTxOut.fromStored(out).map(ConwayTxOut.toStored), Right(out), out.raw.hex)
    }
  }

  test("outputs are equal when their fields are, whatever the encoding they arrived as") {
    // coin 1000000 in its shortest form and in eight bytes: one `BabbageTxOut` under the derived
    // `Eq` (`Babbage/TxOut.hs:318-320`, cardano-ledger f649f975), two encodings kept
    def stored(coin: String) =
      xyz.kd5ujc.scarab.ledger.TxOut
        .decode(
          node(s"a200581d61${"ab" * 28}01$coin"),
          xyz.kd5ujc.scarab.ledger.Address.Mode.Rigorous,
          xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap,
          ScriptKind.newestKnown
        )(using ByteStrings.Definite)
        .toOption
        .get
    val short = stored("1a000f4240")
    val long = stored("1b00000000000f4240")
    assertNotEquals(short.raw, long.raw)
    val (a, b) = (ConwayTxOut.fromStored(short), ConwayTxOut.fromStored(long))
    assertEquals(a, b)
    assertEquals(a.map(_.hashCode), b.map(_.hashCode))
    // each keeps the bytes it arrived as
    assertEquals(a.map(ConwayTxOut.toStored).map(_.raw), Right(short.raw))
    assertEquals(b.map(ConwayTxOut.toStored).map(_.raw), Right(long.raw))
    assertNotEquals(ConwayTxOut.fromStored(stored("1a000f4241")), a)
  }

  /** The typed action agrees with the flat one the decoder read: a parameter change field by field
    * ([[sameUpdate]]), any other action as the flat action it reads back as.
    */
  private def agrees(typed: GovAction[Conway], flat: FlatGovAction): Unit = (typed, flat) match
    case (
          GovAction.ParameterChange(parent, update, guardrails),
          FlatGovAction.ParameterChange(flatParent, flatUpdate, flatGuardrails)
        ) =>
      assertEquals(parent.map(_.unGovPurposeId), flatParent)
      assertEquals(guardrails.map(_.bytes), flatGuardrails)
      sameUpdate(update, flatUpdate)
    case (other, _) => assertEquals(actionOf(other), flat)

  private val conwayPParams = summon[ConwayEraPParams[Conway]]

  /** The typed update sets as many fields as the flat one, and applied to the same parameters gives
    * the parameters the flat update gives: each through the existing flat code (`applyTo`) and the
    * record's own application, neither of them the conversion the update was read by. As Plutus
    * data it is what the flat path puts in a V3 script context.
    */
  private def sameUpdate(typed: PParamsUpdate[Conway], flat: ConwayParamUpdate): Unit =
    assertEquals(
      ToPlutusData[PParamsUpdate[Conway]].toPlutusData(typed),
      xyz.kd5ujc.scarab.ledger.ScriptContexts.changedParameters(flat)
    )
    val set = typed.hkd.productIterator.count {
      case Some(_) => true
      case _       => false
    }
    assertEquals(set, flat.params.size)
    val base = xyz.kd5ujc.scarab.ledger.TestLedger.params(10)
    val typedBase = PParams[Conway](ConwayPParams.fromStored(base).toOption.get)
    assertEquals(
      ConwayPParams.toStored(conwayPParams.applyPPUpdates(typedBase, typed).hkd, base),
      flat.applyTo(base)
    )

  /** The flat action an action other than a parameter change reads back as. */
  private def actionOf(a: GovAction[Conway]): FlatGovAction = a match
    case GovAction.ParameterChange(_, _, _) => fail("a parameter change is compared by its fields")
    case GovAction.HardForkInitiation(parent, v) =>
      FlatGovAction.HardFork(parent.map(_.unGovPurposeId), v.pvMajor.value, v.pvMinor.value)
    case GovAction.TreasuryWithdrawals(withdrawals, guardrails) =>
      FlatGovAction.TreasuryWithdrawals(
        withdrawals.toVector.map((a, c) => a -> c.value),
        guardrails.map(_.bytes)
      )
    case GovAction.NoConfidence(parent) => FlatGovAction.NoConfidence(parent.map(_.unGovPurposeId))
    case GovAction.UpdateCommittee(parent, removed, added, threshold) =>
      FlatGovAction.UpdateCommittee(
        parent.map(_.unGovPurposeId),
        removed.map(Convert.back(_)),
        added.map((c, e) => Convert.back(c) -> e.toBigInt),
        threshold.rational
      )
    case GovAction.NewConstitution(parent, constitution) =>
      FlatGovAction.NewConstitution(
        parent.map(_.unGovPurposeId),
        constitution.script,
        constitution.anchor
      )
    case GovAction.InfoAction() => FlatGovAction.Info

  test("the recorded Conway bodies carry votes and proposals of every action") {
    val read = bodies.map(raw => ConwayTxBody.decode(raw, StateLayout.Node11_1).toOption.get)
    val proposals = read.flatMap(body.proposalProcedures[TxLevel.Top](_).toVector)
    val votes = read.map(body.votingProcedures[TxLevel.Top](_).unVotingProcedures.size).sum
    assert(proposals.nonEmpty, "no recorded Conway body has a proposal")
    assert(votes > 0, "no recorded Conway body has a vote")
    // each of the seven actions is among them, so every action's fields are compared above
    val kinds = proposals.map(_.pProcGovAction.ordinal).toSet
    assertEquals(kinds, (0 to 6).toSet)
  }

  test("who may vote on each recorded action, as the flat rule decides it") {
    // `isCommitteeVotingAllowed`, `isDRepVotingAllowed` and `isStakePoolVotingAllowed`
    // (`Conway/Governance/Internal.hs:350-532`, cardano-ledger f649f975) on the era types, against
    // the flat rule's, on the recorded proposals of each of the seven actions
    given ConwayEraPParams[Conway] = ConwayEraPParams.conway
    val read = bodies.map(raw => ConwayTxBody.decode(raw, StateLayout.Node11_1).toOption.get)
    // and two crafted parameter changes: of the maximum block body size, of the security group,
    // and of the DReps' activity, of none
    val crafted = Vector("8400f6a1021903e8f6", "8400f6a1182005f6").map(action =>
      proposal(action)._2.fold(e => fail(s"$action: $e"), identity)
    )
    val proposals = read.flatMap(body.proposalProcedures[TxLevel.Top](_).toVector) ++ crafted
    proposals.foreach { p =>
      val (typed, flat) = (p.pProcGovAction, p.read.action)
      assertEquals(
        (
          VotingThreshold.isCommitteeVotingAllowed(
            xyz.kd5ujc.scarab.ledger.state.EpochNo.fromUnsigned(0),
            xyz.kd5ujc.scarab.ledger.state
              .CommitteeState(xyz.kd5ujc.scarab.ledger.state.Sorted.empty),
            typed
          ),
          VotingThreshold.isDRepVotingAllowed(typed),
          VotingThreshold.isStakePoolVotingAllowed(typed)
        ),
        (
          xyz.kd5ujc.scarab.ledger.Ratify.isCommitteeVotingAllowed(flat),
          xyz.kd5ujc.scarab.ledger.Ratify.isDRepVotingAllowed(flat),
          xyz.kd5ujc.scarab.ledger.Ratify.isPoolVotingAllowed(flat)
        ),
        s"$typed"
      )
    }
    val poolOnChanges = proposals.collect {
      case p if p.pProcGovAction.ordinal == 0 =>
        VotingThreshold.isStakePoolVotingAllowed(p.pProcGovAction)
    }.toSet
    assertEquals(
      poolOnChanges,
      Set(true, false),
      "parameter changes with and without the security group"
    )
  }

  // `[deposit, return account, action, anchor]`, the account a Shelley reward account
  private val (r1, r2) = (s"581de0${"a1" * 28}", s"581de0${"a2" * 28}")
  private val anchor = "826161" + "5820" + "cc" * 32
  private val conwayVersion = xyz.kd5ujc.scarab.ledger.DecoderVersion.of(Era.Conway)

  /** The proposal of `action` as the flat decoder reads it, and as Conway's type reads that. */
  private def proposal(
      action: String,
      deposit: String = "00"
  ): (Either[LedgerDecodeError, Proposal], Either[EraTxError, ProposalProcedure[Conway]]) =
    val flat = Proposal.decode(node(s"84$deposit$r1$action$anchor"), conwayVersion)
    (flat, flat.leftMap(EraTxError.Decode(_)).flatMap(ConwayProcedures.proposal))

  test("proposals are equal when they mean the same, whatever the order of their withdrawals") {
    // treasury withdrawals are a `Map AccountAddress Coin` (`Procedures.hs:826-834`, cardano-ledger
    // f649f975), so one proposal
    def withdrawing(ws: String) = proposal("8302" + ws + "f6")
    val (sorted, reversed) = (withdrawing(s"a2${r1}01${r2}02"), withdrawing(s"a2${r2}02${r1}01"))
    assertNotEquals(sorted._1, reversed._1, "the flat proposals keep the entries in the order read")
    assertEquals(sorted._2, reversed._2)
    assertEquals(sorted._2.map(_.hashCode), reversed._2.map(_.hashCode))
    assertNotEquals(withdrawing(s"a2${r1}01${r2}03")._2, sorted._2)
  }

  test("each action reads at the reference's types, at the bounds of each") {
    val id = "825820" + "dd" * 32 + "03"
    def parent[P <: GovActionPurpose]: Option[GovPurposeId[P]] =
      Some(GovPurposeId[P](GovActionId(Bytes.fromHex("dd" * 32).toOption.get, 3)))
    val cold = Credential.KeyHashObj[ColdCommittee](
      KeyHash.fromBytes(Bytes.fromHex(k).toOption.get).toOption.get
    )
    // the bounds: a parameter change setting rho to one, guarded by a script; the highest version
    // Conway's action reads, 12, and the highest minor version; a member until the last epoch, and
    // a threshold of one
    val expected: Vector[(String, GovAction[Conway])] = Vector(
      s"8400${id}a10ad81e820101581c$s" -> GovAction.ParameterChange(
        parent,
        PParamsUpdate[Conway](
          ConwayPParams.emptyUpdate.copy(rho = THKD(UnitInterval.boundRational(Rational.one)))
        ),
        Some(ScriptHash.fromBytes(Bytes.fromHex(s).toOption.get).toOption.get)
      ),
      s"8301${id}820c1affffffff" -> GovAction.HardForkInitiation(
        parent,
        ProtVer(Version.lit(12), Unsigned.lit[Unsigned.Width.W32](0xffffffffL))
      ),
      s"8302a1${r2}1bfffffffffffffffff6" -> GovAction.TreasuryWithdrawals(
        Map(
          RewardAccount.decode(Bytes.fromHex(r2.drop(4)).toOption.get).toOption.get ->
            Coin(BigInt("18446744073709551615"))
        ),
        None
      ),
      s"8203$id" -> GovAction.NoConfidence(parent),
      s"8504f681$key" + s"a1${key}1bffffffffffffffff" + "d81e820101" -> GovAction
        .UpdateCommittee(
          None,
          Set(cold),
          Map(cold -> EpochNo.fromUnsigned(-1L)),
          UnitInterval.boundRational(Rational.one).get
        ),
      s"8305f682${anchor}f6" -> GovAction.NewConstitution(
        None,
        xyz.kd5ujc.scarab.ledger.Constitution(
          xyz.kd5ujc.scarab.ledger.Anchor("a", Bytes.fromHex("cc" * 32).toOption.get),
          None
        )
      ),
      "8106" -> GovAction.InfoAction()
    )
    expected.foreach { (action, rep) =>
      val (flat, typed) = proposal(action)
      assert(flat.isRight, s"$action: $flat")
      assertEquals(typed.map(_.pProcGovAction), Right(rep), action)
    }
  }

  test("what the typed proposal would refuse, the flat decoder refuses first, with its error") {
    // each bound the typed action checks, passed: the flat decoder refuses the proposal at that
    // field, and the typed path reports that refusal
    val h27 = s"581b${"51" * 27}"
    val key27 = s"8200581b${"4b" * 27}"
    val rho = "a10ad81e820101"
    val word32 = BigInt(1) << 32
    def bignum(e: LedgerDecodeError): Boolean = e match
      case LedgerDecodeError.Expected(
            DecodeField.MemberTerm,
            LedgerDecodeError.Shape.Unsigned,
            _
          ) =>
        true
      case _ => false
    Vector[(String, String, LedgerDecodeError => Boolean)](
      (
        "a ratio above one in the update",
        "8400f6a10ad81e820302f6",
        _ == LedgerDecodeError.RationalOutOfBounds(
          DecodeField.Rho,
          LedgerDecodeError.OutOfBounds.Reduced(Rational(3, 2))
        )
      ),
      (
        "a guardrails hash of 27 bytes on a change",
        s"8400f6$rho$h27",
        _ == LedgerDecodeError.ByteLength(DecodeField.PolicyHash, 28, 27)
      ),
      (
        "major version 13",
        "8301f6820d00",
        _ == LedgerDecodeError.NumberOutOfRange(DecodeField.Major, 13, 0, 12)
      ),
      (
        "minor version 2^32",
        "8301f6820c1b0000000100000000",
        _ == LedgerDecodeError.NumberOutOfRange(DecodeField.Minor, word32, 0, word32 - 1)
      ),
      (
        "a committee member's hash of 27 bytes",
        s"8504f680a1${key27}01d81e820101",
        _ == LedgerDecodeError.ByteLength(DecodeField.CommitteeMember, 28, 27)
      ),
      (
        "a member's term as a bignum, 2^64 (a CBOR unsigned integer is at most 2^64 - 1)",
        s"8504f680a1${key}c249010000000000000000d81e820101",
        bignum
      ),
      (
        "a threshold above one",
        "8504f680a0d81e820302",
        _ == LedgerDecodeError.RationalOutOfBounds(
          DecodeField.Quorum,
          LedgerDecodeError.OutOfBounds.Reduced(Rational(3, 2))
        )
      ),
      (
        "a guardrails hash of 27 bytes on withdrawals",
        s"8302a0$h27",
        _ == LedgerDecodeError.ByteLength(DecodeField.PolicyHash, 28, 27)
      )
    ).foreach { (what, action, expected) =>
      val (flat, typed) = proposal(action)
      flat match
        case Left(e) =>
          assert(expected(e), s"$what: $e")
          assertEquals(typed, Left(EraTxError.Decode(e)), what)
        case Right(p) => fail(s"$what: the flat decoder accepts it: $p")
    }
  }

  test("parents are ordered as their action ids, as the derived Ord compares them") {
    def id(b: Int, ix: Int) = GovPurposeId[GovActionPurpose.HardFork](
      GovActionId(Bytes(Vector.fill(32)(b.toByte)), ix)
    )
    val ids = Vector(id(2, 0), id(1, 5), id(1, 2))
    assertEquals(ids.sorted.map(_.unGovPurposeId), ids.map(_.unGovPurposeId).sorted)
    assertEquals(ids.sorted, Vector(id(1, 2), id(1, 5), id(2, 0)))
  }

  test("a parent is of its action's purpose") {
    assertEquals(
      compileErrors(
        "val p: GovPurposeId[GovActionPurpose.HardFork] = GovPurposeId[GovActionPurpose.Committee](null)"
      ).contains("Found"),
      true
    )
    assertEquals(
      compileErrors(
        "val p: GovPurposeId[GovActionPurpose.HardFork] = GovPurposeId[GovActionPurpose.HardFork](null)"
      ),
      ""
    )
  }

  test("a stored output whose kept script is not what its bytes read as is refused") {
    val out = storedWith("8202420102")
    val other = out.copy(scriptRef = out.scriptRef.map(_.copy(hash = Bytes(Vector.fill(28)(7)))))
    assertEquals(ConwayTxOut.fromStored(other), Left(EraTxError.StoredOutputDiffers))
    assertEquals(
      xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxOut.fromStored(other),
      Left(EraTxError.StoredOutputDiffers)
    )
    assert(ConwayTxOut.fromStored(out).isRight)
  }

  test(
    "an inline datum is read as data from a block or a transaction, and taken unread from the store"
  ) {
    // `[1, 24(h'f6')]`: the bytes of `null`, which are not data (#721)
    val notData = Bytes(Vector(0xf6.toByte))
    val refusal = xyz.kd5ujc.scarab.plutus.PlutusData
      .decode(notData)
      .swap
      .getOrElse(fail("null read as data"))
    val raw = Bytes.fromHex(s"a300581d61${"ab" * 28}011a000f4240028201d81841f6").toOption.get
    // a block's or a transaction's decoder refuses it (`DecCBOR (BinaryData era)`, `makeBinaryData`)
    assertEquals(
      xyz.kd5ujc.scarab.ledger.TxOut.decode(
        node(raw.hex),
        xyz.kd5ujc.scarab.ledger.Address.Mode.Rigorous,
        xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap,
        ScriptKind.newestKnown
      )(using ByteStrings.Definite),
      Left(LedgerDecodeError.PlutusData(DecodeField.InlineDatum, refusal))
    )
    // the store's output keeps it unread (the reference's `MemPack` of `BinaryData`)
    val stored = xyz.kd5ujc.scarab.ledger.TxOut
      .fromBytes(raw)(using ByteStrings.Chunked)
      .fold(e => fail(e.toString), identity)
    assertEquals(
      Datum.decoded(stored.datum),
      Left(LedgerDecodeError.PlutusData(DecodeField.InlineDatum, refusal))
    )
    val out = ConwayTxOut.fromStored(stored).fold(e => fail(e.toString), identity)
    assertEquals(
      EraTxOut.conway
        .datum(out)
        .match
          case Datum.Datum(binaryData)            => Some(binaryData.bytes)
          case Datum.NoDatum | Datum.DatumHash(_) => None
      ,
      Some(notData)
    )
    assertEquals(ConwayTxOut.toStored(out), stored)
    // read as data where it is asked for, its failure returned where the reference raises `error`
    assertEquals(EraTxOut.conway.data(out), Left(refusal))
    val undecodable: ContextError[Conway] = conway.ConwayContextError.BabbageContextError(
      BabbageContextError.AlonzoContextError(
        AlonzoContextError.Undecodable(xyz.kd5ujc.scarab.ledger.DataSite.InlineDatum, refusal)
      )
    )
    val source = xyz.kd5ujc.scarab.ledger.TxOutSource.TxOutFromOutput(0)
    assertEquals(
      conway.ConwayTxInfo.transTxOutV1[Conway](source, out)(using EraTxOut.conway),
      Left(undecodable)
    )
    assertEquals(
      BabbageTxInfo.transTxOutV2[Conway](source, out)(using EraTxOut.conway),
      Left(undecodable)
    )
  }

  test("an inline datum that reads as data is the same datum from a block and from the store") {
    // `[1, 24(h'00')]`: the integer 0
    val raw = Bytes.fromHex(s"a300581d61${"ab" * 28}011a000f4240028201d8184100").toOption.get
    val decoded = xyz.kd5ujc.scarab.ledger.TxOut
      .decode(
        node(raw.hex),
        xyz.kd5ujc.scarab.ledger.Address.Mode.Rigorous,
        xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap,
        ScriptKind.newestKnown
      )(using ByteStrings.Definite)
      .fold(e => fail(e.toString), identity)
    val datum = Datum.decoded(decoded.datum).fold(e => fail(e.toString), identity)
    assertEquals(Datum.toStored(datum), decoded.datum)
    val out = ConwayTxOut.fromStored(decoded).fold(e => fail(e.toString), identity)
    assertEquals(EraTxOut.conway.datum(out), datum)
    assertEquals(
      EraTxOut.conway.data(out).map(_.map(_.getPlutusData)),
      Right(Some(xyz.kd5ujc.scarab.plutus.PlutusData.I(0)))
    )
  }

  test("only BabbageTxOutRep.fromStored takes an inline datum's bytes unread") {
    // the unchecked constructors ask for the evidence `StoreRead`, which only `fromStored` makes
    assert(
      compileErrors("BinaryData.stored(Bytes(Vector(0xf6.toByte)))").contains("StoreRead"),
      "BinaryData.stored without the evidence"
    )
    assert(
      compileErrors("Datum.stored(xyz.kd5ujc.scarab.ledger.Datum.NoDatum)").contains("StoreRead"),
      "Datum.stored without the evidence"
    )
    assert(
      compileErrors("BabbageTxOutRep.StoreRead()").nonEmpty,
      "the evidence made outside BabbageTxOutRep"
    )
    assertEquals(
      compileErrors("BinaryData.makeBinaryData(Bytes(Vector(0xf6.toByte)))"),
      ""
    )
  }

  test("a submitted Conway transaction reads as its parts do from a block, in every form") {
    transactions.foreach { t =>
      val submitted = xyz.kd5ujc.scarab.ledger.Tx.envelope(t)
      assertEquals(
        ConwayTx.fromEnvelope(submitted, StateLayout.Node11_1),
        ConwayTx.decode(t, StateLayout.Node11_1)
      )
      // of three items, a valid transaction (`Alonzo/Tx.hs:541-553`, cardano-ledger f649f975)
      val aux = t.auxiliaryData.getOrElse(Bytes(Vector(0xf6.toByte)))
      val three = Bytes(Vector(0x83.toByte)) ++ t.body ++ t.witnesses ++ aux
      if t.isValid then
        assertEquals(
          ConwayTx.fromEnvelope(three, StateLayout.Node11_1),
          ConwayTx.decode(t, StateLayout.Node11_1)
        )
      // indefinite, as the flat decoder reads it
      val indefinite = Bytes(Vector(0x9f.toByte)) ++ submitted.drop(1) ++ Bytes(Vector(0xff.toByte))
      assertEquals(
        ConwayTx.fromEnvelope(indefinite, StateLayout.Node11_1).isRight,
        xyz.kd5ujc.scarab.ledger.Tx.fromEnvelope(indefinite, Era.Conway).isRight
      )
    }
  }

  // a map-form output with the reference script `scriptHex`, read by the flat output decoder
  private def storedWith(scriptHex: String): xyz.kd5ujc.scarab.ledger.TxOut =
    val script = Bytes.fromHex(scriptHex).toOption.get
    val wrapped =
      if script.size < 24 then f"d818${0x40 + script.size}%02x$scriptHex"
      else f"d81858${script.size}%02x$scriptHex"
    xyz.kd5ujc.scarab.ledger.TxOut
      .decode(
        node(s"a300581d61${"ab" * 28}011a000f424003$wrapped"),
        xyz.kd5ujc.scarab.ledger.Address.Mode.Rigorous,
        xyz.kd5ujc.scarab.ledger.TxOut.Forms.PairDatumHashOrMap,
        ScriptKind.newestKnown
      )(using ByteStrings.Definite)
      .toOption
      .get

  test("no Conway value that keeps its bytes has a Mirror to build it from other fields") {
    // a case class's derived `Mirror.ProductOf` builds one from any fields (`fromProduct`), so
    // these are plain classes: their only way in is their decoder
    Vector(
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxBody]]"
      ),
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxWits]]"
      ),
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxAuxData]]"
      ),
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTx]]"
      ),
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.TxDats]]"
      ),
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.Redeemers[Int]]]"
      )
    ).foreach(errs => assert(errs.contains("No given instance"), errs))
    // the same summon compiles for a case class
    assertEquals(
      compileErrors(
        "summon[scala.deriving.Mirror.ProductOf[xyz.kd5ujc.scarab.ledger.era.ValidityInterval]]"
      ),
      ""
    )
  }

  test("a certificate's credential is of its role: a staking credential is not a DRep's") {
    // `Credential Staking`, `DRepRole`, `ColdCommitteeRole` and `HotCommitteeRole`
    // (`Conway/TxCert.hs:172-214`, cardano-ledger f649f975)
    val asDRep = compileErrors(
      "def f(c: xyz.kd5ujc.scarab.ledger.state.Credential[xyz.kd5ujc.scarab.ledger.state.Staking]) = EraTxCert.conway.mkUnRegDRepTxCert(c, xyz.kd5ujc.scarab.ledger.state.Coin(0))"
    )
    assert(asDRep.contains("DRepRole"), asDRep)
    assertEquals(
      compileErrors(
        "def f(c: xyz.kd5ujc.scarab.ledger.state.Credential[xyz.kd5ujc.scarab.ledger.state.DRepRole]) = EraTxCert.conway.mkUnRegDRepTxCert(c, xyz.kd5ujc.scarab.ledger.state.Coin(0))"
      ),
      ""
    )
    // a hot key given as the cold one
    val swapped = compileErrors(
      "def f(h: xyz.kd5ujc.scarab.ledger.state.Credential[xyz.kd5ujc.scarab.ledger.state.HotCommittee], c: xyz.kd5ujc.scarab.ledger.state.Credential[xyz.kd5ujc.scarab.ledger.state.ColdCommittee]) = EraTxCert.conway.mkAuthCommitteeHotKeyTxCert(h, c)"
    )
    assert(swapped.contains("ColdCommittee"), swapped)
    assertEquals(
      compileErrors(
        "def f(h: xyz.kd5ujc.scarab.ledger.state.Credential[xyz.kd5ujc.scarab.ledger.state.HotCommittee], c: xyz.kd5ujc.scarab.ledger.state.Credential[xyz.kd5ujc.scarab.ledger.state.ColdCommittee]) = EraTxCert.conway.mkAuthCommitteeHotKeyTxCert(c, h)"
      ),
      ""
    )
  }
