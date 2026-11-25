// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.NonEmptyList
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, LeiosBlock, TxIn}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraDatum,
  DijkstraScript as FlatScript,
  DijkstraTxIn,
  DijkstraTxOut as FlatTxOut
}
import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  CollectError,
  PlutusWithContext,
  SubOnly,
  AlonzoUTxO,
  BabbageUTxO,
  BothLevels,
  EraScript,
  EraTx,
  EraTxBody,
  EraTxWits,
  EraUTxO,
  IsPhase2Valid,
  PParams,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, LedgerStateGen}
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusLanguage}

import DijkstraCorpus.*

/** Dijkstra's functions of the UTxO set and its instances of the transaction and UTxO classes on
  * the era types (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/{Tx,UTxO}.hs`, cardano-ledger
  * 9df9f98c).
  *
  * On every Dijkstra transaction the repository holds ([[DijkstraCorpus]]) at both levels: the
  * scripts needed, by purpose, as the flat Dijkstra rules counted them when they were compared
  * (#85); and, from what the transaction's own parts and its outputs hold, the execution units of
  * the batch and of each sub-transaction, the reference scripts' size and the supplemental datums,
  * exactly. A transaction whose corpus records no UTxO set is given stand-in outputs, a key's and a
  * script's in turn.
  *
  * Against the reference, as component checks (CNF-2), not its verdict or the resulting state: on
  * the 200 transactions recorded on the public Leios testnet's chain, which its ledger accepted,
  * with the outputs they spend, what acceptance requires of these functions (the value consumed
  * equals the value produced, the keys needed are among the witnesses', the scripts needed less the
  * reference scripts are the witnesses, the witnesses' datums are allowed); and on the reference's
  * UTXOW cases, the values, keys, scripts and fee its refusals record.
  */
class DijkstraUTxOSuite extends FunSuite:
  private val txs = EraTx.dijkstra
  private val bodies = EraTxBody.dijkstra
  private val utxos = EraUTxO.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** A transaction of the corpus: the era decoder's reading and, where the corpus records them, the
    * outputs its inputs spend, as input and output bytes.
    */
  private final case class Case(
      name: String,
      era: DijkstraTx[TxLevel.Top],
      recorded: Option[Vector[(Bytes, Bytes)]]
  )

  private def read(name: String, raw: Bytes, recorded: Option[Vector[(Bytes, Bytes)]]) =
    DijkstraTx.inBlock(node(raw)) match
      case Right(e)                   => Vector(Case(name, e, recorded))
      case Left(EraTxError.Decode(_)) => Vector.empty
      case Left(other)                => fail(s"$name: $other")

  private def utxoOf(j: io.circe.Json): Vector[(Bytes, Bytes)] =
    j.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map((i, o) => (hex(i), hex(o)))

  private lazy val cases: Vector[Case] =
    val corpusTxs = leiosCorpus
      .filter(j => str(j, "kind") == "tx")
      .flatMap(j =>
        DijkstraCorpus
          .cbor(hex(str(j, "bytes")))
          .toVector
          .flatMap(_ => read(str(j, "name"), hex(str(j, "bytes")), None))
      )
    val corpusBlocks = leiosCorpus
      .filter(j => str(j, "kind") == "block")
      .flatMap(j =>
        LeiosBlock
          .decode(hex(str(j, "bytes")))
          .toOption
          .toVector
          .flatMap(_ =>
            blockItems(hex(str(j, "bytes"))).zipWithIndex
              .flatMap((t, i) => read(s"${str(j, "name")} tx $i", t, None))
          )
      )
    def shelleyBlock(name: String, raw: Bytes) = Block.decodeShelley(raw) match
      case Right(_: Block.Dijkstra) =>
        blockItems(raw).zipWithIndex.flatMap((t, i) => read(s"$name tx $i", t, None))
      case other => fail(s"$name: $other")
    val blocks = (testnetBlocks ++ devnetBlocks).flatMap(shelleyBlock)
    val contexts =
      (contextCases ++ chainContextCases).flatMap(c =>
        read(str(c, "name"), hex(str(c, "bytes")), Some(utxoOf(c)))
      )
    val utxow = utxowCases.flatMap(c => read(str(c, "name"), hex(str(c, "tx")), Some(utxoOf(c))))
    val mempool = (devnetSubmitted +: crossingForms).zipWithIndex.flatMap { (raw, i) =>
      DijkstraTx.fromSubmission(raw) match
        case Right(e) => Vector(Case(s"mempool form $i", e, None))
        case other    => fail(s"mempool form $i: $other")
    }
    corpusTxs ++ corpusBlocks ++ blocks ++ contexts ++ utxow ++ mempool

  // ---- the UTxO set, with each output's own reading by the output decoder

  private final case class Utxos(outputs: Map[TxIn, FlatTxOut], era: UTxO[Dijkstra])

  private def txIn(i: DijkstraTxIn): TxIn = TxIn(i.id, BigInt(i.index))

  // the bodies of a transaction: its own and its sub-transactions'
  private def levels(
      c: Case
  ): Vector[Either[DijkstraTxBody[TxLevel.Top], DijkstraTxBody[TxLevel.Sub]]] =
    Left(c.era.body) +: bodies.subTransactions(c.era.body).toVector.map(s => Right(s.body))

  private def spentAndRead(
      b: Either[DijkstraTxBody[TxLevel.Top], DijkstraTxBody[TxLevel.Sub]]
  ): Set[TxIn] = b match
    case Left(t) => (bodies.inputs[TxLevel.Top](t) ++ bodies.referenceInputs[TxLevel.Top](t)).toSet
    case Right(s) =>
      given BothLevels[TxLevel.Sub] = BothLevels.Sub
      (bodies.inputs[TxLevel.Sub](s) ++ bodies.referenceInputs[TxLevel.Sub](s)).toSet

  /** An output at an enterprise address of the testnet holding one ada: a key's or a script's. */
  private def standIn(script: Boolean, hash: Bytes): Bytes =
    hex(s"a200581d${if script then "70" else "60"}${hash.hex}011a000f4240")

  private def utxosOf(c: Case): Utxos = utxosOf(c, Map.empty)

  /** The case's UTxO set, recorded or stand-in, with the outputs `crafted` gives its inputs, by the
    * input's bytes, in place of those.
    */
  private def utxosOf(c: Case, crafted: Map[Bytes, Bytes]): Utxos =
    val pairs = c.recorded.getOrElse {
      val inputs = levels(c).flatMap {
        case Left(t) =>
          bodies.inputs[TxLevel.Top](t).toVector ++ bodies.referenceInputs[TxLevel.Top](t).toVector
        case Right(s) =>
          given BothLevels[TxLevel.Sub] = BothLevels.Sub
          bodies.inputs[TxLevel.Sub](s).toVector ++ bodies.referenceInputs[TxLevel.Sub](s).toVector
      }.distinct
      inputs.zipWithIndex.map { (in, k) =>
        val hash = Bytes(Vector.fill(28)((k % 251).toByte))
        (DijkstraCorpus.encodeTxIn(DijkstraTxIn(in.id, in.index.toInt)), standIn(k % 2 == 1, hash))
      }
    }
    val read = pairs.map { (i, o0) =>
      val o = crafted.getOrElse(i, o0)
      val in = DijkstraTxIn.decode(node(i)).fold(e => fail(e.toString), identity)
      val flatOut = FlatTxOut.decode(node(o)).fold(e => fail(e.toString), identity)
      val eraOut = DijkstraTxOut.decode(node(o)).fold(e => fail(e.toString), identity)
      (in, flatOut, eraOut)
    }
    Utxos(
      read.map((i, f, _) => txIn(i) -> f).toMap,
      UTxO[Dijkstra](read.map((i, _, e) => txIn(i) -> e).toMap)
    )

  // ---- the corpus

  test("the corpus holds the transactions the suites of the era types read") {
    // every transaction the flat decoder accepts (`DijkstraTxSuite`): the Leios corpus's 273 and
    // its blocks' 63, the testnet's 40, the devnet's one, the contexts' 11 and 200, the reference's
    // 18 UTXOW cases (12 distinct transactions: the 17 earlier cases, and the collateral return to
    // a pointer address of the cases of 2026-11-24, `utxow/collateral-return-pointer`), the devnet's
    // submitted one and the crossing corpus's 149
    assertEquals(cases.size, 273 + 63 + 40 + 1 + 11 + 200 + (17 + 1) + 1 + 149)
  }

  test("the scripts needed at each level are those the flat rules needed, by purpose") {
    val needed = cases.flatMap { c =>
      val u = utxosOf(c)
      utxos.getScriptsNeeded[TxLevel.Top](u.era, c.era.body) +:
        bodies
          .subTransactions(c.era.body)
          .toVector
          .map(sub => utxos.getScriptsNeeded[TxLevel.Sub](u.era, sub.body)(using BothLevels.Sub))
    }
    val needs = needed.flatMap(_.unAlonzoScriptsNeeded)
    // every purpose is reached, the guarding one among them, at both levels; the count of each, as
    // the flat rules needed them on these transactions when the two were compared (#85)
    assertEquals(
      needs.groupBy(_._1.tag).view.mapValues(_.size).toMap,
      Map(0 -> 160, 1 -> 130, 2 -> 19, 3 -> 60, 4 -> 6, 5 -> 10, 6 -> 13)
    )
    assert(needed.size > cases.size, "no sub-transaction")
  }

  // the units of a witness set's redeemers
  private def units(w: DijkstraTxWits): ExUnits =
    w.redeemers.entries.values.foldLeft(ExUnits.zero)((acc, r) => acc + r._2)

  test("the execution units add the sub-transactions' to the transaction's redeemers'") {
    // `getTotalExUnits` (`Dijkstra/Tx.hs:417-422`, cardano-ledger 9df9f98c): each
    // sub-transaction's its own redeemers', the top level's its own and every sub-transaction's
    val subUnits = cases.flatMap { c =>
      val subs = bodies.subTransactions(c.era.body).toVector
      val own = subs.map { sub =>
        val got = txs.getTotalExUnits[TxLevel.Sub](sub)(using BothLevels.Sub)
        assertEquals(got, units(sub.wits), s"${c.name}: sub-transaction ${sub.id.hex}")
        got
      }
      assertEquals(
        txs.getTotalExUnits[TxLevel.Top](c.era),
        own.foldLeft(units(c.era.wits))(_ + _),
        c.name
      )
      own
    }
    assert(subUnits.exists(_ != ExUnits.zero), "no sub-transaction has execution units")
  }

  // `originalBytesSize` of a reference script: a Plutus script's program, a native script's bytes
  private def scriptSize(script: FlatScript): Long = script match
    case FlatScript.Native(raw)        => raw.size.toLong
    case FlatScript.Plutus(_, program) => program.size.toLong

  test("the reference scripts' size of the batch adds each level's spent and read outputs'") {
    // `batchNonDistinctRefScriptsSize` (`Dijkstra/UTxO.hs:301-314`, cardano-ledger 9df9f98c): at
    // each level, the reference script of each output its inputs and reference inputs name
    cases.foreach { c =>
      val u = utxosOf(c)
      val expected = levels(c)
        .map(b =>
          spentAndRead(b).toVector
            .flatMap(u.outputs.get)
            .map(_.referenceScript.fold(0L)(scriptSize))
            .sum
        )
        .sum
      assertEquals(DijkstraUTxO.batchNonDistinctRefScriptsSize(u.era, c.era), expected, c.name)
    }
  }

  // ---- what the reference's acceptance of the testnet's transactions requires

  /** The testnet's parameters, as its genesis files give them (no parameter change touched them).
    */
  private lazy val testnetPParams: PParams[Dijkstra] = DijkstraCorpus.testnetPParams

  private lazy val chainCases: Vector[Case] =
    val names = chainContextCases.map(str(_, "name")).toSet
    cases.filter(c => names(c.name))

  test("the testnet's transactions reach the batch's parts the functions add up") {
    def subs(c: Case) = bodies.subTransactions(c.era.body).toVector
    def anyLevel(c: Case)(
        top: DijkstraTxBody[TxLevel.Top] => Boolean,
        sub: DijkstraTxBody[TxLevel.Sub] => Boolean
    ) = top(c.era.body) || subs(c).exists(s => sub(s.body))
    val withSubs = chainCases.count(subs(_).nonEmpty)
    val deposits = chainCases.count(
      anyLevel(_)(
        bodies.directDeposits(_).nonEmpty,
        bodies.directDeposits(_)(using BothLevels.Sub).nonEmpty
      )
    )
    val certs = chainCases.count(
      anyLevel(_)(bodies.certs(_).nonEmpty, bodies.certs(_)(using BothLevels.Sub).nonEmpty)
    )
    val subUnits = chainCases.count(c =>
      subs(c).exists(s => txs.getTotalExUnits[TxLevel.Sub](s)(using BothLevels.Sub) != ExUnits.zero)
    )
    // 28 with sub-transactions, one with direct deposits, ten with certificates, 15 with units in a
    // sub-transaction
    assertEquals((withSubs, deposits, certs, subUnits), (28, 1, 10, 15))
  }

  test(
    "on every transaction the testnet's ledger accepted, the value consumed is the value produced"
  ) {
    assertEquals(chainCases.size, 200)
    chainCases.foreach { c =>
      val u = utxosOf(c)
      assertEquals(
        DijkstraUTxO.dijkstraConsumed[TxLevel.Top](testnetPParams, u.era, c.era.body),
        utxos.getProducedValue(testnetPParams, _ => false, c.era.body),
        c.name
      )
    }
  }

  test(
    "on every transaction the testnet's ledger accepted, the keys needed at each level are witnessed"
  ) {
    chainCases.foreach { c =>
      val u = utxosOf(c)
      def check[L <: TxLevel](tx: DijkstraTx[L], at: String)(using BothLevels[L]) =
        val needed = utxos.getWitsVKeyNeeded[L](LedgerStateGen.conwayCert, u.era, tx.body)
        val witnessed =
          EraTxWits.keyHashWitnessesTxWits[Dijkstra](tx.wits)(using EraTxWits.dijkstra)
        assert(needed.subsetOf(witnessed), s"$at: ${(needed -- witnessed).map(_.hex)}")
      check[TxLevel.Top](c.era, c.name)
      bodies
        .subTransactions(c.era.body)
        .toVector
        .foreach(s =>
          check[TxLevel.Sub](s, s"${c.name}: sub-transaction ${s.id.hex}")(using BothLevels.Sub)
        )
    }
  }

  test(
    "on every transaction the testnet's ledger accepted, the scripts needed are exactly those provided"
  ) {
    // `babbageMissingScripts` over the batch (`Dijkstra/Rules/Utxow.hs:259-277`, cardano-ledger 9df9f98c): every script
    // needed at any level is a reference script of an input or a witness of some level, and every
    // witness script is needed
    chainCases.foreach(c =>
      assertEquals(
        missingAndExtraneous(c, utxosOf(c)),
        (Set.empty[Bytes], Set.empty[Bytes]),
        c.name
      )
    )
  }

  test("on every transaction the testnet's ledger accepted, the witnesses' datums are allowed") {
    // `missingRequiredDatums` (`Alonzo/Rules/Utxow.hs`): the top level's witness datums are those
    // its spent Plutus inputs require or its outputs, its sub-transactions' and its reference
    // inputs' outputs name (`getSupplementalDataHashes`)
    chainCases.foreach { c =>
      val u = utxosOf(c)
      val provided = utxos.getScriptsProvided[TxLevel.Top](u.era, c.era)
      val (inputHashes, _) = AlonzoUTxO.getInputDataHashesTxBody[Dijkstra, TxLevel.Top](
        u.era,
        c.era.body,
        provided
      )(using bodies, BothLevels.Top, bodies.outputsOf, EraScript.dijkstra)
      val allowed = inputHashes ++ utxos.getSupplementalDataHashes[TxLevel.Top](u.era, c.era.body)
      val datums = EraTxWits.dijkstra.datsTxWits(c.era.wits).datums.keySet
      assertEquals(datums -- allowed, Set.empty[Bytes], c.name)
    }
  }

  // ---- the supplemental datums, exactly

  /** The datum hashes a body's outputs, its collateral return and the outputs of its reference
    * inputs name, each output read again by the output decoder from its bytes
    * (`getBabbageSupplementalDataHashes`, `Babbage/UTxO.hs:74-83`, cardano-ledger 9df9f98c).
    */
  private def outputSupplemental(
      u: Utxos,
      b: Either[DijkstraTxBody[TxLevel.Top], DijkstraTxBody[TxLevel.Sub]]
  ): Set[Bytes] =
    val (outs, refs) = b match
      case Left(t) =>
        (
          bodies.allSizedOutputs[TxLevel.Top](t).map(_.value),
          bodies.referenceInputs[TxLevel.Top](t)
        )
      case Right(s) =>
        given BothLevels[TxLevel.Sub] = BothLevels.Sub
        (
          bodies.allSizedOutputs[TxLevel.Sub](s).map(_.value),
          bodies.referenceInputs[TxLevel.Sub](s)
        )
    (outs.map(o => FlatTxOut.decode(node(o.rep.raw)).fold(e => fail(e.toString), identity)) ++
      refs.toVector.flatMap(u.outputs.get))
      .flatMap(_.datum match
        case DijkstraDatum.Hash(h)                           => Some(h)
        case DijkstraDatum.NoDatum | DijkstraDatum.Inline(_) => None)
      .toSet

  /** The supplemental datums of a transaction at each level, each equal to its own body's, the top
    * level's with its sub-transactions' too (`getDijkstraSupplementalDataHashes`,
    * `Dijkstra/UTxO.hs:213-230`, cardano-ledger 9df9f98c): the top level's and each
    * sub-transaction's.
    */
  private def supplemental(c: Case, u: Utxos): (Set[Bytes], Vector[Set[Bytes]]) =
    val subs = bodies.subTransactions(c.era.body).toVector.map { sub =>
      val got =
        utxos.getSupplementalDataHashes[TxLevel.Sub](u.era, sub.body)(using BothLevels.Sub)
      assertEquals(got, outputSupplemental(u, Right(sub.body)), s"${c.name}: sub-transaction")
      got
    }
    val top = utxos.getSupplementalDataHashes[TxLevel.Top](u.era, c.era.body)
    assertEquals(top, outputSupplemental(u, Left(c.era.body)) ++ subs.flatten, c.name)
    (top, subs)

  /** The transaction with its sub-transactions taken out of its body (key 23), its other fields as
    * they were read: the transaction UTXOW reads the datums of in the legacy mode
    * (`Dijkstra/Rules/Utxow.hs:281-285`, cardano-ledger 9df9f98c).
    */
  private def withoutSubTransactions(c: Case): Case =
    import xyz.kd5ujc.scarab.codec.Value as V
    val entries = node(c.era.body.raw).value match
      case V.Map(es) => es
      case other     => fail(s"not a map: $other")
    val kept = entries.filterNot((k, _) =>
      k.value match
        case V.UInt(23) => true
        case _          => false
    )
    assert(kept.size < entries.size && kept.size < 24, s"${c.name}: ${kept.size} fields")
    val body = Bytes(Vector((0xa0 + kept.size).toByte)) ++
      kept.map((k, v) => k.original ++ v.original).foldLeft(Bytes.empty)(_ ++ _)
    val tx = hex("84") ++ body ++ c.era.wits.raw ++ c.era.auxData.fold(hex("f6"))(_.raw) ++
      hex(if DijkstraTx.isValid(c.era) == IsPhase2Valid.Phase2Valid then "f5" else "f4")
    read(s"${c.name} without its sub-transactions", tx, c.recorded).headOption
      .getOrElse(fail(s"${c.name}: refused without its sub-transactions"))

  test("the supplemental datums at each level are exactly those their outputs name") {
    cases.foreach(c => supplemental(c, utxosOf(c)))
    // a sub-transaction's reference input given an output with a datum hash: the hash is the
    // sub-transaction's and the top level's
    val c = cases
      .find(c =>
        bodies
          .subTransactions(c.era.body)
          .toVector
          .exists(s => bodies.referenceInputs[TxLevel.Sub](s.body)(using BothLevels.Sub).nonEmpty)
      )
      .getOrElse(fail("no sub-transaction with a reference input"))
    val ref = bodies
      .subTransactions(c.era.body)
      .toVector
      .flatMap(s => bodies.referenceInputs[TxLevel.Sub](s.body)(using BothLevels.Sub))
      .map(in => DijkstraTxIn(in.id, in.index.toInt))
      .head
    val hash = hex("ab" * 32)
    val withDatum = hex(s"a300581d60${"cd" * 28}011a000f42400282005820${hash.hex}")
    val crafted = Map(DijkstraCorpus.encodeTxIn(ref) -> withDatum)
    val u = utxosOf(c, crafted)
    val (top, subs) = supplemental(c, u)
    assert(subs.exists(_.contains(hash)) && top.contains(hash), s"$top $subs")
    // in the legacy mode UTXOW reads the top level alone, whose supplemental datums lack it
    val legacy = withoutSubTransactions(c)
    val (legacyTop, legacySubs) = supplemental(legacy, utxosOf(legacy, crafted))
    assertEquals(legacySubs, Vector.empty)
    assert(!legacyTop.contains(hash), legacyTop.toString)
  }

  // ---- the values the reference's UTXOW refusals record

  private def failures(c: io.circe.Json): Vector[xyz.kd5ujc.scarab.codec.Node] =
    c.hcursor
      .downField("reference")
      .get[Vector[String]]("failures")
      .getOrElse(Vector.empty)
      .map(f => node(hex(f)))

  /** The scripts the batch needs that neither a reference input nor a witness provides, and the
    * witness scripts that are not needed or that a reference input provides
    * (`babbageMissingScripts`, `Babbage/Rules/Utxow.hs:208-223`, cardano-ledger 9df9f98c, over the
    * batch, `Dijkstra/Rules/Utxow.hs:259-277`). The witnesses and reference scripts it reads are
    * those the scripts provided are made of (`getScriptsProvided`), which it checks too.
    */
  private def missingAndExtraneous(c: Case, u: Utxos): (Set[Bytes], Set[Bytes]) =
    val subs = bodies.subTransactions(c.era.body).toVector
    val needed =
      utxos.getScriptsHashesNeeded(utxos.getScriptsNeeded[TxLevel.Top](u.era, c.era.body)) ++
        subs.flatMap(s =>
          utxos.getScriptsHashesNeeded(
            utxos.getScriptsNeeded[TxLevel.Sub](u.era, s.body)(using BothLevels.Sub)
          )
        )
    val wits = EraTxWits.dijkstra
    val witnessScripts =
      wits.scriptTxWits(c.era.wits).keySet.toSet ++ subs.flatMap(s =>
        wits.scriptTxWits(s.wits).keySet
      )
    val inputs = (bodies.referenceInputs[TxLevel.Top](c.era.body) ++
      bodies.inputs[TxLevel.Top](c.era.body)).toSet ++ subs.flatMap(s =>
      bodies.referenceInputs[TxLevel.Sub](s.body)(using BothLevels.Sub) ++
        bodies.inputs[TxLevel.Sub](s.body)(using BothLevels.Sub)
    )
    val refScripts = BabbageUTxO.getReferenceScripts(u.era, inputs)(using bodies.outputsOf).keySet
    assertEquals(
      utxos.getScriptsProvided[TxLevel.Top](u.era, c.era).unScriptsProvided.keySet.toSet,
      refScripts ++ witnessScripts,
      s"${c.name}: the scripts provided"
    )
    val neededNonRefs = needed -- refScripts
    (neededNonRefs -- witnessScripts, witnessScripts -- neededNonRefs)

  test("a witness of a script a reference input provides is extraneous, as the reference has it") {
    // the reference's accepted native-script case, its spent output given the witness's script as
    // its reference script: the witness then duplicates a needed reference script
    val j = utxowCases.find(str(_, "name") == "utxow/native-all-of").getOrElse(fail("no case"))
    val c = cases.find(_.name == "utxow/native-all-of").getOrElse(fail("not read"))
    val (input, output) = utxoOf(j).head
    val script = EraTxWits.dijkstra.scriptTxWits(c.era.wits).values.head
    val native = EraScript.dijkstra.originalBytes(script)
    val address = output.slice(1, 1 + 31)
    def len(n: Int) =
      if n < 24 then f"${0x40 + n}%02x" else if n < 256 then f"58$n%02x" else f"59$n%04x"
    val scriptRef = s"8200${native.hex}"
    val withRef = hex(
      s"a300${address.hex}011a00989680" +
        s"03d818${len(scriptRef.length / 2)}$scriptRef"
    )
    val u = utxosOf(c, Map(input -> withRef))
    assertEquals(
      missingAndExtraneous(c, u),
      (Set.empty[Bytes], Set(EraScript.dijkstra.hashScript(script)))
    )
  }

  test("the reference's UTXOW cases: what its refusals record, or acceptance") {
    val compared = utxowCases.map { j =>
      val name = str(j, "name")
      val c = cases.find(_.name == name).getOrElse(fail(s"$name: not read"))
      val u = utxosOf(c)
      val pp = DijkstraCorpus.utxowPParams(j)
      val consumed = DijkstraUTxO.dijkstraConsumed[TxLevel.Top](pp, u.era, c.era.body)
      val produced = utxos.getProducedValue(pp, _ => false, c.era.body)
      val needed =
        utxos.getWitsVKeyNeeded[TxLevel.Top](LedgerStateGen.conwayCert, u.era, c.era.body)
      val witnessed =
        EraTxWits.keyHashWitnessesTxWits[Dijkstra](c.era.wits)(using EraTxWits.dijkstra)
      val minFee = utxos.getMinFeeTxUtxo[TxLevel.Top](pp, c.era, u.era)
      val (missing, extraneous) = missingAndExtraneous(c, u)
      val recorded = failures(j).map(DijkstraCorpus.utxowRecord)
      import DijkstraCorpus.Recorded
      recorded.foreach {
        case Recorded.ValueNotConserved(supplied, expected) =>
          assertEquals((consumed, produced), (supplied, expected), name)
        case Recorded.FeeTooSmall(_, expected)      => assertEquals(minFee, expected, name)
        case Recorded.MissingVKeyWitnesses(keys)    => assertEquals(needed -- witnessed, keys, name)
        case Recorded.MissingScriptWitnesses(sh)    => assertEquals(missing, sh, name)
        case Recorded.ExtraneousScriptWitnesses(sh) => assertEquals(extraneous, sh, name)
        case Recorded.Other                         => ()
      }
      if recorded.isEmpty then
        assertEquals(consumed, produced, name)
        assert(needed.subsetOf(witnessed), name)
        assert(minFee.value <= bodies.fee(c.era.body).value, name)
        assertEquals((missing, extraneous), (Set.empty[Bytes], Set.empty[Bytes]), name)
      recorded
    }.flatten
    // three refusals record the values consumed and produced, and one each the fee, the keys, the
    // missing scripts and the extraneous ones
    import DijkstraCorpus.Recorded
    def count(p: Recorded => Boolean) = compared.count(p)
    assertEquals(
      (
        count { case Recorded.ValueNotConserved(_, _) => true; case _ => false },
        count { case Recorded.FeeTooSmall(_, _) => true; case _ => false },
        count { case Recorded.MissingVKeyWitnesses(_) => true; case _ => false },
        count { case Recorded.MissingScriptWitnesses(_) => true; case _ => false },
        count { case Recorded.ExtraneousScriptWitnesses(_) => true; case _ => false }
      ),
      (3, 1, 1, 1, 1)
    )
  }

  // ---- the annotated transaction, on built values: these show only that each member reads its
  // field (the makers' are DijkstraPlutusContextSuite's)

  test("the annotated transaction's members read its fields, the batch's contexts together") {
    val c = chainCases
      .find(c => bodies.subTransactions(c.era.body).toVector.nonEmpty)
      .getOrElse(fail("no sub-transaction"))
    val sub = bodies.subTransactions(c.era.body).toVector.head
    val u = utxosOf(c)
    // never looked up here
    val unforced =
      new DijkstraTxInfoResult(fail("forced"), fail("forced"), fail("forced"), fail("forced"))
    val provided = utxos.getScriptsProvided[TxLevel.Top](u.era, c.era)
    val subNeeded = utxos.getScriptsNeeded[TxLevel.Sub](u.era, sub.body)(using BothLevels.Sub)
    val topNeeded = utxos.getScriptsNeeded[TxLevel.Top](u.era, c.era.body)
    val noCostModel = CollectError.NoCostModel[Dijkstra](PlutusLanguage.V4)
    def subAnn(contexts: Either[NonEmptyList[CollectError[Dijkstra]], List[PlutusWithContext]]) =
      DijkstraStAnnTx.SubTx(
        sub,
        cats.Eval.now(subNeeded),
        cats.Eval.now(utxos.getScriptsHashesNeeded(subNeeded)),
        cats.Eval.now(provided),
        cats.Eval.now(unforced),
        cats.Eval.now(Set(PlutusLanguage.V4)),
        cats.Eval.now(Map.empty),
        cats.Eval.now(contexts)
      )
    def top(s: DijkstraStAnnTx.SubTx) = DijkstraStAnnTx.TopTx(
      c.era,
      cats.Eval.now(topNeeded),
      cats.Eval.now(provided),
      cats.Eval.now(PlutusLegacyMode.NotLegacy),
      cats.Eval.now(Set(PlutusLanguage.V3)),
      cats.Eval.now(Map.empty),
      cats.Eval.now(Right(Nil)),
      cats.Eval.now(List(s))
    )
    val failing = subAnn(Left(NonEmptyList.one(noCostModel)))
    val ann = top(failing)
    assertEquals(txs.txStAnnTx[TxLevel.Top](ann), c.era)
    assertEquals(txs.txStAnnTx[TxLevel.Sub](failing)(using BothLevels.Sub), sub)
    assertEquals(txs.cacheStAnnTx[TxLevel.Top](ann), Map.empty)
    assertEquals(utxos.scriptsNeededStAnnTx[TxLevel.Top](ann), topNeeded)
    assertEquals(utxos.scriptsNeededStAnnTx[TxLevel.Sub](failing)(using BothLevels.Sub), subNeeded)
    assertEquals(utxos.scriptsProvidedStAnnTx[TxLevel.Top](ann), provided)
    assertEquals(utxos.plutusLanguagesUsedStAnnTx[TxLevel.Top](ann), Set(PlutusLanguage.V3))
    assertEquals(
      utxos.plutusLanguagesUsedStAnnTx[TxLevel.Sub](failing)(using BothLevels.Sub),
      Set(PlutusLanguage.V4)
    )
    assertEquals(utxos.subTransactionsStAnnTx(ann), List(failing))
    assertEquals(utxos.plutusLegacyModeStAnnTx(ann), PlutusLegacyMode.NotLegacy)
    assertEquals(
      utxos.scriptsHashesNeededStAnnTx[TxLevel.Sub](failing)(using SubOnly.Sub),
      utxos.getScriptsHashesNeeded(subNeeded)
    )
    // a sub-transaction's collection failure is the batch's (`UTxO.hs:266-276`, cardano-ledger 9df9f98c)
    assertEquals(
      utxos.plutusScriptsWithContextStAnnTx[TxLevel.Top](ann),
      Left(NonEmptyList.one(noCostModel))
    )
    assertEquals(
      utxos.plutusScriptsWithContextStAnnTx[TxLevel.Top](top(subAnn(Right(Nil)))),
      Right(Nil)
    )
  }

  test(
    "the top level's values and datums without its sub-transactions are those of the body without them"
  ) {
    // `txBody & subTransactionsTxBodyL .~ mempty` (`Dijkstra/Rules/Utxo.hs:380-387`, `Utxow.hs:281-285`,
    // cardano-ledger 9df9f98c): the functions that leave the sub-transactions out, against the
    // functions of the whole batch on the body written again without key 23, of every batch whose
    // other fields a map head of one byte counts (the one crafted body of all 24 fields aside)
    import xyz.kd5ujc.scarab.codec.Value as V
    def fields(c: Case) = node(c.era.body.raw).value match
      case V.Map(es) => es.size
      case other     => fail(s"not a map: $other")
    val batches =
      cases.filter(c => bodies.subTransactions(c.era.body).toVector.nonEmpty && fields(c) <= 24)
    assert(batches.size >= 28, s"${batches.size} batches")
    val pp = testnetPParams
    batches.foreach { c =>
      val u = utxosOf(c)
      val without = withoutSubTransactions(c).era.body
      val isReg: xyz.kd5ujc.scarab.ledger.state.KeyHash[
        xyz.kd5ujc.scarab.ledger.state.StakePool
      ] => Boolean =
        _ => false
      assertEquals(
        DijkstraUTxO.dijkstraConsumedWithoutSubTransactions(pp, u.era, c.era.body),
        DijkstraUTxO.dijkstraConsumed[TxLevel.Top](pp, u.era, without),
        c.name
      )
      assertEquals(
        DijkstraUTxO.dijkstraProducedValueWithoutSubTransactions(pp, isReg, c.era.body),
        DijkstraUTxO.dijkstraProducedValue(pp, isReg, without),
        c.name
      )
      assertEquals(
        DijkstraUTxO.getDijkstraSupplementalDataHashesWithoutSubTransactions(u.era, c.era.body),
        DijkstraUTxO.getDijkstraSupplementalDataHashes[TxLevel.Top](u.era, without),
        c.name
      )
    }
  }
