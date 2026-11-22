// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.node.mempool.FlatEntries.tx
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.node.assertions.Assertions
import xyz.kd5ujc.scarab.protocol.{ChainSync, ProtocolError, TxSubmission2, TxSubmission2Engine}
import xyz.kd5ujc.scarab.protocol.TxSubmission2.{EraTx, EraTxId}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Block, TestLedger}
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, NativeRules, PreConway}
import xyz.kd5ujc.scarab.ledger.state.{
  FuturePParams,
  Allegra,
  Alonzo,
  Babbage,
  EraTag,
  Mary,
  Shelley,
  ShelleyBasedEra
}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

import TestLedger.{out, txIn}
import TxKit.{ada, alice, bob, bstr, head}

/** The mempool in every Shelley-based era (#934): it holds transactions of the era of its ledger,
  * lists them under that era, and takes a transaction of another era as the selected reference
  * build does ([[EraInjection]]): cardano-node 11.1.3 translates an earlier one forward through
  * each era and refuses a later one, 11.2.1 and the Leios build decode one of any other era in the
  * ledger's. The refusals of each era's rules, through this same path, are checked byte for byte
  * against the reference's in `ReferenceRefusalSuite`.
  */
class EraMempoolSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]
  private given cats.mtl.Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))
  private given cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError] with
    def functor = cats.effect.IO.asyncForIO
    def raise[E <: xyz.kd5ujc.scarab.network.NetworkError, A](error: E): IO[A] =
      IO(fail(error.message))

  private val shelleyBased = Vector(Era.Shelley, Era.Allegra, Era.Mary, Era.Alonzo, Era.Babbage)
  private val eras = shelleyBased :+ Era.Conway

  private val translating = TxKit.env
  private val redecoding =
    TxKit.env.copy(globals = TxKit.env.globals.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c))

  /** Alice pays bob from `txIn(i)`, Shelley's body `{0: inputs, 1: outputs, 2: fee, 3: ttl}` with
    * `extra` fields after it, signed by alice: the whole transaction as `era` writes it, and the
    * parts.
    */
  private def payment(
      era: Era,
      i: Int = 1,
      extra: Vector[(Int, Vector[Byte])] = Vector.empty,
      forged: Boolean = false,
      paid: BigInt = 99 * ada
  ): (Bytes, Block.Transaction) =
    val body = Bytes(
      head(5, 4 + extra.size) ++
        Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(txIn(i).id) ++ head(0, 0) ++
        Vector(0x01.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(bob.address().raw) ++
        head(0, paid) ++
        Vector(0x02.toByte) ++ head(0, ada) ++
        Vector(0x03.toByte) ++ head(0, 100000) ++
        extra.flatMap((k, v) => head(0, k) ++ v)
    )
    val id = Blake2b.hash256.hash(body)
    val witnesses = Bytes(
      head(5, 1) ++ Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(alice.vkey) ++
        bstr(alice.sign(if forged then Blake2b.hash256.hash(id) else id))
    )
    val parts = Block.Transaction(body, witnesses, None, true)
    (EraInjection.envelope(parts, era), parts)

  /** A Shelley update proposal with no proposals, `[{}, 5]`: read from Shelley to Babbage, and no
    * field of Conway's body.
    */
  private val update: (Int, Vector[Byte]) = 6 -> (head(4, 2) ++ head(5, 0) ++ head(0, 5))

  private def earlier[E <: EraTag](major: Int)(using pre: PreConway[E]): FlatLedger =
    given ShelleyBasedEra[E] = pre.era
    val nes = NativeRules.stateIn[E](major, (1 to 2).toVector.map(i => txIn(i) -> funds))
    EarlierLedger(pre, nes.es.ls, nes.es.chainAccount, nes.epoch.toBigInt)

  private def funds = out(alice.address(), 100 * ada)

  /** A ledger of `era` with two outputs of 100 ada for alice, at txIn(1) and txIn(2). */
  private def ledger(era: Era): FlatLedger = era match
    case Era.Shelley                           => earlier[Shelley](2)
    case Era.Allegra                           => earlier[Allegra](3)
    case Era.Mary                              => earlier[Mary](4)
    case Era.Alonzo                            => earlier[Alonzo](5)
    case Era.Babbage                           => earlier[Babbage](7)
    case Era.Conway | Era.Byron | Era.Dijkstra => TxKit.ledger(2)

  private def pool(era: Era): MempoolState = MempoolState.empty(Right(ledger(era)), 1000, None)

  private def submit(
      era: Era,
      submitted: Era,
      raw: Bytes,
      env: MempoolEnv = translating
  ): Either[Rejection, (MempoolState, Entry)] =
    pool(era).add(Submission(submitted.index, raw), env, Source.Local)

  private def held(result: Either[Rejection, (MempoolState, Entry)]): (MempoolState, Entry) =
    result.fold(r => fail(s"refused: ${r.message}"), identity)

  /** The reply's era mismatch `[[tx era, name], [ledger era, name]]`. */
  private def mismatch(transaction: Era, ledgerEra: Era): String =
    def named(e: Era): String =
      "82" + f"${e.index}%02x" + f"${0x60 + e.toString.length}%02x" +
        e.toString.getBytes("US-ASCII").map(b => f"$b%02x").mkString
    "82" + named(transaction) + named(ledgerEra)

  private def reply(rejection: Rejection, era: Era): String =
    LocalSubmission.reasonItem(rejection, Some(era)).fold(e => fail(e.message), _.hex)

  eras.foreach { era =>
    test(s"$era: a transaction of the ledger's era is held, and listed under the era") {
      val (raw, parts) = payment(era)
      val (state, entry) = held(submit(era, era, raw))
      assertEquals(entry.era, era)
      assertEquals(entry.id, parts.id)
      assertEquals(entry.raw, raw, "held as the era writes it")
      val listed = LocalMonitor.snapshot(state).txs.map(_.tx)
      assertEquals(listed, Vector(LocalMonitor.wrapTx(era, raw)), "NextTx names the era")
      assertEquals(listed.head.value.take(2), Vector(0x82.toByte, era.index.toByte))
    }
  }

  test("the capacity and the measure have the components of the ledger's era") {
    eras.foreach { era =>
      val (raw, _) = payment(era)
      val (state, entry) = held(submit(era, era, raw))
      val capacity = state.capacity
      val measure = Capacity.measure(entry)
      era match
        case Era.Shelley | Era.Allegra | Era.Mary =>
          assertEquals(
            (capacity.exUnitsMemory, capacity.exUnitsSteps, capacity.refScriptsBytes),
            (BigInt(0), BigInt(0), 0L),
            s"$era measures bytes alone"
          )
        case Era.Alonzo | Era.Babbage =>
          assert(capacity.exUnitsMemory > 0, s"$era measures execution units")
          assertEquals(capacity.refScriptsBytes, 0L, s"$era has no reference-script measure")
        case _ =>
          assert(capacity.refScriptsBytes > 0, s"$era measures reference scripts")
      assertEquals(measure, TxMeasure(entry.tx.size + 4, BigInt(0), BigInt(0), 0L))
    }
  }

  shelleyBased.foreach { era =>
    test(s"$era: a transaction of a later era is refused with the era mismatch, under 11.1") {
      val (raw, _) = payment(Era.Conway)
      submit(era, Era.Conway, raw) match
        case Left(rejection) =>
          assertEquals(rejection, Rejection.WrongEra(Era.Conway.index, era))
          assertEquals(reply(rejection, era), mismatch(Era.Conway, era))
        case Right(_) => fail("a later-era transaction was taken")
    }
  }

  eras.foreach { era =>
    test(s"$era: a Byron transaction is not injected") {
      // mainnet's transaction at slot 3,313, as Byron's `MempoolPayload`
      submit(era, Era.Byron, ByronPayloads.payload(0, ByronPayloads.redeemTx.original)) match
        case Left(rejection) =>
          assertEquals(rejection, Rejection.WrongEra(Era.Byron.index, era))
          assertEquals(reply(rejection, era), mismatch(Era.Byron, era))
        case Right(_) => fail("a Byron transaction was taken")
    }
  }

  eras.filter(_ != Era.Shelley).foreach { era =>
    test(s"$era: a Shelley transaction is translated into the era and held there, under 11.1") {
      val (raw, parts) = payment(Era.Shelley)
      val (state, entry) = held(submit(era, Era.Shelley, raw))
      assertEquals(entry.era, era)
      assertEquals(entry.id, parts.id, "the id is the hash of the same body")
      // from Alonzo the record has the flag, which a transaction of Mary or before passes
      assertEquals(entry.raw, EraInjection.envelope(parts, era))
      assertEquals(
        LocalMonitor.snapshot(state).txs.map(_.tx),
        Vector(LocalMonitor.wrapTx(era, entry.raw))
      )
    }
  }

  test("an Alonzo transaction no Conway body reads is refused naming Babbage, under 11.1") {
    // translated into Babbage, which reads the update, and refused by Conway's body
    val (raw, _) = payment(Era.Alonzo, extra = Vector(update))
    submit(Era.Conway, Era.Alonzo, raw) match
      case Left(rejection) =>
        assertEquals(rejection, Rejection.WrongEra(Era.Babbage.index, Era.Conway))
        assertEquals(reply(rejection, Era.Conway), mismatch(Era.Babbage, Era.Conway))
      case Right(_) => fail("Conway took a body with an update proposal")
  }

  test("the same transaction is refused naming Alonzo, under the Leios build") {
    val (raw, _) = payment(Era.Alonzo, extra = Vector(update))
    assertEquals(
      submit(Era.Conway, Era.Alonzo, raw, redecoding).left.toOption,
      Some(Rejection.WrongEra(Era.Alonzo.index, Era.Conway))
    )
  }

  test(
    "a later-era transaction whose bytes the ledger's era reads is held, under the Leios build"
  ) {
    val (raw, parts) = payment(Era.Conway)
    val (_, entry) = held(submit(Era.Babbage, Era.Conway, raw, redecoding))
    assertEquals(entry.era, Era.Babbage)
    assertEquals(entry.id, parts.id)
    assertEquals(
      submit(Era.Babbage, Era.Conway, raw, translating).left.toOption,
      Some(Rejection.WrongEra(Era.Conway.index, Era.Babbage)),
      "11.1 does not take it"
    )
  }

  test("an earlier-era transaction is held in the ledger's era under both builds") {
    val (raw, parts) = payment(Era.Mary)
    Vector(translating, redecoding).foreach { env =>
      val (_, entry) = held(submit(Era.Babbage, Era.Mary, raw, env))
      assertEquals(entry.era, Era.Babbage)
      assertEquals(entry.raw, EraInjection.envelope(parts, Era.Babbage))
    }
  }

  /** A pool of `era` holding `raw` of that era. */
  private def holding(era: Era, raw: Bytes): MempoolState =
    held(submit(era, era, raw))._1

  test("held transactions follow the ledger into its next era, with their tickets") {
    Vector(translating, redecoding).foreach { env =>
      Vector(Era.Mary -> Era.Alonzo, Era.Babbage -> Era.Conway).foreach { (from, to) =>
        val (raw, parts) = payment(from)
        val before = holding(from, raw)
        val (after, dropped) = before.sync(Right(ledger(to)), 1001, env)
        assertEquals(dropped, Vector.empty, s"$from to $to")
        assertEquals(after.entries.map(_.era), Vector(to))
        assertEquals(after.entries.map(_.ticket), before.entries.map(_.ticket))
        assertEquals(after.entries.map(_.raw), Vector(EraInjection.envelope(parts, to)))
        assertEquals(
          LocalMonitor.snapshot(after).txs.map(_.tx),
          Vector(LocalMonitor.wrapTx(to, EraInjection.envelope(parts, to)))
        )
      }
    }
  }

  test("a held transaction that does not reach the new era leaves the pool with the mismatch") {
    // held in Babbage with an update proposal, which Conway's body has no field for
    val (_, parts) = payment(Era.Babbage, extra = Vector(update))
    val tx =
      xyz.kd5ujc.scarab.ledger.Tx.fromBlock(parts, Era.Babbage).fold(e => fail(e.message), identity)
    val entry = Entry(Era.Babbage, parts, tx, 1L)
    val state = pool(Era.Babbage).copy(entries = Vector(entry), nextTicket = 2L)
    Vector(translating, redecoding).foreach { env =>
      val (after, dropped) = state.sync(Right(ledger(Era.Conway)), 1001, env)
      assertEquals(after.entries, Vector.empty)
      assertEquals(dropped, Vector(entry -> Rejection.WrongEra(Era.Babbage.index, Era.Conway)))
    }
  }

  test("the injection of each build") {
    assertEquals(EraInjection.of(ReferenceBuild.Release11_1), EraInjection.Translate)
    assertEquals(EraInjection.of(ReferenceBuild.Release11_2), EraInjection.Redecode)
    assertEquals(EraInjection.of(ReferenceBuild.LeiosB004fe0c), EraInjection.Redecode)
    assertEquals(translating.injection, EraInjection.Translate)
    assertEquals(redecoding.injection, EraInjection.Redecode)
  }

  test("a transaction is written as its era writes it: three items before Alonzo, four from it") {
    val (_, parts) = payment(Era.Shelley)
    Vector(Era.Shelley, Era.Allegra, Era.Mary).foreach(e =>
      assertEquals(EraInjection.envelope(parts, e).value.head, 0x83.toByte)
    )
    Vector(Era.Alonzo, Era.Babbage, Era.Conway).foreach(e =>
      assertEquals(EraInjection.envelope(parts, e), xyz.kd5ujc.scarab.ledger.Tx.envelope(parts))
    )
  }

  // ---- node to node: the hard-fork combinator's GenTx and GenTxId of each era (#934)

  private val codec = TxSubmission2.eraCodec(RelayedTransactions)

  private def roundTrip(
      state: TxSubmission2.State,
      sender: ChainSync.Role,
      message: TxSubmission2Engine.Message[EraTxId, EraTx]
  ): (Bytes, TxSubmission2Engine.Message[EraTxId, EraTx]) =
    val wire = codec.encode(state, sender, message).fold(e => fail(e.message), identity)
    codec.decodePrefix(state, sender, wire) match
      case ChainSync.DecodeResult.Decoded(decoded, n) if n == wire.size => (wire, decoded)
      case other => fail(s"not decoded whole: $other")

  test("TxSubmission2 carries each era's transaction and id under the era's index") {
    eras.foreach { era =>
      val (raw, parts) = payment(era)
      val id = EraTxId(era, parts.id)
      val (offered, ids) = roundTrip(
        TxSubmission2.State.Ids(true),
        ChainSync.Role.Client,
        TxSubmission2Engine.Message.ReplyIds(Vector(TxSubmission2Engine.Offer(id, 100L)))
      )
      assertEquals(
        ids,
        TxSubmission2Engine.Message.ReplyIds(Vector(TxSubmission2Engine.Offer(id, 100L)))
      )
      assert(
        offered.hex.contains("82" + f"${era.index}%02x" + "5820" + parts.id.hex),
        s"$era: the id is [${era.index}, id]"
      )
      val (sent, txs) = roundTrip(
        TxSubmission2.State.Txs,
        ChainSync.Role.Client,
        TxSubmission2Engine.Message.ReplyTxs(Vector(EraTx(era, raw)))
      )
      assertEquals(txs, TxSubmission2Engine.Message.ReplyTxs(Vector(EraTx(era, raw))))
      assert(
        sent.hex.contains("82" + f"${era.index}%02x" + "d818"),
        s"$era: [${era.index}, #6.24(tx)]"
      )
      assertEquals(codec.bodyId(EraTx(era, raw)), Right(id), s"$era: the id of the body")
    }
  }

  test("a Byron GenTx and a transaction its era does not read end the protocol") {
    val (raw, _) = payment(Era.Conway)
    val byron = TxSubmission2Engine.Message.ReplyTxs(Vector(EraTx(Era.Byron, raw)))
    assert(codec.encode(TxSubmission2.State.Txs, ChainSync.Role.Client, byron).isLeft)
    // a Conway body with a Shelley tag: Shelley's record has three items, not four
    val shelley = TxSubmission2Engine.Message.ReplyTxs(Vector(EraTx(Era.Shelley, raw)))
    val wire = TxSubmission2
      .codec(RelayedTransactions)
      .encode(
        TxSubmission2.State.Txs,
        ChainSync.Role.Client,
        TxSubmission2Engine.Message.ReplyTxs(Vector(raw))
      )
      .fold(e => fail(e.message), identity)
    // the same bytes, the era index 6 written as 1
    val tagged = Bytes(
      wire.value
        .updated(wire.value.indexOfSlice(Vector(0x82, 0x06, 0xd8).map(_.toByte)) + 1, 0x01.toByte)
    )
    codec.decodePrefix(TxSubmission2.State.Txs, ChainSync.Role.Client, tagged) match
      case ChainSync.DecodeResult.Failed(_: ProtocolError) => ()
      case other => fail(s"a Conway transaction read as Shelley's: $other")
    assert(codec.encode(TxSubmission2.State.Txs, ChainSync.Role.Client, shelley).isLeft)
  }

  test(
    "the pool offers its peers the transactions of its era, and takes theirs of an earlier era"
  ) {
    val (babbage, babbageParts) = payment(Era.Babbage, i = 1)
    val (mary, maryParts) = payment(Era.Mary, i = 2)
    for
      pool <- Mempool
        .create[IO](Right(ledger(Era.Babbage)), 1000, translating, None, MempoolEvents.none[IO])
      local <- pool.submit(Submission(Era.Babbage.index, babbage), Source.Local)
      sink = pool.sink(failure => IO(fail(s"unexpected fatal: $failure")))
      _ <- sink.submit(Vector(EraTx(Era.Mary, mary)))
      offered <- pool.source.after(0L, 10)
      bodies <- offered.traverse(h => pool.source.body(h.id))
    yield
      assertEquals(local, Right(babbageParts.id))
      assertEquals(
        offered.map(h => (h.id, h.era)),
        Vector(babbageParts.id -> Era.Babbage, maryParts.id -> Era.Babbage)
      )
      assertEquals(
        bodies,
        Vector(
          Some(EraTx(Era.Babbage, babbage)),
          Some(EraTx(Era.Babbage, EraInjection.envelope(maryParts, Era.Babbage)))
        )
      )
  }

  shelleyBased.foreach { era =>
    test(s"$era: a held transaction is applied again without the static checks") {
      // a signature over other bytes: the witness check, a static one, refuses it once
      val (_, parts) = payment(era, forged = true)
      val tx =
        xyz.kd5ujc.scarab.ledger.Tx.fromBlock(parts, era).fold(e => fail(e.message), identity)
      val l = ledger(era)
      val txEnv = translating.txEnv(1000, l)
      assert(l.submit(tx, txEnv, translating.rules).isLeft, s"$era: every check refuses it")
      assert(l.reapply(tx, txEnv, translating.rules).isRight, s"$era: reapplyTx does not verify it")
    }
  }

  /** The pointers of the stake credentials registered in the pool's ledger. */
  private def pointers(s: MempoolState): Set[xyz.kd5ujc.scarab.ledger.Ptr] = s.current match
    case Right(l: EarlierLedger[e]) => l.pre.dState.get(l.ls).accounts.ptrs.keySet.toSet
    case other                      => fail(s"not a ledger before Conway: $other")

  shelleyBased.foreach { era =>
    test(
      s"$era: a registration held second gets the pointer of transaction index 0, as every held one"
    ) {
      // `mkMempoolEnv` applies every transaction of the mempool at `ledgerIx = minBound`
      // (Shelley/API/Mempool.hs:363-377, cardano-ledger f649f975), and DELEGS makes the pointer of
      // a registration of it (Delegs.hs:194-197)
      val registration =
        4 -> (head(4, 1) ++ head(4, 2) ++ head(0, 0) ++ head(4, 2) ++ head(0, 0) ++
          bstr(Bytes(Vector.fill(28)(0x5a.toByte))))
      val (first, _) = payment(era, i = 1)
      val (second, _) = payment(era, i = 2, extra = Vector(registration), paid = 97 * ada)
      val held = Vector(first, second).foldLeft(pool(era)) { (s, raw) =>
        s.add(Submission(era.index, raw), translating, Source.Local)
          .fold(r => fail(r.message), _._1)
      }
      val expected = Set(xyz.kd5ujc.scarab.ledger.Ptr(BigInt(1001), BigInt(0), BigInt(0)))
      assertEquals(pointers(held), expected, "admitted")
      val (synced, dropped) = held.sync(held.base, 1000, translating)
      assertEquals(dropped, Vector.empty)
      assertEquals(pointers(synced), expected, "revalidated")
      assertEquals(synced.entries.map(_.ticket), Vector(1L, 2L), "the tickets keep their order")
    }
  }

  // ---- a real hard fork, the two revalidations, and a peer's earlier-era transaction

  test("a hard fork in the tick brings the held transactions into the new era: Babbage to Conway") {
    given ShelleyBasedEra[Babbage] = ShelleyBasedEra.SBabbage
    val nes = NativeRules.stateIn[Babbage](7, (1 to 2).toVector.map(i => txIn(i) -> funds))
    val pre = PreConway[Babbage]
    // the definite update of the next boundary is to Conway's version: the chain enters Conway there
    val utxo = pre.gov.modify(g =>
      g.copy(futurePParams =
        FuturePParams.DefinitePParamsUpdate(g.curPParams.copy(protocolMajor = BigInt(9)))
      )
    )(nes.es.ls.utxo)
    val forking =
      nes.copy[Babbage](es = nes.es.copy[Babbage](ls = nes.es.ls.copy[Babbage](utxo = utxo)))
    val boundary = translating.geometry.firstSlot(nes.epoch.toBigInt + 1)
    def at(tip: BigInt) =
      PoolLedger
        .ticked(
          EraNes.InEra(ShelleyBasedEra.SBabbage, forking),
          0L,
          Some(tip),
          translating,
          NativeRules.transition
        )
        .fold(f => fail(f.toString), identity)
    val before = at(boundary - 2)
    assertEquals(before.map(_.era), Right(Era.Babbage), "the slot before the boundary")
    val (raw, parts) = payment(Era.Babbage)
    val held = MempoolState
      .empty(before, boundary - 2, None)
      .add(Submission(Era.Babbage.index, raw), translating, Source.Local)
      .fold(r => fail(r.message), _._1)
    val after = at(boundary - 1)
    assertEquals(after.map(_.era), Right(Era.Conway), "the boundary's slot, translated")
    Vector(translating, redecoding).foreach { env =>
      val (moved, dropped) = held.sync(after, boundary - 1, env)
      assertEquals(dropped, Vector.empty)
      assertEquals(
        moved.entries.map(e => (e.id, e.era, e.ticket)),
        Vector((parts.id, Era.Conway, 1L))
      )
      assertEquals(
        LocalMonitor.snapshot(moved).txs.map(_.tx),
        Vector(LocalMonitor.wrapTx(Era.Conway, raw))
      )
    }
  }

  test(
    "a held transaction brought into a new era is applied again without the static checks under 11.1, with every check under the Leios build"
  ) {
    // held in Babbage with a signature over other bytes, as no admission would take it: only the
    // full validation of a re-decoded transaction checks the signature again
    val (_, parts) = payment(Era.Babbage, forged = true)
    val tx =
      xyz.kd5ujc.scarab.ledger.Tx.fromBlock(parts, Era.Babbage).fold(e => fail(e.message), identity)
    val entry = Entry(Era.Babbage, parts, tx, 1L)
    val state = pool(Era.Babbage).copy(entries = Vector(entry), nextTicket = 2L)
    val (translated, kept) = state.sync(Right(ledger(Era.Conway)), 1000, translating)
    assertEquals(kept, Vector.empty, "11.1 reapplies it: the signature is a static check")
    assertEquals(translated.entries.map(_.era), Vector(Era.Conway))
    val (redecoded, dropped) = state.sync(Right(ledger(Era.Conway)), 1000, redecoding)
    assertEquals(redecoded.entries, Vector.empty)
    dropped match
      case Vector(
            (`entry`, Rejection.Ledger(_: xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected))
          ) =>
        ()
      case other => fail(s"the Leios build must refuse it again: $other")
  }

  test(
    "a peer's transaction of an earlier era reaches a Conway pool, held and offered as Conway's"
  ) {
    val (babbage, parts) = payment(Era.Babbage, i = 1)
    for
      pool <- Mempool
        .create[IO](Right(ledger(Era.Conway)), 1000, translating, None, MempoolEvents.none[IO])
      sink = pool.sink(failure => IO(fail(s"unexpected fatal: $failure")))
      _ <- sink.submit(Vector(EraTx(Era.Babbage, babbage)))
      offered <- pool.source.after(0L, 10)
      body <- pool.source.body(parts.id)
    yield
      assertEquals(offered.map(h => (h.id, h.era)), Vector(parts.id -> Era.Conway))
      assertEquals(body, Some(EraTx(Era.Conway, babbage)))
  }

  test(
    "a peer's transaction keeps the era it is sent in: under 11.1 a Babbage pool holds a Babbage one and refuses a Conway one its era reads"
  ) {
    // the combinator matches a transaction in the era it names before any injection, and one of a
    // later era than the ledger's is a mismatch (`matchPolyTx'`, `HardFork/Combinator/InjectTxs.hs:60-82`,
    // ouroboros-consensus 82ecba32), though Babbage reads these bytes
    val (conway, _) = payment(Era.Conway, i = 1)
    val (babbage, parts) = payment(Era.Babbage, i = 2)
    for
      log <- Ref.of[IO, Vector[MempoolEvent]](Vector.empty)
      pool <- Mempool.create[IO](
        Right(ledger(Era.Babbage)),
        1000,
        translating,
        None,
        event => log.update(_ :+ event)
      )
      sink = pool.sink(failure => IO(fail(s"unexpected fatal: $failure")))
      _ <- sink.submit(Vector(EraTx(Era.Conway, conway), EraTx(Era.Babbage, babbage)))
      offered <- pool.source.after(0L, 10)
      events <- log.get
    yield
      assertEquals(
        offered.map(h => (h.id, h.era)),
        Vector(parts.id -> Era.Babbage),
        "the Babbage one is held and offered as Babbage's"
      )
      assertEquals(
        events.collect { case MempoolEvent.Rejected(_, reason) => reason },
        Vector(Rejection.WrongEra(Era.Conway.index, Era.Babbage)),
        "the Conway one is refused with the era mismatch"
      )
  }
