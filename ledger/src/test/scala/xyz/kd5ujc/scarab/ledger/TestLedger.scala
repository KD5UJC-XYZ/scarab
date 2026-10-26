// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import java.security.{KeyPair, KeyPairGenerator, Signature}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{Counted, Globals, Ledger, LedgerEnv, Sts}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState as EraLedgerState, NewEpochState}

/** Builders for ledger rule tests: parameters per era, funded states, keys that really sign, and
  * transactions assembled as values (no CBOR).
  */
object TestLedger:
  final case class Key(pair: KeyPair):
    val vkey: Bytes = Bytes.fromArray(pair.getPublic.getEncoded.takeRight(32))
    val hash: Bytes = Blake2b.hash224.hash(vkey)
    def sign(message: Bytes): Bytes =
      val s = Signature.getInstance("Ed25519")
      s.initSign(pair.getPrivate)
      s.update(message.toArray)
      Bytes.fromArray(s.sign())
    def witness(id: Bytes): (Bytes, Bytes) = vkey -> sign(id)
    def credential: Credential = Credential.Key(hash)
    def address(network: Int = 0): Address =
      Address.decode(Bytes(((0x60 | network).toByte) +: hash.value)).toOption.get
    def baseAddress(stake: Key, network: Int = 0): Address =
      Address
        .decode(Bytes(((0x00 | network).toByte) +: (hash.value ++ stake.hash.value)))
        .toOption
        .get

  def key(): Key = Key(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())

  /** A Conway parameter update of fields given by key and their encoded values, read by the Conway
    * decoder; a field it refuses fails the test.
    */
  def conwayUpdateOf(fields: (Int, Bytes)*): ConwayParamUpdate =
    ConwayParamUpdate
      .fromEncoded(fields.toVector.map((k, v) => BigInt(k) -> v))
      .fold(e => munit.Assertions.fail(s"Conway update: ${e.message}"), identity)

  /** A Conway parameter update of unsigned integer fields, each value in its shortest encoding. */
  def conwayUpdate(fields: (Int, BigInt)*): ConwayParamUpdate =
    conwayUpdateOf(fields.map((k, v) => k -> uintBytes(v))*)

  /** The shortest encoding of an unsigned integer. */
  def uintBytes(v: BigInt): Bytes =
    xyz.kd5ujc.scarab.codec.Cbor
      .encode(xyz.kd5ujc.scarab.codec.Value.UInt(v))
      .fold(e => munit.Assertions.fail(s"uint $v: ${e.message}"), identity)

  def params(protocolMajor: Int): ProtocolParams =
    // none before Babbage (the mirror holds none)
    ProtocolParams(
      minFeeA = 1,
      minFeeB = 100,
      maxTxSize = 16384,
      maxBlockBodySize = 65536,
      maxValueSize = 5000,
      coinsPerUTxOByte = if protocolMajor >= 7 then 4310 else 0,
      keyDeposit = 2000000,
      poolDeposit = 500000000,
      eMax = 18,
      nOpt = 3,
      a0 = Rational(3, 10),
      rho = Rational(3, 1000),
      tau = Rational(1, 5),
      minPoolCost = 0,
      protocolMajor = protocolMajor,
      drepDeposit = 0,
      drepActivity = 0,
      govActionDeposit = 0,
      govActionLifetime = 0,
      minFeeRefScriptCostPerByte = Rational.zero,
      minUTxOValue = 1000000,
      coinsPerUTxOWord = 34482,
      priceMem = Rational(577, 10000),
      priceSteps = Rational(721, 10000000),
      maxTxExUnits = ExUnits(10000000, 10000000000L),
      maxBlockExUnits = ExUnits(50000000, 40000000000L),
      collateralPercentage = 150,
      maxCollateralInputs = 3,
      costModels = Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7))
    )

  val Supply: BigInt = 45000000000000000L

  /** The era history the rule tests run in: epochs of 100 slots, one second each, k = 2, f = 1/2.
    */
  val geometry: EpochGeometry =
    EpochGeometry
      .checked(java.time.Instant.EPOCH, 1000L, 100L, 2, BigDecimal("0.5"))
      .fold(e => sys.error(e.message), identity)

  /** The constants of the rules over [[geometry]]. */
  val globals: Globals =
    Globals(
      LedgerGlobals(Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
      geometry
    )

  /** The Conway LEDGER rule on one transaction of the state `nes`: the ledger state after it, or
    * the refusal.
    */
  def conwayLedger(
      nes: NewEpochState[Conway],
      tx: Tx,
      env: TxEnv
  ): Either[LedgerFailure, Counted[EraLedgerState[Conway]]] =
    Sts(Ledger)(
      globals,
      LedgerEnv(env, nes.es.chainAccount.treasury),
      Counted(nes.es.ls, PlutusStats.zero),
      tx
    )

  def txIn(n: Int): TxIn = TxIn(Blake2b.hash256.hash(Bytes(Vector(n.toByte))), 0)

  val env: TxEnv =
    TxEnv(
      slot = 1000,
      epoch = 5,
      network = 0,
      firstSlotNextEpoch = 5000,
      stabilityWindow = 100,
      updateQuorum = 2,
      clock = Some(PosixClock.Total(slot => slot * 1000 + 1596059091000L))
    )

  /** An evaluator whose verdict is fixed: every script succeeds or every script fails. */
  def scripted(succeeds: Boolean): xyz.kd5ujc.scarab.plutus.ScriptEvaluator[cats.Id] =
    _ =>
      if succeeds then xyz.kd5ujc.scarab.plutus.EvalResult.Success(ExUnits(1, 1), Vector.empty)
      else
        xyz.kd5ujc.scarab.plutus.EvalResult
          .Failure(
            xyz.kd5ujc.scarab.plutus.EvalFailure.Script(
              xyz.kd5ujc.scarab.plutus
                .ScriptFailure(new IllegalStateException("scripted failure"), Vector.empty)
            ),
            ExUnits(1, 1),
            Vector.empty
          )

  def body(
      id: String,
      inputs: Vector[TxIn],
      outputs: Vector[TxOut],
      fee: BigInt,
      certificates: Vector[Certificate] = Vector.empty,
      withdrawals: Map[RewardAccount, BigInt] = Map.empty,
      mint: Map[Bytes, Map[Bytes, BigInt]] = Map.empty,
      ttl: Option[BigInt] = None,
      validityStart: Option[BigInt] = None,
      collateral: Vector[TxIn] = Vector.empty,
      scriptDataHash: Option[Bytes] = None,
      update: Option[Update] = None,
      votes: Vector[(Voter, Vector[(GovActionId, Vote)])] = Vector.empty,
      proposals: Vector[Proposal] = Vector.empty,
      donation: BigInt = 0,
      currentTreasury: Option[BigInt] = None,
      referenceInputs: Vector[TxIn] = Vector.empty,
      requiredSigners: Vector[Bytes] = Vector.empty,
      collateralReturn: Option[TxOut] = None
  ): TxBody =
    TxBody(
      raw = Bytes(id.getBytes.toVector),
      inputs = inputs,
      outputs = outputs,
      fee = fee,
      ttl = ttl,
      validityStart = validityStart,
      certificates = certificates,
      withdrawals = withdrawals,
      mint = mint,
      collateral = collateral,
      requiredSigners = requiredSigners,
      networkId = None,
      referenceInputs = referenceInputs,
      scriptDataHash = scriptDataHash,
      hasGovernance = votes.nonEmpty || proposals.nonEmpty,
      donation = donation,
      update = update,
      votes = votes.map { case (voter, ballots) =>
        voter -> ballots.map { case (id, vote) => id -> VotingProcedure(vote, None) }
      },
      proposals = proposals,
      currentTreasury = currentTreasury,
      collateralReturn = collateralReturn
    )

  /** A transaction signed by `signers`; its size is fixed so fees are easy to state. */
  def tx(
      b: TxBody,
      signers: Vector[Key],
      witnesses: Witnesses = Witnesses.empty,
      isValid: Boolean = true
  ): Tx =
    Tx(
      b,
      witnesses.copy(vkeys = signers.map(_.witness(b.id)) ++ witnesses.vkeys),
      size = 100,
      isValid = isValid,
      auxiliary = None
    )

  def out(address: Address, coin: BigInt): TxOut = TxOut(address, Value.coin(coin))
