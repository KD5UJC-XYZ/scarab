// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32
import xyz.kd5ujc.scarab.ledger.{Block as FlatBlock, LeiosBlock, Nonce}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraTx as FlatDijkstraTx, DijkstraTxIn}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  BlockIssuer,
  BlocksMade,
  ChainAccountState,
  Coin,
  Dijkstra,
  EpochNo,
  KeyHash,
  Network,
  StakePool,
  UnitInterval
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScarabEvaluator}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Dijkstra's BBODY on the era types (`era.rules.DijkstraBbody`; `Dijkstra/Rules/Bbody.hs`,
  * cardano-ledger 9df9f98c), with the block and header classes it reads:
  *   - every Dijkstra block of the captures (the public testnet's, ranking and certifying, and the
  *     devnet's) reads into the era's types with the flat block decoder's transactions, size, hash,
  *     certificates and header fields;
  *   - each of BBODY's header checks refuses a crafted block in its branch, in the reference's
  *     order, and admits the block at its boundary;
  *   - the block's execution units and reference scripts against the flat rules' sums on the
  *     chain's recorded transactions, the reference scripts at versions 10 and 12, the limits at
  *     their boundaries, and a block past both refused by BBODY after LEDGERS's failures;
  *   - the node's run (`DijkstraBlockRun.bbody`) on the network of its globals with every check;
  *   - no derived `Mirror` for the block's body or header, which only the block decoder makes;
  *   - the block counted for its issuer, and an overlay slot not counted;
  *   - the injections into BBODY on built values against the reference's compositions, the 24 the
  *     reference lists, and none for the constructor the reference calls impossible or a failure
  *     LEDGERS has none for: they do not compile.
  *
  * The reference's verdicts on whole blocks are `DijkstraRulesOracleSuite` 's (node), on the 15
  * `bbody` cases of `fixtures/reference/dijkstra-rules`, byte for byte through the flat form; the
  * reference's own BBODY cases that run for Dijkstra are `DijkstraBbodyImpSuite` 's.
  *
  * Which UTxO set the reference scripts are measured against, before LEDGERS or after, the sample
  * does not tell apart (every sampled reference script is read through a reference input, which
  * LEDGERS does not spend): `DijkstraBbodyImpSuite` 's crafted case does, and says where the
  * union's bias shows.
  *
  * Incomplete (CNF-2): no recording holds a refusal of the units, the reference scripts or a Peras
  * certificate.
  */
class DijkstraBbodySuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val tx = EraTx.dijkstra
  private val bodies = EraTxBody.dijkstra

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private def flatBlock(raw: Bytes): LeiosBlock = FlatBlock.decodeShelley(raw) match
    case Right(FlatBlock.Dijkstra(b)) => b
    case other                        => fail(s"not a Dijkstra block: $other")

  private lazy val captured: Vector[(String, Bytes, LeiosBlock)] =
    (testnetBlocks ++ devnetBlocks).map((name, raw) => (name, raw, flatBlock(raw)))

  test("every captured Dijkstra block reads into the era's types as the block decoder reads it") {
    assertEquals(captured.size, 13)
    captured.foreach { (name, raw, b) =>
      val block = ok(DijkstraBlock.decode(raw))
      val body = block.blockBody
      val bb = EraBlockBody.dijkstra
      val h = EraBlockHeader.dijkstra
      val l = LeiosEraBlockHeader.dijkstra
      val txs = bb.txSeqBlockBody(body)
      assertEquals(
        txs.map(t => bodies.id[TxLevel.Top](tx.body(t))),
        b.transactions.map(_.body.id),
        name
      )
      assertEquals(
        txs.map(t => IsPhase2Valid.of(true) == tx.isValid(t)),
        b.transactions.map(_.isValid),
        name
      )
      assertEquals(
        bb.blockBodySize(EraPParams.dijkstra.ppProtocolVersionL.get(testnetPParams), body),
        b.bodySize,
        name
      )
      assertEquals(bb.hashBlockBody(body).bytes, b.bodyHash, name)
      assertEquals(bb.leiosCertBlockBody(body), b.leiosCertificate, name)
      assertEquals(bb.perasCertBlockBody(body), b.perasCertificate.map(PerasCert(_)), name)
      assertEquals(h.slotNoBlockHeader(block).toBigInt, b.header.slot, name)
      assertEquals(BigInt(h.blockBodySizeBlockHeader(block)), b.header.bodySize, name)
      assertEquals(h.blockBodyHashBlockHeader(block).bytes, b.header.bodyHash, name)
      assertEquals(
        h.blockIssuerBlockHeader(block).bytes,
        Blake2b.hash224.hash(b.header.issuerVkey),
        name
      )
      val info = l.versionInfoBlockHeader(block)
      assertEquals(
        (info.bhviHighestSupportedMajorVersion.value, info.bhviSelfReportedSoftwareTag.value),
        (b.header.leios.versionInfo.highestSupportedMajor, b.header.leios.versionInfo.softwareTag),
        name
      )
      assertEquals(l.prevNonceBlockHeader(block), Nonce.Neutral, name)
      // each block's body and header agree, as the chain's ledger accepted them
      val protVer = EraPParams.dijkstra.ppProtocolVersionL.get(testnetPParams)
      assert(ShelleyBbody.validateBlockBodySize(block, protVer).isValid, name)
      assert(ShelleyBbody.validateBlockBodyHash(block).isValid, name)
    }
    // the captures hold a certifying block, blocks with transactions, and blocks with neither
    assert(captured.exists(_._3.leiosCertificate.isDefined))
    assert(captured.exists(_._3.transactions.nonEmpty))
  }

  // ---- BBODY on crafted blocks ----------------------------------------------------------------------

  private val globals: Globals =
    flat.TestLedger.globals.copy(ledger =
      flat.TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c)
    )

  private lazy val history: flat.Horizon =
    val params = flat.Horizon.Params(100, 1000, 30, 30)
    flat.Horizon
      .History(Vector.empty, flat.Horizon.Edge(0, 0, 0), params, Some(params))
      .at(flat.Horizon.Transition.Unknown(Some(BigInt(0))))

  private val context =
    DijkstraUtxoContext(
      Network.Testnet,
      xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
      history,
      flat.TxEnv.Validation.All
    )

  private lazy val empty: ShelleyBbodyState[Dijkstra] =
    ShelleyBbodyState[Dijkstra](
      xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(EpochNo.fromUnsigned(0L), testnetStored).es.ls,
      BlocksMade.empty
    )

  /** The devnet's first Dijkstra block, which has no transaction: its bytes. */
  private lazy val plain: Bytes =
    devnetBlocks.map(_._2).find(raw => flatBlock(raw).transactions.isEmpty).getOrElse(fail("none"))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  /** A definite array of the items encoded as `parts`. */
  private def array(parts: Vector[Bytes]): Bytes =
    val n = parts.size
    val head =
      if n < 24 then Bytes.fromArray(Array((0x80 + n).toByte))
      else if n < 256 then Bytes.fromArray(Array(0x98.toByte, n.toByte))
      else Bytes.fromArray(Array(0x99.toByte, (n >> 8).toByte, n.toByte))
    parts.foldLeft(head)(_ ++ _)

  private def encoded(v: Value): Bytes = ok(Cbor.encode(v))

  /** The block `raw`, `[8, [[header body, signature], body]]`, with its body's three parts and its
    * header body's fields as `body` and `header` make them from theirs, every other item the bytes
    * it was read as: a block whose body commits to what it holds, as its decoder reads it.
    */
  private def crafted(raw: Bytes)(
      body: Vector[Bytes] => Vector[Bytes],
      header: (Vector[Bytes], Bytes) => Vector[Bytes] = (fields, _) => fields
  ): Bytes =
    val outer = items(node(raw))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val newBody = array(body(items(inner(1)).map(_.original)))
    val newHeader = array(
      Vector(array(header(items(signed(0)).map(_.original), newBody)), signed(1).original)
    )
    array(Vector(outer(0).original, array(Vector(newHeader, newBody))))

  /** A header body's fields with the body size and hash (fields 6 and 7) of `body`. */
  private def committing(fields: Vector[Bytes], body: Bytes): Vector[Bytes] =
    fields
      .updated(6, encoded(Value.UInt(BigInt(body.size))))
      .updated(7, encoded(Value.ByteString(Blake2b.hash256.hash(body))))

  private def run(
      raw: Bytes,
      pp: PParams[Dijkstra],
      state: ShelleyBbodyState[Dijkstra] = empty,
      judged: DijkstraUtxoContext = context
  ) =
    DijkstraBbodyRun.bbody(
      globals,
      BbodyEnv[Dijkstra](pp, ChainAccountState(Coin(0), Coin(0))),
      ok(DijkstraBlock.decode(raw)),
      judged,
      state,
      EraGovRun.dijkstraEmpty,
      ScarabEvaluator.default,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slot => Right(slot * 1000)
    )

  private def refused(raw: Bytes, pp: PParams[Dijkstra] = testnetPParams) =
    run(raw, pp) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"not refused: $other")

  private def admitted(raw: Bytes, pp: PParams[Dijkstra] = testnetPParams) =
    run(raw, pp) match
      case Right(Right(after)) => after
      case other               => fail(s"not admitted: $other")

  private def withMajor(major: Int): PParams[Dijkstra] =
    EraPParams.dijkstra.ppProtocolVersionL
      .modify(v => v.copy(pvMajor = Version.mkVersion(major).getOrElse(fail(s"$major"))))(
        testnetPParams
      )

  private def hash(byte: Int) =
    xyz.kd5ujc.scarab.ledger.state.Hash32
      .fromBytes(Bytes.fromArray(Array.fill(32)(byte.toByte)))
      .fold(e => fail(e.toString), identity)

  test(
    "only the block decoder makes an era body, so its contents are those its size and hash commit to"
  ) {
    // the control: a block's bytes make one
    assertEquals(compileErrors("DijkstraBlock.decode(plain)"), "")
    // a copied flat block or transaction does not, in this package either: the evidence only the
    // decoder makes is missing, and cannot be made here
    assert(compileErrors("DijkstraBlockBody.ofDecoded(flatBlock(plain))").nonEmpty)
    assert(
      compileErrors("DijkstraTx.ofBlock(flatBlock(captured.last._2).transactions.head)").nonEmpty
    )
    assert(compileErrors("new DijkstraBlock.Decoded()").nonEmpty)
  }

  test("BBODY admits a block whose body is its header's, counted once for its issuer") {
    // the crafting itself: the block with its own parts again is the same bytes
    assertEquals(crafted(plain)(identity), plain)
    val (after, _, events) = admitted(plain)
    val issuer = KeyHash
      .fromBytes[BlockIssuer](Blake2b.hash224.hash(flatBlock(plain).header.issuerVkey))
      .fold(e => fail(e.toString), identity)
    assertEquals(
      after.blocksMade.blocks.toVector.map((k, n) => (k.bytes, n.bits)),
      Vector((issuer.bytes, 1L))
    )
    assertEquals(after.ls, empty.ls)
    // LEDGERS over no transaction tells nothing
    assertEquals(events.toVector, Vector.empty)
  }

  test("BBODY refuses each check's crafted block in the reference's order, all of them recorded") {
    val original = flatBlock(plain)
    val declaredSize = original.header.bodySize + 1
    val other = crafted(plain)(
      identity,
      (fields, _) =>
        fields
          .updated(6, encoded(Value.UInt(declaredSize)))
          .updated(7, encoded(Value.ByteString(hash(9).bytes)))
    )
    // the size is checked before the hash; the judgment reports what each predicate recorded in
    // the order the reference's `applySTS` returns it (`DijkstraRulesOracleSuite`,
    // `bbody/other-body-size-and-hash`)
    assertEquals(
      refused(other, testnetPParams),
      Vector[DijkstraBbodyPredFailure[Dijkstra]](
        DijkstraBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](
          Mismatch(ok(xyz.kd5ujc.scarab.ledger.state.Hash32.fromBytes(original.bodyHash)), hash(9))
        ),
        DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](
          Mismatch(original.bodySize, declaredSize.toLong)
        )
      )
    )
    // the header's highest major below the parameters' is refused, equal to it admitted
    val highest = original.header.leios.versionInfo.highestSupportedMajor.toInt
    assertEquals(
      refused(plain, withMajor(highest + 1)),
      Vector[DijkstraBbodyPredFailure[Dijkstra]](
        DijkstraBbodyPredFailure.HeaderProtVerTooLow[Dijkstra](
          Mismatch(Unsigned.wrapping[W32](highest), Unsigned.wrapping[W32](highest + 1))
        )
      )
    )
    admitted(plain, withMajor(highest))
    // a highest major with its high bit set is a word past every major, not a negative number
    val highBit = crafted(plain)(
      identity,
      (fields, _) =>
        fields.updated(
          9,
          array(Vector(encoded(Value.UInt(BigInt(0xffffffffL))), encoded(Value.UInt(0))))
        )
    )
    admitted(highBit, withMajor(13))
    // a Peras certificate is refused whatever it is, with the header's previous nonce, the neutral
    // one; the header commits to the body that holds it
    val cert = Bytes.fromArray(Array.fill(4)(1))
    val peras = crafted(plain)(_.updated(2, encoded(Value.ByteString(cert))), committing)
    assertEquals(
      refused(peras),
      Vector[DijkstraBbodyPredFailure[Dijkstra]](
        DijkstraBbodyPredFailure.PerasCertValidationFailed[Dijkstra](PerasCert(cert), Nonce.Neutral)
      )
    )
    assertEquals(DijkstraBbody.validatePerasCert(Nonce.Neutral, PerasCert(cert)), false)
  }

  // ---- the block's units and reference scripts ------------------------------------------------------

  /** A chain transaction in the era's and the flat types, with the outputs it spends. */
  private final case class Recorded(
      block: BigInt,
      index: Int,
      era: DijkstraTx[TxLevel.Top],
      flat: FlatDijkstraTx,
      utxo: UTxO[Dijkstra]
  )

  private lazy val recorded: Vector[Recorded] = chainContextCases.map { c =>
    val raw = hex(str(c, "bytes"))
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map((i, o) =>
        txIn(ok(DijkstraTxIn.decode(node(hex(i))))) -> ok(DijkstraTxOut.decode(node(hex(o))))
      )
    Recorded(
      c.hcursor.get[BigInt]("block").fold(e => fail(e.message), identity),
      c.hcursor.get[Int]("index").fold(e => fail(e.message), identity),
      ok(DijkstraTx.inBlock(node(raw))),
      ok(FlatDijkstraTx.inBlock(node(raw))),
      UTxO[Dijkstra](utxo.toMap)
    )
  }

  /** The chain's transactions of each block the sample holds, in their order there. */
  private lazy val byBlock: Vector[Vector[Recorded]] =
    recorded.groupBy(_.block).toVector.sortBy(_._1).map(_._2.sortBy(_.index))

  test("the block's units are the flat rules' sum, and the limit is point-wise") {
    val txs = recorded.map(_.era)
    val units = txs.foldLeft(ExUnits.zero)((a, t) => a + tx.getTotalExUnits[TxLevel.Top](t))
    assertEquals(
      units,
      recorded.foldLeft(ExUnits.zero)((a, r) =>
        a + flat.rules.DijkstraTxMeasures.totalExUnits(r.flat)
      )
    )
    assert(units.mem > 0 && units.steps > 0, s"$units")
    assert(AlonzoBbody.validateExUnits[Dijkstra](txs, units).isValid)
    // point-wise: either component past its limit refuses
    Vector(ExUnits(units.mem, units.steps - 1), ExUnits(units.mem - 1, units.steps)).foreach {
      fewer =>
        assertEquals(
          AlonzoBbody
            .validateExUnits[Dijkstra](txs, fewer)
            .fold(_.toChain.toVector, _ => Vector.empty),
          Vector(new AlonzoBbodyPredFailure.TooManyExUnits[Dijkstra](Mismatch(units, fewer)))
        )
    }
  }

  test(
    "the block's reference scripts are the flat rules' at versions 10 and 12, and the limit inclusive"
  ) {
    def flatUtxo(utxo: UTxO[Dijkstra]): flat.Utxo =
      flat.Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
    // the block's UTxO set as recorded, and without the outputs its own transactions create, which
    // only from version 11 a later transaction of the block reads (`txouts`, `collOuts`)
    def recordedUtxo(rs: Vector[Recorded]) = UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap)
    def withoutOwn(rs: Vector[Recorded]) =
      val own =
        rs.flatMap(r => UTxOs.txouts[Dijkstra, TxLevel.Top](tx.body(r.era)).unUTxO.keys).toSet
      UTxO[Dijkstra](recordedUtxo(rs).unUTxO.filterNot((in, _) => own.contains(in)))
    def sized(major: Int, rs: Vector[Recorded], utxo: UTxO[Dijkstra]): Long =
      val era = ConwayBbody.totalRefScriptSizeInBlock[Dijkstra](
        EraPParams.dijkstra.ppProtocolVersionL.get(withMajor(major)),
        rs.map(_.era),
        utxo
      )
      val stored = flat.dijkstra.DijkstraFixture.genesis.upgrade(flat.TestLedger.params(major))
      assertEquals(
        era,
        flat.rules.DijkstraBbody.refScriptsSize(stored, flatUtxo(utxo), rs.map(_.flat)),
        s"$major"
      )
      era
    val sizes = for
      major <- Vector(10, 12)
      rs <- byBlock
      utxo <- Vector(recordedUtxo(rs), withoutOwn(rs))
    yield sized(major, rs, utxo)
    assert(sizes.exists(_ > 0), "no reference script in the sample")
    // two sampled transactions spend an output an earlier sampled one of their block creates (in
    // block 59,559 and in block 59,563), but neither output has a reference script: so the two
    // versions agree on every block even without those outputs: the accumulation from version 11
    // is not told apart here, but by the reference's cases (`DijkstraBbodyImpSuite`)
    assert(byBlock.forall(rs => sized(10, rs, withoutOwn(rs)) == sized(12, rs, withoutOwn(rs))))
    // the limit admits a block at it and refuses one past it
    val rs = byBlock.maxBy(rs =>
      ConwayBbody.totalRefScriptSizeInBlock[Dijkstra](
        EraPParams.dijkstra.ppProtocolVersionL.get(testnetPParams),
        rs.map(_.era),
        UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap)
      )
    )
    val utxo = UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap)
    val total = ConwayBbody.totalRefScriptSizeInBlock[Dijkstra](
      EraPParams.dijkstra.ppProtocolVersionL.get(testnetPParams),
      rs.map(_.era),
      utxo
    )
    def limited(limit: Long): PParams[Dijkstra] =
      val stored = testnetStored.copy(maxRefScriptSizePerBlock = BigInt(limit))
      era(stored)
    assert(
      ConwayBbody
        .validateBodyRefScriptsSizeTooBig[Dijkstra](limited(total), rs.map(_.era), utxo)
        .isValid
    )
    assertEquals(
      ConwayBbody
        .validateBodyRefScriptsSizeTooBig[Dijkstra](limited(total - 1), rs.map(_.era), utxo)
        .fold(_.toChain.toVector, _ => Vector.empty),
      Vector(
        new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](Mismatch(total, total - 1))
      )
    )
  }

  test(
    "BBODY refuses a block past its units and reference-script limits, after LEDGERS's failures"
  ) {
    // the sampled block with the most reference-script bytes, its transactions in a block of the
    // devnet's header, on a state holding the outputs they spend; the checks that skip the signal's
    // static checks, so no script is evaluated
    val pv = EraPParams.dijkstra.ppProtocolVersionL.get(testnetPParams)
    def refBytes(rs: Vector[Recorded]): Long =
      ConwayBbody.totalRefScriptSizeInBlock[Dijkstra](
        pv,
        rs.map(_.era),
        UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap)
      )
    val rs = byBlock.maxBy(refBytes)
    val total = refBytes(rs)
    assert(total > 0, s"$total")
    // the sample's reference scripts are all read through reference inputs, which LEDGERS does not
    // spend, so the set after LEDGERS would give the same bytes: which set is read is not told apart
    // here, but by `DijkstraBbodyImpSuite` 's crafted case
    def afterSpending(rs: Vector[Recorded]): Long =
      val spent = rs.flatMap(r => bodies.inputs[TxLevel.Top](tx.body(r.era))).toSet
      ConwayBbody.totalRefScriptSizeInBlock[Dijkstra](
        pv,
        rs.map(_.era),
        UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap.filterNot((in, _) => spent.contains(in)))
      )
    assert(byBlock.forall(rs => afterSpending(rs) == refBytes(rs)))
    val units = rs.foldLeft(ExUnits.zero)((a, r) => a + tx.getTotalExUnits[TxLevel.Top](r.era))
    val utxo = UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap)
    val ls = empty.ls
    val seeded = empty.copy(ls =
      ls.copy[Dijkstra](utxo =
        ls.utxo.copy[Dijkstra](
          utxo = xyz.kd5ujc.scarab.ledger.state.UtxoTables(
            flat.Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
          ),
          instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, ls.utxo.instantStake))
        )
      )
    )
    val fewer = ExUnits(units.mem, units.steps - 1)
    val limited = era(
      testnetStored.copy(maxRefScriptSizePerBlock = BigInt(total - 1), maxBlockExUnits = fewer)
    )
    val nonStatic =
      DijkstraUtxoContext(
        Network.Testnet,
        xyz.kd5ujc.scarab.ledger.SystemStart(java.time.Instant.EPOCH),
        history,
        flat.TxEnv.Validation.NonStatic
      )
    // the sampled transactions in the devnet block's body, its header committing to that body
    val withTxs = crafted(plain)(_.updated(0, array(rs.map(_.flat.raw))), committing)
    val fs = run(withTxs, limited, seeded, nonStatic) match
      case Right(Left(fs)) => fs.toChain.toVector
      case other           => fail(s"not refused: $other")
    // the whole refusal, in the order the reference returns it (the last recorded first): the
    // reference scripts, the units, then LEDGERS's failures of the block's transactions, which
    // the devnet block's slot and the empty certificate state refuse
    assertEquals(
      fs.take(2),
      Vector[DijkstraBbodyPredFailure[Dijkstra]](
        DijkstraBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](Mismatch(total, total - 1)),
        DijkstraBbodyPredFailure.TooManyExUnits[Dijkstra](Mismatch(units, fewer))
      )
    )
    assert(fs.size > 2, s"$fs")
    fs.drop(2).foreach {
      case DijkstraBbodyPredFailure.LedgersFailure(_) => ()
      case other                                      => fail(s"not LEDGERS's: $other")
    }
  }

  // ---- the node's run --------------------------------------------------------------------------------

  /** The chain's transactions `rs` in the devnet block's body, its header committing to that body,
    * and a state holding the outputs they spend.
    */
  private def sampled(rs: Vector[Recorded]): (Bytes, ShelleyBbodyState[Dijkstra]) =
    val utxo = UTxO[Dijkstra](rs.flatMap(_.utxo.unUTxO).toMap)
    val ls = empty.ls
    val seeded = empty.copy(ls =
      ls.copy[Dijkstra](utxo =
        ls.utxo.copy[Dijkstra](
          utxo = xyz.kd5ujc.scarab.ledger.state.UtxoTables(
            flat.Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)
          ),
          instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, ls.utxo.instantStake))
        )
      )
    )
    (crafted(plain)(_.updated(0, array(rs.map(_.flat.raw))), committing), seeded)

  /** The globals with the network `n`. */
  private def onNetwork(n: Network): Globals =
    globals.copy(ledger = globals.ledger.copy(network = n))

  /** The node's run of BBODY (`DijkstraBlockRun.bbody`) on `raw` from `state` under `on`. */
  private def nodeRun(on: Globals, raw: Bytes, state: ShelleyBbodyState[Dijkstra]) =
    DijkstraBlockRun.bbody(
      on,
      BbodyEnv[Dijkstra](testnetPParams, ChainAccountState(Coin(0), Coin(0))),
      ok(DijkstraBlock.decode(raw)),
      0,
      history,
      state,
      EraGovRun.dijkstraEmpty,
      ScarabEvaluator.default,
      flat.Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      slot => Right(slot * 1000)
    )

  test("the node's run reads its network from the globals and validates every check") {
    val (raw, seeded) = sampled(byBlock.head)
    // the refusal or the state after, of the node's run and of BBODY on a context given whole
    def ofNode(n: Network) = nodeRun(onNetwork(n), raw, seeded) match
      case Right(Left(fs))          => Left(fs.toChain.toVector)
      case Right(Right((after, _))) => Right(after)
      case Left(d)                  => fail(s"a defect: $d")
    def judgedOn(network: Network, validation: flat.TxEnv.Validation) =
      run(raw, testnetPParams, seeded, DijkstraUtxoContext(network, 0, history, validation)) match
        case Right(Left(fs))             => Left(fs.toChain.toVector)
        case Right(Right((after, _, _))) => Right(after)
        case Left(d)                     => fail(s"a defect: $d")
    val all = flat.TxEnv.Validation.All
    // the two networks tell the block's transactions apart, and so do the two policies
    assertNotEquals(judgedOn(Network.Testnet, all), judgedOn(Network.Mainnet, all))
    assertNotEquals(
      judgedOn(Network.Mainnet, all),
      judgedOn(Network.Mainnet, flat.TxEnv.Validation.NonStatic)
    )
    // the run is BBODY on the globals' network (`networkId`) with every check
    assertEquals(ofNode(Network.Testnet), judgedOn(Network.Testnet, all))
    assertEquals(ofNode(Network.Mainnet), judgedOn(Network.Mainnet, all))
  }

  test("no Dijkstra block value that keeps its bytes has a Mirror to build it from other fields") {
    // a case class's derived `Mirror.ProductOf` builds one from any fields (`fromProduct`), so the
    // block's body and header are plain classes: the body is made only by the block decoder, and
    // the header's fields only as projections of the header it is made from (`fromHeader`)
    Vector(
      compileErrors("summon[scala.deriving.Mirror.ProductOf[DijkstraBlockBody]]"),
      compileErrors("summon[scala.deriving.Mirror.ProductOf[DijkstraBlockHeader]]")
    ).foreach(errs => assert(errs.contains("No given instance"), errs))
    // nor does the header's companion build one from a product of fields
    val fromProduct = compileErrors("DijkstraBlockHeader.fromProduct(EmptyTuple)")
    assert(fromProduct.contains("fromProduct"), fromProduct)
    // the same summon compiles for a case class
    assertEquals(compileErrors("summon[scala.deriving.Mirror.ProductOf[ValidityInterval]]"), "")
  }

  // ---- the blocks made --------------------------------------------------------------------------------

  test("a block is counted for its issuer as a pool, twice for two, and not on an overlay slot") {
    val block = ok(DijkstraBlock.decode(plain))
    val issuer = EraBlockHeader.dijkstra.blockIssuerBlockHeader(block).coerceKeyRole[StakePool]
    val once = ShelleyBbody.incrBlocks(block, 0, UnitInterval.zero, BlocksMade.empty)
    val twice = ShelleyBbody.incrBlocks(block, 0, UnitInterval.zero, once)
    assertEquals(twice.blocks.get(issuer).map(_.bits), Some(2L))
    // with a decentralisation of one every slot is the overlay's (`isOverlaySlot`,
    // `Slot.hs:117-130`, cardano-ledger 9df9f98c)
    val d = UnitInterval.boundRational(Rational(1)).getOrElse(fail("d"))
    assertEquals(ShelleyBbody.incrBlocks(block, 0, d, BlocksMade.empty), BlocksMade.empty)
  }

  // ---- the injections ---------------------------------------------------------------------------------

  test("BBODY injects each failure as the reference composes it") {
    def into[F](f: F)(using i: InjectBbodyFailure[F, Dijkstra]): BbodyPredFailure[Dijkstra] =
      i.injectFailure(f)
    val size = Mismatch[RelEQ, Long](1L, 2L)
    val refScripts = Mismatch[RelLTEQ, Long](1L, 2L)
    val majors = Mismatch[RelGTEQ, Unsigned[W32]](Unsigned.lit(1), Unsigned.lit(2))
    val hashes = Mismatch[RelEQ, xyz.kd5ujc.scarab.ledger.state.Hash32](hash(1), hash(2))
    val units = Mismatch[RelLTEQ, ExUnits](ExUnits(1, 2), ExUnits(0, 0))
    val ledger: DijkstraLedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch(Mismatch(Coin(1), Coin(2)))
    val ledgers = ShelleyLedgersPredFailure.LedgerFailure[Dijkstra](ledger)
    // Dijkstra's own: the identity
    val own: DijkstraBbodyPredFailure[Dijkstra] =
      DijkstraBbodyPredFailure.HeaderProtVerTooLow[Dijkstra](majors)
    assertEquals(into(own), own)
    // Shelley's: `conwayToDijkstra . shelleyToConway`
    assertEquals(
      into(ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](size)),
      DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](size)
    )
    assertEquals(
      into(ShelleyBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](hashes)),
      DijkstraBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](hashes)
    )
    assertEquals(
      into(ShelleyBbodyPredFailure.LedgersFailure[Dijkstra](ledgers)),
      DijkstraBbodyPredFailure.LedgersFailure[Dijkstra](ledgers)
    )
    // Alonzo's: `conwayToDijkstra . alonzoToConway`
    val alonzo = AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure[Dijkstra](
      ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY(size)
    )
    assertEquals(
      into(alonzo),
      into(
        ConwayBbodyPredFailure.alonzoToConwayBbodyPredFailure(alonzo) match
          case c: ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra] => c
          case other                                                       => fail(s"$other")
      )
    )
    assertEquals(
      into(AlonzoBbodyPredFailure.TooManyExUnits[Dijkstra](units)),
      DijkstraBbodyPredFailure.TooManyExUnits[Dijkstra](units)
    )
    // Alonzo's Shelley failures, each as Dijkstra's of the same name and payload
    assertEquals(
      into(
        AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure[Dijkstra](
          ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY(size)
        )
      ),
      DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](size)
    )
    assertEquals(
      into(
        AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure[Dijkstra](
          ShelleyBbodyPredFailure.InvalidBodyHashBBODY(hashes)
        )
      ),
      DijkstraBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](hashes)
    )
    assertEquals(
      into(
        AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure[Dijkstra](
          ShelleyBbodyPredFailure.LedgersFailure(ledgers)
        )
      ),
      DijkstraBbodyPredFailure.LedgersFailure[Dijkstra](ledgers)
    )
    // Conway's, on the five it maps
    assertEquals(
      into(new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](size)),
      DijkstraBbodyPredFailure.WrongBlockBodySizeBBODY[Dijkstra](size)
    )
    assertEquals(
      into(new ConwayBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](hashes)),
      DijkstraBbodyPredFailure.InvalidBodyHashBBODY[Dijkstra](hashes)
    )
    assertEquals(
      into(new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](refScripts)),
      DijkstraBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra](refScripts)
    )
    assertEquals(
      into(new ConwayBbodyPredFailure.TooManyExUnits[Dijkstra](units)),
      DijkstraBbodyPredFailure.TooManyExUnits[Dijkstra](units)
    )
    assertEquals(
      into(new ConwayBbodyPredFailure.LedgersFailure[Dijkstra](ledgers)),
      DijkstraBbodyPredFailure.LedgersFailure[Dijkstra](ledgers)
    )
    // LEDGERS's and below: `LedgersFailure . injectFailure`
    assertEquals(into(ledgers), DijkstraBbodyPredFailure.LedgersFailure[Dijkstra](ledgers))
    assertEquals(into(ledger), DijkstraBbodyPredFailure.LedgersFailure[Dijkstra](ledgers))
    // and the embedding of LEDGERS
    assertEquals(
      EmbedLedgersInBbody.dijkstra.wrapFailed(ledgers),
      DijkstraBbodyPredFailure.LedgersFailure[Dijkstra](ledgers)
    )
  }

  test("BBODY has an injection for each failure the reference lists, and the classes resolve") {
    // the four of BBODY's own (`Dijkstra/Rules/Bbody.hs:150-159`, cardano-ledger 9df9f98c)
    val own = Vector[AnyRef](
      summon[InjectBbodyFailure[DijkstraBbodyPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ConwayBbodyFailureInDijkstra[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[AlonzoBbodyPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ShelleyBbodyPredFailure[Dijkstra], Dijkstra]]
    )
    // the twenty through LEDGERS (`:161-296`)
    val throughLedgers = Vector[AnyRef](
      summon[InjectBbodyFailure[ShelleyLedgersPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraLedgerPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraUtxowPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[BabbageUtxowPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[AlonzoUtxowPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ShelleyUtxowPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraUtxoPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraUtxo.BabbageFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraUtxo.AlonzoFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[AlonzoUtxosPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ConwayUtxosPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraUtxo.ShelleyFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraUtxo.AllegraFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ConwayCertPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ConwayDelegPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ShelleyPoolPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraGovCertPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[ConwayGovPredFailure[Dijkstra], Dijkstra]],
      summon[InjectBbodyFailure[DijkstraGovPredFailure[Dijkstra], Dijkstra]]
    )
    assertEquals(own.size + throughLedgers.size, 24)
    // a failure LEDGERS has no instance for has none in BBODY either, as in the reference
    assert(
      compileErrors(
        "summon[InjectBbodyFailure[ShelleyLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    // the block classes resolve from both companions, to Dijkstra's instance
    assert(summon[EraBlockBody[Dijkstra]] eq summon[DijkstraEraBlockBody[Dijkstra]])
    assert(summon[EraBlockHeader[DijkstraBlockHeader, Dijkstra]] eq DijkstraBlockHeader.instance)
    assert(
      summon[LeiosEraBlockHeader[DijkstraBlockHeader, Dijkstra]] eq DijkstraBlockHeader.instance
    )
  }

  test("BBODY has no injection for the constructor the reference calls impossible") {
    // the control: a constructor `conwayToDijkstraBbodyPredFailure` maps has one
    assertEquals(
      compileErrors(
        "summon[InjectBbodyFailure[ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    // `error "Impossible: HeaderProtVerTooHigh ..."` (`Dijkstra/Rules/Bbody.hs:413`, cardano-ledger
    // 9df9f98c)
    assert(
      compileErrors(
        "summon[InjectBbodyFailure[ConwayBbodyPredFailure.HeaderProtVerTooHigh[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectBbodyFailure[ConwayBbodyPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }
