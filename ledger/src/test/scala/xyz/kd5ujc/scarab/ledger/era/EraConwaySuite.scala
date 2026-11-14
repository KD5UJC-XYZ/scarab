// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CborRead,
  Certificate,
  LedgerDecodeError,
  PreprodDeepTx,
  Purpose,
  TxBody as FlatTxBody,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTxBody, ConwayTxCert, ConwayTxWits}
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's types (stage 2 of `docs/design/era-typed-transactions.md`): every recorded Conway body
  * read as a `ConwayTxBody` has, through Conway's classes, every field the flat decoder reads; and
  * what only the projection from the flat body refuses, the flat Conway decoder never produces.
  */
class EraConwaySuite extends munit.FunSuite:
  // every block of the fixtures from Shelley on (`[era, block]`, era 2 to 7), each of which must
  // decode, so a regression that loses one fails here rather than shrinking the corpus
  private lazy val conwayBlocks: Vector[Block.Shelley] =
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/preview/conway.hex",
      "fixtures/preview/conway-governance.hex"
    ).flatMap { f =>
      Files
        .readString(Path.of(f))
        .linesIterator
        .zipWithIndex
        .map((l, i) => (s"$f:${i + 1}", l.trim.split("\\s+").last))
        .filter((_, hex) => hex.nonEmpty && !hex.startsWith("8200") && !hex.startsWith("8201"))
        .map { (at, hex) =>
          val raw = Bytes.fromHex(hex).fold(e => fail(s"$at: $e"), identity)
          Block.decodeShelley(raw) match
            case Right(b: Block.Shelley) => b
            case other                   => fail(s"$at: not a Shelley-based block: $other")
        }
    }.filter(_.era == Era.Conway)

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
        body.allSizedOutputs(c).map(o => (body.outputsOf.raw(o.value), o.size)),
        (flat.outputs ++ flat.collateralReturn).map(o => (o.raw, o.ledgerSize.toLong))
      )
      assertEquals(body.currentTreasuryValue[TxLevel.Top](c).map(_.value), flat.currentTreasury)
      assertEquals(
        body.votingProcedures[TxLevel.Top](c).toVector.map((v, m) => v -> m.toVector),
        flat.votes.map((v, m) => v -> m.sortBy(_._1)).sortBy(_._1)
      )
      assertEquals(body.proposalProcedures[TxLevel.Top](c).toVector, flat.proposals)
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
      (s"8307${script}00", ConwayTxCertDeleg(ConwayRegCert(cred, Some(0))), Some(sh), None),
      (s"8201$script", ConwayTxCertDeleg(ConwayUnRegCert(cred, None)), Some(sh), None),
      (s"8308${script}00", ConwayTxCertDeleg(ConwayUnRegCert(cred, Some(0))), Some(sh), None),
      (
        s"8302$script$pool",
        ConwayTxCertDeleg(ConwayDelegCert(cred, Delegatee.DelegStake(ph))),
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
        ConwayTxCertDeleg(ConwayDelegCert(cred, Delegatee.DelegStakeVote(ph, drep))),
        Some(sh),
        None
      ),
      (
        s"840b$script${pool}00",
        ConwayTxCertDeleg(ConwayRegDelegCert(cred, Delegatee.DelegStake(ph), 0)),
        Some(sh),
        None
      ),
      (
        s"840c$script${abstain}00",
        ConwayTxCertDeleg(ConwayRegDelegCert(cred, Delegatee.DelegVote(drep), 0)),
        Some(sh),
        None
      ),
      (
        s"850d$script$pool${abstain}00",
        ConwayTxCertDeleg(ConwayRegDelegCert(cred, Delegatee.DelegStakeVote(ph, drep), 0)),
        Some(sh),
        None
      ),
      (s"8304${pool}0a", ConwayTxCertPool(PoolCert.RetirePool(ph, 10)), None, Some(ph)),
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
      (s"8410${script}00f6", ConwayTxCertGov(ConwayRegDRep(cred, 0, None)), Some(sh), None),
      (s"8311${script}00", ConwayTxCertGov(ConwayUnRegDRep(cred, 0)), Some(sh), None),
      (s"8312${script}f6", ConwayTxCertGov(ConwayUpdateDRep(cred, None)), Some(sh), None)
    )
    val c = EraTxCert.conway
    // `[3, operator, vrf, pledge, cost, margin, reward account, owners, relays, metadata]`
    val regPool = node(
      s"8a03$pool${"5820" + "76" * 32}0000d81e820001581de0${"72" * 28}8080f6"
    )
    ConwayTxCert.decode(regPool) match
      case Right(cert @ ConwayTxCertPool(PoolCert.RegPool(params))) =>
        assertEquals(params.operator, ph)
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
