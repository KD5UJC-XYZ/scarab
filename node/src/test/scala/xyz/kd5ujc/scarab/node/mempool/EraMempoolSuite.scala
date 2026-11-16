// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Block, TestLedger}
import xyz.kd5ujc.scarab.ledger.rules.{NativeRules, PreConway}
import xyz.kd5ujc.scarab.ledger.state.{
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
class EraMempoolSuite extends FunSuite:
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
      extra: Vector[(Int, Vector[Byte])] = Vector.empty
  ): (Bytes, Block.Transaction) =
    val body = Bytes(
      head(5, 4 + extra.size) ++
        Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(txIn(i).id) ++ head(0, 0) ++
        Vector(0x01.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(bob.address().raw) ++
        head(0, 99 * ada) ++
        Vector(0x02.toByte) ++ head(0, ada) ++
        Vector(0x03.toByte) ++ head(0, 100000) ++
        extra.flatMap((k, v) => head(0, k) ++ v)
    )
    val id = Blake2b.hash256.hash(body)
    val witnesses = Bytes(
      head(5, 1) ++ Vector(0x00.toByte) ++ head(4, 1) ++ head(4, 2) ++ bstr(alice.vkey) ++
        bstr(alice.sign(id))
    )
    val parts = Block.Transaction(body, witnesses, None, true)
    (EraInjection.envelope(parts, era), parts)

  /** A Shelley update proposal with no proposals, `[{}, 5]`: read from Shelley to Babbage, and no
    * field of Conway's body.
    */
  private val update: (Int, Vector[Byte]) = 6 -> (head(4, 2) ++ head(5, 0) ++ head(0, 5))

  private def earlier[E <: EraTag](major: Int)(using pre: PreConway[E]): PoolLedger =
    given ShelleyBasedEra[E] = pre.era
    val nes = NativeRules.stateIn[E](major, (1 to 2).toVector.map(i => txIn(i) -> funds))
    EarlierLedger(pre, nes.es.ls, nes.es.chainAccount, nes.epoch.toBigInt)

  private def funds = out(alice.address(), 100 * ada)

  /** A ledger of `era` with two outputs of 100 ada for alice, at txIn(1) and txIn(2). */
  private def ledger(era: Era): PoolLedger = era match
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
      submit(era, Era.Byron, Bytes(Vector(0x80.toByte))) match
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
