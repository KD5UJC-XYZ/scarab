// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import java.security.{KeyPair, KeyPairGenerator, Signature}
import lab.Blake2b
import lab.cbor.Bytes

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

  def params(protocolMajor: Int): ProtocolParams =
    ProtocolParams(
      minFeeA = 1,
      minFeeB = 100,
      maxTxSize = 16384,
      maxBlockBodySize = 65536,
      maxValueSize = 5000,
      coinsPerUTxOByte = 4310,
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
      maxCollateralInputs = 3
    )

  val Supply: BigInt = 45000000000000000L

  def txIn(n: Int): TxIn = TxIn(Blake2b.hash256.hash(Bytes(Vector(n.toByte))), 0)

  /** A state whose UTxO is `funds` and whose reserves make the total equal [[Supply]]. */
  def state(protocolMajor: Int, funds: Vector[(TxIn, TxOut)]): LedgerState =
    val utxo = funds.toMap
    LedgerState(
      epoch = 5,
      params = params(protocolMajor),
      utxo = utxo,
      fees = 0,
      deposited = 0,
      accounts = Map.empty,
      pools = Map.empty,
      futurePools = Map.empty,
      retiring = Map.empty,
      dreps = Map.empty,
      dormantEpochs = 0,
      treasury = 0,
      reserves = Supply - utxo.valuesIterator.map(_.value.coin).sum,
      snapshots = Snapshots(Snapshot.empty, Snapshot.empty, Snapshot.empty, 0),
      blocksCurrent = Map.empty,
      blocksPrevious = Map.empty,
      rewardUpdate = None,
      poolDistribution = Snapshot.empty
    )

  val env: TxRules.Env =
    TxRules.Env(
      slot = 1000,
      epoch = 5,
      network = 0,
      firstSlotNextEpoch = 5000,
      stabilityWindow = 100,
      updateQuorum = 2
    )

  def body(
      id: String,
      inputs: Vector[TxIn],
      outputs: Vector[TxOut],
      fee: BigInt,
      certificates: Vector[Certificate] = Vector.empty,
      withdrawals: Vector[(RewardAccount, BigInt)] = Vector.empty,
      mint: Map[Bytes, Map[Bytes, BigInt]] = Map.empty,
      ttl: Option[BigInt] = None,
      validityStart: Option[BigInt] = None,
      collateral: Vector[TxIn] = Vector.empty,
      scriptDataHash: Option[Bytes] = None,
      update: Option[Update] = None
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
      requiredSigners = Vector.empty,
      networkId = None,
      referenceInputs = Vector.empty,
      scriptDataHash = scriptDataHash,
      hasGovernance = false,
      donation = 0,
      update = update
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
