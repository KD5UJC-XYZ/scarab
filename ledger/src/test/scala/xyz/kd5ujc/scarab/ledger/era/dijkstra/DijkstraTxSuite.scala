// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, CborRead, DecodeField, LedgerDecodeError, PreprodDeepTx}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.{
  AsIxItem,
  BothLevels,
  EraScript,
  EraTx,
  EraTxBody,
  EraTxError,
  IsPhase2Valid,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{Credential, PoolParamsBlsKey}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

/** Dijkstra's transactions on the era types ([[DijkstraTx]], [[DijkstraTxBody]],
  * [[DijkstraTxWits]], [[DijkstraTxAuxData]], cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/{Tx,TxBody,TxWits,TxAuxData}.hs`). Every
  * Dijkstra transaction the repository holds is read by the era decoder, which accepts and refuses
  * what the flat block decoder it replaced did (the tallies here were the flat decoder's, #85);
  * each transaction read is held to what its own parts give: its id, its size for the fee and its
  * block form with either flag, its sub-transactions at the sub-transaction level in the order and
  * with the ids read, its inputs, and the items every redeemer key names; a pool registration's
  * parameters with their voting key.
  *
  * The transactions are those of `fixtures/reference/leios-testnet-blocks` (the corpus of the Leios
  * build's verdicts, its blocks and its transactions alone, recorded and synthetic), the public
  * Leios testnet's blocks (`fixtures/leios/testnet`), the reference cluster's Conway to Dijkstra
  * chain and its submitted transaction (`fixtures/dijkstra-devnet`), and the transactions of the
  * script-context fixtures (`fixtures/reference/leios-testnet-contexts` and
  * `leios-testnet-chain-contexts`, the latter recorded on the testnet's chain).
  */
class DijkstraTxSuite extends FunSuite:
  import DijkstraCorpus.{hex, node, str}

  private val bodies = EraTxBody.dijkstra

  /** The size of `[body, witnesses, auxiliary data / null]` with a one-byte list head, the parts
    * written as they arrived: the bytes of `toCBORForSizeComputation` (`Tx.hs:297-308`,
    * cardano-ledger 9df9f98c).
    */
  private def partsSize(body: Bytes, wits: Bytes, aux: Option[Bytes]): Long =
    (Bytes.fromArray(Array(0x83.toByte)) ++ body ++ wits ++
      aux.getOrElse(Bytes.fromArray(Array(0xf6.toByte)))).size.toLong

  /** A purpose read by each of the script class's readers and built again by that reader's builder
    * (`ConwayEraScript`, `DijkstraEraScript`, and Alonzo's builders, `Scripts.hs:508-581`,
    * cardano-ledger 9df9f98c): only the reader of its own constructor reads it, and its builder
    * gives it back.
    */
  private def roundTrips(p: DijkstraPlutusPurpose[AsIxItem], what: String): Unit =
    val s = EraScript.dijkstra
    val rebuilt = Vector(
      s.toSpendingPurpose[AsIxItem](p).map(s.mkSpendingPurpose[AsIxItem]),
      s.toMintingPurpose[AsIxItem](p).map(s.mkMintingPurpose[AsIxItem]),
      s.toCertifyingPurpose[AsIxItem](p).map(s.mkCertifyingPurpose[AsIxItem]),
      s.toWithdrawingPurpose[AsIxItem](p).map(s.mkWithdrawingPurpose[AsIxItem]),
      s.toVotingPurpose[AsIxItem](p).map(s.mkVotingPurpose[AsIxItem]),
      s.toProposingPurpose[AsIxItem](p).map(s.mkProposingPurpose[AsIxItem]),
      s.toGuardingPurpose[AsIxItem](p).map(s.mkGuardingPurpose[AsIxItem])
    )
    assertEquals(rebuilt.flatten, Vector(p), what)
    assertEquals(rebuilt.indexWhere(_.isDefined), p.tag, what)

  /** Every redeemer key of each purpose of a body, to one past its last item
    * (`dijkstraRedeemerPointerInverse`, `TxBody.hs:1297-1316`, cardano-ledger 9df9f98c): an index
    * within the items names one, which its purpose's readers and builders give back, but a guard
    * that is a key's; one past the last names none.
    */
  private def pointers[L <: TxLevel](era: DijkstraTxBody[L], what: String)(using
      BothLevels[L]
  ): Unit =
    val b = bodies
    val guards = b.guards[L](era).toVector
    val counts = Vector(
      b.inputs[L](era).size,
      b.mint[L](era).size,
      b.certs[L](era).size,
      b.withdrawals[L](era).unWithdrawals.size,
      b.votingProcedures[L](era).unVotingProcedures.size,
      b.proposalProcedures[L](era).toVector.size,
      guards.size
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
      val named = b.redeemerPointerInverse(era, ptr)
      val expected = ix < count && (tag != 6 || (guards(ix) match
        case Credential.ScriptHashObj(_) => true
        case Credential.KeyHashObj(_)    => false))
      assertEquals(named.isDefined, expected, s"$what: purpose $tag at $ix")
      named.foreach(roundTrips(_, s"$what: purpose $tag at $ix"))

  /** A transaction the era decoder read from its item `n`, held to what its own parts give: its id
    * is its body's hash; its size for the fee and its block form (`toCBORForBlockInclusion`, with
    * either flag, read again to itself) are its parts' as they arrived; its sub-transactions are in
    * the order read, by their ids, each at the sub-transaction level with its own size; every input
    * it names is among `allInputsTxBodyF` 's; and every redeemer key resolves within its items. The
    * number of its sub-transactions.
    */
  private def checked(era: DijkstraTx[TxLevel.Top], what: String): Int =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    assertEquals(era.id, xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(era.body.raw), what)
    assertEquals(
      era.sizeTxF,
      partsSize(era.body.raw, era.wits.raw, era.auxData.map(_.raw)),
      what
    )
    IsPhase2Valid.values.foreach { v =>
      val flagged = DijkstraTx.withIsValid(era, v)
      assertEquals(
        DijkstraTx.inBlock(node(DijkstraTx.forBlockInclusion(flagged))),
        Right(flagged),
        s"$what, $v"
      )
    }
    pointers(era.body, what)
    val subs = bodies.subTransactions(era.body)
    assertEquals(subs.keys, subs.toVector.map(_.id), what)
    subs.keys.foreach(k => assertEquals(subs.get(k).map(_.id), Some(k), what))
    assertEquals(subs.get(era.id), None, what)
    subs.toVector.foreach { s =>
      given BothLevels[TxLevel.Sub] = BothLevels.Sub
      val sw = s"$what, sub-transaction ${s.id.hex}"
      assertEquals(s.sizeTxF, partsSize(s.body.raw, s.wits.raw, s.auxData.map(_.raw)), sw)
      assertEquals(bodies.spendableInputs(s.body), bodies.inputs(s.body), sw)
      pointers(s.body, sw)
    }
    // `dijkstraAllInputsTxBodyF`: the top level's spent, collateral and reference inputs and each
    // sub-transaction's spent and reference inputs
    assertEquals(
      bodies.allInputs(era.body),
      bodies.inputs(era.body) ++ bodies.collateralInputs(era.body) ++
        bodies.referenceInputs(era.body) ++ subs.toVector.flatMap(s =>
          bodies.inputs(s.body)(using BothLevels.Sub) ++
            bodies.referenceInputs(s.body)(using BothLevels.Sub)
        ),
      what
    )
    subs.size

  private def registersPool(era: DijkstraTx[TxLevel.Top]): Boolean =
    def in[L <: TxLevel](b: DijkstraTxBody[L])(using BothLevels[L]) =
      bodies.certs[L](b).exists(bodies.certsOf.getRegPoolTxCert(_).isDefined)
    in(era.body)(using BothLevels.Top) ||
    bodies.subTransactions(era.body).toVector.exists(s => in(s.body)(using BothLevels.Sub))

  /** The era decoder's reading of a transaction: the transactions read, their sub-transactions and
    * those that register a pool, and the refusals, each the decoder's.
    */
  private final case class Tally(txs: Int, subs: Int, pools: Int, refused: Int):
    def +(o: Tally): Tally = Tally(txs + o.txs, subs + o.subs, pools + o.pools, refused + o.refused)

  private val none = Tally(0, 0, 0, 0)

  private def tallied(era: Either[EraTxError, DijkstraTx[TxLevel.Top]], what: String): Tally =
    era match
      case Left(EraTxError.Decode(_)) => Tally(0, 0, 0, 1)
      case Left(other)                => fail(s"$what: not the decoder's refusal: $other")
      case Right(t) => Tally(1, checked(t, what), if registersPool(t) then 1 else 0, 0)

  // the bytes read as CBOR first, by the one reader both decoders are given, as a block body reads
  // each transaction; bytes that are not one item of CBOR are refused before either decoder
  private def inBlock(raw: Bytes, what: String): Tally =
    CborRead.decode(raw, V12.nestedLimits) match
      case Right(n) => tallied(DijkstraTx.inBlock(n), what)
      case Left(_)  => Tally(0, 0, 0, 1)

  private def ofBlock(raw: Bytes, what: String): Tally =
    Block.decodeShelley(raw) match
      case Right(_: Block.Dijkstra) =>
        DijkstraCorpus
          .blockItems(raw)
          .zipWithIndex
          .map((t, i) => inBlock(t, s"$what tx $i"))
          .foldLeft(none)(_ + _)
      case other => fail(s"$what: $other")

  private def corpus = DijkstraCorpus.leiosCorpus

  test(
    "every transaction of the Leios corpus reads as the flat decoder reads it, or is refused as it"
  ) {
    val txs = corpus.filter(j => str(j, "kind") == "tx")
    val tally = txs.map(j => inBlock(hex(str(j, "bytes")), str(j, "name"))).foldLeft(none)(_ + _)
    // the corpus's 536 transactions alone: the 273 the build accepts, 16 of which register a pool,
    // each read with its parameters, with 20 sub-transactions among them, and the 263 it refuses
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
    // and its pool registrations, read with the voting key the Leios build's `StakePoolParams`
    // holds (`sppBlsKey`, cardano-ledger 9df9f98c `State/StakePool.hs:479`), or with its place
    // empty
    val keys = txs
      .flatMap(j => CborRead.decode(hex(str(j, "bytes")), V12.nestedLimits).toOption)
      .flatMap(n => DijkstraTx.inBlock(n).toOption)
      .flatMap(t => bodies.certs(t.body)(using BothLevels.Top).toVector)
      .flatMap(bodies.certsOf.getRegPoolTxCert)
      .map(_.blsKey match
        case PoolParamsBlsKey.Leios(key) => key.isDefined
        case PoolParamsBlsKey.Node11_1   => fail("a Dijkstra registration of the 11.1 layout"))
    assertEquals((keys.count(identity), keys.count(!_)), (4, 12))
  }

  test(
    "every transaction of the corpus's accepted Leios blocks is read as recorded"
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
      case Right(_) =>
        DijkstraCorpus
          .blockItems(raw)
          .zipWithIndex
          .map((t, i) => inBlock(t, s"$what tx $i"))
          .foldLeft(none)(_ + _)
      case Left(_) => none

  test(
    "every transaction of the public Leios testnet's blocks is read as recorded"
  ) {
    val rows = DijkstraCorpus.testnetBlocks
    assert(rows.nonEmpty)
    val tally = rows.map((name, raw) => ofBlock(raw, name)).foldLeft(none)(_ + _)
    // the 40 transactions of the announcing block, none with sub-transactions
    assertEquals(tally, Tally(txs = 40, subs = 0, pools = 0, refused = 0))
  }

  test(
    "the devnet chain's Dijkstra transactions and its submitted one are read as recorded"
  ) {
    val tally =
      DijkstraCorpus.devnetBlocks.map((name, raw) => ofBlock(raw, name)).foldLeft(none)(_ + _)
    val submitted = DijkstraCorpus.devnetSubmitted
    val mempool = tallied(DijkstraTx.fromSubmission(submitted), "the submitted transaction")
    assertEquals((tally, mempool), (Tally(1, 0, 0, 0), Tally(1, 0, 0, 0)))
  }

  test("the transactions of the script-context fixtures are read as recorded") {
    val contexts = DijkstraCorpus.contextCases
      .map(c => inBlock(hex(str(c, "bytes")), str(c, "name")))
      .foldLeft(none)(_ + _)
    val chain = DijkstraCorpus.chainContextCases
      .map(c => inBlock(hex(str(c, "bytes")), str(c, "name")))
      .foldLeft(none)(_ + _)
    // the 200 transactions recorded on the testnet's chain hold 38 of its 280 sub-transactions
    assertEquals((contexts, chain), (Tally(11, 3, 1, 0), Tally(200, 38, 0, 0)))
    // some arrived in an order that is not their ids', which the ordered map keeps
    val unsorted = (DijkstraCorpus.contextCases ++ DijkstraCorpus.chainContextCases)
      .flatMap(c => DijkstraTx.inBlock(node(hex(str(c, "bytes")))).toOption)
      .map(t => bodies.subTransactions(t.body).keys)
      .count(ids => ids.sizeIs >= 2 && ids != ids.sorted)
    assert(unsorted > 0, "every recorded sub-transaction list is in its ids' order")
  }

  test(
    "the transactions compared for their block form cover auxiliary data, its absence and redeemers of sub-transactions"
  ) {
    // what `sameMeasures` compares is covered: a transaction with auxiliary data and one without
    // (their block forms differ in the third item), and one whose sub-transactions carry
    // redeemers (whose units `getTotalExUnits` adds at the top level)
    val blocks = DijkstraCorpus.testnetBlocks.flatMap((_, raw) =>
      Block.decodeShelley(raw) match
        case Right(_: Block.Dijkstra) => DijkstraCorpus.blockItems(raw)
        case _                        => Vector.empty
    )
    val read = ((DijkstraCorpus.contextCases ++ DijkstraCorpus.chainContextCases)
      .map(c => hex(str(c, "bytes"))) ++ blocks)
      .flatMap(raw => DijkstraTx.inBlock(node(raw)).toOption)
    assert(read.exists(_.auxData.isDefined), "no transaction with auxiliary data")
    assert(read.exists(_.auxData.isEmpty), "no transaction without auxiliary data")
    val subUnits = read.count(t =>
      bodies
        .subTransactions(t.body)
        .toVector
        .exists(s =>
          EraTx.dijkstra.getTotalExUnits[TxLevel.Sub](s)(using BothLevels.Sub) !=
            xyz.kd5ujc.scarab.plutus.ExUnits.zero
        )
    )
    assert(subUnits > 0, "no sub-transaction with redeemers")
  }

  test(
    "the distinct transactions of the reference's UTXOW cases are read as recorded"
  ) {
    // `fixtures/reference/dijkstra-rules`: the devnet's transaction and its variants, each a
    // transaction of a block body; 11 of the earlier cases, and the collateral return to a pointer
    // address of the cases of 2026-11-24 (`utxow/collateral-return-pointer`)
    val txs = DijkstraCorpus.utxowCases.map(j => str(j, "tx")).distinct
    val tally = txs.zipWithIndex.map((t, i) => inBlock(hex(t), s"utxow $i")).foldLeft(none)(_ + _)
    assertEquals(tally, Tally(11 + 1, 0, 0, 0))
  }

  test("the transactions of the reference's ENTITIES cases are read as recorded") {
    // `fixtures/reference/dijkstra-rules`, the cases of 2026-11-24: 13 transactions of a block body
    // whose bodies withdraw, deposit directly, bound balances or deregister, all accepted by the
    // reference's decoder, which the oracle reads them with
    val txs = DijkstraCorpus
      .jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
      .filter(j => str(j, "kind") == "entities")
      .map(j => str(j, "tx"))
      .distinct
    val tally =
      txs.zipWithIndex.map((t, i) => inBlock(hex(t), s"entities $i")).foldLeft(none)(_ + _)
    assertEquals(tally, Tally(13, 0, 0, 0))
  }

  test("the Dijkstra mempool forms of the crossing corpus are read as recorded") {
    // `fixtures/reference/dijkstra-crossing`: each Conway transaction the reference carried into
    // Dijkstra, in Dijkstra's mempool form (`toCBORForMempoolSubmission`), as a client or the
    // hard-fork mempool submits it; that form is also the size the reference computes for the fee
    val forms = DijkstraCorpus.crossingForms
    val tally = forms.zipWithIndex
      .map { (raw, i) =>
        val era = DijkstraTx.fromSubmission(raw)
        era.foreach(t => assertEquals(t.sizeTxF, raw.size.toLong, s"crossing $i: size"))
        tallied(era, s"crossing $i")
      }
      .foldLeft(none)(_ + _)
    // all 149 accepted, 9 of them registering a pool
    assertEquals(forms.size, 149)
    assertEquals(tally, Tally(149, 0, 9, 0))
  }

  test("a body and a transaction are made only by the decoders, from what they read from bytes") {
    // the readers of a body, a witness set and auxiliary data take a proof only the decoders hold
    val proof =
      "No given instance of type xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.FromBytes was found"
    Vector(
      compileErrors("def f(n: xyz.kd5ujc.scarab.codec.Node) = DijkstraTxBody.readSub(n)"),
      compileErrors(
        "def f(n: xyz.kd5ujc.scarab.codec.Node, s: xyz.kd5ujc.scarab.codec.Node => Either[xyz.kd5ujc.scarab.ledger.era.EraTxError, DijkstraTx[TxLevel.Sub]]) = DijkstraTxBody.readTop(n, s)"
      ),
      compileErrors("def f(n: xyz.kd5ujc.scarab.codec.Node) = DijkstraTxWits.read(n)"),
      compileErrors("def f(n: xyz.kd5ujc.scarab.codec.Node) = DijkstraTxAuxData.read(n)")
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

  test("a sub-transaction id given twice is refused with the decoder's error") {
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
    "the cardano-cbor-dataset's Dijkstra witness and auxiliary samples read, their bytes kept"
  ) {
    // `CborDatasetSuite`'s redeemers, indefinite, as key 5 of a witness set: their bytes are kept
    val redeemers = "bf820000829f01ff821903e81b0000000100000000ff"
    val set = hex("a105" + redeemers)
    val era = DijkstraTxWits.decode(node(set)).fold(e => fail(e.toString), identity)
    assertEquals(era.raw, set)
    // one redeemer, of the spending purpose at index 0, with its data and units
    assertEquals(era.redeemers.entries.size, 1)
    assertEquals(era.redeemers.raw, Some(hex(redeemers)))
    assertEquals(era.datums.raw, None)
    // its auxiliary data with a native script that requires a guard
    val guard = "82068200581c" + "00" * 28
    val aux = hex("82a081" + guard)
    val eraAux = DijkstraTxAuxData.decode(node(aux)).fold(e => fail(e.toString), identity)
    assertEquals(eraAux.raw, aux)
    assertEquals(eraAux.metadata.size, 0)
    assertEquals(eraAux.nativeScripts.map(_.raw), Vector(hex(guard)))
  }

  test("a witness set's datums tagged 258 and redeemers in an indefinite map keep their bytes") {
    val datums = "d9010281d87980"
    val redeemers = "bf82000082d87980820101ff"
    val set = hex(s"a204${datums}05$redeemers")
    val era = DijkstraTxWits.decode(node(set)).fold(e => fail(e.toString), identity)
    assertEquals(era.raw, set)
    assertEquals(era.redeemers.entries.size, 1)
    assertEquals((era.datums.raw, era.redeemers.raw), (Some(hex(datums)), Some(hex(redeemers))))
    assertEquals(era.datums.datums.values.map(_.originalBytes).toVector, Vector(hex("d87980")))
  }

  test(
    "auxiliary Plutus scripts arrive by language, as the block decoder lists them, in any map order"
  ) {
    // the tagged map with Plutus V4 (key 5) before Plutus V1 (key 2)
    val aux = hex("d90103a2058141aa028141bb")
    val era = DijkstraTxAuxData.decode(node(aux)).fold(e => fail(e.toString), identity)
    assertEquals(era.raw, aux)
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
