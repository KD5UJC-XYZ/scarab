// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.IO
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{Build, Conway}
import xyz.kd5ujc.scarab.node.forge.BlockBuilder

/** `WhetherToIntervene` of the reference: a transaction from a peer is held with the `is_valid` its
  * scripts call for, one from a local client is judged as it came. The transactions are the four
  * recorded `raw-dst` boundary cases (a script that passes or fails, each claimed valid or invalid;
  * `fixtures/reference/plutus-boundaries`) over a Conway ledger that holds their inputs.
  */
class MempoolInterventionSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given cats.mtl.Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private val mempoolEnv = TxKit.env

  /** A recorded case, its parts as received and the ledger that holds what it reads. */
  private final case class Case(plutus: PlutusCase, parts: Block.Transaction):
    val ledger: MempoolLedger =
      val funds = plutus.spent ++ plutus.references ++ plutus.collateral
      MempoolLedger.of(Build.funded(Build.nes[Conway](5, plutus.params), funds))
    val tipSlot: BigInt = plutus.slot - 1
    def raw(flag: Boolean): Bytes = MempoolState.encode(parts.copy(isValid = flag))
    def pool: MempoolState = MempoolState.empty(Right(ledger), tipSlot, 1000000)

  private def hex(j: io.circe.Json, field: String): Option[Bytes] =
    j.hcursor
      .get[Option[String]](field)
      .fold(e => fail(e.message), identity)
      .map(h => Bytes.fromHex(h).fold(e => fail(e.message), identity))

  private val cases: Map[String, Case] =
    parse(
      Files.readString(
        Path.of("fixtures/reference/plutus-boundaries/transactions/conway-pv10.json")
      )
    )
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("the boundary fixture is not an array"))
      .map { j =>
        val plutus = PlutusCase.fromJson(j)
        val body = hex(j, "body").getOrElse(fail("a case has no body"))
        val witnesses = hex(j, "witnesses").getOrElse(fail("a case has no witnesses"))
        plutus.name -> Case(
          plutus,
          Block.Transaction(body, witnesses, hex(j, "auxiliary"), plutus.tx.isValid)
        )
      }
      .toMap

  private def scenario(name: String): Case = cases.getOrElse(name, fail(s"no case $name"))

  private def admitted(c: Case, flag: Boolean, source: Source): Entry =
    c.pool
      .add(c.raw(flag), mempoolEnv, source)
      .fold(r => fail(s"refused: ${r.message}"), _._2)

  private def flagByte(raw: Bytes): Byte = raw(raw.size - 2)

  private def tagMismatch(flag: Boolean): PartialFunction[Any, Boolean] =
    case Left(
          Rejection.Ledger(
            LedgerFailure.Rejected(
              _,
              PredicateFailure.Utxow(
                UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(`flag`, _)))
              )
            )
          )
        ) =>
      true

  private val failing = scenario("raw-dst-fail-valid-true")
  private val passing = scenario("raw-dst-pass-valid-false")

  test(
    "a remote transaction claiming valid whose script fails is held as invalid, flag byte only"
  ) {
    val received = failing.raw(true)
    val entry = admitted(failing, true, Source.Remote)
    assert(!entry.tx.isValid, "the held transaction must carry is_valid = false")
    assertEquals(flagByte(entry.raw), 0xf4.toByte)
    assertEquals(entry.raw, failing.raw(false))
    assertEquals(entry.raw.size, received.size)
    val differing = (0 until received.size).filter(i => received(i) != entry.raw(i))
    assertEquals(differing, Vector(received.size - 2))
    assertEquals(entry.id, Blake2b.hash256.hash(failing.parts.body))
    assertEquals(entry.size, received.size)
  }

  test("a remote transaction claiming invalid whose scripts pass is held as valid") {
    val entry = admitted(passing, false, Source.Remote)
    assert(entry.tx.isValid, "the held transaction must carry is_valid = true")
    assertEquals(entry.raw, passing.raw(true))
    assertEquals(entry.id, Blake2b.hash256.hash(passing.parts.body))
  }

  test("a remote transaction whose flag agrees with its scripts is held as received") {
    val ok = scenario("raw-dst-pass-valid-true")
    val bad = scenario("raw-dst-fail-valid-false")
    assertEquals(admitted(ok, true, Source.Remote).raw, ok.raw(true))
    assertEquals(admitted(bad, false, Source.Remote).raw, bad.raw(false))
    assert(!admitted(bad, false, Source.Remote).tx.isValid)
  }

  test("a local transaction with a wrong flag is refused as before") {
    val wrongTrue = failing.pool.add(failing.raw(true), mempoolEnv, Source.Local)
    val wrongFalse = passing.pool.add(passing.raw(false), mempoolEnv, Source.Local)
    assert(tagMismatch(true).applyOrElse(wrongTrue, _ => false), wrongTrue.toString)
    assert(tagMismatch(false).applyOrElse(wrongFalse, _ => false), wrongFalse.toString)
  }

  test("a flipped transaction survives a tip change and is listed as invalid by the forger") {
    val held = failing.pool
      .add(failing.raw(true), mempoolEnv, Source.Remote)
      .fold(r => fail(r.message), _._1)
    val (synced, dropped) = held.sync(Right(failing.ledger), failing.tipSlot, mempoolEnv)
    assertEquals(dropped, Vector.empty)
    assertEquals(synced.entries.map(_.raw), held.entries.map(_.raw))
    assertEquals(synced.entries.map(_.tx.isValid), Vector(false))
    val entry = synced.entries.head
    val piece = BlockBuilder.piece(entry).fold(e => fail(e.message), identity)
    assert(!piece.valid)
    assertEquals(piece.body, failing.parts.body)
    assertEquals(piece.witnesses, failing.parts.witnesses)
    assertEquals(piece.aux, failing.parts.auxiliaryData)
    // an invalid_transactions list of one index, the first transaction: [0]
    assertEquals(
      BlockBuilder.body(Vector(piece)).invalid,
      Bytes.fromArray(Array(0x81, 0x00).map(_.toByte))
    )
    val selected = BlockBuilder.select(
      failing.ledger,
      failing.tipSlot + 1,
      failing.ledger.epoch,
      mempoolEnv.geometry,
      mempoolEnv.globals,
      synced.entries,
      BlockBuilder.Limits(65536, xyz.kd5ujc.scarab.plutus.ExUnits(14000000, 10000000000L))
    )
    assertEquals(selected.map(_.map(_._1.id)), Right(Vector(entry.id)))
  }

  test(
    "a peer's transactions enter through the remote path and a local client's through the local one"
  ) {
    for
      pool <- Mempool.create[IO](
        Right(failing.ledger),
        failing.tipSlot,
        mempoolEnv,
        1000000,
        MempoolEvents.none[IO]
      )
      sink = pool.sink(f => IO(fail(s"unexpected fatal: $f")))
      local <- pool.add(failing.raw(true), Source.Local)
      _ <- sink.submit(Vector(failing.raw(true)))
      held <- pool.snapshot
    yield
      assert(tagMismatch(true).applyOrElse(local, _ => false), local.toString)
      assertEquals(held.entries.map(_.tx.isValid), Vector(false))
  }
