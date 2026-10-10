// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.plutus.PlutusExecution.Budget

/** Pure numerical predicates, not source-parameter authentication or transaction admission. Pinned
  * ledger-core 1.21.0.0 ExUnits.hs:185-190 takes one ceiling of the combined price. Alonzo 1.16.0.0
  * Rules/Utxo.hs:343-350 checks collateral against the supplied transaction fee.
  */
object PlutusFees:
  private val Max = (BigInt(1) << 64) - 1
  enum Failure:
    case InvalidInput(detail: String)
    case ExecutionUnitsTooLarge
    case BlockExecutionUnitsTooLarge
    case TooManyCollateralInputs
    case FeeTooSmall(supplied: BigInt, minimum: BigInt)
    case CollateralTooSmall(supplied: BigInt, minimum: BigInt)
    case TransactionTooLarge(supplied: BigInt, maximum: BigInt)

  final class Price private[PlutusFees] (val numerator: BigInt, val denominator: BigInt)
  def price(numerator: BigInt, denominator: BigInt): Either[Failure, Price] =
    if !word(numerator) || !word(denominator) || denominator == 0 then
      Left(Failure.InvalidInput("nonnegative bounded rational price required"))
    else Right(new Price(numerator, denominator))

  final class Parameters private[PlutusFees] (
      val memoryPrice: Price,
      val stepPrice: Price,
      val maxTxUnits: Budget,
      val maxBlockUnits: Budget,
      val collateralPercentage: BigInt,
      val maxCollateralInputs: BigInt
  )
  def parameters(
      memoryPrice: Price,
      stepPrice: Price,
      maxTxUnits: Budget,
      maxBlockUnits: Budget,
      collateralPercentage: BigInt,
      maxCollateralInputs: BigInt
  ): Either[Failure, Parameters] =
    if memoryPrice == null || stepPrice == null || !budget(maxTxUnits) ||
      !budget(maxBlockUnits) || !word(collateralPercentage) || !word(maxCollateralInputs)
    then Left(Failure.InvalidInput("bounded execution parameters required"))
    else
      Right(
        new Parameters(
          memoryPrice,
          stepPrice,
          maxTxUnits,
          maxBlockUnits,
          collateralPercentage,
          maxCollateralInputs
        )
      )

  final case class Receipt(
      memoBytes: BigInt,
      linearFee: BigInt,
      executionFee: BigInt,
      minimumFee: BigInt,
      suppliedFee: BigInt,
      minimumCollateral: BigInt,
      suppliedCollateral: BigInt,
      declared: Budget
  )

  def executionFee(p: Parameters, declared: Budget): Either[Failure, BigInt] =
    if p == null || !budget(declared) then Left(Failure.InvalidInput("execution budget required"))
    else
      val memory = p.memoryPrice
      val steps = p.stepPrice
      val numerator = declared.memory * memory.numerator * steps.denominator +
        declared.steps * steps.numerator * memory.denominator
      val denominator = memory.denominator * steps.denominator
      Right(ceil(numerator, denominator))

  /** The single successful-spend profile preserves collateral; this checks its eligibility only. */
  def check(
      linear: FeeSize.Parameters,
      execution: Parameters,
      bodyBytes: BigInt,
      witnessBytes: BigInt,
      suppliedFee: BigInt,
      declared: Budget,
      collateralCoin: BigInt,
      collateralInputs: BigInt
  ): Either[Failure, Receipt] =
    if linear == null || execution == null || !word(suppliedFee) || !word(collateralCoin) ||
      !word(collateralInputs) || collateralInputs == 0 || bodyBytes == null || witnessBytes == null
    then Left(Failure.InvalidInput("bounded fee and nonempty collateral required"))
    else if !budget(declared) then Left(Failure.InvalidInput("bounded execution budget required"))
    else if !within(declared, execution.maxTxUnits) then Left(Failure.ExecutionUnitsTooLarge)
    else if !within(declared, execution.maxBlockUnits) then
      Left(Failure.BlockExecutionUnitsTooLarge)
    else if collateralInputs > execution.maxCollateralInputs then
      Left(Failure.TooManyCollateralInputs)
    else
      for
        size <- FeeSize
          .componentSize(bodyBytes, witnessBytes)
          .left
          .map(e => Failure.InvalidInput(e.toString))
        _ <- Either.cond(
          size <= linear.maxTxSize,
          (),
          Failure.TransactionTooLarge(size, linear.maxTxSize)
        )
        scriptFee <- executionFee(execution, declared)
        base = size * linear.feePerByte + linear.feeFixed
        minimum = base + scriptFee
        _ <- Either.cond(suppliedFee >= minimum, (), Failure.FeeTooSmall(suppliedFee, minimum))
        collateral = ceil(suppliedFee * execution.collateralPercentage, 100)
        _ <- Either.cond(
          collateralCoin >= collateral,
          (),
          Failure.CollateralTooSmall(collateralCoin, collateral)
        )
      yield Receipt(
        size,
        base,
        scriptFee,
        minimum,
        suppliedFee,
        collateral,
        collateralCoin,
        declared
      )

  /** Check the sum of declared units for all transactions in the candidate block, before commit. */
  def checkBlock(p: Parameters, declared: Vector[Budget]): Either[Failure, Budget] =
    if p == null || declared == null || declared.size > 16 || !declared.forall(budget) then
      Left(Failure.InvalidInput("bounded block budgets required"))
    else if declared.exists(b => !within(b, p.maxTxUnits)) then Left(Failure.ExecutionUnitsTooLarge)
    else
      val sum =
        declared.foldLeft(Budget(0, 0))((a, b) => Budget(a.memory + b.memory, a.steps + b.steps))
      if within(sum, p.maxBlockUnits) then Right(sum) else Left(Failure.BlockExecutionUnitsTooLarge)

  private def word(n: BigInt): Boolean = n != null && n >= 0 && n <= Max
  private def budget(b: Budget): Boolean = b != null && word(b.memory) && word(b.steps)
  private def within(a: Budget, b: Budget): Boolean = a.memory <= b.memory && a.steps <= b.steps
  private def ceil(n: BigInt, d: BigInt): BigInt = (n + d - 1) / d
