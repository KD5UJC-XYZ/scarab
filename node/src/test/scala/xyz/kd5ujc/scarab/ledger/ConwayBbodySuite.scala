// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyChain
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.Block as FlatBlock
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraPParams,
  EraBlockBody,
  EraBlockHeader,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  IsPhase2Valid,
  PParams,
  PraosEraBlockHeader,
  ProtVer,
  ScriptIntegrity,
  StoredTxOut,
  TxLevel,
  UTxO,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.Mismatch as EraMismatch
import xyz.kd5ujc.scarab.ledger.era.Relation.{RelEQ, RelLTEQ}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayBlock, ConwayPParams, ConwayTx}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraImpBatch as B
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoBbody,
  AlonzoBbodyPredFailure,
  BbodyEnv,
  BbodyPredFailure,
  ConwayBbody,
  ConwayBbodyPredFailure,
  ConwayBlockRun,
  HeaderProtVerGate,
  InjectBbodyFailure,
  ShelleyBbody,
  ShelleyBbodyPredFailure,
  ShelleyBbodyState,
  ShelleyLedgersPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{Bbody as FlatBbody, EraBlock, EraNes, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  Conway,
  ConwayNewEpochState,
  LedgerState,
  Network,
  NewEpochState,
  ShelleyBasedEra,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{
  EvalRequest,
  EvalResult,
  ExUnits,
  PlutusLanguage,
  ScarabEvaluator,
  ScriptEvaluator
}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild, StateLayout}

/** Conway's BBODY on the era types (`ConwayBbody.conwayBbody`, run by `ConwayBlockRun`;
  * `Conway/Rules/Bbody.hs` and the Alonzo and Shelley BBODY functions it runs, cardano-ledger
  * f649f975) against the flat BBODY the node runs for Conway (`rules.Bbody` through
  * `EraBlock.body`), from the same ticked ledger state, as `MirrorRules` ticks it, on the same
  * block's bytes, each side reading them with its own decoder:
  *   - every recorded Conway block of the era-walking captures (`fixtures/eras` and
  *     `fixtures/chain-dep`, each replayed from genesis by the node's rules), which the reference
  *     node accepted: both admit it with the same ledger state and blocks made on its testnet, and
  *     as if on mainnet, where the header's version is checked, both refuse it for that version
  *     (the clusters' headers declare major 11 over parameters at 9);
  *   - refusals crafted from a recorded block's bytes: another declared body size, another declared
  *     hash, a header major version past the one after the parameters' on mainnet (which the flat
  *     side checks in the envelope, `consensus.HardForkConfig.check`, before its BBODY, and its
  *     BBODY alone admits), each refused by both sides with equal failures in the node's flat form,
  *     and each boundary admitted by both;
  *   - blocks of built transactions (real CBOR and signatures, a reference script and a Plutus V3
  *     script of the reference's examples) on the state before the third Conway block of
  *     `fixtures/eras` with the outputs they spend: reference scripts past the block's limit, a
  *     block's units past `maxBlockExUnits` in either component, and a transaction whose input is
  *     missing, refused by both with equal failures, and the same blocks within the limits admitted
  *     by both with the same state, a script run among them;
  *   - the era block reads every recorded Conway block (the captures' and the Preview blocks of
  *     `fixtures/preview`) as the flat decoder reads it, and the units of a block's transactions
  *     are the flat rules' sum.
  *
  * The flat form of an era failure (`flat`): the body's size or hash `BodyCommitment`, which holds
  * neither; the units `BlockExUnitsTooBig`; the reference scripts `RefScriptsTooLarge`; the
  * header's version the envelope's `HeaderProtVerTooHigh`; LEDGERS's failures the transaction's
  * `Rejected`, through `EraLedgerRun.flat`. The flat BBODY stops at its first failure, the era's
  * records every one (`ValidateAll`, the accumulation the flat rules take on in #412), so each
  * refusal here fails one check, and the two lists are compared whole; a named difference pins the
  * accumulation on a block failing both the size and the hash.
  *
  * Incomplete (CNF-2): no recording holds a Conway block's refusal or a Conway block with
  * transactions and the state before it, so the refusals are crafted, the transactions built, and
  * the reference's verdict on a whole block with transactions is not compared (the Preview blocks
  * with the state #1324 builds are the next evidence); the second clause of the 11.1 build's gate
  * of the header's version (`HeaderProtVerGate`) is never met with a failure, since from major 12
  * the version after it is the last (13) and no header's major decodes past it.
  */
class ConwayBbodySuite extends FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private def ok[A](what: String)(e: Either[?, A]): A = e.fold(x => fail(s"$what: $x"), identity)

  private val layout: StateLayout = ok("layout")(StateLayout.of(ReferenceBuild.Release11_1))

  // ---- the recorded chains --------------------------------------------------------------------------

  /** A Conway block of a recorded chain with the node's state before it. */
  private final case class Before(
      name: String,
      setup: NodeSetup,
      mirror: MirrorState,
      raw: Bytes
  )

  private def lines(path: Path): Vector[Bytes] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(l => ok(s"$path")(Bytes.fromHex(l)))

  /** The Conway blocks of the capture in `dir`, each with the state the node's rules leave before
    * it, the chain replayed from genesis.
    */
  private def conwayOf(dir: String): Vector[Before] =
    val setup =
      ok(dir)(NodeSetup.fromDirectory(ReferenceBuild.Release11_1)(Path.of(dir, "genesis")))
    val raws = lines(Path.of(dir, "blocks.hex"))
    raws.zipWithIndex
      .foldLeft((setup.mirrorStart, Vector.empty[Before])) { case ((state, acc), (raw, i)) =>
        val block = ok(s"$dir block $i")(FlatBlock.decode(raw, setup.geometry.byron.epochSlots))
        val next = ok(s"$dir block $i")(MirrorSteps.block(setup, state, block.header, raw))
        val seen =
          if block.era == Era.Conway then acc :+ Before(s"$dir block $i", setup, state, raw)
          else acc
        (next, seen)
      }
      ._2

  private lazy val eras: Vector[Before] = conwayOf("fixtures/eras")
  private lazy val chainDep: Vector[Before] = conwayOf("fixtures/chain-dep")

  // ---- the two BBODYs on one ticked state ----------------------------------------------------------

  /** What a BBODY made of a block: its failures in the node's flat form, or the ledger state and
    * the blocks made after it.
    */
  private type Verdict = Either[Vector[BlockFailure], (LedgerState[Conway], BlocksMade)]

  private def same(name: String, era: Verdict, flat: Verdict): Unit =
    (era, flat) match
      case (Right((ls, made)), Right((flatLs, flatMade))) =>
        assert(cats.Eq[LedgerState[Conway]].eqv(ls, flatLs), s"$name: the ledger states differ")
        assertEquals(made, flatMade, s"$name: blocks made")
      case _ => assertEquals(era, flat, name)

  /** The ticked Conway state of a block and what each side reads of the run: the era BBODY's own
    * failures as well as their flat form, and the scripts each side's evaluator ran.
    */
  private final case class Judged(
      era: Verdict,
      flat: Verdict,
      ticked: NewEpochState[Conway],
      flatPlutus: Option[PlutusStats],
      flatBbodyAdmits: Boolean,
      eraFailures: Vector[BbodyPredFailure[Conway]],
      eraEvaluated: Int,
      flatEvaluated: Int
  )

  /** The node's evaluator, counting the scripts it is asked to run. */
  private final class Counting extends ScriptEvaluator[cats.Id]:
    private val runs = new java.util.concurrent.atomic.AtomicInteger(0)
    def evaluate(request: EvalRequest): EvalResult =
      runs.incrementAndGet()
      ScarabEvaluator.default.evaluate(request)
    def count: Int = runs.get

  /** The state of `before` ticked to the block `raw` as `MirrorRules` ticks it (stamped with the
    * block's number, then the combinator's tick), changed by `change`; then the era BBODY and the
    * flat side on it: the envelope's check of the header's version (`HardForkConfig.check`, as
    * `LedgerRules` calls it), then the flat BBODY (`EraBlock.body`), each on `network` under
    * `build`.
    */
  private def judge(
      before: Before,
      raw: Bytes,
      network: Network = Network.Testnet,
      build: ReferenceBuild = ReferenceBuild.Release11_1,
      change: NewEpochState[Conway] => NewEpochState[Conway] = identity
  ): Judged =
    val setup = before.setup
    val block = FlatBlock.decodeShelley(raw) match
      case Right(b: FlatBlock.Shelley) => b
      case other                       => fail(s"${before.name}: not a Shelley block: $other")
    val header = block.header
    val geometry = setup.geometry
    val globals = Globals(
      setup.globals.copy(network = network.wire, referenceBuild = build),
      geometry
    )
    val (flatEvaluator, eraEvaluator) = (Counting(), Counting())
    val ctx = EraBlock.Context(
      globals,
      setup.praos.forks.transition(geometry.k),
      MirrorStep.afterVotingOf(before.mirror),
      verdicts = Verdicts.Direct,
      evaluator = flatEvaluator,
      decoder = ScarabEvaluator.defaultDecoder
    )
    val nes = MirrorStep.nesOf(before.mirror).getOrElse(fail(s"${before.name}: no Shelley state"))
    val stamped = EraNes.mapUtxo(nes)(_.at(header.blockNo.toLong))
    val ticked: NewEpochState[Conway] =
      ok(s"${before.name} tick")(EraBlock.ticked(ctx)(stamped, header, block)) match
        case i: EraNes.InEra[e] =>
          i.era match
            case ShelleyBasedEra.SConway => change(i.nes)
            case other                   => fail(s"${before.name}: ticked into $other")
    val params = ConwayNewEpochState.gov.get(ticked).curPParams
    // the flat side: the envelope's check of the header's version, then the flat BBODY
    val body = EraBlock.body(ctx)(
      EraNes.InEra(ShelleyBasedEra.SConway, ticked),
      header,
      block,
      EraBlock.decode(block),
      PlutusStats.zero
    )
    val outcome: Either[Vector[BlockFailure], EraBlock.Outcome] = setup.praos.forks
      .check(
        header.era,
        Era.Conway,
        geometry.epochOf(header.slot),
        header.protocolVersion._1,
        params.protocolMajor,
        header.raw.size,
        params.maxBlockHeaderSize,
        block.bodySize,
        params.maxBlockBodySize,
        mainnet = network == Network.Mainnet
      ) match
      case Left(error) => Left(Vector(BlockFailure.Envelope(error)))
      case Right(_)    => Right(body)
    val flat: Verdict = outcome.flatMap[Vector[BlockFailure], (LedgerState[Conway], BlocksMade)] {
      case EraBlock.Outcome.Applied(after, _) =>
        after match
          case i: EraNes.InEra[e] =>
            i.era match
              case ShelleyBasedEra.SConway => Right((i.nes.es.ls, i.nes.bcur))
              case other                   => fail(s"${before.name}: applied in $other")
      case EraBlock.Outcome.Refused(failure) => Left(Vector(failure))
      case EraBlock.Outcome.NotInEra(era)    => fail(s"${before.name}: not in era $era")
    }
    // the era side, on the same state stamped as the flat BBODY stamps it
    val eraBlock = ok(s"${before.name} era block")(ConwayBlock.decode(raw, layout))
    val pp = PParams[Conway](ok(s"${before.name} parameters")(ConwayPParams.fromStored(params)))
    val ls = ticked.es.ls
    val state = ShelleyBbodyState[Conway](
      ls.copy[Conway](utxo =
        ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo.at(header.blockNo.toLong)))
      ),
      ticked.bcur
    )
    val clock = PosixClock.total(geometry)
    // the network, the validation policy and the build's injection are the run's own, from the
    // globals; the system start and the epoch information of one era from the cluster's start
    val judged = ConwayBlockRun
      .bbody(
        globals,
        BbodyEnv[Conway](pp, ticked.es.chainAccount),
        eraBlock,
        BigInt(geometry.systemStart.toEpochMilli),
        Horizon(
          cats.data.NonEmptyVector.one(
            Horizon.Era(
              Horizon.Edge(0, 0, 0),
              None,
              Horizon.Params(
                geometry.epochLength,
                geometry.slotLengthMillis,
                geometry.stabilityWindow.toBigInt,
                geometry.stabilityWindow.toBigInt
              )
            )
          )
        ),
        state,
        eraEvaluator,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder,
        clock.posix
      )
      .fold(defect => fail(s"${before.name}: era defect $defect"), identity)
    val era: Verdict = judged match
      case Left(fs)     => Left(flatOf(before.name, fs, block))
      case Right(after) => Right((after.ls, after.blocksMade))
    Judged(
      era,
      flat,
      ticked,
      outcome.toOption.collect { case EraBlock.Outcome.Applied(_, plutus) => plutus },
      body match
        case EraBlock.Outcome.Applied(_, _) => true
        case _                              => false
      ,
      judged.left.toOption.fold(Vector.empty)(_.toChain.toVector),
      eraEvaluator.count,
      flatEvaluator.count
    )

  /** The era BBODY's failures in the node's flat form, in their order; LEDGERS's, which the flat
    * form holds by the transaction refused, as that of the block's one transaction, in the place of
    * the first of them.
    */
  private def flatOf(
      name: String,
      fs: NonEmptyChain[BbodyPredFailure[Conway]],
      block: FlatBlock.Shelley
  ): Vector[BlockFailure] =
    val all = fs.toChain.toVector
    val ledgers = all.collect {
      case ConwayBbodyPredFailure.LedgersFailure(ShelleyLedgersPredFailure.LedgerFailure(f)) =>
        ok(s"$name: flat")(EraLedgerRun.flat(f, _ => None, _ => None))
    }
    lazy val rejected = NonEmptyChain.fromSeq(ledgers).map { fs =>
      block.transactions match
        case Vector(t) => BlockFailure.Tx(LedgerFailure.Rejected(t.id, fs))
        case ts        => fail(s"$name: LEDGERS's failures in a block of ${ts.size}")
    }
    val firstLedgers = all.indexWhere {
      case ConwayBbodyPredFailure.LedgersFailure(_) => true
      case _                                        => false
    }
    all.zipWithIndex.flatMap {
      case (ConwayBbodyPredFailure.WrongBlockBodySizeBBODY(_), _) |
          (ConwayBbodyPredFailure.InvalidBodyHashBBODY(_), _) =>
        Vector(BlockFailure.BodyCommitment)
      case (ConwayBbodyPredFailure.TooManyExUnits(m), _) =>
        Vector(BlockFailure.BlockExUnitsTooBig(m.supplied, m.expected))
      case (ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig(m), _) =>
        Vector(BlockFailure.RefScriptsTooLarge(m.stored))
      case (ConwayBbodyPredFailure.HeaderProtVerTooHigh(m), _) =>
        Vector(
          BlockFailure.Envelope(
            EnvelopeError.HeaderProtVerTooHigh(BigInt(m.supplied.value), BigInt(m.expected.value))
          )
        )
      case (ConwayBbodyPredFailure.LedgersFailure(_), i) =>
        if i == firstLedgers then rejected.toVector else Vector.empty
    }

  // ---- blocks as bytes -------------------------------------------------------------------------------

  private def node(raw: Bytes): Node = ok("cbor")(Cbor.decode(raw, Cbor.Limits.Unbounded))

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def array(parts: Vector[Bytes]): Bytes =
    val n = parts.size
    val head =
      if n < 24 then Bytes.fromArray(Array((0x80 + n).toByte))
      else if n < 256 then Bytes.fromArray(Array(0x98.toByte, n.toByte))
      else Bytes.fromArray(Array(0x99.toByte, (n >> 8).toByte, n.toByte))
    parts.foldLeft(head)(_ ++ _)

  private def encoded(v: Value): Bytes = B.encode(B.leaf(v))

  /** The block `raw`, `[7,
    * [[header body, signature], bodies, witnesses, auxiliary data, invalid]]`, with its body's four
    * parts and its header body's fields as `body` and `header` make them from theirs, every other
    * item the bytes it was read as.
    */
  private def crafted(raw: Bytes)(
      body: Vector[Bytes] => Vector[Bytes],
      header: (Vector[Bytes], Vector[Bytes]) => Vector[Bytes]
  ): Bytes =
    val outer = items(node(raw))
    val inner = items(outer(1))
    val signed = items(inner(0))
    val parts = body(inner.drop(1).map(_.original))
    val newHeader = array(
      Vector(array(header(items(signed(0)).map(_.original), parts)), signed(1).original)
    )
    array(Vector(outer(0).original, array(newHeader +: parts)))

  /** A header body's fields declaring the size and hash (fields 6 and 7) of the body `parts`: their
    * bytes' length and the digest of their digests (`hashAlonzoSegWits`).
    */
  private def committing(fields: Vector[Bytes], parts: Vector[Bytes]): Vector[Bytes] =
    fields
      .updated(6, encoded(Value.UInt(BigInt(parts.map(_.size).sum))))
      .updated(
        7,
        encoded(
          Value.ByteString(
            Blake2b.hash256.hash(Bytes(parts.flatMap(p => Blake2b.hash256.hash(p).value)))
          )
        )
      )

  /** `fields` with the header's protocol version (field 9) of major `major`, its minor kept. */
  private def withMajor(fields: Vector[Bytes], major: Int): Vector[Bytes] =
    val minor = items(node(fields(9)))(1).original
    fields.updated(9, array(Vector(encoded(Value.UInt(BigInt(major))), minor)))

  // ---- the recorded blocks ---------------------------------------------------------------------------

  test(
    "every recorded Conway block of the captures and of fixtures/preview: read by the era decoder as the flat decoder reads it"
  ) {
    assertEquals(eras.size, 3)
    assertEquals(chainDep.size, 50)
    // every Conway block (tag 7) of every block file of fixtures/preview
    val previewFiles = Files
      .list(Path.of("fixtures", "preview"))
      .iterator
      .asScala
      .toVector
      .filter(_.getFileName.toString.endsWith(".hex"))
      .sortBy(_.getFileName.toString)
    val preview = previewFiles.flatMap(f =>
      lines(f).zipWithIndex.collect {
        case (raw, i) if FlatBlock.decodeShelley(raw).exists(_.era == Era.Conway) =>
          (s"${f.getFileName} $i", raw)
      }
    )
    assertEquals(preview.size, 58)
    val recorded = (eras ++ chainDep).map(b => (b.name, b.raw)) ++ preview
    val withTxs = recorded.map { (name, raw) =>
      val flat = FlatBlock.decodeShelley(raw) match
        case Right(b: FlatBlock.Shelley) if b.era == Era.Conway => b
        case other                                              => fail(s"$name: $other")
      val block = ok(name)(ConwayBlock.decode(raw, layout))
      val bb = EraBlockBody.conway
      val h = EraBlockHeader.conway
      val t = EraTx.conway
      val txs = bb.txSeqBlockBody(block.blockBody)
      assertEquals(
        txs.map(tx =>
          t.bodyOf.id[TxLevel.Top](t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using t.bodyOf.top)
        ),
        flat.transactions.map(_.id),
        name
      )
      assertEquals(
        txs.map(t.isValid),
        flat.transactions.map(x => IsPhase2Valid.of(x.isValid)),
        name
      )
      assertEquals(bb.hashBlockBody(block.blockBody).bytes, flat.bodyHash, name)
      val pv = PraosEraBlockHeader.conway.protVerBlockHeader(block)
      assertEquals(bb.blockBodySize(pv, block.blockBody), flat.bodySize, name)
      assertEquals(BigInt(h.blockBodySizeBlockHeader(block)), flat.header.bodySize, name)
      assertEquals(h.blockBodyHashBlockHeader(block).bytes, flat.header.bodyHash, name)
      assertEquals(h.slotNoBlockHeader(block).toBigInt, flat.header.slot, name)
      assertEquals(
        h.blockIssuerBlockHeader(block).bytes,
        Blake2b.hash224.hash(flat.header.issuerVkey),
        name
      )
      assertEquals((BigInt(pv.pvMajor.value), pv.pvMinor.value), flat.header.protocolVersion, name)
      // the units of the block's transactions are the flat rules' sum, refused one step below it
      val units = flat.transactions
        .map(x => ok(name)(Tx.fromBlock(x, Era.Conway)))
        .foldLeft(ExUnits.zero)(_ + _.witnesses.totalExUnits)
      assert(AlonzoBbody.validateExUnits[Conway](txs, units)(using t, t.bodyOf.top).isValid, name)
      if units.mem > 0 then
        val below = ExUnits(units.mem - 1, units.steps)
        assertEquals(
          AlonzoBbody.validateExUnits[Conway](txs, below)(using t, t.bodyOf.top),
          cats.data.Validated.invalidNec(
            new AlonzoBbodyPredFailure.TooManyExUnits[Conway](EraMismatch(units, below))
          ),
          name
        )
      txs.size
    }
    // the Preview blocks hold transactions, some with redeemers
    assertEquals(withTxs.sum, 76)
  }

  test(
    "every recorded Conway block: admitted by both BBODYs with the same state on its testnet; as on mainnet, refused by both for its header's version"
  ) {
    val all = eras ++ chainDep
    val onMainnet = all.map { b =>
      val j = judge(b, b.raw)
      same(b.name, j.era, j.flat)
      j.era match
        case Right((_, made)) =>
          // counted once more for its issuer
          assertEquals(
            made.blocks.values.map(_.bits).sum,
            j.ticked.bcur.blocks.values.map(_.bits).sum + 1,
            b.name
          )
        case Left(fs) => fail(s"${b.name}: refused $fs")
      val m = judge(b, b.raw, Network.Mainnet)
      same(s"${b.name} on mainnet", m.era, m.flat)
      m.era
    }
    // the clusters' headers declare major 11 and their parameters' 9: as on mainnet, each is past
    // the version after it
    onMainnet.foreach(v =>
      assertEquals(
        v,
        Left(Vector(BlockFailure.Envelope(EnvelopeError.HeaderProtVerTooHigh(11, 10))))
      )
    )
  }

  // ---- refusals crafted from a recorded block ----------------------------------------------------------

  private lazy val first: Before = eras.head

  test("another declared body size or hash: refused by both, with equal failures") {
    val size = crafted(first.raw)(
      identity,
      (fields, parts) =>
        committing(fields, parts).updated(6, encoded(Value.UInt(BigInt(parts.map(_.size).sum + 1))))
    )
    val hash = crafted(first.raw)(
      identity,
      (fields, parts) =>
        committing(fields, parts)
          .updated(7, encoded(Value.ByteString(Bytes(Vector.fill(32)(7.toByte)))))
    )
    val committed = crafted(first.raw)(identity, committing)
    assertEquals(committed, first.raw, "the recorded header commits to its body")
    Vector("size" -> size, "hash" -> hash).foreach { (what, raw) =>
      val j = judge(first, raw)
      assertEquals(j.flat, Left(Vector(BlockFailure.BodyCommitment)), what)
      same(what, j.era, j.flat)
    }
    // the era predicates' own failures, with the reference's mismatches: the body's (supplied)
    // and the header's (expected)
    val actual = sizeOf(first.raw)
    val sized = ok("size")(ConwayBlock.decode(size, layout))
    assertEquals(
      ShelleyBbody
        .validateBlockBodySize(sized, PraosEraBlockHeader.conway.protVerBlockHeader(sized)),
      cats.data.Validated.invalidNec(
        new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](EraMismatch(actual, actual + 1))
      )
    )
    val hashed = ok("hash")(ConwayBlock.decode(hash, layout))
    assertEquals(
      ShelleyBbody
        .validateBlockBodyHash(hashed)
        .leftMap(_.map(f => (f.mismatch.supplied.bytes, f.mismatch.expected.bytes))),
      cats.data.Validated.invalidNec(
        (
          EraBlockBody.conway.hashBlockBody(hashed.blockBody).bytes,
          Bytes(Vector.fill(32)(7.toByte))
        )
      )
    )
  }

  test(
    "named difference: a declared size and hash both other than the body's: the era BBODY records both, the flat BBODY its first"
  ) {
    // the flat BBODY stops at its first failure, and its form of the body's commitment holds
    // neither mismatch (the accumulation of #412); the reference records both
    // (`Alonzo/Rules/Bbody.hs:194-196`, cardano-ledger f649f975)
    val both = crafted(first.raw)(
      identity,
      (fields, parts) =>
        committing(fields, parts)
          .updated(6, encoded(Value.UInt(BigInt(parts.map(_.size).sum + 1))))
          .updated(7, encoded(Value.ByteString(Bytes(Vector.fill(32)(7.toByte)))))
    )
    val j = judge(first, both)
    assertEquals(j.flat, Left(Vector(BlockFailure.BodyCommitment)))
    assertEquals(j.era, Left(Vector(BlockFailure.BodyCommitment, BlockFailure.BodyCommitment)))
    // the era rule's own failures, before their flat form: the size is checked before the hash, and
    // the root's list is the reverse of the order its predicates recorded (each failure put in
    // front, `Extended.hs:708`, cardano-ledger f649f975; `Judgment.flatten`), so the hash's first,
    // as the reference returns the same pair for Dijkstra's BBODY (`DijkstraBbodySuite`,
    // `DijkstraRulesOracleSuite`'s `bbody/other-body-size-and-hash`)
    val actual = FlatBlock.decodeShelley(first.raw) match
      case Right(b: FlatBlock.Shelley) => b
      case other                       => fail(s"$other")
    assertEquals(
      j.eraFailures,
      Vector[BbodyPredFailure[Conway]](
        new ConwayBbodyPredFailure.InvalidBodyHashBBODY[Conway](
          EraMismatch(
            ok("hash")(xyz.kd5ujc.scarab.ledger.state.Hash32.fromBytes(actual.bodyHash)),
            ok("hash")(
              xyz.kd5ujc.scarab.ledger.state.Hash32.fromBytes(Bytes(Vector.fill(32)(7.toByte)))
            )
          )
        ),
        new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](
          EraMismatch(actual.bodySize, actual.bodySize + 1)
        )
      )
    )
  }

  /** The body size of the block `raw`, as the flat decoder computes it. */
  private def sizeOf(raw: Bytes): Long = FlatBlock.decodeShelley(raw) match
    case Right(b: FlatBlock.Shelley) => b.bodySize
    case other                       => fail(s"$other")

  test(
    "the header's major version past the one after the parameters': refused on mainnet by the era BBODY and the flat envelope"
  ) {
    val major =
      ConwayNewEpochState.gov.get(judge(first, first.raw).ticked).curPParams.protocolMajor.toInt
    def at(m: Int) =
      crafted(first.raw)(identity, (fields, parts) => withMajor(committing(fields, parts), m))
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2).foreach { build =>
      val tooHigh = judge(first, at(major + 2), Network.Mainnet, build)
      assertEquals(
        tooHigh.era,
        Left(
          Vector(BlockFailure.Envelope(EnvelopeError.HeaderProtVerTooHigh(major + 2, major + 1)))
        ),
        s"$build"
      )
      same(s"$build too high", tooHigh.era, tooHigh.flat)
      // the flat BBODY alone admits it: the flat rules check the version in the envelope
      assert(tooHigh.flatBbodyAdmits, s"$build")
      // the boundary, and another network, admitted by both
      Vector(
        judge(first, at(major + 1), Network.Mainnet, build),
        judge(first, at(major + 2), Network.Testnet, build)
      ).foreach { j =>
        assert(j.era.isRight, s"$build: ${j.era}")
        same(s"$build admitted", j.era, j.flat)
      }
    }
  }

  test("the gate of the header's version by build, whose clause from version 12 meets no failure") {
    assertEquals(
      HeaderProtVerGate.of(ReferenceBuild.Release11_1),
      HeaderProtVerGate.MainnetOrFromVersion12
    )
    assertEquals(HeaderProtVerGate.of(ReferenceBuild.Release11_2), HeaderProtVerGate.MainnetOnly)
    assertEquals(HeaderProtVerGate.of(ReferenceBuild.LeiosB004fe0c), HeaderProtVerGate.MainnetOnly)
    val v12 = Version.mkVersion(12).getOrElse(fail("12"))
    val v11 = Version.mkVersion(11).getOrElse(fail("11"))
    assert(HeaderProtVerGate.MainnetOrFromVersion12.checks(Network.Testnet, v12))
    assert(!HeaderProtVerGate.MainnetOrFromVersion12.checks(Network.Testnet, v11))
    assert(!HeaderProtVerGate.MainnetOnly.checks(Network.Testnet, v12))
    assert(HeaderProtVerGate.MainnetOnly.checks(Network.Mainnet, v11))
    // from 12 the version after it is the last, past which no header's major decodes: a header at
    // 13 on parameters at 12 is admitted under each build, on testnet
    assertEquals(Version.mkVersion(14), None)
    val at13 =
      crafted(first.raw)(identity, (fields, parts) => withMajor(committing(fields, parts), 13))
    Vector(ReferenceBuild.Release11_1, ReferenceBuild.Release11_2).foreach { build =>
      val j = judge(
        first,
        at13,
        Network.Testnet,
        build,
        ConwayNewEpochState.gov.modify(g =>
          g.copy(curPParams = g.curPParams.copy(protocolMajor = 12))
        )
      )
      assert(j.era.isRight, s"$build: ${j.era}")
    }
  }

  // ---- blocks of built transactions --------------------------------------------------------------------

  private lazy val third: Before = eras(2)
  private lazy val payer = TestLedger.key()
  private def paid(coin: BigInt): Node = B.out(B.keyAddress(payer), coin)

  /** `alwaysSucceedsWithDatum SPlutusV3` and its hash
    * (`libs/cardano-ledger-core/testlib/Test/Cardano/Ledger/Plutus/Examples.hs:109-181`,
    * cardano-ledger 9df9f98c, as `DijkstraBbodyImpSuite` holds it).
    */
  private def hex(s: String): Bytes = ok("hex")(Bytes.fromHex(s))

  private val alwaysSucceedsWithDatumV3: Bytes = hex(
    "58ab0101009800aab9daba2ab9aaab9eaba1ab9c48888889660026464b30013370e90011804800c4cc00888c8c" +
      "94cd4d6600266e1d2000001891909118010019805000c56600266e1d200200189091000c5900b2016300a00221" +
      "4a22940c030004dd518038009803000c528200e37546a66002446464253001005801c009001180398050011803" +
      "00098029baa0022220012233002300500130080018a4d1326330024901035054350049900501"
  )
  private val alwaysSucceedsWithDatumV3Hash: Bytes =
    hex("90a4869bd26787c6e649fbc84b03dfbd7ef4f46fcd619d5931fd0280")

  /** A transaction as a block holds it: its body and witness set's bytes. */
  private final case class Built(body: Bytes, wits: Bytes):
    def transaction: FlatBlock.Transaction = FlatBlock.Transaction(body, wits, None, true)
    def id: Bytes = transaction.id
    def era: ConwayTx = ok("era transaction")(ConwayTx.decode(transaction, layout))

  private def built(body: Node, wits: Node): Built = Built(B.encode(body), B.encode(wits))

  /** The outputs of container bodies that no block holds, a hundred each: the entries a test's UTxO
    * set gets, in order, keyed by their body's id and their indices, each read by the flat decoder.
    */
  private def funded(outputs: Vector[Node]): Vector[(TxIn, TxOut)] =
    outputs.grouped(100).toVector.zipWithIndex.flatMap { (group, n) =>
      val body = B.body(0 -> B.set(B.fresh(n).node), 1 -> B.arr(group*), 2 -> B.uint(0))
      val t = Built(B.encode(body), B.encode(B.cmap()))
      val flat = ok("container")(Tx.fromBlock(t.transaction, Era.Conway))
      TxIn.txouts(t.id, flat.body.outputs)
    }

  /** The block of the third Conway block's header with the transactions `txs` as its body, which
    * its header commits to.
    */
  private def blockOf(
      txs: Vector[Built],
      header: Vector[Bytes] => Vector[Bytes] = identity
  ): Bytes =
    crafted(third.raw)(
      _ =>
        Vector(
          array(txs.map(_.body)),
          array(txs.map(_.wits)),
          encoded(Value.Map(Vector.empty)),
          array(Vector.empty)
        ),
      (fields, parts) => header(committing(fields, parts))
    )

  /** The ticked state with `utxo` added to its UTxO set (enterprise addresses alone, so no stake
    * changes) and its parameters as `params` makes them.
    */
  private def holding(
      utxo: Vector[(TxIn, TxOut)],
      params: ProtocolParams => ProtocolParams = identity
  ): NewEpochState[Conway] => NewEpochState[Conway] =
    ConwayNewEpochState.ls
      .modify(ls =>
        ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(ls.utxo.utxo.utxo ++ utxo)))
      )
      .andThen(ConwayNewEpochState.gov.modify(g => g.copy(curPParams = params(g.curPParams))))

  private def in(utxo: Vector[(TxIn, TxOut)], ix: Int): Node =
    val i = utxo(ix)._1
    B.arr(B.bytes(i.id), B.uint(i.index))

  test(
    "reference scripts past the block's limit: refused by both with equal failures, within it admitted with the same state"
  ) {
    val script = B.purposeIsWellformedNoDatumV3
    val scriptSize = script.size.toLong
    val perTx = 100
    // transactions of 100 references each, as many as the limit needs to be passed
    val limit = 1024L * 1024
    val count = (limit / (perTx * scriptSize) + 1).toInt
    val refOuts = Vector.fill(count * perTx)(B.refScriptOut(B.keyAddress(payer), 10000000, script))
    val fees = Vector.fill(count)(paid(20000000))
    val utxo = funded(refOuts ++ fees)
    val txs = (0 until count).toVector.map { i =>
      val body = B.body(
        0 -> B.set(in(utxo, count * perTx + i)),
        1 -> B.arr(paid(17000000)),
        2 -> B.uint(3000000),
        18 -> B.set((0 until perTx).map(j => in(utxo, i * perTx + j))*)
      )
      built(body, B.witnesses(body, Vector(payer)))
    }
    val total = count * perTx * scriptSize
    assert(total > limit && (count - 1) * perTx * scriptSize <= limit, s"$total")
    Vector(9, 10, 11).foreach { major =>
      // the body is past the cluster's 65,536 bytes, which the flat side's envelope checks before
      // its BBODY (BBODY reads no such limit): a limit that holds it
      val world =
        holding(utxo, _.copy(protocolMajor = major, maxBlockBodySize = BigInt(1024 * 1024)))
      val refused = judge(third, blockOf(txs), change = world)
      assertEquals(
        refused.flat,
        Left(Vector(BlockFailure.RefScriptsTooLarge(Mismatch(total, limit)))),
        s"$major"
      )
      same(s"$major refused", refused.era, refused.flat)
      val admitted = judge(third, blockOf(txs.init), change = world)
      assert(admitted.era.isRight, s"$major: ${admitted.era}")
      same(s"$major admitted", admitted.era, admitted.flat)
    }
  }

  test(
    "reference scripts created in the block count from version 11, as the flat rules count them"
  ) {
    val script = B.purposeIsWellformedNoDatumV3
    val utxo = funded(Vector(paid(50000000), paid(20000000)))
    val producer =
      val body = B.body(
        0 -> B.set(in(utxo, 0)),
        1 -> B.arr(B.refScriptOut(B.keyAddress(payer), 20000000, script), paid(27000000)),
        2 -> B.uint(3000000)
      )
      built(body, B.witnesses(body, Vector(payer)))
    val consumer =
      val body = B.body(
        0 -> B.set(in(utxo, 1)),
        1 -> B.arr(paid(17000000)),
        2 -> B.uint(3000000),
        18 -> B.set(B.arr(B.bytes(producer.id), B.uint(0)))
      )
      built(body, B.witnesses(body, Vector(payer)))
    val txs = Vector(producer, consumer)
    val flatTxs = txs.map(t => ok("flat")(Tx.fromBlock(t.transaction, Era.Conway)))
    val eraUtxo = UTxO[Conway](
      utxo.map((i, o) => i -> ok("stored")(StoredTxOut.conway.fromStored(o))).toMap
    )
    Vector(10 -> 0L, 11 -> script.size.toLong).foreach { (major, expected) =>
      val pv = ProtVer(
        Version.mkVersion(major).getOrElse(fail("major")),
        Unsigned.lit[Unsigned.Width.W32](0)
      )
      val t = EraTx.conway
      val era = ConwayBbody.totalRefScriptSizeInBlock[Conway](pv, txs.map(_.era), eraUtxo)(using
        t,
        EraTxBody.conway,
        t.bodyOf.top,
        EraTxBody.conway.top
      )
      val flat = FlatBbody.refScriptsSize(
        ConwayNewEpochState.gov
          .get(judge(third, third.raw).ticked)
          .curPParams
          .copy(protocolMajor = major),
        Utxo.fromMap(utxo.toMap),
        flatTxs
      )
      assertEquals((era, flat), (expected, expected), s"$major")
      val j = judge(third, blockOf(txs), change = holding(utxo, _.copy(protocolMajor = major)))
      assert(j.era.isRight, s"$major: ${j.era}")
      same(s"$major", j.era, j.flat)
    }
  }

  test(
    "reference scripts made in the block alone past the block's limit: admitted at version 10, refused at 11 by both"
  ) {
    // the version switch of `totalRefScriptSizeInBlock` (`Conway/Rules/Bbody.hs:360-370`,
    // cardano-ledger f649f975) through BBODY: up to 10 each transaction is measured against the set
    // before the block, which holds none of the scripts; from 11 the outputs of the transactions
    // before it count too. Producers of 100 outputs holding the script each, then as many
    // transactions referencing a producer's 100 outputs each, together past the limit
    val script = B.purposeIsWellformedNoDatumV3
    val scriptSize = script.size.toLong
    val perTx = 100
    val limit = 1024L * 1024
    val count = (limit / (perTx * scriptSize) + 1).toInt
    val fund = BigInt(600000000)
    val funds = funded(Vector.fill(2 * count)(paid(fund)))
    val producers = (0 until count).toVector.map { i =>
      val body = B.body(
        0 -> B.set(in(funds, i)),
        1 -> B.arr(
          (Vector.fill(perTx)(B.refScriptOut(B.keyAddress(payer), 5000000, script)) :+
            paid(fund - perTx * 5000000 - 3000000))*
        ),
        2 -> B.uint(3000000)
      )
      built(body, B.witnesses(body, Vector(payer)))
    }
    val consumers = (0 until count).toVector.map { i =>
      val body = B.body(
        0 -> B.set(in(funds, count + i)),
        1 -> B.arr(paid(fund - 3000000)),
        2 -> B.uint(3000000),
        18 -> B.set((0 until perTx).map(j => B.arr(B.bytes(producers(i).id), B.uint(j)))*)
      )
      built(body, B.witnesses(body, Vector(payer)))
    }
    val total = count * perTx * scriptSize
    assert(total > limit, s"$total")
    val raw = blockOf(producers ++ consumers)
    // the producers' bodies are past the cluster's transaction size, and the block past its body
    // size, which the flat side's envelope checks before its BBODY: limits that hold them (BBODY
    // reads neither)
    def world(major: Int) = holding(
      funds,
      _.copy(
        protocolMajor = major,
        maxTxSize = BigInt(128 * 1024),
        maxBlockBodySize = BigInt(4 * 1024 * 1024)
      )
    )
    val at10 = judge(third, raw, change = world(10))
    assert(at10.era.isRight, s"${at10.era}")
    same("10", at10.era, at10.flat)
    val at11 = judge(third, raw, change = world(11))
    assertEquals(
      at11.eraFailures,
      Vector[BbodyPredFailure[Conway]](
        new ConwayBbodyPredFailure.BodyRefScriptsSizeTooBig[Conway](EraMismatch(total, limit))
      )
    )
    assertEquals(at11.flat, Left(Vector(BlockFailure.RefScriptsTooLarge(Mismatch(total, limit)))))
    same("11", at11.era, at11.flat)
  }

  test(
    "the block's units past maxBlockExUnits, in either component: refused by both with equal failures; within, the script run and the same state"
  ) {
    val datum = B.uint(0)
    val utxo = funded(
      Vector(
        B.datumHashOut(B.scriptAddress(alwaysSucceedsWithDatumV3Hash), 10000000, B.dataHash(datum)),
        paid(20000000),
        paid(30000000)
      )
    )
    assertEquals(B.scriptHash(3, alwaysSucceedsWithDatumV3), alwaysSucceedsWithDatumV3Hash)
    val params = ConwayNewEpochState.gov.get(judge(third, third.raw).ticked).curPParams
    val pp = PParams[Conway](ok("parameters")(ConwayPParams.fromStored(params)))
    // the units the redeemer declares, within the transaction's and the block's limits
    val units = ExUnits(2000000, 1000000000)
    assert(units.fits(AlonzoEraPParams.ppMaxTxExUnitsL[Conway].get(pp).units))
    assert(units.fits(params.maxBlockExUnits))
    def spend(integrity: Bytes): Built =
      val body = B.body(
        0 -> B.set(in(utxo, 0), in(utxo, 1)),
        1 -> B.arr(paid(27000000)),
        2 -> B.uint(3000000),
        11 -> B.bytes(integrity),
        13 -> B.set(in(utxo, 2))
      )
      built(
        body,
        B.witnesses(
          body,
          Vector(payer),
          4 -> B.set(datum),
          5 -> B.cmap(
            B.arr(B.uint(0), B.uint(0)) -> B
              .arr(B.uint(0), B.arr(B.uint(units.mem), B.uint(units.steps)))
          ),
          7 -> B.set(B.bytes(alwaysSucceedsWithDatumV3))
        )
      )
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Conway, TxLevel.Top](
        pp,
        spend(Bytes(Vector.fill(32)(0.toByte))).era,
        Set(PlutusLanguage.V3)
      )(using
        EraTx.conway,
        AlonzoEraPParams.conway,
        xyz.kd5ujc.scarab.ledger.era.EraTxWits.conway,
        EraTx.conway.bodyOf.top
      )
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    val raw = blockOf(Vector(spend(integrity)))
    val admitted = judge(third, raw, change = holding(utxo))
    assert(admitted.era.isRight, s"${admitted.era}")
    same("admitted", admitted.era, admitted.flat)
    // the script ran, as the flat rules count it, and its output is spent
    assertEquals((admitted.eraEvaluated, admitted.flatEvaluated), (1, 1))
    assertEquals(
      admitted.flatPlutus.map(p => (p.succeeded, p.failed, p.scripts)),
      Some((1L, 0L, 1L))
    )
    admitted.era.foreach((ls, _) => assert(ls.utxo.utxo.utxo.get(utxo.head._1).isEmpty))
    Vector(
      ExUnits(units.mem - 1, params.maxBlockExUnits.steps),
      ExUnits(params.maxBlockExUnits.mem, units.steps - 1)
    ).foreach { below =>
      val refused = judge(third, raw, change = holding(utxo, _.copy(maxBlockExUnits = below)))
      assertEquals(
        refused.flat,
        Left(Vector(BlockFailure.BlockExUnitsTooBig(units, below))),
        s"$below"
      )
      same(s"$below", refused.era, refused.flat)
    }
  }

  test("a transaction whose input is missing: LEDGERS's refusal, embedded, the flat LEDGER's") {
    val utxo = funded(Vector(paid(20000000)))
    val body = B.body(
      0 -> B.set(in(utxo, 0), B.fresh(0x77).node),
      1 -> B.arr(paid(17000000)),
      2 -> B.uint(3000000)
    )
    val j = judge(
      third,
      blockOf(Vector(built(body, B.witnesses(body, Vector(payer))))),
      change = holding(utxo)
    )
    assert(j.flat.isLeft, s"${j.flat}")
    same("missing input", j.era, j.flat)
    // the input LEDGERS refuses, as UTXO's `BadInputsUTxO` names it
    val missing = TxIn(B.fresh(0x77).txId, 0)
    assertEquals(
      j.eraFailures.collect {
        case ConwayBbodyPredFailure.LedgersFailure(
              ShelleyLedgersPredFailure.LedgerFailure(
                xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayUtxowFailure(
                  xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxowPredFailure.UtxoFailure(
                    xyz.kd5ujc.scarab.ledger.era.rules.ConwayUtxoPredFailure.BadInputsUTxO(ins)
                  )
                )
              )
            ) =>
          ins.toSortedSet.toSet
      },
      Vector(Set(missing))
    )
  }

  test("the injections into Conway's BBODY are the reference's compositions") {
    // LEDGERS's and below, `shelleyToConwayBbodyPredFailure . Shelley.LedgersFailure .
    // injectFailure` (`Conway/Rules/Bbody.hs:164-165`, cardano-ledger f649f975), through LEDGERS's
    // own injection
    val treasury =
      new xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[
        Conway
      ](EraMismatch(xyz.kd5ujc.scarab.ledger.state.Coin(1), xyz.kd5ujc.scarab.ledger.state.Coin(2)))
    assertEquals(
      summon[InjectBbodyFailure[
        xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure[Conway],
        Conway
      ]].injectFailure(treasury),
      ConwayBbodyPredFailure.LedgersFailure[Conway](
        ShelleyLedgersPredFailure.LedgerFailure(treasury)
      )
    )
    val m = EraMismatch[RelEQ, Long](1L, 2L)
    assertEquals(
      summon[InjectBbodyFailure[ShelleyBbodyPredFailure[Conway], Conway]]
        .injectFailure(new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)),
      new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)
    )
    val u = EraMismatch[RelLTEQ, ExUnits](ExUnits(1, 2), ExUnits(0, 0))
    assertEquals(
      summon[InjectBbodyFailure[AlonzoBbodyPredFailure[Conway], Conway]]
        .injectFailure(new AlonzoBbodyPredFailure.TooManyExUnits[Conway](u)),
      new ConwayBbodyPredFailure.TooManyExUnits[Conway](u)
    )
    assertEquals(
      summon[InjectBbodyFailure[AlonzoBbodyPredFailure[Conway], Conway]].injectFailure(
        AlonzoBbodyPredFailure.ShelleyInAlonzoBbodyPredFailure(
          new ShelleyBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)
        )
      ),
      new ConwayBbodyPredFailure.WrongBlockBodySizeBBODY[Conway](m)
    )
  }
