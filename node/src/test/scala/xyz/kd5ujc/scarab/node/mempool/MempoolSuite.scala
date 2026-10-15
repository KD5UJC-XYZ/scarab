// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import java.time.Instant
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.TxSubmissionClient
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import TestLedger.*

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
        securityParam = 5,
        activeSlotsCoeff = 0.5
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
      votes: Vector[Byte] = Vector.empty
  ): (Bytes, Bytes) =
    val ins =
      head(4, inputs.size) ++ inputs.flatMap(i => head(4, 2) ++ bstr(i.id) ++ head(0, i.index))
    val outs =
      head(4, outputs.size) ++ outputs.flatMap((a, c) => head(4, 2) ++ bstr(a.raw) ++ head(0, c))
    val body = Bytes(
      head(5, if votes.isEmpty then 3 else 4) ++ Vector(0x00.toByte) ++ ins ++ Vector(
        0x01.toByte
      ) ++ outs ++ Vector(0x02.toByte) ++ head(0, fee) ++
        (if votes.isEmpty then Vector.empty else Vector(0x13.toByte) ++ votes)
    )
    val id = Blake2b.hash256.hash(body)
    val vkeys = head(4, signers.size) ++ signers.flatMap { k =>
      head(4, 2) ++ bstr(k.vkey) ++ bstr(k.sign(id))
    }
    val witnesses = head(5, 1) ++ Vector(0x00.toByte) ++ vkeys
    (
      Bytes(
        head(4, 4) ++ body.value ++ witnesses ++ Vector(
          if valid then 0xf5.toByte else 0xf4.toByte,
          0xf6.toByte
        )
      ),
      id
    )

  /** The ledger with `n` outputs of `value` for alice, at txIn(1..n). */
  def ledger(n: Int, value: BigInt = 100 * ada): LedgerState =
    state(10, (1 to n).toVector.map(i => txIn(i) -> out(alice.address(), value)))

  /** Alice pays `value - fee` to bob from `txIn(i)`. */
  def pay(i: Int, fee: BigInt = ada, value: BigInt = 100 * ada): (Bytes, Bytes) =
    tx(Vector(txIn(i)), Vector(bob.address() -> (value - fee)), fee, Vector(alice))

class MempoolSuite extends CatsEffectSuite with ScalaCheckSuite:
  import TxKit.*

  private def pool(ledger: LedgerState, capacity: Long = 100000) =
    MempoolState.empty(ledger, 1000, capacity)

  private def add(s: MempoolState, raw: Bytes): Either[Rejection, MempoolState] =
    s.add(raw, env).map(_._1)

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
      LocalSubmission.reasonItem(spent).hex,
      "8182068182077844416c6c20696e7075747320617265207370656e742e205472616e73616374696f6e206861732070726f6261626c7920616c7265616479206265656e20696e636c75646564"
    )
    // an unspent input and a fee below the minimum: FeeTooSmallUTxO, the minimum first
    val (cheap, _) = pay(1, fee = 1)
    val refused = add(s0, cheap).left.getOrElse(fail("accepted"))
    val reason = LocalSubmission.reasonItem(refused).hex
    assert(reason.startsWith("81820681820182008305"), reason)
    // a transaction already held is, for the ledger, one whose inputs are spent
    val (t1, _) = pay(1)
    val s1 = add(s0, t1).fold(r => fail(r.message), identity)
    assertEquals(
      LocalSubmission.reasonItem(add(s1, t1).left.getOrElse(fail("accepted"))).hex,
      LocalSubmission.reasonItem(spent).hex
    )
    // a transaction of another era is the combinator's era mismatch
    assertEquals(
      LocalSubmission.reasonItem(Rejection.WrongEra(5)).hex,
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
      add(pool(l.copy(params = l.params.copy(protocolMajor = protocol))), raw).left
        .map {
          case Rejection.Ledger(LedgerFailure.Invalid(`id`, rule, _)) => rule
          case other                                                  => other.message
        }
        .map(_ => "accepted")
    assertEquals(attempt(10), Left("ConwayMempoolFailure"))
    assertEquals(attempt(11), Left("UnelectedCommitteeVoters"))
  }

  test("capacity is counted in bytes and refuses what does not fit") {
    val (t1, _) = pay(1)
    val (t2, _) = pay(2)
    val s0 = pool(ledger(3), capacity = t1.size.toLong + t2.size - 1)
    val s1 = add(s0, t1).fold(r => fail(r.message), identity)
    assertEquals(s1.bytes, t1.size.toLong)
    assertEquals(add(s1, t2), Left(Rejection.Full(t2.size, s1.free)))
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
      TxRules(l, tx, env.txEnv(1000, l, 0)).fold(f => fail(f.message), identity)
    }
    val (synced, dropped) = s.sync(l1, 1001, env)
    assertEquals(dropped.map(_._1.id).toSet, Set(id1, id2))
    assertEquals(synced.entries.map(_.id), Vector(idChild))
    assertEquals(synced.bytes, child.size.toLong)
    // a rollback of that block: the child no longer has its parent and is dropped, nothing is added back
    val (back, droppedAgain) = synced.sync(l0, 1000, env)
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

  private def replay(s: MempoolState): Either[String, LedgerState] =
    s.entries.zipWithIndex.foldLeft[Either[String, LedgerState]](Right(s.base)) {
      case (Right(l), (e, i)) =>
        TxRules(l, e.tx, env.txEnv(s.tipSlot, s.base, i)).left.map(_.message)
      case (left, _) => left
    }

  property("held transactions always apply in order to the tip state; accounting stays exact") {
    forAll(opGen) { script =>
      val l0 = ledger(6)
      var s = pool(l0, capacity = 3000)
      var history = List.empty[LedgerState]
      script.foreach {
        case Op.Add(i) =>
          s.add(candidates(i)._1, env).foreach { case (next, _) => s = next }
        case Op.Remove(i) =>
          s = s.remove(Set(candidates(i)._2), env)._1
        case Op.Block(n) =>
          val adopted = s.entries.take(n)
          val l1 = adopted.foldLeft(s.base)((l, e) =>
            TxRules(l, e.tx, env.txEnv(s.tipSlot, s.base, 0)).toOption.get
          )
          history = s.base :: history
          s = s.sync(l1, s.tipSlot + 1, env)._1
        case Op.Rollback =>
          history match
            case previous :: rest =>
              history = rest
              s = s.sync(previous, s.tipSlot - 1, env)._1
            case Nil => ()
      }
      assertEquals(replay(s).map(_.utxo), Right(s.current.utxo))
      assertEquals(s.entries.map(_.id).distinct.size, s.entries.size)
      assertEquals(s.bytes, s.entries.map(_.size.toLong).sum)
      assert(s.bytes <= s.capacityBytes)
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
      mempool <- Mempool.create[IO](ledger(4), 1000, env, 100000, ev._1)
      (t1, id1) = pay(1)
      (t2, id2) = pay(2)
      first <- mempool.add(t1)
      dup <- mempool.add(t1)
      // as a TxSubmission2 server we collect from a peer; known ids are not fetched
      knownBefore <- mempool.sink.known(id1)
      knownOther <- mempool.sink.known(id2)
      _ <- mempool.sink.submit(Vector(t2, Bytes(Vector(1, 2))))
      // as a client we offer what we hold, once per peer cursor
      offered <- mempool.source.after(0L, 10)
      later <- mempool.source.after(offered.head.ticket, 10)
      body <- mempool.source.body(id2)
      waiting <- mempool.source.awaitAfter(offered.last.ticket).start
      (t3, id3) = pay(3)
      _ <- IO.sleep(scala.concurrent.duration.DurationInt(20).millis)
      _ <- mempool.add(t3)
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

/** The pool reads inputs from the UTxO store, like the block path does. */
class MempoolStoreSuite extends CatsEffectSuite:
  import TxKit.*
  import xyz.kd5ujc.scarab.ledger.StorageError
  import xyz.kd5ujc.scarab.storage.{MemoryUtxoStore, UtxoStore}

  private given cats.mtl.Raise[IO, StorageError] = new cats.mtl.Raise[IO, StorageError]:
    def functor = cats.Functor[IO]
    def raise[E2 <: StorageError, A](e: E2): IO[A] = IO.raiseError(new RuntimeException(e.message))

  test("add and sync fetch the inputs a ledger state does not hold from the store") {
    val full = ledger(3)
    val bare = full.copy(utxo = Utxo.empty)
    for
      store <- MemoryUtxoStore[IO]
      _ <- store.write(full.utxo.toVector.map((i, o) => i -> Some(o)), 0L)
      mempool <- Mempool.create[IO](
        bare,
        1000,
        env,
        100000,
        MempoolEvents.none[IO],
        utxoStore = Some(store: UtxoStore[IO])
      )
      (t1, id1) = pay(1)
      added <- mempool.add(t1)
      _ <- IO(assertEquals(added, Right(id1)))
      dropped <- mempool.sync(bare, 1001)
      _ <- IO(assertEquals(dropped, Vector.empty))
      held <- mempool.contains(id1)
      _ <- IO(assert(held))
    yield ()
  }
