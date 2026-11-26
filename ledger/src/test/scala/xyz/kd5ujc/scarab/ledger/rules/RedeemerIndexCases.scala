// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.Assertions
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{
  EraTag,
  KeyHash,
  NewEpochState,
  ShelleyBasedEra,
  Sorted,
  StakePool,
  Staking,
  VocabError
}
import xyz.kd5ujc.scarab.plutus.{ExUnits, UplcScripts}

/** The transactions of the redeemer-index cases and the verdict the node's pool gives them
  * ([[CertificateRedeemerIndexSuite]]): one Plutus script locks an output, is a registered stake
  * credential (a pool is registered to delegate it to) and is a minting policy, so a transaction
  * can need a redeemer for each purpose of the eras before Conway.
  */
object RedeemerIndexCases extends Assertions:
  val alice = TestLedger.key()
  val ada = BigInt(1000000)
  val script =
    Script.plutus(
      ScriptKind.PlutusV1,
      UplcScripts.compile("(program 1.0.0 (builtin addInteger))")
    )
  val credential = Credential.Script(script.hash)
  val account = RewardAccount(0, credential)
  val poolId = Bytes(Vector.fill(28)(0x33.toByte))
  val otherPoolId = Bytes(Vector.fill(28)(0x44.toByte))
  val delegation = Certificate.Delegate(credential, Some(poolId), None, None)
  val otherDelegation = Certificate.Delegate(credential, Some(otherPoolId), None, None)
  val funding = TestLedger.txIn(1)
  val collateral = TestLedger.txIn(2)
  val locked = TestLedger.txIn(3)
  val fee = 2 * ada
  val units = ExUnits(1000, 1000000)
  val ctx = TestLedger.env.copy(evaluator = TestLedger.scripted(succeeds = true))

  /** A datum (the integer 1), its hash and its witness-set list `[1]`. */
  val datum = Bytes(Vector(0x01.toByte))
  val datumHash = Blake2b.hash256.hash(datum)
  val datums = Bytes(Vector(0x81, 0x01).map(_.toByte))

  val scriptAddress =
    Address.decode(Bytes(0x70.toByte +: script.hash.value)).fold(e => fail(e.toString), identity)
  val lockedOut =
    TxOut(scriptAddress, Value.coin(50 * ada)).copy(datum = Datum.Hash(datumHash))

  private def ok[A](v: Either[VocabError, A]): A = v.fold(e => fail(e.toString), identity)

  /** The era's chain at protocol version `major`: the funding, collateral and script-locked
    * outputs, both pools registered and the script's stake credential registered with nothing in
    * its account.
    */
  def state[E <: EraTag](major: Int)(using ShelleyBasedEra[E]): NewEpochState[E] =
    chain[E](
      TestLedger.params(major),
      Vector(
        funding -> TestLedger.out(alice.address(), 1000 * ada),
        collateral -> TestLedger.out(alice.address(), 100 * ada),
        locked -> lockedOut
      ),
      credential,
      Vector(poolId, otherPoolId)
    )

  /** The era's chain under the parameters `pp`: `funds` in its UTxO set, the pools `pools`
    * registered and the stake credential `staked` registered with nothing in its account.
    */
  def chain[E <: EraTag](
      pp: ProtocolParams,
      funds: Vector[(TxIn, TxOut)],
      staked: Credential,
      pools: Vector[Bytes]
  )(using ShelleyBasedEra[E]): NewEpochState[E] =
    val nes = NativeRules.stateUnder[E](pp, funds)
    val staking = ok(Convert.credential[Staking](staked))
    val registered =
      pools.map(id =>
        ok(KeyHash.fromBytes[StakePool](id)) -> NativeRewardFixture.stakePool(staking)
      )
    val ls = nes.es.ls
    val stakePools = EraLedger.pState(ls).copy(stakePools = Sorted.map(registered))
    NativeRewardFixture.registered(
      nes.copy(es = nes.es.copy(ls = EraLedger.withPState(ls, stakePools))),
      Vector(staking)
    )

  /** The witness-set list of `redeemers`: `[[tag, index, 1, [1000, 1000000]], ...]`. */
  def listed(redeemers: Vector[Redeemer]): Bytes =
    Bytes(
      (0x80 + redeemers.size).toByte +: redeemers.flatMap(r =>
        Vector(
          0x84,
          r.tag,
          r.index.toInt,
          0x01,
          0x82,
          0x19,
          0x03,
          0xe8,
          0x1a,
          0x00,
          0x0f,
          0x42,
          0x40
        ).map(_.toByte)
      )
    )

  /** A transaction signed by alice with the script among its witnesses, the given redeemers and
    * datums, and the script integrity hash of the era `E` over them.
    */
  def transaction[E <: EraTag](
      pp: ProtocolParams,
      body: Option[Bytes] => TxBody,
      redeemers: Vector[Redeemer],
      withDatum: Boolean
  )(using ShelleyBasedEra[E]): Tx =
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(script),
      datums = if withDatum then Vector(datum) else Vector.empty,
      redeemers = redeemers,
      redeemersRaw = Option.when(redeemers.nonEmpty)(listed(redeemers)),
      datumsRaw = Option.when(withDatum)(datums)
    )
    val hash =
      ScriptIntegrity.hash[E](TestLedger.tx(body(None), Vector(alice), wits), pp, Set(script.kind))
    TestLedger.tx(body(hash), Vector(alice), wits)

  def certifying(index: Int): Redeemer =
    Redeemer(2, index, Bytes(Vector(0x01.toByte)), units)

  /** Two identical delegations of the script's credential, with a redeemer at each of `indices`.
    */
  def twice[E <: EraTag](pp: ProtocolParams, indices: Vector[Int])(using
      ShelleyBasedEra[E]
  ): Tx = delegating[E](pp, Vector(delegation, delegation), indices)

  /** Two delegations of the script's credential to different pools, with a redeemer at each of
    * `indices`.
    */
  def distinct[E <: EraTag](pp: ProtocolParams, indices: Vector[Int])(using
      ShelleyBasedEra[E]
  ): Tx = delegating[E](pp, Vector(delegation, otherDelegation), indices)

  private def delegating[E <: EraTag](
      pp: ProtocolParams,
      certificates: Vector[Certificate],
      indices: Vector[Int]
  )(using ShelleyBasedEra[E]): Tx =
    transaction[E](
      pp,
      hash =>
        TestLedger.body(
          "delegations",
          Vector(funding),
          Vector(TestLedger.out(alice.address(), 1000 * ada - fee)),
          fee,
          certificates = certificates,
          collateral = Vector(collateral),
          scriptDataHash = hash
        ),
      indices.map(certifying),
      withDatum = false
    )

  /** The verdict on the transaction `of` builds from the parameters in force, at version `major`,
    * with every predicate failure in the reference's order, as the node's pool judges a submitted
    * transaction ([[judged]]). The refusal, or the number of scripts evaluated.
    */
  def verdict[E <: EraTag](major: Int)(of: ProtocolParams => Tx)(using
      ShelleyBasedEra[E]
  ): Either[Vector[PredicateFailure], Long] =
    val nes = state[E](major)
    judged[E](nes, ctx)(of(EraLedger.curPParams(nes.es.ls.utxo.gov)))

  /** The verdict on `tx` in the chain `nes` of an era before Conway with the rules' context
    * `context`, as the node's pool judges a submitted transaction: the era's LEDGER, every
    * predicate failure in the reference's order. The refusal, or the number of scripts evaluated.
    * Conway's cases run its era rules (`RedeemerIndexReferenceSuite`), not these.
    */
  def judged[E <: EraTag](nes: NewEpochState[E], context: TxEnv)(tx: Tx)(using
      era: ShelleyBasedEra[E]
  ): Either[Vector[PredicateFailure], Long] =
    given PreConway[E] = PreConway.of(era).getOrElse(fail(s"$era is not before Conway"))
    val judgment = Judgment
      .run(ShelleyLedger.sts[E], Judgment.Policy.Accumulate)(
        NativeRules.globalsOf(context),
        ShelleyLedgerEnv(context, nes.es.chainAccount),
        Counted(nes.es.ls, PlutusStats.zero),
        tx
      )
      .map(_.map(_.plutus))
    judgment
      .fold(defect => fail(s"no verdict: ${defect.message}"), identity)
      .left
      .map(_.toChain.toVector)
      .map(_.scripts)

  /** A transaction that spends the script-locked output, withdraws nothing from the script's
    * account, delegates it and mints with the script as the policy, with no redeemer.
    */
  def unredeemed[E <: EraTag](pp: ProtocolParams)(using ShelleyBasedEra[E]): Tx =
    val token = Map(script.hash -> Map(Bytes(Vector(0x41.toByte)) -> BigInt(1)))
    transaction[E](
      pp,
      hash =>
        TestLedger.body(
          "missing-redeemers",
          Vector(funding, locked),
          Vector(TxOut(alice.address(), Value(1050 * ada - fee, token))),
          fee,
          certificates = Vector(delegation),
          withdrawals = Map(account -> BigInt(0)),
          mint = token,
          scriptDataHash = hash
        ),
      Vector.empty,
      withDatum = true
    )

  /** The purposes of [[unredeemed]] in the order of the scripts needed. */
  val purposes = Vector(
    Purpose.Spending(Vector(funding, locked).sorted.indexOf(locked), locked, lockedOut),
    Purpose.Rewarding(0, account),
    Purpose.Certifying(0, delegation),
    Purpose.Minting(0, script.hash)
  )
