// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.node.mempool.LocalSubmission.message
import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.effect.{IO, Ref}
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import cats.data.NonEmptyChain
import munit.{CatsEffectSuite, ScalaCheckSuite}
import xyz.kd5ujc.scarab.ledger.rules.NativeRules
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState}
import org.scalacheck.Gen
import org.scalacheck.Prop.*

import TestLedger.*
import Capacity.fits

/** Real transaction bytes over a synthetic Conway ledger. */
object TxKit:
  val alice: Key = key()
  val bob: Key = key()
  val ada: BigInt = 1000000

  val env: MempoolEnv =
    MempoolEnv(
      LedgerGlobals(Supply, 1000, Rational(1, 20), network = 0, updateQuorum = 2),
      EpochGeometry(
        Instant.EPOCH,
        1000,
        epochLength = 1000,
        securityParam = TestGeometry.k(5),
        activeSlotsCoeff = TestGeometry.f("0.5")
      )
    )

  def head(major: Int, n: BigInt): Vector[Byte] =
    if n < 24 then Vector(((major << 5) | n.toInt).toByte)
    else if n < 256 then Vector(((major << 5) | 24).toByte, n.toByte)
    else if n < 65536 then Vector(((major << 5) | 25).toByte, (n >> 8).toByte, n.toByte)
    else if n < (BigInt(1) << 32) then
      ((major << 5) | 26).toByte +: Vector(24, 16, 8, 0).map(s => (n >> s).toByte)
    else
      ((major << 5) | 27).toByte +: Vector(56, 48, 40, 32, 24, 16, 8, 0).map(s => (n >> s).toByte)
  def bstr(b: Bytes): Vector[Byte] = head(2, b.size) ++ b.value

  /** A signed Conway transaction `[body, witnesses, true, null]` and its id. */
  def tx(
      inputs: Vector[TxIn],
      outputs: Vector[(Address, BigInt)],
      fee: BigInt,
      signers: Vector[Key],
      valid: Boolean = true,
      votes: Vector[Byte] = Vector.empty,
      referenceScript: Option[(ScriptKind, Bytes)] = None,
      expires: Option[BigInt] = None,
      exUnits: Option[(BigInt, BigInt)] = None,
      certificates: Vector[Byte] = Vector.empty
  ): (Bytes, Bytes) =
    val ins =
      head(4, inputs.size) ++ inputs.flatMap(i => head(4, 2) ++ bstr(i.id) ++ head(0, i.index))
    val outs =
      head(4, outputs.size) ++ outputs.flatMap { (a, c) =>
        referenceScript.fold(head(4, 2) ++ bstr(a.raw) ++ head(0, c)) { (kind, code) =>
          val script = Bytes(head(4, 2) ++ head(0, kind.tag) ++ bstr(code))
          head(5, 3) ++ head(0, 0) ++ bstr(a.raw) ++ head(0, 1) ++ head(0, c) ++
            head(0, 3) ++ head(6, 24) ++ bstr(script)
        }
      }
    val body = Bytes(
      head(
        5,
        3 + (if votes.isEmpty then 0 else 1) + expires.size + (if certificates.isEmpty then 0
                                                               else 1)
      ) ++ Vector(
        0x00.toByte
      ) ++ ins ++ Vector(
        0x01.toByte
      ) ++ outs ++ Vector(0x02.toByte) ++ head(0, fee) ++
        expires.fold(Vector.empty[Byte])(slot => Vector(0x03.toByte) ++ head(0, slot)) ++
        (if certificates.isEmpty then Vector.empty else Vector(0x04.toByte) ++ certificates) ++
        (if votes.isEmpty then Vector.empty else Vector(0x13.toByte) ++ votes)
    )
    val id = Blake2b.hash256.hash(body)
    val vkeys = head(4, signers.size) ++ signers.flatMap { k =>
      head(4, 2) ++ bstr(k.vkey) ++ bstr(k.sign(id))
    }
    // one spending redeemer `[0, 0, 0, [mem, steps]]` (no script runs: the measure is read first)
    val redeemers = exUnits.fold(Vector.empty[Byte]) { (mem, steps) =>
      Vector(0x05.toByte) ++ head(4, 1) ++ head(4, 4) ++ head(0, 0) ++ head(0, 0) ++ head(0, 0) ++
        head(4, 2) ++ head(0, mem) ++ head(0, steps)
    }
    val witnesses =
      head(5, 1 + exUnits.size) ++ Vector(0x00.toByte) ++ vkeys ++ redeemers
    (
      Bytes(
        head(4, 4) ++ body.value ++ witnesses ++ Vector(
          if valid then 0xf5.toByte else 0xf4.toByte,
          0xf6.toByte
        )
      ),
      id
    )

  /** The Conway state of the rule suites ([[NativeRules.stateIn]], protocol version 10) with `n`
    * outputs of `value` for alice, at txIn(1..n).
    */
  def native(n: Int, value: BigInt = 100 * ada): NewEpochState[Conway] =
    NativeRules
      .stateIn[Conway](10, (1 to n).toVector.map(i => txIn(i) -> out(alice.address(), value)))

  /** The Conway ledger of a new-epoch state. */
  def conway(nes: NewEpochState[Conway]): MempoolLedger = MempoolLedger.of(nes)

  /** The Conway ledger with `n` outputs of `value` for alice, at txIn(1..n). */
  def ledger(n: Int, value: BigInt = 100 * ada): MempoolLedger = conway(native(n, value))

  extension (l: MempoolLedger)
    /** The ledger with the protocol version changed (the rules read the version in force). */
    def withProtocol(major: Int): MempoolLedger = withParams(_.copy(protocolMajor = major))

    /** The ledger with its protocol parameters changed. */
    def withParams(f: ProtocolParams => ProtocolParams): MempoolLedger =
      val gov = l.ls.utxo.gov
      l.copy(ls =
        l.ls.copy[Conway](utxo = l.ls.utxo.copy(gov = gov.copy(curPParams = f(gov.curPParams))))
      )

    /** The ledger after the transaction, which the test knows to be valid. */
    def applied(tx: Tx, slot: BigInt = 1000): MempoolLedger =
      l.submit(tx, env.txEnv(slot, l, 0), env.rules).fold(f => sys.error(f.message), identity)

  /** Alice pays `value - fee` to bob from `txIn(i)`. */
  def pay(i: Int, fee: BigInt = ada, value: BigInt = 100 * ada): (Bytes, Bytes) =
    tx(Vector(txIn(i)), Vector(bob.address() -> (value - fee)), fee, Vector(alice))

class MempoolSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScalaCheckSuite:
  private given cats.mtl.Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private given cats.mtl.Raise[IO, xyz.kd5ujc.scarab.network.NetworkError] with
    def functor = cats.effect.IO.asyncForIO
    def raise[E <: xyz.kd5ujc.scarab.network.NetworkError, A](error: E): IO[A] =
      IO(fail(error.message))
  import TxKit.*

  private def pool(ledger: MempoolLedger, capacityOverride: Option[Long] = None) =
    MempoolState.empty(Right(ledger), 1000, capacityOverride)

  private def add(s: MempoolState, raw: Bytes): Either[Rejection, MempoolState] =
    s.add(raw, env, Source.Local).map(_._1)

  private def reasonItem(rejection: Rejection): Bytes =
    LocalSubmission.reasonItem(rejection).fold(error => fail(error.message), identity)

  test("the next-slot view applies the full epoch transition before transaction validation") {
    import xyz.kd5ujc.scarab.ledger.state.{DeltaCoin, FuturePParams, PulsingRewUpdate, RewardUpdate}
    val before = native(1)
    val nextParams = before.es.ls.utxo.gov.curPParams.copy(maxTxSize = 1)
    val pending = RewardUpdate.empty.copy(deltaT = DeltaCoin(123), deltaR = DeltaCoin(-123))
    val scheduled = before.copy(
      ru = Some(PulsingRewUpdate.Complete(pending)),
      es = before.es.copy(ls =
        before.es.ls.copy[Conway](utxo =
          before.es.ls.utxo.copy(
            gov = before.es.ls.utxo.gov
              .copy(futurePParams = FuturePParams.DefinitePParamsUpdate(nextParams))
          )
        )
      )
    )
    val (raw, _) = pay(1)
    val old = MempoolLedger.of(scheduled)
    assert(MempoolState.empty(Right(old), 5999, None).add(raw, env, Source.Local).isRight)
    val ticked =
      MempoolLedger.tick(scheduled, Some(5999), env).fold(e => fail(e.toString), identity)
    assertEquals(ticked.epoch, BigInt(6))
    assertEquals(ticked.params, nextParams)
    assertEquals(ticked.treasury.value, BigInt(123))
    MempoolState.empty(Right(ticked), 5999, None).add(raw, env, Source.Local) match
      case Left(Rejection.Limits(_, failures)) =>
        val parsed = MempoolState.parse(raw).fold(e => fail(e.message), _._2)
        assertEquals(
          failures.toList,
          List(
            PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(BigInt(parsed.size), BigInt(1))))
          )
        )
      case other => fail(s"expected the adopted maximum-size rejection, received $other")
    val held = MempoolState
      .empty(Right(old), 5999, None)
      .add(raw, env, Source.Local)
      .fold(e => fail(e.message), _._1)
    // a held transaction is applied again without the static checks (`reapplyTx`), and the size
    // check is one (`runTestOnSignal`, `Babbage/Rules/Utxo.hs` 406): it stays in the pool
    val (synced, dropped) = held.sync(Right(ticked), 5999, env)
    assertEquals(synced.entries.map(_.raw), Vector(raw))
    assertEquals(dropped, Vector.empty)
    assertEquals(scheduled.ru, Some(PulsingRewUpdate.Complete(pending)))
  }

  test("next-slot tick handles origin and preserves a typed overflow failure") {
    val before =
      native(1).copy(epoch = xyz.kd5ujc.scarab.ledger.state.EpochNo.fromUnsigned(0))
    val origin = MempoolLedger.tick(before, None, env).fold(e => fail(e.toString), identity)
    assertEquals(origin.epoch, BigInt(0))
    val overflow = MempoolLedger.tick(before, Some((BigInt(1) << 64) - 1), env)
    assert(overflow.left.exists(_.isInstanceOf[xyz.kd5ujc.scarab.ledger.rules.TickFailure.Vocab]))
  }

  test("rollback exposes adopted and invalidated IDs for ordered parent-child re-offers") {
    val base = ledger(2)
    val (parent, parentId) = pay(1)
    val (child, childId) = tx(
      Vector(TxIn(parentId, 0)),
      Vector(alice.address() -> (98 * ada)),
      ada,
      Vector(bob)
    )
    val parentTx = MempoolState.parse(parent).fold(e => fail(e.message), _._2)
    for
      pool <- Mempool.create[IO](Right(base), 1000, env, None, MempoolEvents.none[IO])
      sink = pool.sink(failure => IO(fail(s"unexpected fatal: $failure")))
      _ <- pool.add(parent, Source.Local)
      _ <- pool.add(child, Source.Local)
      first <- pool.source.after(0, 10)
      _ <- pool.sync(Right(base.applied(parentTx)), 1001, Set(parentId))
      adoptedKnown <- sink.known(parentId)
      retained <- pool.source.body(childId)
      _ <- pool.sync(Right(base), 1000)
      invalidKnown <- sink.known(childId)
      rolledBack <- pool.snapshot
      _ <- sink.submit(Vector(parent, child))
      offered <- pool.source.after(first.last.ticket, 10)
    yield
      assert(!adoptedKnown)
      assertEquals(retained, Some(child))
      assert(!invalidKnown)
      assertEquals(rolledBack.entries, Vector.empty)
      assertEquals(offered.map(e => e.id), Vector(parentId, childId))
      assertEquals(offered.map(_.ticket), Vector(3L, 4L))
  }

  test("the pool keeps the hash of the tip it was synchronised with, also across a removal") {
    val base = ledger(2)
    val (first, firstId) = pay(1)
    val (second, _) = pay(2)
    val tip = Bytes(Vector.fill(32)(7.toByte))
    for
      pool <- Mempool.create[IO](Right(base), 1000, env, None, MempoolEvents.none[IO])
      initial <- pool.snapshot
      _ <- pool.add(first, Source.Local)
      _ <- pool.add(second, Source.Local)
      _ <- pool.sync(Right(base), 1001, Set.empty, Some(tip))
      synced <- pool.snapshot
      _ <- pool.removeTxsEvenIfValid(Set(firstId))
      removed <- pool.snapshot
    yield
      assertEquals(initial.tipHash, None)
      assertEquals((synced.tipSlot, synced.tipHash), (BigInt(1001), Some(tip)))
      assertEquals((removed.tipSlot, removed.tipHash), (BigInt(1001), Some(tip)))
      assertEquals(removed.entries.size, 1)
  }

  test("expiry removes a held ID and a rollback permits the same original bytes again") {
    val base = ledger(1)
    val (raw, id) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      expires = Some(1002)
    )
    for
      pool <- Mempool.create[IO](Right(base), 1000, env, None, MempoolEvents.none[IO])
      admitted <- pool.add(raw, Source.Local)
      dropped <- pool.sync(Right(base), 1002)
      known <- pool.sink(failure => IO(fail(s"unexpected fatal: $failure"))).known(id)
      _ <- pool.sync(Right(base), 1000)
      retried <- pool.add(raw, Source.Local)
      body <- pool.source.body(id)
    yield
      assertEquals(admitted, Right(id))
      assertEquals(dropped.map(_._1.raw), Vector(raw))
      assert(!known)
      assertEquals(retried, Right(id))
      assertEquals(body, Some(raw))
  }

  test("a refused conflicting transaction remains requestable after a fork frees its input") {
    val base = ledger(1)
    val (accepted, _) = pay(1)
    val (refused, refusedId) = pay(1, fee = 2 * ada)
    for
      pool <- Mempool.create[IO](Right(base), 1000, env, None, MempoolEvents.none[IO])
      _ <- pool.add(accepted, Source.Local)
      first <- pool.add(refused, Source.Local)
      known <- pool.sink(failure => IO(fail(s"unexpected fatal: $failure"))).known(refusedId)
      empty = base.withUtxo(Utxo.empty)
      _ <- pool.sync(Right(empty), 1001)
      _ <- pool.sync(Right(base), 1000)
      retried <- pool.add(refused, Source.Local)
    yield
      assert(first.isLeft)
      assert(!known)
      assertEquals(retried, Right(refusedId))
  }

  test(
    "a valid transaction is admitted; duplicates, double spends and bad bytes are typed rejections"
  ) {
    val s0 = pool(ledger(3))
    val (t1, id1) = pay(1)
    val s1 = add(s0, t1).fold(r => fail(r.message), identity)
    assertEquals(s1.entries.map(_.id), Vector(id1))
    assertEquals(add(s1, t1), Left(Rejection.AlreadyHeld(id1)))
    // the same input spent again, differently (another fee) conflicts with the held one
    val (clash, _) = pay(1, fee = 2 * ada)
    assert(add(s1, clash).left.exists(_.isInstanceOf[Rejection.Ledger]), add(s1, clash).toString)
    assert(add(s0, Bytes(Vector(0x80.toByte))).left.exists(_.isInstanceOf[Rejection.Malformed]))
    assert(add(s0, Bytes(Vector(1, 2, 3))).left.exists(_.isInstanceOf[Rejection.Malformed]))
    // not signed by the owner of the input
    val (forged, _) = tx(Vector(txIn(2)), Vector(bob.address() -> (99 * ada)), ada, Vector(bob))
    assert(add(s0, forged).left.exists(_.isInstanceOf[Rejection.Ledger]), add(s0, forged).toString)
    // an input that does not exist
    val (ghost, _) = pay(9)
    assert(add(s0, ghost).left.exists(_.isInstanceOf[Rejection.Ledger]))
  }

  test("refusals are sent as the ledger's ApplyTxErr: the MEMPOOL rule first, then the rule") {
    val s0 = pool(ledger(3))
    // none of the inputs is unspent: the MEMPOOL rule's failure, whatever else is wrong
    val (ghost, ghostId) = pay(9, fee = 1)
    val spent = add(s0, ghost).left.getOrElse(fail("accepted"))
    assertEquals(
      reasonItem(spent).hex,
      "8182068182077844416c6c20696e7075747320617265207370656e742e205472616e73616374696f6e206861732070726f6261626c7920616c7265616479206265656e20696e636c75646564"
    )
    // an unspent input and a fee below the minimum: FeeTooSmallUTxO, the minimum first
    val (cheap, _) = pay(1, fee = 1)
    val refused = add(s0, cheap).left.getOrElse(fail("accepted"))
    val reason = reasonItem(refused).hex
    assert(reason.startsWith("81820681820182008305"), reason)
    // a transaction already held is, for the ledger, one whose inputs are spent
    val (t1, _) = pay(1)
    val s1 = add(s0, t1).fold(r => fail(r.message), identity)
    assertEquals(
      reasonItem(add(s1, t1).left.getOrElse(fail("accepted"))).hex,
      reasonItem(spent).hex
    )
    // a transaction of another era is the combinator's era mismatch
    assertEquals(
      reasonItem(Rejection.WrongEra(5)).hex,
      "8282056742616262616765820666436f6e776179"
    )
    assert(ghostId.size == 32)
  }

  test(
    "a vote by an unelected committee hot key is refused by the mempool before protocol version 11 and by the GOV rule from it"
  ) {
    val hot = key()
    val action = Blake2b.hash256.hash(Bytes(Vector(7)))
    // { [0, hot]: { [action, 0]: [1, null] } }
    val votes = head(5, 1) ++ head(4, 2) ++ head(0, 0) ++ bstr(hot.hash) ++ head(5, 1) ++
      head(4, 2) ++ bstr(action) ++ head(0, 0) ++ head(4, 2) ++ head(0, 1) ++ Vector(0xf6.toByte)
    val (raw, id) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice, hot),
      votes = votes
    )
    def attempt(protocol: Int) =
      val l = ledger(1)
      add(pool(l.withProtocol(protocol)), raw).map(_ => ())
    // MEMPOOL records the unelected voter and runs LEDGER, whose GOV does not know the voter; the
    // root's failures come out reversed, LEDGER's after MEMPOOL's own
    assertEquals(
      attempt(10),
      Left(
        Rejection.Ledger(
          LedgerFailure.Rejected(
            id,
            NonEmptyChain(
              PredicateFailure.gov(
                GovFailure.VotersDoNotExist(Vector(Voter.CommitteeHot(hot.credential)))
              ),
              PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(Vector(hot.credential)))
            )
          )
        )
      )
    )
    // from 11 GOV refuses the unelected voter, and goes on to find it is not known either
    assertEquals(
      attempt(11),
      Left(
        Rejection.Ledger(
          LedgerFailure.Rejected(
            id,
            NonEmptyChain(
              PredicateFailure.gov(
                GovFailure.VotersDoNotExist(Vector(Voter.CommitteeHot(hot.credential)))
              ),
              PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(Vector(hot.credential)))
            )
          )
        )
      )
    )
    // applied again (`reapplyTx`, by revalidation and block production), the refusal holds every
    // failure as well, in the same order (`applySTSNonStatic`; issue 412)
    val l = ledger(1).withProtocol(11)
    val parsed = MempoolState.parse(raw).fold(r => fail(r.toString), _._2)
    assertEquals(
      l.reapply(parsed, env.txEnv(0, l, 0), env.rules).map(_ => ()),
      Left(
        LedgerFailure.Rejected(
          id,
          NonEmptyChain(
            PredicateFailure.gov(
              GovFailure.VotersDoNotExist(Vector(Voter.CommitteeHot(hot.credential)))
            ),
            PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(Vector(hot.credential)))
          )
        )
      )
    )
  }

  test("capacity is counted in the measure of a transaction and refuses what does not fit") {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val measure = (t: Bytes) => MempoolState.parse(t).fold(r => fail(r.message), _._2.size) + 4L
    // a block whose body leaves room for 3/4 of a transaction: the two blocks hold one
    val unit = measure(t1) * 3 / 4 + 1
    val s0 = pool(ledger(3).withParams(_.copy(maxBlockBodySize = 1024 + unit)))
    assertEquals(s0.capacity.bytes, 2 * unit)
    val s1 = add(s0, t1).fold(r => fail(r.message), identity)
    assertEquals(s1.bytes, t1.size.toLong)
    assertEquals(s1.used.bytes, measure(t1))
    add(s1, t2) match
      case Left(full @ Rejection.Full(needed, free)) =>
        assertEquals(needed.bytes, measure(t2))
        assertEquals(free, s1.free)
        assertEquals(full.dimension, CapacityDimension.Bytes)
      case other => fail(s"expected a full pool, received $other")
  }

  test("a transaction may spend the outputs of one held before it, and only in that order") {
    val s0 = pool(ledger(2))
    val (t1, id1) = pay(1)
    val (child, _) =
      tx(Vector(TxIn(id1, 0)), Vector(alice.address() -> (98 * ada)), ada, Vector(bob))
    assert(add(s0, child).isLeft, "the parent is not held yet")
    val s2 = add(s0, t1).flatMap(add(_, child)).fold(r => fail(r.message), identity)
    assertEquals(s2.entries.size, 2)
    assertEquals(s2.entries.map(_.ticket), Vector(1L, 2L))
  }

  test("sync drops the transactions a block contains and keeps the ones that still apply") {
    val l0 = ledger(3)
    val (t1, id1) = pay(1)
    val (child, idChild) =
      tx(Vector(TxIn(id1, 0)), Vector(alice.address() -> (98 * ada)), ada, Vector(bob))
    val (t2, id2) = pay(2)
    val s = List(t1, child, t2).foldLeft(pool(l0))((s, t) =>
      add(s, t).fold(r => fail(r.message), identity)
    )
    // a block adopts t1 and t2
    val l1 = List(t1, t2).foldLeft(l0) { (l, raw) =>
      val (_, tx) = MempoolState.parse(raw).toOption.get
      l.applied(tx)
    }
    val (synced, dropped) = s.sync(Right(l1), 1001, env)
    assertEquals(dropped.map(_._1.id).toSet, Set(id1, id2))
    assertEquals(synced.entries.map(_.id), Vector(idChild))
    assertEquals(synced.bytes, child.size.toLong)
    // a rollback of that block: the child no longer has its parent and is dropped, nothing is added back
    val (back, droppedAgain) = synced.sync(Right(l0), 1000, env)
    assertEquals(back.entries, Vector.empty)
    assertEquals(droppedAgain.map(_._1.id), Vector(idChild))
  }

  test("remove revalidates the rest: removing a parent removes its dependants") {
    val (t1, id1) = pay(1)
    val (child, idChild) =
      tx(Vector(TxIn(id1, 0)), Vector(alice.address() -> (98 * ada)), ada, Vector(bob))
    val (t2, id2) = pay(2)
    val s = List(t1, child, t2).foldLeft(pool(ledger(3)))((s, t) =>
      add(s, t).fold(r => fail(r.message), identity)
    )
    val (rest, dropped) = s.remove(Set(id1), env)
    assertEquals(rest.entries.map(_.id), Vector(id2))
    assertEquals(dropped.map(_._1.id), Vector(idChild))
  }

  test("forBlock takes the longest prefix within the byte budget") {
    val txs = (1 to 3).map(pay(_)._1)
    val s = txs.foldLeft(pool(ledger(3)))((s, t) => add(s, t).fold(r => fail(r.message), identity))
    val each = txs.head.size
    assertEquals(s.forBlock(each * 2L).map(_.raw), Vector(txs(0), txs(1)))
    assertEquals(s.forBlock(each - 1L), Vector.empty)
    assertEquals(s.forBlock(1000000L).size, 3)
  }

  // ---- property: whatever happens, what is held applies in order to the tip state

  enum Op:
    case Add(candidate: Int)
    case Block(take: Int) // a block adopts the first `take` held transactions
    case Rollback // the chain returns to the state before the last block
    case Remove(candidate: Int)

  /** Candidates: spends of the 6 outputs (two per output conflict), plus children of the first. */
  private val candidates: Vector[(Bytes, Bytes)] =
    val firsts = (1 to 6).toVector.flatMap(i => Vector(pay(i), pay(i, fee = 2 * ada)))
    val parent = pay(1)._2
    val children = (1 to 2).toVector.map(f =>
      tx(Vector(TxIn(parent, 0)), Vector(alice.address() -> (98 * ada - f)), ada, Vector(bob))
    )
    firsts ++ children

  private val opGen: Gen[List[Op]] = Gen.listOf(
    Gen.frequency(
      6 -> Gen.choose(0, candidates.size - 1).map(Op.Add(_)),
      2 -> Gen.choose(0, 4).map(Op.Block(_)),
      1 -> Gen.const(Op.Rollback),
      1 -> Gen.choose(0, candidates.size - 1).map(Op.Remove(_))
    )
  )

  private def replay(s: MempoolState): Either[LedgerFailure, MempoolLedger] =
    val base = s.base.fold(u => sys.error(u.toString), identity)
    s.entries.zipWithIndex.foldLeft[Either[LedgerFailure, MempoolLedger]](Right(base)) {
      case (Right(l), (e, i)) =>
        l.reapply(e.tx, env.txEnv(s.tipSlot, base, i), env.rules)
      case (left, _) => left
    }

  property("held transactions always apply in order to the tip state; accounting stays exact") {
    forAll(opGen) { script =>
      val l0 = ledger(6)
      var s = pool(l0.withParams(_.copy(maxBlockBodySize = 1024 + 1500)))
      var history = List.empty[MempoolLedger]
      script.foreach {
        case Op.Add(i) =>
          s.add(candidates(i)._1, env, Source.Local).foreach { case (next, _) => s = next }
        case Op.Remove(i) =>
          s = s.remove(Set(candidates(i)._2), env)._1
        case Op.Block(n) =>
          val adopted = s.entries.take(n)
          val base = s.base.fold(u => sys.error(u.toString), identity)
          val l1 = adopted.foldLeft(base)((l, e) => l.applied(e.tx, s.tipSlot))
          history = base :: history
          s = s.sync(Right(l1), s.tipSlot + 1, env)._1
        case Op.Rollback =>
          history match
            case previous :: rest =>
              history = rest
              s = s.sync(Right(previous), s.tipSlot - 1, env)._1
            case Nil => ()
      }
      assertEquals(replay(s).map(_.utxo), Right(s.current.toOption.get.utxo))
      assertEquals(s.entries.map(_.id).distinct.size, s.entries.size)
      assertEquals(s.bytes, s.entries.map(_.size.toLong).sum)
      assert(s.used.fits(s.capacity), s"${s.used} over ${s.capacity}")
      assert(s.entries.map(_.ticket) == s.entries.map(_.ticket).sorted)
    }
  }

  // ---- the effectful interpreter

  private def events: IO[(MempoolEvents[IO], Ref[IO, Vector[MempoolEvent]])] =
    Ref
      .of[IO, Vector[MempoolEvent]](Vector.empty)
      .map(r => ((e: MempoolEvent) => r.update(_ :+ e), r))

  test("Mempool[F]: add, offer to peers by ticket, collect from peers, sync") {
    for
      ev <- events
      mempool <- Mempool.create[IO](Right(ledger(4)), 1000, env, None, ev._1)
      (t1, id1) = pay(1)
      (t2, id2) = pay(2)
      first <- mempool.add(t1, Source.Local)
      dup <- mempool.add(t1, Source.Local)
      // as a TxSubmission2 server we collect from a peer; known ids are not fetched
      knownBefore <- mempool.sink(failure => IO(fail(s"unexpected fatal: $failure"))).known(id1)
      knownOther <- mempool.sink(failure => IO(fail(s"unexpected fatal: $failure"))).known(id2)
      _ <- mempool
        .sink(failure => IO(fail(s"unexpected fatal: $failure")))
        .submit(Vector(t2, Bytes(Vector(1, 2))))
      // as a client we offer what we hold, once per peer cursor
      offered <- mempool.source.after(0L, 10)
      later <- mempool.source.after(offered.head.ticket, 10)
      body <- mempool.source.body(id2)
      waiting <- mempool.source.awaitAfter(offered.last.ticket).start
      (t3, id3) = pay(3)
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(20).millis)
      _ <- mempool.add(t3, Source.Local)
      _ <- waiting.joinWithNever
      log <- ev._2.get
    yield
      assertEquals(first, Right(id1))
      assertEquals(dup, Left(Rejection.AlreadyHeld(id1)))
      assert(knownBefore && !knownOther)
      assertEquals(offered.map(_.id), Vector(id1, id2))
      assertEquals(later.map(_.id), Vector(id2))
      assertEquals(body, Some(t2))
      assert(log.exists { case MempoolEvent.Added(`id3`, _, 3, _) => true; case _ => false })
      assert(log.exists {
        case MempoolEvent.Rejected(_, _: Rejection.Malformed) => true; case _ => false
      })
  }

  test(
    "removeTxsEvenIfValid drops a parent's dependant, keeps the rest in order and wakes awaiters"
  ) {
    val (t1, id1) = pay(1)
    val (child, idChild) =
      tx(Vector(TxIn(id1, 0)), Vector(alice.address() -> (98 * ada)), ada, Vector(bob))
    val (t2, id2) = pay(2)
    val (t3, id3) = pay(3)
    for
      ev <- events
      mempool <- Mempool.create[IO](Right(ledger(4)), 1000, env, None, ev._1)
      _ <- List(t1, child, t2, t3).foldLeft(IO.unit)((done, t) =>
        done *> mempool.add(t, Source.Local).void
      )
      before <- mempool.snapshot
      waiting <- mempool.awaitChange(_.entries.size == 4).start
      dependants <- mempool.removeTxsEvenIfValid(Set(id1))
      woken <- waiting.joinWithNever
      after <- mempool.snapshot
      log <- ev._2.get
      reoffered <- mempool.add(t1, Source.Local)
    yield
      assertEquals(dependants.map(_._1.id), Vector(idChild))
      assertEquals(woken.entries.map(_.id), Vector(id2, id3))
      assertEquals(after.entries.map(_.id), Vector(id2, id3))
      assertEquals(
        after.entries.map(_.ticket),
        before.entries.filter(e => Set(id2, id3)(e.id)).map(_.ticket)
      )
      assertEquals(log.collect { case MempoolEvent.Removed(id) => id }, Vector(id1))
      assertEquals(log.collect { case MempoolEvent.Dropped(id, _) => id }, Vector(idChild))
      assertEquals(reoffered, Right(id1))
  }

  test("removeTxsEvenIfValid of ids that are not held changes nothing") {
    val (t1, id1) = pay(1)
    val (_, unknown) = pay(2)
    for
      ev <- events
      mempool <- Mempool.create[IO](Right(ledger(2)), 1000, env, None, ev._1)
      _ <- mempool.add(t1, Source.Local)
      before <- mempool.snapshot
      dependants <- mempool.removeTxsEvenIfValid(Set(unknown))
      after <- mempool.snapshot
      log <- ev._2.get
    yield
      assertEquals(dependants, Vector.empty)
      assertEquals(after, before)
      assertEquals(after.entries.map(_.id), Vector(id1))
      assert(!log.exists(_.isInstanceOf[MempoolEvent.Removed]))
  }

  test("an unavailable add is retried after decoder recovery; revalidation does not decode") {
    val fixture = xyz.kd5ujc.scarab.ledger.PlutusCase
      .load(java.nio.file.Path.of("fixtures/preprod/plutus/tx-2edd684f.json"))
    val script =
      (fixture.tx.witnesses.scripts ++ fixture.references.values.flatMap(_.referenceScript))
        .find(_.kind.isPlutus)
        .getOrElse(fail("fixture has no Plutus script"))
    val code = script.plutus.getOrElse(fail("fixture discarded script bytes"))
    val (raw, id) = tx(
      Vector(txIn(1)),
      Vector(bob.address() -> (99 * ada)),
      ada,
      Vector(alice),
      referenceScript = Some(script.kind -> code)
    )
    val stopped: xyz.kd5ujc.scarab.plutus.ScriptDecoder[cats.Id] = (_, _, _) =>
      Left(
        xyz.kd5ujc.scarab.plutus.ScriptDecodeFailure
          .Unavailable(xyz.kd5ujc.scarab.plutus.BackendFailure.StackExhausted)
      )
    val unavailableEnv = env.copy(decoder = stopped)
    val base = ledger(2)
    for
      context <- Ref.of[IO, MempoolEnv](unavailableEnv)
      ask = new cats.mtl.Ask[IO, MempoolEnv]:
        val applicative = summon[cats.Applicative[IO]]
        def ask[E >: MempoolEnv]: IO[E] = context.get.map(value => value: E)
      mempool <- Mempool
        .create[IO](Right(base), 1000, env, None, MempoolEvents.none[IO], environment = Some(ask))
      first <- mempool.add(raw, Source.Local)
      knownAfterUnavailableAdd <- mempool
        .sink(failure => IO(fail(s"unexpected fatal: $failure")))
        .known(id)
      _ <- context.set(env)
      retried <- mempool.add(raw, Source.Local)
      _ <- context.set(unavailableEnv)
      dropped <- mempool.sync(Right(base), 1000)
      knownAfterUnavailableSync <- mempool
        .sink(failure => IO(fail(s"unexpected fatal: $failure")))
        .known(id)
    yield
      first match
        case Left(Rejection.Ledger(_: LedgerFailure.Unavailable)) => ()
        case other => fail(s"unexpected first validation $other")
      assert(!knownAfterUnavailableAdd)
      assertEquals(retried, Right(id))
      // well-formed scripts are a static check (`Babbage/Rules/Utxow.hs` 379): applied again, the
      // held transaction does not reach the decoder that is unavailable now
      assertEquals(dropped, Vector.empty)
      assert(knownAfterUnavailableSync)
  }

/** The pool reads inputs from the UTxO store, like the block path does. */
class MempoolStoreSuite extends CatsEffectSuite:
  import TxKit.*
  import xyz.kd5ujc.scarab.ledger.StorageError
  import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, UtxoStore}

  private given cats.mtl.Raise[IO, StorageError] = new cats.mtl.Raise[IO, StorageError]:
    def functor = cats.Functor[IO]
    def raise[E2 <: StorageError, A](e: E2): IO[A] = IO(fail(e.message))

  test("add and sync fetch the inputs a ledger state does not hold from the store") {
    val full = ledger(3)
    val bare = full.withUtxo(Utxo.empty)
    for
      store <- MemoryUtxoStore[IO]
      _ <- store.write(full.utxo.toVector.map((i, o) => i -> Some(o)), 0L)
      mempool <- Mempool.create[IO](
        Right(bare),
        1000,
        env,
        None,
        MempoolEvents.none[IO],
        utxoStore = Some(store: UtxoStore[IO])
      )
      (t1, id1) = pay(1)
      added <- mempool.add(t1, Source.Local)
      _ <- IO(assertEquals(added, Right(id1)))
      dropped <- mempool.sync(Right(bare), 1001)
      _ <- IO(assertEquals(dropped, Vector.empty))
      held <- mempool.contains(id1)
      _ <- IO(assert(held))
    yield ()
  }
