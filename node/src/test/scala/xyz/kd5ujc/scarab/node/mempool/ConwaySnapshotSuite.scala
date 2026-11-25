// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.era.EraTxError
import xyz.kd5ujc.scarab.ledger.era.rules.{ConwayMempoolRun, UtxoStateDefect}
import xyz.kd5ujc.scarab.protocol.{Era, ReferenceBuild}

/** The pool's Conway snapshot of a slot, which a Conway block takes its transactions from
  * ([[MempoolState.conwaySnapshot]], `getSnapshotFor`): the held Conway transactions applied again
  * in order on the ledger of the slot as each build's consensus applies them, the refused left out
  * and kept in the pool, and those the rules have no verdict on withheld, by the type of the run's
  * outcome.
  */
class ConwaySnapshotSuite extends FunSuite:
  import TxKit.*
  import xyz.kd5ujc.scarab.ledger.TestLedger.txIn

  /** TxKit's slot, in its state's epoch (5: 1000-slot epochs). */
  private val slot: BigInt = 5001

  private def under(build: ReferenceBuild): MempoolEnv =
    env.copy(globals = env.globals.copy(referenceBuild = build))

  /** `raw` held as validated in `ledger` at `slot`, as the pool holds what it admitted. */
  private def held(ledger: ConwayPoolLedger, raw: Bytes, e: MempoolEnv = env): Entry =
    Entry(
      Held.InConway(ledger.heldAsValidated(ledger.readOf(raw), ConwayPoolEnv.of(e, slot))),
      1L,
      0L,
      scala.concurrent.duration.Duration.Zero
    )

  private def ids(kept: Vector[(Entry, ConwayAdmitted)]): Vector[Bytes] = kept.map(_._1.id)

  test("the transactions that still apply, in order; the refused left out, none withheld") {
    val l3 = TxKit.ledger(3)
    val first = held(l3, pay(1)._1)
    // its only input is not in the state: refused (`AllInputsSpent`)
    val missing = held(l3, pay(9)._1)
    // the same input as the first, which the first spends: refused after it
    val again = held(l3, pay(1, fee = 2 * ada)._1)
    val third = held(l3, pay(2)._1)
    ReferenceBuild.values.foreach { build =>
      val (kept, withheld) = MempoolState.conwaySnapshot(
        Vector(first, missing, again, third),
        l3,
        ConwayPoolEnv.of(under(build), slot)
      )
      assertEquals(ids(kept), Vector(first.id, third.id), build.name)
      assertEquals(withheld, Vector.empty, build.name)
    }
  }

  test("a transaction of another era is not a Conway block's") {
    val l3 = TxKit.ledger(3)
    val conway = held(l3, pay(1)._1)
    val (raw, _) = pay(2)
    val parsed = MempoolState.parseParts(raw).fold(r => fail(r.message), identity)
    val babbage = Entry(Era.Babbage, parsed.parts, parsed.tx, 2L)
    val (kept, withheld) =
      MempoolState.conwaySnapshot(Vector(babbage, conway), l3, ConwayPoolEnv.of(env, slot))
    assertEquals(ids(kept), Vector(conway.id))
    assertEquals(withheld, Vector.empty)
  }

  test("each build applies a held transaction again as its consensus does") {
    // alice's payment with a signature not over its body, held as validated: only the static
    // checks refuse it
    val (raw, id) = pay(1)
    val signature = alice.sign(id).value
    val at = raw.value.indexOfSlice(signature)
    val forged = Bytes(raw.value.updated(at, (raw.value(at) ^ 0x01).toByte))
    def atVersion(major: Int) =
      TxKit.ledger(3).withParams(_.copy(protocolMajor = BigInt(major)))
    ReferenceBuild.values.foreach { build =>
      val e = under(build)
      val snapshot = (from: Int, on: Int) =>
        ids(
          MempoolState
            .conwaySnapshot(
              Vector(held(atVersion(from), forged, e)),
              atVersion(on),
              ConwayPoolEnv.of(e, slot)
            )
            ._1
        )
      // at the version it was validated at, every build skips the static checks
      assertEquals(snapshot(11, 11), Vector(id), build.name)
      // at another, 11.1's `reapplyTx` still skips them; 11.2's and Leios's `reapplyValidatedTx`
      // validates the transaction in full, and the signature fails
      val expected =
        if ConwayMempoolRun.Reapply.of(build) == ConwayMempoolRun.Reapply.ReapplyTx then Vector(id)
        else Vector.empty
      assertEquals(snapshot(10, 11), expected, build.name)
    }
  }

  test("no verdict on a transaction, an exception of the rules: it is withheld, the rest kept") {
    val l3 = TxKit.ledger(3)
    val first = held(l3, pay(1)._1)
    val second = held(l3, pay(2)._1)
    // the first annotation made throws: 11.1's `reapplyTx` annotates each transaction again, and
    // so the first is withheld; 11.2 and Leios keep the annotation of its validation at the same
    // version, and the run throws for neither
    ReferenceBuild.values.foreach { build =>
      val calls = AtomicInteger(0)
      val base = ConwayPoolEnv.of(under(build), slot)
      val throwing = base.copy(judging =
        base.judging.copy(annotation =
          (posix, pp, utxo, tx) =>
            if calls.getAndIncrement() == 0 then throw IllegalStateException("annotation")
            else ConwayMempoolRun.annotate(posix, pp, utxo, tx)
        )
      )
      val (kept, withheld) = MempoolState.conwaySnapshot(Vector(first, second), l3, throwing)
      if ConwayMempoolRun.Reapply.of(build) == ConwayMempoolRun.Reapply.ReapplyTx then
        assertEquals(ids(kept), Vector(second.id), build.name)
        withheld match
          case Vector((entry, Rejection.RulesDefect(_))) => assertEquals(entry.id, first.id)
          case other => fail(s"${build.name}: not the first withheld: $other")
      else
        assertEquals(ids(kept), Vector(first.id, second.id), build.name)
        assertEquals(withheld, Vector.empty, build.name)
    }
  }

  test("no verdict on a transaction, a defect of Conway's rules: it is withheld, the rest kept") {
    // an output with a reference script, created by a payment, then kept with a script hash that is
    // not its bytes' (an output only a damaged store holds)
    val fixture = xyz.kd5ujc.scarab.ledger.PlutusCase
      .load(Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
    val script = (fixture.tx.witnesses.scripts ++
      fixture.references.values.flatMap(_.referenceScript))
      .find(_.kind.isPlutus)
      .getOrElse(fail("fixture has no Plutus script"))
    val plutus = script.kind -> script.plutus.getOrElse(fail("fixture discarded script bytes"))
    val l3 = TxKit.ledger(3)
    val (created, _) =
      tx(
        Vector(txIn(1)),
        Vector(bob.address() -> (99 * ada)),
        ada,
        Vector(alice),
        referenceScript = Some(plutus)
      )
    val withScript = l3.applied(created, slot)
    val (scripted, out) = withScript.utxo.toMap
      .collectFirst { case (in, o) if o.scriptRef.nonEmpty => in -> o }
      .getOrElse(fail("no output with a reference script"))
    val damaged = withScript.withUtxo(
      withScript.utxo.updated(
        scripted,
        out.copy(scriptRef = out.scriptRef.map(_.copy(hash = Bytes(Vector.fill(28)(7.toByte)))))
      )
    )
    // held as validated before the damage: a payment that reads the output as a reference input
    val referencing = tx(
      Vector(txIn(2)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      referenceInputs = Vector(scripted)
    )._1
    val reading = held(withScript, referencing)
    val paying = held(withScript, pay(3)._1)
    // 11.1's `reapplyTx` annotates it again, which reads the damaged output: the node's defect
    val (kept, withheld) = MempoolState.conwaySnapshot(
      Vector(reading, paying),
      damaged,
      ConwayPoolEnv.of(under(ReferenceBuild.Release11_1), slot)
    )
    assertEquals(ids(kept), Vector(paying.id))
    withheld match
      case Vector(
            (
              entry,
              Rejection.ConwayUnjudged(
                Some(id),
                ConwayUnjudged.UtxoState(UtxoStateDefect.StoredOutput(`scripted`, _: EraTxError))
              )
            )
          ) =>
        assertEquals(entry.id, reading.id)
        assertEquals(id, reading.id)
      case other => fail(s"not the reading transaction withheld: $other")
  }
