// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.legacy as flat
import xyz.kd5ujc.scarab.legacy.{LedgerImage, LedgerMirror}
import xyz.kd5ujc.scarab.legacy.LedgerMirrorBack.*
import cats.data.Kleisli
import cats.syntax.all.*
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Account,
  Address,
  Certificate,
  Credential as NodeCredential,
  Datum,
  GenDelegPair,
  LedgerFailure,
  NativeScript,
  ParamUpdate,
  PoolParams,
  PoolState as NodePoolState,
  Pot,
  Redeemer,
  RewardAccount,
  Script,
  ScriptIntegrity,
  ScriptKind,
  TestLedger,
  TxEnv,
  Tx,
  TxOut,
  Update,
  Value,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.legacy.FlatTestLedger.*

/** The LEDGER rules of Shelley, Allegra, Mary, Alonzo and Babbage on the era-indexed state
  * ([[ShelleyLedger]], one instance over the witness), on one transaction at a time and in every
  * era: the state is a flat fixture, which the mirror turns into the state the rules run on, and
  * what the rules do with the transaction is checked: the rule that refuses it, if it does, as the
  * typed predicate failure with its payload, and the state after it, if it does not (converted
  * back, which checks what the rules maintain that the flat state derives: the accounts and their
  * pointers, the pointer stake, the deposits, the instantaneous rewards, the proposed updates), and
  * the pools' delegators against the accounts. (Until J2.9 the suite ran the old rules beside these
  * and compared the two.)
  */
class ShelleyLedgerSuite extends FunSuite:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]
  private val mirror: LedgerMirror[M] = LedgerMirror[M]
  private val env = FlatStateGen.env
  private val at: WithOrigin[SlotNo] = WithOrigin.At(SlotNo.fromUnsigned(50L))

  private def run[A](program: M[A]): Result[A] = program.run(env)

  private val globals = Globals(
    node.LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    env.geometry
  )

  private val alice = key()
  private val bob = key()
  private val carol = key()
  private val ada = BigInt(1000000)

  /** An era: the protocol version its parameters have, and its witness with the evidence. */
  private final class Case[E <: EraTag](
      val name: String,
      val pv: Int,
      val era: ShelleyBasedEra[E],
      val pre: PreConway[E]
  )

  private val shelley = Case("Shelley", 2, ShelleyBasedEra.SShelley, summon[PreConway[Shelley]])
  private val allegra = Case("Allegra", 3, ShelleyBasedEra.SAllegra, summon[PreConway[Allegra]])
  private val mary = Case("Mary", 4, ShelleyBasedEra.SMary, summon[PreConway[Mary]])
  private val alonzo = Case("Alonzo", 5, ShelleyBasedEra.SAlonzo, summon[PreConway[Alonzo]])
  private val babbage = Case("Babbage", 7, ShelleyBasedEra.SBabbage, summon[PreConway[Babbage]])
  private val eras: Vector[Case[?]] = Vector(shelley, allegra, mary, alonzo, babbage)

  /** What a side did with a transaction. */
  private enum Out:
    case Accepted(state: flat.LedgerState)
    case Refused(failure: LedgerFailure)
    case Broken(why: String)

  /** The new side's ledger state after the transaction, or why it refused it. */
  private def afterOf[E <: EraTag](s: flat.LedgerState, t: Tx, ctx: TxEnv)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Either[LedgerFailure, (LedgerImage[E], Counted[LedgerState[E]])] =
    val image = run(mirror.to[E](at, s)).fold(e => fail(s"to the mirror: ${e.show}"), _.value)
    val nes = image.nes
    Sts(ShelleyLedger.sts[E])(
      globals,
      ShelleyLedgerEnv(ctx, nes.es.chainAccount),
      Counted(nes.es.ls, s.plutus),
      t
    ).map(image -> _)

  private def newSideOf[E <: EraTag](s: flat.LedgerState, t: Tx, ctx: TxEnv)(using
      era: ShelleyBasedEra[E],
      pre: PreConway[E]
  ): Out =
    afterOf[E](s, t, ctx) match
      case Left(f)               => Out.Refused(f)
      case Right((image, after)) =>
        // what the rules maintain that the old state derives must be what it derives
        val delegators = Invariants.poolDelegators(pre.cert.get(after.state))
        if delegators.nonEmpty then Out.Broken(s"delegators: $delegators")
        else
          val nes = image.nes
          val next = image.copy(
            nes = nes.copy(es = nes.es.copy(ls = after.state)),
            extras = image.extras.copy(plutus = after.plutus)
          )
          run(mirror.from[E](at, next)) match
            case Left(e)      => Out.Broken(s"back from the mirror: ${e.show}")
            case Right(state) => Out.Accepted(state)

  private def newSide(c: Case[?], s: flat.LedgerState, t: Tx, ctx: TxEnv): Out = c match
    case k: Case[e] => newSideOf[e](s, t, ctx)(using k.era, k.pre)

  /** The new side's governance state after the transaction. */
  private def govAfter(c: Case[?], s: flat.LedgerState, t: Tx, ctx: TxEnv): ShelleyGovState =
    c match
      case k: Case[e] =>
        afterOf[e](s, t, ctx)(using k.era, k.pre) match
          case Left(f)             => fail(s"${c.name}: ${f.message}")
          case Right((_, counted)) => k.pre.gov.get(counted.state.utxo)

  private def name(f: LedgerFailure): String = f match
    case LedgerFailure.Rejected(_, p) => p.ruleName
    case other                        => other.message

  /** The rules on one transaction: the state after it, or the refusal. */
  private def agree(c: Case[?], s: flat.LedgerState, t: Tx, ctx: TxEnv): Out = newSide(c, s, t, ctx)

  private def accepted(
      c: Case[?],
      s: flat.LedgerState,
      t: Tx,
      ctx: TxEnv = TestLedger.env
  ): flat.LedgerState =
    agree(c, s, t, ctx) match
      case Out.Accepted(next) => next
      case other              => fail(s"${c.name}: expected the transaction to be accepted: $other")

  private def refusedWith(
      c: Case[?],
      s: flat.LedgerState,
      t: Tx,
      ctx: TxEnv = TestLedger.env
  ): String =
    agree(c, s, t, ctx) match
      case Out.Refused(f) => name(f)
      case other          => fail(s"${c.name}: expected a refusal: $other")

  /** The rule that refuses a transaction on one side, which accepts or refuses it. */
  private def refusal(out: Out, c: Case[?]): String = out match
    case Out.Refused(f) => name(f)
    case other          => fail(s"${c.name}: expected a refusal: $other")

  // ---- the states and the transactions

  private val coinIn = txIn(1)

  private def base(
      c: Case[?],
      funds: Vector[(node.TxIn, node.TxOut)],
      accounts: Map[NodeCredential, Account] = Map.empty,
      pools: Map[Bytes, NodePoolState] = Map.empty,
      genDelegs: Map[Bytes, GenDelegPair] = Map.empty,
      ptrs: Map[node.Ptr, NodeCredential] = Map.empty
  ): flat.LedgerState =
    val s = state(c.pv, funds)
    val deposits = accounts.values.map(_.deposit).sum + pools.values.map(_.deposit).sum
    s.copy(
      // the parameters of the eras before Babbage have no cost per UTxO byte
      params = s.params.copy(
        minPoolCost = 0,
        coinsPerUTxOByte = if c.pv >= 7 then s.params.coinsPerUTxOByte else 0
      ),
      accounts = accounts,
      pools = pools,
      genDelegs = genDelegs,
      ptrs = node.PtrMap(ptrs),
      deposited = deposits,
      reserves = s.reserves - deposits,
      epoch = TestLedger.env.epoch
    )

  private val rich = 10000 * ada

  private def fundedBy(k: Key, coin: BigInt = rich, i: Int = 1) =
    Vector(txIn(i) -> out(k.address(), coin))

  /** A transaction of `k` spending `coinIn`, the change back to it. */
  private def pay(
      id: String,
      k: Key,
      funds: BigInt = rich,
      fee: BigInt = 200,
      extra: BigInt = 0,
      certificates: Vector[Certificate] = Vector.empty,
      withdrawals: Vector[(RewardAccount, BigInt)] = Vector.empty,
      update: Option[Update] = None,
      signers: Vector[Key] = Vector.empty,
      ttl: Option[BigInt] = None
  ): Tx =
    val b = body(
      id,
      Vector(coinIn),
      Vector(out(k.address(), funds - fee - extra + withdrawals.map(_._2).sum)),
      fee,
      certificates = certificates,
      withdrawals = withdrawals,
      update = update,
      ttl = ttl
    )
    tx(b, (k +: signers).distinct)

  private def poolParams(operator: Key, cost: BigInt = 0): PoolParams =
    PoolParams(
      operator.hash,
      Blake2b.hash256.hash(operator.hash),
      pledge = 0,
      cost = cost,
      margin = Rational(1, 100),
      rewardAccount = RewardAccount(0, operator.credential),
      owners = Set(operator.hash),
      relays = Bytes.empty,
      metadata = None
    )

  private def poolOf(operator: Key): NodePoolState =
    NodePoolState(poolParams(operator), 500 * ada)

  private def forEras(f: Case[?] => Unit): Unit = eras.foreach(f)

  // ---- UTXOW and UTXO

  test("a payment is accepted in every era: the outputs move and the fee is collected") {
    forEras { c =>
      val s = base(c, fundedBy(alice))
      val next = accepted(c, s, pay("pay", alice))
      assertEquals(next.fees, BigInt(200), c.name)
      assertEquals(next.total, Supply, c.name)
      assert(!next.utxo.contains(coinIn), c.name)
    }
  }

  test(
    "the stake of an output follows its address: the credential of a base address, the pointer of a pointer address"
  ) {
    val pointer = Address
      .decode(Bytes(0x40.toByte +: (alice.hash.value ++ Vector(7, 1, 0).map(_.toByte))))
      .fold(e => fail(e), identity)
    forEras { c =>
      val s = base(c, fundedBy(alice, 100 * ada))
      val t = tx(
        body(
          "stake",
          Vector(coinIn),
          Vector(
            out(bob.baseAddress(carol), 40 * ada),
            out(pointer, 30 * ada),
            out(alice.address(), 30 * ada - 200)
          ),
          200
        ),
        Vector(alice)
      )
      val next = accepted(c, s, t)
      assertEquals(next.utxo.stake.get(carol.credential), Some(40 * ada), c.name)
      assertEquals(next.utxo.ptrStake.get(node.Ptr(7, 1, 0)), Some(30 * ada), c.name)
    }
  }

  test("the transactions that break a rule are refused as the rule is named, in every era") {
    forEras { c =>
      val s = base(c, fundedBy(alice))
      def named(t: Tx) = refusedWith(c, s, t)
      val dearer = pay("unbalanced", alice)
      assertEquals(
        named(
          dearer.copy(body =
            dearer.body.copy(outputs =
              dearer.body.outputs.map(o => o.copy(value = Value.coin(o.value.coin + 1)))
            )
          )
        ),
        "ValueNotConservedUTxO"
      )
      assertEquals(named(pay("cheap", alice, fee = 100)), "FeeTooSmallUTxO")
      assertEquals(
        named(tx(pay("unsigned", alice).body, Vector(bob))),
        "MissingVKeyWitnessesUTXOW"
      )
      assertEquals(
        named(
          tx(
            body("missing", Vector(txIn(9)), Vector(out(alice.address(), ada)), 200),
            Vector(alice)
          )
        ),
        "BadInputsUTxO"
      )
      assertEquals(
        named(
          tx(
            body(
              "tiny",
              Vector(coinIn),
              Vector(out(alice.address(), 100), out(alice.address(), rich - 300)),
              200
            ),
            Vector(alice)
          )
        ),
        "OutputTooSmallUTxO"
      )
      // the slot is 1000: Shelley's time to live is inclusive, Allegra's upper bound exclusive
      if c.pv == 2 then
        accepted(c, s, pay("ttl", alice, ttl = Some(1000)))
        assertEquals(named(pay("late", alice, ttl = Some(999))), "ExpiredUTxO")
      else assertEquals(named(pay("late", alice, ttl = Some(1000))), "OutsideValidityIntervalUTxO")
    }
  }

  // ---- DELEGS: registrations, delegations, pointers

  test("a stake key is registered with its pointer, delegated, then deregistered, in every era") {
    forEras { c =>
      val pool = key()
      val s = base(c, fundedBy(alice), pools = Map(pool.hash -> poolOf(pool)))
      val ctx = TestLedger.env.copy(txIndex = 3)
      val register = pay(
        "reg",
        alice,
        extra = 2 * ada,
        certificates = Vector(
          Certificate.Register(bob.credential, None),
          Certificate.Delegate(bob.credential, Some(pool.hash), None, None)
        ),
        signers = Vector(bob)
      )
      val s1 = accepted(c, s, register, ctx)
      // the pointer is the slot, the position of the transaction in the block and of the certificate
      assertEquals(s1.ptrs.get(node.Ptr(1000, 3, 0)), Some(bob.credential), c.name)
      assertEquals(s1.accounts(bob.credential).pool, Some(pool.hash), c.name)
      assertEquals(s1.deposited, 2 * ada + 500 * ada, c.name)
      val dereg = pay(
        "dereg",
        alice,
        extra = -2 * ada,
        certificates = Vector(Certificate.Unregister(bob.credential, None)),
        signers = Vector(bob)
      )
      val s2 = accepted(c, s1.copy(utxo = s.utxo), dereg)
      assertEquals(s2.accounts.get(bob.credential), None, c.name)
      assertEquals(s2.ptrs.get(node.Ptr(1000, 3, 0)), None, c.name)
      assertEquals(s2.deposited, 500 * ada, c.name)
    }
  }

  test(
    "a registered key cannot be registered again, an unknown one cannot delegate or be deregistered"
  ) {
    forEras { c =>
      val pool = key()
      val s = base(
        c,
        fundedBy(alice),
        accounts = Map(bob.credential -> Account(0, 2 * ada, None, None)),
        pools = Map(pool.hash -> poolOf(pool))
      )
      def certify(cert: Certificate, who: Key, deposit: BigInt = 0) =
        pay("cert", alice, extra = deposit, certificates = Vector(cert), signers = Vector(who))
      assertEquals(
        refusedWith(c, s, certify(Certificate.Register(bob.credential, None), bob, 2 * ada)),
        "StakeKeyAlreadyRegisteredDELEG"
      )
      assertEquals(
        refusedWith(
          c,
          s,
          certify(Certificate.Delegate(carol.credential, Some(pool.hash), None, None), carol)
        ),
        "StakeDelegationImpossibleDELEG"
      )
      assertEquals(
        refusedWith(
          c,
          s,
          certify(Certificate.Delegate(bob.credential, Some(key().hash), None, None), bob)
        ),
        "DelegateeNotRegisteredDELEG"
      )
      val n = newSide(
        c,
        s,
        certify(Certificate.Unregister(carol.credential, None), carol),
        TestLedger.env
      )
      assertEquals(refusal(n, c), "StakeKeyNotRegisteredDELEG", c.name)
    }
  }

  test("a key with a reward balance cannot be deregistered; the refund is the deposit held") {
    forEras { c =>
      val s = base(
        c,
        fundedBy(alice),
        accounts = Map(
          bob.credential -> Account(5, 2 * ada, None, None),
          carol.credential -> Account(0, 3 * ada, None, None)
        )
      )
      assertEquals(
        refusedWith(
          c,
          s,
          pay(
            "dereg",
            alice,
            extra = -2 * ada,
            certificates = Vector(Certificate.Unregister(bob.credential, None)),
            signers = Vector(bob)
          )
        ),
        "StakeKeyNonZeroAccountBalanceDELEG"
      )
      // the reference refunds the deposit the account holds (3 ada), not the parameter's (2 ada)
      val dereg = pay(
        "refund",
        alice,
        extra = -3 * ada,
        certificates = Vector(Certificate.Unregister(carol.credential, None)),
        signers = Vector(carol)
      )
      val n = newSide(c, s, dereg, TestLedger.env)
      n match
        case Out.Accepted(next) => assertEquals(next.accounts.get(carol.credential), None, c.name)
        case other              => fail(s"${c.name}: $other")
    }
  }

  test("withdrawals drain the accounts; a partial one is refused as the reference names it") {
    forEras { c =>
      val s = base(
        c,
        fundedBy(alice),
        accounts = Map(bob.credential -> Account(7 * ada, 2 * ada, None, None))
      )
      val account = RewardAccount(0, bob.credential)
      val full = pay("full", alice, withdrawals = Vector(account -> 7 * ada), signers = Vector(bob))
      val next = accepted(c, s, full)
      assertEquals(next.accounts(bob.credential).balance, BigInt(0), c.name)
      val partial =
        pay("partial", alice, withdrawals = Vector(account -> 3 * ada), signers = Vector(bob))
      val n = newSide(c, s, partial, TestLedger.env)
      assertEquals(refusal(n, c), "ConwayIncompleteWithdrawals", c.name)
      val unknown = pay(
        "unknown",
        alice,
        withdrawals = Vector(RewardAccount(0, carol.credential) -> 1),
        signers = Vector(carol)
      )
      val missing = newSide(c, s, unknown, TestLedger.env)
      assertEquals(refusal(missing, c), "ConwayWithdrawalsMissingAccounts", c.name)
    }
  }

  // ---- POOL

  test("a pool is registered, re-registered and retired in every era") {
    forEras { c =>
      val op = key()
      val s = base(c, fundedBy(alice), pools = Map(op.hash -> poolOf(op)))
      val fresh = key()
      val registered = accepted(
        c,
        s,
        pay(
          "new",
          alice,
          extra = 500 * ada,
          certificates = Vector(Certificate.RegisterPool(poolParams(fresh))),
          signers = Vector(fresh)
        )
      )
      assert(registered.pools.contains(fresh.hash), c.name)
      assertEquals(registered.deposited, 1000 * ada, c.name)
      // a re-registration is the future parameters, and pays no deposit
      val again = accepted(
        c,
        s,
        pay(
          "again",
          alice,
          certificates = Vector(Certificate.RegisterPool(poolParams(op, cost = 340))),
          signers = Vector(op)
        )
      )
      assertEquals(again.futurePools.get(op.hash).map(_.cost), Some(BigInt(340)), c.name)
      val retiring = accepted(
        c,
        s,
        pay(
          "retire",
          alice,
          certificates = Vector(Certificate.RetirePool(op.hash, 7)),
          signers = Vector(op)
        )
      )
      assertEquals(retiring.retiring.get(op.hash), Some(BigInt(7)), c.name)
      assertEquals(
        refusedWith(
          c,
          s,
          pay(
            "far",
            alice,
            certificates = Vector(Certificate.RetirePool(op.hash, 100)),
            signers = Vector(op)
          )
        ),
        "StakePoolRetirementWrongEpochPOOL"
      )
      assertEquals(
        refusedWith(
          c,
          s,
          pay(
            "none",
            alice,
            certificates = Vector(Certificate.RetirePool(fresh.hash, 7)),
            signers = Vector(fresh)
          )
        ),
        "StakePoolNotRegisteredOnKeyPOOL"
      )
    }
  }

  // ---- MIR and genesis delegation

  private val genesisKeys = Vector(key(), key())
  private val delegates = Vector(key(), key())

  private def genDelegs: Map[Bytes, GenDelegPair] =
    genesisKeys
      .zip(delegates)
      .zipWithIndex
      .map { case ((g, d), i) => g.hash -> GenDelegPair(d.hash, Bytes(Vector.fill(32)(i.toByte))) }
      .toMap

  private def withGenesis(c: Case[?]): flat.LedgerState =
    base(
      c,
      fundedBy(alice),
      accounts = Map(bob.credential -> Account(0, 2 * ada, None, None)),
      genDelegs = genDelegs
    )

  test("MIR needs the genesis quorum's signatures and moves coin at the next boundary") {
    forEras { c =>
      val s = withGenesis(c)
      val cert = Certificate.MoveRewards(Pot.Reserves, Right(Map(bob.credential -> 7 * ada)))
      val signed = pay("mir", alice, certificates = Vector(cert), signers = delegates)
      val next = accepted(c, s, signed)
      assertEquals(next.mir.reserves(bob.credential), 7 * ada, c.name)
      // one signature of two: UTXOW refuses it
      val partial = pay("mir", alice, certificates = Vector(cert), signers = delegates.take(1))
      val n = newSide(c, s, partial, TestLedger.env)
      assertEquals(refusal(n, c), "MIRInsufficientGenesisSigsUTXOW", c.name)
      // too late in the epoch: the slot is within 3k/f (100) of the end of the epoch (5000)
      assertEquals(
        refusedWith(c, s, signed, TestLedger.env.copy(slot = 4950)),
        "MIRCertificateTooLateinEpochDELEG"
      )
    }
  }

  test("MIR: transfers between pots and negative entries need protocol 5 (Alonzo)") {
    forEras { c =>
      val s = withGenesis(c)
      def mir(cert: Certificate) =
        pay("mir", alice, certificates = Vector(cert), signers = delegates)
      val transfer = mir(Certificate.MoveRewards(Pot.Reserves, Left(3 * ada)))
      val negative = mir(Certificate.MoveRewards(Pot.Treasury, Right(Map(bob.credential -> -1))))
      if c.pv >= 5 then
        val next = accepted(c, s, transfer)
        assertEquals(next.mir.deltaTreasury, 3 * ada, c.name)
        assertEquals(next.mir.deltaReserves, -3 * ada, c.name)
        assertEquals(refusedWith(c, s, negative), "MIRProducesNegativeUpdate")
      else
        assertEquals(refusedWith(c, s, transfer), "MIRTransferNotCurrentlyAllowed")
        assertEquals(refusedWith(c, s, negative), "MIRNegativesNotCurrentlyAllowed")
    }
  }

  test("MIR: a later certificate replaces an earlier one before Alonzo and adds to it after") {
    forEras { c =>
      val s = withGenesis(c)
      val twice = pay(
        "twice",
        alice,
        certificates = Vector(
          Certificate.MoveRewards(Pot.Reserves, Right(Map(bob.credential -> 3 * ada))),
          Certificate.MoveRewards(Pot.Reserves, Right(Map(bob.credential -> 4 * ada)))
        ),
        signers = delegates
      )
      val next = accepted(c, s, twice)
      assertEquals(
        next.mir.reserves(bob.credential),
        if c.pv >= 5 then 7 * ada else 4 * ada,
        c.name
      )
    }
  }

  test(
    "a genesis delegation is scheduled; the reference refuses a delegate or VRF key already used"
  ) {
    forEras { c =>
      val s = withGenesis(c)
      val delegate = key()
      val vrf = Bytes(Vector.fill(32)(0x55))
      def delegation(g: Key, d: Bytes, v: Bytes) =
        pay(
          "gen",
          alice,
          certificates = Vector(Certificate.GenesisDelegate(g.hash, d, v)),
          signers = Vector(g)
        )
      val next = accepted(c, s, delegation(genesisKeys(0), delegate.hash, vrf))
      assertEquals(
        next.futureGenDelegs.map(d => (d.slot, d.genesisKey)),
        Vector((BigInt(1100), genesisKeys(0).hash)),
        c.name
      )
      assertEquals(
        refusedWith(c, s, delegation(carol, delegate.hash, vrf)),
        "GenesisKeyNotInMappingDELEG"
      )
      // the other genesis key's delegate
      val dup = delegation(genesisKeys(0), delegates(1).hash, vrf)
      val n = newSide(c, s, dup, TestLedger.env)
      assertEquals(refusal(n, c), "DuplicateGenesisDelegateDELEG", c.name)
      val dupVrf = delegation(genesisKeys(0), delegate.hash, Bytes(Vector.fill(32)(1.toByte)))
      val n2 = newSide(c, s, dupVrf, TestLedger.env)
      assertEquals(refusal(n2, c), "DuplicateGenesisVRFDELEG", c.name)
    }
  }

  // ---- PPUP

  private val newDeposit =
    ParamUpdate(Map(5 -> Bytes(Vector(0x1a, 0x00, 0x2d, 0xc6, 0xc0).map(_.toByte))))

  test(
    "update proposals: this epoch's make the prediction, next epoch's wait; wrong ones are refused"
  ) {
    forEras { c =>
      val s = withGenesis(c)
      def propose(from: Vector[Int], epoch: BigInt = 5, update: ParamUpdate = newDeposit) =
        pay(
          "up",
          alice,
          update = Some(Update(from.map(i => genesisKeys(i).hash -> update).toMap, epoch)),
          signers = from.map(delegates)
        )
      val both2 = propose(Vector(0, 1))
      val next = accepted(c, s, both2)
      assertEquals(next.ppup.current.keySet, genesisKeys.map(_.hash).toSet, c.name)
      // two of two voted for the same update: the prediction is the parameters it makes
      govAfter(c, s, both2, TestLedger.env).futurePParams match
        case FuturePParams.PotentialPParamsUpdate(Some(p)) =>
          assertEquals(p.keyDeposit, BigInt(3000000), c.name)
        case other => fail(s"${c.name}: $other")
      govAfter(c, s, propose(Vector(0)), TestLedger.env).futurePParams match
        case FuturePParams.PotentialPParamsUpdate(None) => ()
        case other                                      => fail(s"${c.name}: $other")
      // after the point of no return (4800) a proposal is for the next epoch
      val late = TestLedger.env.copy(slot = 4900)
      val upcoming = accepted(c, s, propose(Vector(0), epoch = 6), late)
      assertEquals(upcoming.ppup.future.keySet, Set(genesisKeys(0).hash), c.name)
      assertEquals(upcoming.ppup.current, Map.empty[Bytes, ParamUpdate], c.name)
      assertEquals(refusedWith(c, s, propose(Vector(0), epoch = 9)), "PPUpdateWrongEpoch")
      assertEquals(refusedWith(c, s, propose(Vector(0), epoch = 5), late), "PPUpdateWrongEpoch")
      // a protocol version that cannot follow: (9, 0) from a version below 8
      val version = ParamUpdate(Map(14 -> Bytes(Vector(0x82, 0x09, 0x00).map(_.toByte))))
      assertEquals(refusedWith(c, s, propose(Vector(0), update = version)), "PVCannotFollowPPUP")
      // not a genesis delegate's proposal
      val stranger = pay(
        "up",
        alice,
        update = Some(Update(Map(carol.hash -> newDeposit), 5)),
        signers = Vector.empty
      )
      assertEquals(refusedWith(c, s, stranger), "NonGenesisUpdatePPUP")
      // the delegate must sign
      assertEquals(
        refusedWith(
          c,
          s,
          pay("up", alice, update = Some(Update(Map(genesisKeys(0).hash -> newDeposit), 5)))
        ),
        "MissingVKeyWitnessesUTXOW"
      )
    }
  }

  // ---- Mary: native scripts and multi-asset values

  private def scriptOf(s: NativeScript): Script =
    def head(major: Int, n: Int): Vector[Byte] = Vector(((major << 5) | n).toByte)
    def go(s: NativeScript): Vector[Byte] = s match
      case NativeScript.Sig(h) =>
        head(4, 2) ++ head(0, 0) ++ Vector(0x58, 28).map(_.toByte) ++ h.value
      case NativeScript.AllOf(xs) => head(4, 2) ++ head(0, 1) ++ head(4, xs.size) ++ xs.flatMap(go)
      case other                  => fail(s"not encoded in this suite: $other")
    xyz.kd5ujc.scarab.codec.Cbor
      .decode(Bytes(go(s)))
      .flatMap(Script.native)
      .fold(e => fail(e), identity)

  test("from Mary a native script mints: it must be provided, hold and be needed") {
    val signer = key()
    val policyScript = scriptOf(NativeScript.AllOf(Vector(NativeScript.Sig(signer.hash))))
    val policy = policyScript.hash
    val asset = Bytes("coin".getBytes.toVector)
    val minted = TxOut(bob.address(), Value(2 * ada, Map(policy -> Map(asset -> BigInt(100)))))
    val b = body(
      "mint",
      Vector(coinIn),
      Vector(minted, out(alice.address(), rich - 2 * ada - 200)),
      200,
      mint = Map(policy -> Map(asset -> BigInt(100)))
    )
    val provided = Witnesses(Vector.empty, scripts = Vector(policyScript))
    val extra = Witnesses(
      Vector.empty,
      scripts = Vector(policyScript, scriptOf(NativeScript.AllOf(Vector.empty)))
    )
    List(mary, alonzo, babbage).foreach { c =>
      val s = base(c, fundedBy(alice))
      val next = accepted(c, s, tx(b, Vector(alice, signer), provided))
      assertEquals(next.total, Supply, c.name)
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice, signer))),
        "MissingScriptWitnessesUTXOW",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), provided)),
        "ScriptWitnessNotValidatingUTXOW",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice, signer), extra)),
        "ExtraneousScriptWitnessesUTXOW",
        c.name
      )
    }
  }

  // ---- Alonzo and Babbage: Plutus scripts, collateral, reference inputs

  private def plutusCase(c: Case[?]) =
    // a script that deserialises, as Babbage requires
    val plutus = Script.plutus(
      ScriptKind.PlutusV1,
      Bytes.fromArray(
        scalus.uplc
          .Program((1, 0, 0), scalus.uplc.Term.Builtin(scalus.uplc.DefaultFun.AddInteger))
          .cborEncoded
      )
    )
    val scriptAddress =
      Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e), identity)
    val datum = Bytes(Vector(0x01))
    val locked = TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val s = base(c, Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked))
    val units = ExUnits(1000, 1000000)
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      redeemersRaw = Some(
        Bytes(
          Vector(0x81, 0x84, 0x00, 0x00, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42, 0x40)
            .map(_.toByte)
        )
      ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (s.params.priceMem * Rational(1000) +
      s.params.priceSteps * Rational(1000000)).ceil
    def bodyWith(hash: Option[Bytes], returned: Option[TxOut]) =
      body(
        "plutus",
        Vector(lockedIn),
        Vector(out(bob.address(), 50 * ada - fee)),
        fee,
        collateral = Vector(collateralIn),
        scriptDataHash = hash,
        collateralReturn = returned
      )
    val hash = ScriptIntegrity.hash(
      tx(bodyWith(None, None), Vector(alice), wits),
      s.params,
      Set(ScriptKind.PlutusV1)
    )
    (s, wits, hash, bodyWith, collateralIn, lockedIn, fee)

  test(
    "Plutus: the verdict of the scripts must be the block's flag; a failed transaction collects its collateral"
  ) {
    List(alonzo, babbage).foreach { c =>
      val (s, wits, hash, bodyWith, collateralIn, lockedIn, _) = plutusCase(c)
      val b = bodyWith(hash, None)
      def ctx(succeeds: Boolean) = TestLedger.env.copy(evaluator = scripted(succeeds))
      val valid = accepted(c, s, tx(b, Vector(alice), wits), ctx(true))
      assertEquals((valid.plutus.succeeded, valid.plutus.failed), (1L, 0L), c.name)
      assert(valid.utxo.contains(collateralIn) && !valid.utxo.contains(lockedIn), c.name)
      val failed = accepted(c, s, tx(b, Vector(alice), wits, isValid = false), ctx(false))
      assertEquals((failed.plutus.succeeded, failed.plutus.failed), (0L, 1L), c.name)
      assert(!failed.utxo.contains(collateralIn) && failed.utxo.contains(lockedIn), c.name)
      assertEquals(failed.fees, 100 * ada, c.name)
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), wits, isValid = true), ctx(false)),
        "ValidationTagMismatch",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), wits, isValid = false), ctx(true)),
        "ValidationTagMismatch",
        c.name
      )
      assertEquals(
        refusedWith(
          c,
          s,
          tx(bodyWith(Some(Bytes(Vector.fill(32)(9))), None), Vector(alice), wits),
          ctx(true)
        ),
        "PPViewHashesDontMatch",
        c.name
      )
      assertEquals(
        refusedWith(c, s, tx(b, Vector(alice), wits.copy(redeemers = Vector.empty)), ctx(true)),
        "MissingRedeemers",
        c.name
      )
    }
  }

  test("Babbage: the collateral return comes back when a transaction fails phase 2") {
    val (s, wits, hash, bodyWith, collateralIn, _, _) = plutusCase(babbage)
    val returned = out(alice.address(), 99 * ada)
    val b = bodyWith(hash, Some(returned))
    val failed = accepted(
      babbage,
      s,
      tx(b, Vector(alice), wits, isValid = false),
      TestLedger.env.copy(evaluator = scripted(false))
    )
    assertEquals(failed.fees, ada, babbage.name)
    assert(!failed.utxo.contains(collateralIn))
    assertEquals(failed.utxo.get(node.TxIn(b.id, 1)), Some(returned))
    assertEquals(failed.utxo.stake.get(alice.credential), None)
  }

  test("Babbage: a reference input is read and not spent") {
    val referenced = txIn(7)
    val s = base(babbage, fundedBy(alice) ++ Vector(referenced -> out(bob.address(), 5 * ada)))
    val t = tx(
      body(
        "ref",
        Vector(coinIn),
        Vector(out(alice.address(), rich - 200)),
        200,
        referenceInputs = Vector(referenced)
      ),
      Vector(alice)
    )
    val next = accepted(babbage, s, t)
    assert(next.utxo.contains(referenced))
    val missing = tx(
      body(
        "ref",
        Vector(coinIn),
        Vector(out(alice.address(), rich - 200)),
        200,
        referenceInputs = Vector(txIn(8))
      ),
      Vector(alice)
    )
    assertEquals(refusedWith(babbage, s, missing), "BadInputsUTxO")
  }

  test("the era-indexed rules and the reference stack agree: RuleT and Kleisli over StateT") {
    import cats.Monad
    import cats.data.StateT
    type R[A] = Either[LedgerFailure, A]
    type S[A] = StateT[R, Counted[LedgerState[Babbage]], A]
    type K[A] = Kleisli[S, Globals, A]
    given Monad[K] = Kleisli.catsDataMonadForKleisli[S, Globals]
    val pool = key()
    val s = base(babbage, fundedBy(alice), pools = Map(pool.hash -> poolOf(pool)))
    val image = run(mirror.to[Babbage](at, s)).fold(e => fail(e.show), _.value)
    val start = Counted(image.nes.es.ls, s.plutus)
    val ledgerEnv = ShelleyLedgerEnv(TestLedger.env, image.nes.es.chainAccount)
    val delegate = pay(
      "delegate",
      alice,
      extra = 2 * ada,
      certificates = Vector(
        Certificate.Register(bob.credential, None),
        Certificate.Delegate(bob.credential, Some(pool.hash), None, None)
      ),
      signers = Vector(bob)
    )
    for t <- Vector(delegate, pay("cheap", alice, fee = 1)) do
      val rules = ShelleyLedger.sts[Babbage]
      val onRuleT = Sts(rules)(globals, ledgerEnv, start, t)
      val onStack = rules
        .transition[K](ledgerEnv, t)(using summon, summon, summon, summon)
        .run(globals)
        .run(start)
        .map(_._1)
      assertEquals(onRuleT, onStack)
  }

  // ---- width: ADR 0011

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.getOrElse(fail("the thread did not finish")).fold(throw _, identity)

  test("a transaction of thousands of certificates and withdrawals runs on a 256 KiB stack") {
    val pool = key()
    val width = 1500
    val keys = Vector.fill(width)(key())
    forEras { c =>
      val s = base(
        c,
        fundedBy(alice),
        pools = Map(pool.hash -> poolOf(pool)),
        accounts = keys.map(k => k.credential -> Account(1, 2 * ada, None, None)).toMap
      )
      val others = Vector.fill(width)(key())
      val certificates =
        others.map(k => Certificate.Register(k.credential, None)) ++
          others.map(k => Certificate.Delegate(k.credential, Some(pool.hash), None, None))
      val t = pay(
        "wide",
        alice,
        extra = 2 * ada * width,
        certificates = certificates,
        withdrawals = keys.map(k => RewardAccount(0, k.credential) -> BigInt(1)),
        signers = keys ++ others
      )
      onSmallStack(newSide(c, s, t, TestLedger.env)) match
        case Out.Accepted(b) =>
          assertEquals(b.accounts.size, 2 * width, c.name)
          assertEquals(b.ptrs.size, width, c.name)
        case other => fail(s"${c.name}: expected the wide transaction to be accepted: $other")
    }
  }
