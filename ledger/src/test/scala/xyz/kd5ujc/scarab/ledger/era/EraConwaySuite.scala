// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  ByteStrings,
  CborRead,
  Certificate,
  LedgerDecodeError,
  Metadatum,
  NativeScript as FlatNativeScript,
  PreprodDeepTx,
  Purpose,
  Script as FlatScript,
  ScriptKind,
  Unsigned,
  TxBody as FlatTxBody,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.era.conway.{
  ConwayPlutusPurpose,
  ConwayTxOut,
  ConwayTx,
  ConwayTxAuxData,
  ConwayTxBody,
  ConwayTxCert,
  ConwayTxWits
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, EpochNo, KeyHash, StakePool}
import xyz.kd5ujc.scarab.protocol.Era

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
      assertEquals(w.datsTxWits(c).datums.values.toSet, flat.datums.toSet)
      assertEquals(w.datsTxWits(c).raw, flat.datumsRaw)
      assertEquals(w.rdmrsTxWits(c).raw, flat.redeemersRaw)
      assertEquals(
        w.rdmrsTxWits(c).entries.toVector.map((p, v) => (p.tag, BigInt(p.ix.value.toString), v)),
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
      val c = ConwayTxBody.decode(raw).toOption.get
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
        body.votingProcedures[TxLevel.Top](c).toVector.map((v, m) => v -> m.toVector),
        flat.votes.map((v, m) => v -> m.sortBy(_._1)).sortBy(_._1)
      )
      assertEquals(body.proposalProcedures[TxLevel.Top](c).toVector.map(_.value), flat.proposals)
      assertEquals(body.treasuryDonation[TxLevel.Top](c).value, flat.donation)
      // a certificate's script witness is the one the flat rules ask for
      val typed = body.certs[TxLevel.Top](c).toVector
      assertEquals(typed.size, flat.certificates.size)
      assertEquals(
        typed.map(body.certsOf.getScriptWitnessTxCert),
        flat.certificates.map(Purpose.certificateScript)
      )
      typed.size
    }.sum
    assert(certs > 0, "no recorded Conway body has a certificate")
  }

  private def node(hex: String) = Bytes.fromHex(hex).flatMap(CborRead.decode(_)).toOption.get

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
      assertEquals(ConwayTxCert.decode(n), Left(EraTxError.Decode(refused)))
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
      ConwayTxBody.decode(withUpdate).left.toOption,
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
    val cred = xyz.kd5ujc.scarab.ledger.Credential.Script(sh)
    val drep = xyz.kd5ujc.scarab.ledger.DRep.AlwaysAbstain
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
          ConwayAuthCommitteeHotKey(cred, xyz.kd5ujc.scarab.ledger.Credential.Key(kh))
        ),
        Some(sh),
        None
      ),
      (
        s"830e$key$script",
        ConwayTxCertGov(
          ConwayAuthCommitteeHotKey(xyz.kd5ujc.scarab.ledger.Credential.Key(kh), cred)
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
    ConwayTxCert.decode(regPool) match
      case Right(cert @ ConwayTxCertPool(PoolCert.RegPool(params))) =>
        assertEquals(params.value.operator, ph)
        assertEquals(c.getVKeyWitnessTxCert(cert), Some(ph))
        assertEquals(c.getScriptWitnessTxCert(cert), None)
        assertEquals(c.getRegPoolTxCert(cert), Some(params))
      case other => fail(s"not a pool registration: $other")
    assertEquals(cases.map(_._1.take(4)).distinct.size + 1, 17, "every Conway form but tag 3")
    cases.foreach { (hex, expected, scriptWitness, keyWitness) =>
      val n = node(hex)
      val cert = ConwayTxCert.decode(n)
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
    val cred = xyz.kd5ujc.scarab.ledger.Credential.Script(sh)
    val hot = xyz.kd5ujc.scarab.ledger.Credential.Key(kh)
    val drep = xyz.kd5ujc.scarab.ledger.DRep.AlwaysAbstain
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
    built.foreach((hex, cert) => assertEquals(ConwayTxCert.decode(node(hex)), Right(cert), hex))
    // and each reader reads back what its builder took, and nothing from another form
    assertEquals(c.getRegDepositTxCert(c.mkRegDepositTxCert(cred, Coin(5))), Some((cred, Coin(5))))
    assertEquals(c.getRegDepositTxCert(c.mkRegTxCert(cred)), None)
    assertEquals(c.getRegTxCert(c.mkRegDepositTxCert(cred, Coin(5))), None)
    assertEquals(c.getUnRegTxCert(c.mkUnRegDepositTxCert(cred, Coin(5))), None)
    assertEquals(c.getDelegStakeTxCert(c.mkDelegTxCert(cred, Delegatee.DelegVote(drep))), None)
    assertEquals(c.getDelegStakeTxCert(c.mkDelegStakeTxCert(cred, pk)), Some((cred, pk)))
    assertEquals(
      c.getRetirePoolTxCert(c.mkRetirePoolTxCert(pk, EpochNo.fromUnsigned(10))),
      Some((pk, EpochNo.fromUnsigned(10)))
    )
    assertEquals(c.getUnRegDRepTxCert(c.mkRegDRepTxCert(cred, Coin(5), None)), None)
    assertEquals(c.getUpdateDRepTxCert(c.mkUpdateDRepTxCert(cred, None)), Some((cred, None)))
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

  test("pool registrations are equal when their relays mean the same, as the reference compares") {
    // the empty relay list as `80`, and as `98 00`: one `StrictSeq StakePoolRelay`, so one
    // `StakePoolParams` under the derived `Eq` (`State/StakePool.hs:424-435`, cardano-ledger
    // f649f975), while the bytes kept for each differ
    def registration(relays: String) =
      node(s"8a03$pool${"5820" + "76" * 32}0000d81e820001581de0${"72" * 28}80${relays}f6")
    val flat = Vector("80", "9800").map(r => Certificate.decode(registration(r), Era.Conway))
    flat.foreach(f => assert(f.isRight, f))
    assertNotEquals(flat(0), flat(1), "the flat certificates keep the relay bytes they read")
    val typed = Vector("80", "9800").map(r => ConwayTxCert.decode(registration(r)))
    assertEquals(typed(0), typed(1))
    val params = typed.flatMap(_.toOption).flatMap(EraTxCert.conway.getRegPoolTxCert)
    assertEquals(params.size, 2)
    assertEquals(params(0).hashCode, params(1).hashCode)
    // a registration with another relay is another certificate
    val other = ConwayTxCert.decode(registration(s"81${"8400" + "f6" * 3}"))
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
      val c = ConwayTx.decode(t).toOption.get
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

  test("proposals are equal when they mean the same, whatever the order of their withdrawals") {
    // `[deposit, return account, [2, withdrawals, null], anchor]`: treasury withdrawals are a
    // `Map AccountAddress Coin` (`Procedures.hs:826-834`, cardano-ledger f649f975), so one proposal
    val (r1, r2) = (s"581de0${"a1" * 28}", s"581de0${"a2" * 28}")
    def proposal(withdrawals: String) =
      xyz.kd5ujc.scarab.ledger.Proposal
        .decode(
          node(s"8400$r1${"8302" + withdrawals + "f6"}${"826161" + "5820" + "cc" * 32}"),
          xyz.kd5ujc.scarab.ledger.DecoderVersion.of(Era.Conway)
        )
        .toOption
        .get
    val (sorted, reversed) = (proposal(s"a2${r1}01${r2}02"), proposal(s"a2${r2}02${r1}01"))
    assertNotEquals(sorted, reversed, "the flat proposals keep the entries in the order read")
    assertEquals(ProposalProcedure(sorted), ProposalProcedure(reversed))
    assertEquals(ProposalProcedure(sorted).hashCode, ProposalProcedure(reversed).hashCode)
    assertNotEquals(ProposalProcedure(proposal(s"a2${r1}01${r2}03")), ProposalProcedure(sorted))
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

  test("a submitted Conway transaction reads as its parts do from a block, in every form") {
    transactions.foreach { t =>
      val submitted = xyz.kd5ujc.scarab.ledger.Tx.envelope(t)
      assertEquals(ConwayTx.fromEnvelope(submitted), ConwayTx.decode(t))
      // of three items, a valid transaction (`Alonzo/Tx.hs:541-553`, cardano-ledger f649f975)
      val aux = t.auxiliaryData.getOrElse(Bytes(Vector(0xf6.toByte)))
      val three = Bytes(Vector(0x83.toByte)) ++ t.body ++ t.witnesses ++ aux
      if t.isValid then assertEquals(ConwayTx.fromEnvelope(three), ConwayTx.decode(t))
      // indefinite, as the flat decoder reads it
      val indefinite = Bytes(Vector(0x9f.toByte)) ++ submitted.drop(1) ++ Bytes(Vector(0xff.toByte))
      assertEquals(
        ConwayTx.fromEnvelope(indefinite).isRight,
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
