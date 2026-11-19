// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.protocol.StateLayout
import munit.FunSuite
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Anchor, CborRead, DecodeField, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraCert,
  DijkstraDelegatee,
  DijkstraGenesis,
  DijkstraPoolMetadata,
  DijkstraPurposes,
  Params,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayDelegCert,
  ConwayGovCert,
  Delegatee,
  EraTxCert,
  PParams,
  PoolCert
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Credential,
  DRep,
  DRepRole,
  Dijkstra,
  EpochNo,
  FlatView,
  HotCommittee,
  KeyHash,
  KeyRole,
  PoolParamsBlsKey,
  ScriptHash,
  StakePool,
  Staking
}

/** Dijkstra's certificates on the era types ([[DijkstraTxCert]], cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxCert.hs`): each builder makes the certificate
  * the Leios build's block decoder ([[DijkstraCert.decode]]) reads from its bytes, with the
  * witnesses the reference gives it and the script witness the block's rules ask for; the readers
  * read only their own form; the refunds are the certificates' own; a pool registration (of
  * Conway's parameters, without a voting key) pays its deposit once; and a Conway certificate is
  * carried in as `upgradeTxCert` carries it.
  */
class DijkstraTxCertSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private def node(s: String): Node = CborRead.decode(hex(s)).fold(e => fail(e.toString), identity)

  private val s = "51" * 28
  private val k = "4b" * 28
  private val p = "50" * 28
  private val script = s"8201581c$s"
  private val key = s"8200581c$k"
  private val pool = s"581c$p"
  private val abstain = "8102"

  private val pk = KeyHash.fromBytes[StakePool](hex(p)).fold(e => fail(e.toString), identity)
  private def cred[R <: KeyRole]: Credential[R] =
    Credential.ScriptHashObj[R](ScriptHash.fromBytes(hex(s)).fold(e => fail(e.toString), identity))
  private def keyCred[R <: KeyRole]: Credential[R] =
    Credential.KeyHashObj[R](KeyHash.fromBytes[R](hex(k)).fold(e => fail(e.toString), identity))
  private val drep = DRep.DRepAlwaysAbstain
  private val epoch = EpochNo.fromUnsigned(10)

  private val c = EraTxCert.dijkstra

  // (bytes, the certificate its builder makes), one per form the block decoder reads but the
  // pool registration, whose voting key the era types do not hold yet
  private val built: Vector[(String, DijkstraTxCert)] = Vector(
    s"8302$script$pool" -> c.mkDelegTxCert(cred, Delegatee.DelegStake(pk)),
    s"8304${pool}0a" -> c.mkRetirePoolTxCert(pk, epoch),
    s"8307${script}05" -> c.mkRegDepositTxCert(cred, Coin(5)),
    s"8308${script}05" -> c.mkUnRegDepositTxCert(cred, Coin(5)),
    s"8309$script$abstain" -> c.mkDelegTxCert(cred, Delegatee.DelegVote(drep)),
    s"840a$script$pool$abstain" -> c.mkDelegTxCert(cred, Delegatee.DelegStakeVote(pk, drep)),
    s"840b$script${pool}05" -> c.mkRegDepositDelegTxCert(cred, Delegatee.DelegStake(pk), Coin(5)),
    s"840c$script${abstain}05" -> c
      .mkRegDepositDelegTxCert(cred, Delegatee.DelegVote(drep), Coin(5)),
    s"850d$script$pool${abstain}05" ->
      c.mkRegDepositDelegTxCert(cred, Delegatee.DelegStakeVote(pk, drep), Coin(5)),
    s"830e$script$key" -> c.mkAuthCommitteeHotKeyTxCert(cred, keyCred[HotCommittee]),
    s"830f${script}f6" -> c.mkResignCommitteeColdTxCert(cred, None),
    s"8410${script}05f6" -> c.mkRegDRepTxCert(cred, Coin(5), None),
    s"8311${script}05" -> c.mkUnRegDRepTxCert(cred, Coin(5)),
    s"8312${script}f6" -> c.mkUpdateDRepTxCert(cred, None)
  )

  // the Leios testnet's parameters, with deposits of their own
  private def pp: PParams[Dijkstra] =
    val testnet = io.circe.parser
      .parse(Files.readString(Path.of("fixtures/leios/testnet/config/dijkstra-genesis.json")))
      .fold(e => fail(e.message), identity)
    val stored = DijkstraGenesis
      .read(testnet)
      .fold(e => fail(e.message), _.upgrade(Params.base))
      .copy(keyDeposit = 2000000, poolDeposit = 500000000, drepDeposit = 7)
    PParams[Dijkstra](DijkstraPParams.fromStored(stored).fold(e => fail(e.toString), identity))

  // The block decoder's reading of a certificate the builders make (no pool registration, and no
  // anchor, which the built certificates do not hold).
  private def asBlockCert(cert: DijkstraTxCert): DijkstraCert =
    import DijkstraTxCert.*
    import DijkstraDelegCert.*
    import ConwayGovCert.*
    def to(d: Delegatee): DijkstraDelegatee = d match
      case Delegatee.DelegStake(p) => DijkstraDelegatee.Stake(p.bytes)
      case Delegatee.DelegVote(v)  => DijkstraDelegatee.Vote(FlatView.drepBack(v))
      case Delegatee.DelegStakeVote(p, v) =>
        DijkstraDelegatee.StakeVote(p.bytes, FlatView.drepBack(v))
    def none(a: Option[Anchor]): Option[DijkstraAnchor] = a.map(x => fail(s"an anchor: $x"))
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
      case DijkstraTxCertGov(ConwayResignCommitteeColdKey(cold, a)) =>
        DijkstraCert.ResignCommitteeColdKey(back(cold), none(a))
      case DijkstraTxCertGov(ConwayRegDRep(cr, d, a)) =>
        DijkstraCert.RegisterDRep(back(cr), d.value, none(a))
      case DijkstraTxCertGov(ConwayUnRegDRep(cr, r)) =>
        DijkstraCert.UnregisterDRep(back(cr), r.value)
      case DijkstraTxCertGov(ConwayUpdateDRep(cr, a)) => DijkstraCert.UpdateDRep(back(cr), none(a))

  test("each builder makes the certificate the block decoder reads, with its script witness") {
    assertEquals(built.map(_._1.take(4)).distinct.size, built.size)
    built.foreach { (h, cert) =>
      val flat = DijkstraCert.decode(node(h)).fold(e => fail(s"$h: $e"), identity)
      assertEquals(flat, asBlockCert(cert), h)
      // `getScriptWitnessDijkstraTxCert` (`TxCert.hs:323-340`, cardano-ledger 9df9f98c): a pool is
      // never a script
      val expected = cert match
        case DijkstraTxCert.DijkstraTxCertPool(_) => None
        case DijkstraTxCert.DijkstraTxCertDeleg(_) | DijkstraTxCert.DijkstraTxCertGov(_) =>
          Some(hex(s))
      assertEquals(c.getScriptWitnessTxCert(cert), expected, h)
      assertEquals(DijkstraPurposes.certificateScript(flat), expected, h)
    }
  }

  test("Dijkstra has no certificate without a deposit: the block decoder refuses tags 0 and 1") {
    // `DecCBOR (DijkstraTxCert era)` (`TxCert.hs:239-250`, cardano-ledger 9df9f98c) fails both
    // before their fields
    Vector(0 -> s"8200$script", 1 -> s"8201$script").foreach { (tag, h) =>
      DijkstraCert.decode(node(h)) match
        case Left(LedgerDecodeError.UnknownTag(_, t)) => assertEquals(t, BigInt(tag), h)
        case other                                    => fail(s"$h: $other")
    }
  }

  test("every staking certificate is witnessed by its credential, a registration too") {
    // `getVKeyWitnessDijkstraTxCert` (`TxCert.hs:342-351`, cardano-ledger 9df9f98c): unlike
    // Conway's tag 0, a registration
    // always pays a deposit and is always witnessed
    val forms = Vector(
      c.mkRegDepositTxCert(keyCred, Coin(5)),
      c.mkUnRegDepositTxCert(keyCred, Coin(5)),
      c.mkDelegTxCert(keyCred, Delegatee.DelegVote(drep)),
      c.mkRegDepositDelegTxCert(keyCred, Delegatee.DelegStake(pk), Coin(5)),
      c.mkRegDRepTxCert(keyCred, Coin(5), None),
      c.mkResignCommitteeColdTxCert(keyCred, None)
    )
    forms.foreach { cert =>
      assertEquals(c.getVKeyWitnessTxCert(cert), Some(hex(k)), cert.toString)
      assertEquals(c.getScriptWitnessTxCert(cert), None, cert.toString)
    }
    val retire = c.mkRetirePoolTxCert(pk, epoch)
    assertEquals(c.getVKeyWitnessTxCert(retire), Some(hex(p)))
    assertEquals(c.getVKeyWitnessTxCert(c.mkRegDepositTxCert(cred, Coin(5))), None)
  }

  test("each reader reads back what its builder took, and nothing from another form") {
    val certs = built.map(_._2)
    def only[A](read: DijkstraTxCert => Option[A], cert: DijkstraTxCert, value: A): Unit =
      certs.foreach(other =>
        assertEquals(read(other), if other == cert then Some(value) else None, other.toString)
      )
    only(c.getRegDepositTxCert, c.mkRegDepositTxCert(cred, Coin(5)), (cred[Staking], Coin(5)))
    only(c.getUnRegDepositTxCert, c.mkUnRegDepositTxCert(cred, Coin(5)), (cred[Staking], Coin(5)))
    only(c.getRetirePoolTxCert, c.mkRetirePoolTxCert(pk, epoch), (pk, epoch))
    only(
      c.getAuthCommitteeHotKeyTxCert,
      c.mkAuthCommitteeHotKeyTxCert(cred, keyCred[HotCommittee]),
      (cred, keyCred[HotCommittee])
    )
    only(c.getResignCommitteeColdTxCert, c.mkResignCommitteeColdTxCert(cred, None), (cred, None))
    only(
      c.getRegDRepTxCert,
      c.mkRegDRepTxCert(cred, Coin(5), None),
      (cred[DRepRole], Coin(5), None)
    )
    only(c.getUnRegDRepTxCert, c.mkUnRegDRepTxCert(cred, Coin(5)), (cred[DRepRole], Coin(5)))
    only(c.getUpdateDRepTxCert, c.mkUpdateDRepTxCert(cred, None), (cred[DRepRole], None))
    assertEquals(certs.flatMap(c.getDelegTxCert).size, 3)
    assertEquals(certs.flatMap(c.getRegDepositDelegTxCert).size, 3)
    assertEquals(certs.flatMap(c.getRegPoolTxCert), Vector.empty)
    // `lookupRegStakeTxCert` and `lookupUnRegStakeTxCert` (`TxCert.hs:311-317`, cardano-ledger
    // 9df9f98c)
    assertEquals(certs.flatMap(c.lookupRegStakeTxCert).size, 4)
    assertEquals(certs.flatMap(c.lookupUnRegStakeTxCert), Vector(cred[Staking]))
  }

  test("the refunds are those the unregistrations name, whatever the state holds") {
    val certs = built.map(_._2) ++ Vector(c.mkUnRegDepositTxCert(keyCred, Coin(11)))
    // `dijkstraTotalRefundsTxCerts` (`TxCert.hs:261-272`, cardano-ledger 9df9f98c): the lookup is
    // not read
    assertEquals(c.getTotalRefundsTxCerts(pp, _ => Some(Coin(1000)), certs), Coin(5 + 5 + 11))
    assertEquals(c.getTotalRefundsTxCerts(pp, _ => None, certs), Coin(21))
    // `conwayTotalDepositsTxCerts`: four registrations at the key deposit and one DRep's
    assertEquals(
      c.getTotalDepositsTxCerts(pp, _ => false, certs),
      Coin(BigInt(4) * 2000000 + 7)
    )
  }

  test("a Conway certificate is carried in as upgradeTxCert carries it") {
    val conway = Vector(
      ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(cred, Some(Coin(5)))),
      ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(cred, Some(Coin(5)))),
      ConwayTxCert.ConwayTxCertDeleg(
        ConwayDelegCert.ConwayDelegCert(cred, Delegatee.DelegStake(pk))
      ),
      ConwayTxCert.ConwayTxCertDeleg(
        ConwayDelegCert.ConwayRegDelegCert(cred, Delegatee.DelegVote(drep), Coin(5))
      ),
      ConwayTxCert.ConwayTxCertPool(PoolCert.RetirePool(pk, epoch)),
      ConwayTxCert.ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(cred, Coin(5)))
    )
    val dijkstra = Vector(
      c.mkRegDepositTxCert(cred, Coin(5)),
      c.mkUnRegDepositTxCert(cred, Coin(5)),
      c.mkDelegTxCert(cred, Delegatee.DelegStake(pk)),
      c.mkRegDepositDelegTxCert(cred, Delegatee.DelegVote(drep), Coin(5)),
      c.mkRetirePoolTxCert(pk, epoch),
      c.mkUnRegDRepTxCert(cred, Coin(5))
    )
    assertEquals(
      conway.map(DijkstraTxCert.translate),
      dijkstra.map(Right[DijkstraTxCertUpgradeError, DijkstraTxCert](_))
    )
    assertEquals(
      DijkstraTxCert.translate(
        ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(cred, None))
      ),
      Left(DijkstraTxCertUpgradeError.RegTxCertExpunged)
    )
    assertEquals(
      DijkstraTxCert.translate(
        ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(cred, None))
      ),
      Left(DijkstraTxCertUpgradeError.UnRegTxCertExpunged)
    )
    // and `dijkstraToConwayDelegCert` (`TxCert.hs:396-401`, cardano-ledger 9df9f98c) takes a
    // staking certificate back
    conway.zip(dijkstra).foreach {
      case (ConwayTxCert.ConwayTxCertDeleg(back), DijkstraTxCert.DijkstraTxCertDeleg(d)) =>
        assertEquals(DijkstraTxCert.dijkstraToConwayDelegCert(d), back)
      case _ => ()
    }
  }

  test("a pool registration builds, reads back and pays its deposit once, as Conway's") {
    // the pool's parameters as the Leios build's Conway decoder reads them: Dijkstra's registration
    // holds the same StakePoolParams, here with no voting key
    val regPool =
      s"8a03$pool${"5820" + "76" * 32}0000d81e820001581de0${"72" * 28}8080f6"
    val params = ConwayTxCert.decode(node(regPool), StateLayout.Leios) match
      case Right(ConwayTxCert.ConwayTxCertPool(PoolCert.RegPool(params))) => params
      case other => fail(s"not a registration: $other")
    val reg = c.mkRegPoolTxCert(params)
    assertEquals(c.getRegPoolTxCert(reg), Some(params))
    assertEquals(c.getRetirePoolTxCert(reg), None)
    assertEquals(c.getVKeyWitnessTxCert(reg), Some(hex(p)))
    assertEquals(c.getScriptWitnessTxCert(reg), None)
    assertEquals(
      DijkstraTxCert.translate(ConwayTxCert.ConwayTxCertPool(PoolCert.RegPool(params))),
      Right(reg)
    )
    // `shelleyTotalDepositsTxCerts` (`Shelley/TxCert.hs:670-689`, cardano-ledger 9df9f98c): a pool
    // pays once, and not when it is registered already
    val certs = Vector(reg, reg, c.mkRegDepositTxCert(cred, Coin(5)))
    assertEquals(c.getTotalDepositsTxCerts(pp, _ => false, certs), Coin(2000000 + 500000000))
    assertEquals(c.getTotalDepositsTxCerts(pp, _ == pk, certs), Coin(2000000))
  }

  test(
    "a registration whose metadata URL is chunked text reads, as the flat decoder at 12 reads it"
  ) {
    // `[url, hash]` with the URL "x" as chunked text (`7f 61 78 ff`) and a one-byte hash: the
    // reference reads it at version 12 (`decodeString`, cardano-ledger 9df9f98c
    // `Decoding/Decoder.hs:1564-1598`), as the flat decoder does
    val metadata = "827f6178ff4100"
    val raw = s"8a03$pool${"5820" + "76" * 32}0000d81e820001581de0${"72" * 28}8080$metadata"
    val n = CborRead.decode(hex(raw), V12.nestedLimits).fold(e => fail(e.toString), identity)
    val flat = DijkstraCert.decode(n).fold(e => fail(e.toString), identity)
    flat match
      case DijkstraCert.RegisterPool(params) =>
        assertEquals(params.metadata, Some(DijkstraPoolMetadata("x", hex("00"))))
      case other => fail(s"not a registration: $other")
    DijkstraProcedures.cert(flat, Right(n)) match
      case Right(DijkstraTxCert.DijkstraTxCertPool(PoolCert.RegPool(params))) =>
        assertEquals(params.metadata.map(m => (m.bytes, m.hash)), Some((hex(metadata), hex("00"))))
        assertEquals(params.blsKey, PoolParamsBlsKey.Leios(None))
      case other => fail(s"not read: $other")
  }

  test("a registration whose VRF key hash is 31 bytes is refused by the certificate's reader") {
    // `decodeStakePoolParamsFlat` reads `sppVrf` as a 32-byte hash (cardano-ledger 9df9f98c
    // `State/StakePool.hs:746-772`): the era types' VRFVerKeyHash is never given another length
    val raw = s"8a03$pool${"581f" + "76" * 31}0000d81e820001581de0${"72" * 28}8080f6"
    val n = CborRead.decode(hex(raw), V12.nestedLimits).fold(e => fail(e.toString), identity)
    assertEquals(
      DijkstraCert.decode(n),
      Left(LedgerDecodeError.ByteLength(DecodeField.PoolVrf, 32, 31))
    )
  }
