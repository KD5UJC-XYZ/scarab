// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import xyz.kd5ujc.scarab.uplc.{DefaultFun, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, Costing, CostingFunction, MachineValue}
import xyz.kd5ujc.scarab.uplc.cost.Measure.{
  literal,
  maxDepth,
  nodeCount,
  one,
  outerSize,
  textBytes,
  totalSize,
  universe,
  words
}

/** The costing of every builtin in one semantics variant from a cost model: the measures of the
  * unlifted arguments and the builtin's costing function, as `toBuiltinMeaning` pairs them
  * (`PlutusCore/Default/Builtins.hs:1092-2528`, plutus 1.70.0.0 `507cab8c`; the line of each
  * builtin's meaning is in [[DefaultFun]]'s table). Each costing function computes the measures it
  * reads only, once each, and gives the CPU and memory streams zipped as the machine spends them
  * (`runCostingFun*`, `CostingFun/Core.hs:303-314,597-611,824-840,871-889,916-936,964-986`).
  *
  * An application is saturated when it is costed, so every argument is there; one that were not
  * would be measured as a value that is not a constant (1).
  */
final class BuiltinCosting private (
    model: BuiltinCostModel[SatInt],
    refined: BuiltinCosting.Refinements
) extends Costing:
  import BuiltinCosting.*

  private val table: Vector[Costed] = DefaultFun.values.toVector.map(costedOf)

  def apply(function: DefaultFun): CostingFunction = table(function.ordinal).function

  /** The measure of each argument of `function`, in order: the reference's Haskell type of the
    * argument in this variant, as far as costing reads it.
    */
  def measures(function: DefaultFun): Vector[Measure] = table(function.ordinal).measures

  private def costedOf(function: DefaultFun): Costed =
    val m = model
    import DefaultFun.*
    function match
      case AddInteger            => two(m.addInteger, universe, universe)
      case SubtractInteger       => two(m.subtractInteger, universe, universe)
      case MultiplyInteger       => two(m.multiplyInteger, universe, universe)
      case DivideInteger         => two(m.divideInteger, universe, universe)
      case QuotientInteger       => two(m.quotientInteger, universe, universe)
      case RemainderInteger      => two(m.remainderInteger, universe, universe)
      case ModInteger            => two(m.modInteger, universe, universe)
      case EqualsInteger         => two(m.equalsInteger, universe, universe)
      case LessThanInteger       => two(m.lessThanInteger, universe, universe)
      case LessThanEqualsInteger => two(m.lessThanEqualsInteger, universe, universe)
      case AppendByteString      => two(m.appendByteString, universe, universe)
      // `Integer` in A, B and D, `Word8` in C and E (`Builtins.hs:1258-1308`).
      case ConsByteString           => two(m.consByteString, refined.consByteStringByte, universe)
      case SliceByteString          => three(m.sliceByteString, one, one, universe)
      case LengthOfByteString       => single(m.lengthOfByteString, universe)
      case IndexByteString          => two(m.indexByteString, universe, one)
      case EqualsByteString         => two(m.equalsByteString, universe, universe)
      case LessThanByteString       => two(m.lessThanByteString, universe, universe)
      case LessThanEqualsByteString => two(m.lessThanEqualsByteString, universe, universe)
      case Sha2_256                 => single(m.sha2_256, universe)
      case Sha3_256                 => single(m.sha3_256, universe)
      case Blake2b_256              => single(m.blake2b_256, universe)
      case VerifyEd25519Signature   => three(m.verifyEd25519Signature, universe, universe, universe)
      case VerifyEcdsaSecp256k1Signature =>
        three(m.verifyEcdsaSecp256k1Signature, universe, universe, universe)
      case VerifySchnorrSecp256k1Signature =>
        three(m.verifySchnorrSecp256k1Signature, universe, universe, universe)
      // `Text` in A to C, `TextCostedByByteLength` in D and E (`Builtins.hs:1507-1587`).
      case AppendString => two(m.appendString, refined.text, refined.text)
      case EqualsString => two(m.equalsString, refined.text, refined.text)
      case EncodeUtf8   => single(m.encodeUtf8, refined.text)
      case DecodeUtf8   => single(m.decodeUtf8, universe)
      case IfThenElse   => three(m.ifThenElse, universe, universe, universe)
      case ChooseUnit   => two(m.chooseUnit, universe, universe)
      case Trace        => two(m.trace, universe, universe)
      case FstPair      => single(m.fstPair, universe)
      case SndPair      => single(m.sndPair, universe)
      case ChooseList   => three(m.chooseList, universe, universe, universe)
      case MkCons       => two(m.mkCons, universe, universe)
      case HeadList     => single(m.headList, universe)
      case TailList     => single(m.tailList, universe)
      case NullList     => single(m.nullList, universe)
      case ChooseData =>
        six(m.chooseData, universe, universe, universe, universe, universe, universe)
      // `Integer` in A to C, `Word64` in D and E (`Builtins.hs:1745-1758`).
      case ConstrData               => two(m.constrData, refined.constrDataTag, universe)
      case MapData                  => single(m.mapData, universe)
      case ListData                 => single(m.listData, universe)
      case IData                    => single(m.iData, universe)
      case BData                    => single(m.bData, universe)
      case UnConstrData             => single(m.unConstrData, universe)
      case UnMapData                => single(m.unMapData, universe)
      case UnListData               => single(m.unListData, universe)
      case UnIData                  => single(m.unIData, universe)
      case UnBData                  => single(m.unBData, universe)
      case EqualsData               => two(m.equalsData, universe, universe)
      case SerialiseData            => single(m.serialiseData, universe)
      case MkPairData               => two(m.mkPairData, universe, universe)
      case MkNilData                => single(m.mkNilData, universe)
      case MkNilPairData            => single(m.mkNilPairData, universe)
      case Bls12_381_G1_add         => two(m.bls12_381_G1_add, universe, universe)
      case Bls12_381_G1_neg         => single(m.bls12_381_G1_neg, universe)
      case Bls12_381_G1_scalarMul   => two(m.bls12_381_G1_scalarMul, universe, universe)
      case Bls12_381_G1_equal       => two(m.bls12_381_G1_equal, universe, universe)
      case Bls12_381_G1_hashToGroup => two(m.bls12_381_G1_hashToGroup, universe, universe)
      case Bls12_381_G1_compress    => single(m.bls12_381_G1_compress, universe)
      case Bls12_381_G1_uncompress  => single(m.bls12_381_G1_uncompress, universe)
      case Bls12_381_G2_add         => two(m.bls12_381_G2_add, universe, universe)
      case Bls12_381_G2_neg         => single(m.bls12_381_G2_neg, universe)
      case Bls12_381_G2_scalarMul   => two(m.bls12_381_G2_scalarMul, universe, universe)
      case Bls12_381_G2_equal       => two(m.bls12_381_G2_equal, universe, universe)
      case Bls12_381_G2_hashToGroup => two(m.bls12_381_G2_hashToGroup, universe, universe)
      case Bls12_381_G2_compress    => single(m.bls12_381_G2_compress, universe)
      case Bls12_381_G2_uncompress  => single(m.bls12_381_G2_uncompress, universe)
      case Bls12_381_millerLoop     => two(m.bls12_381_millerLoop, universe, universe)
      case Bls12_381_mulMlResult    => two(m.bls12_381_mulMlResult, universe, universe)
      case Bls12_381_finalVerify    => two(m.bls12_381_finalVerify, universe, universe)
      case Keccak_256               => single(m.keccak_256, universe)
      case Blake2b_224              => single(m.blake2b_224, universe)
      // The width is `NumBytesCostedAsNumWords` (`Builtins.hs:2093-2101`).
      case IntegerToByteString  => three(m.integerToByteString, universe, words, universe)
      case ByteStringToInteger  => two(m.byteStringToInteger, universe, universe)
      case AndByteString        => three(m.andByteString, universe, universe, universe)
      case OrByteString         => three(m.orByteString, universe, universe, universe)
      case XorByteString        => three(m.xorByteString, universe, universe, universe)
      case ComplementByteString => single(m.complementByteString, universe)
      case ReadBit              => two(m.readBit, universe, one)
      case WriteBits            => three(m.writeBits, universe, universe, universe)
      case ReplicateByte        => two(m.replicateByte, words, one)
      // `IntegerCostedLiterally` in A to C, `Int` in D and E (`Builtins.hs:2237-2266`).
      case ShiftByteString             => two(m.shiftByteString, universe, refined.shift)
      case RotateByteString            => two(m.rotateByteString, universe, refined.shift)
      case CountSetBits                => single(m.countSetBits, universe)
      case FindFirstSetBit             => single(m.findFirstSetBit, universe)
      case Ripemd_160                  => single(m.ripemd_160, universe)
      case ExpModInteger               => three(m.expModInteger, universe, universe, universe)
      case DropList                    => two(m.dropList, literal, universe)
      case LengthOfArray               => single(m.lengthOfArray, universe)
      case ListToArray                 => single(m.listToArray, universe)
      case IndexArray                  => two(m.indexArray, universe, one)
      case Bls12_381_G1_multiScalarMul => two(m.bls12_381_G1_multiScalarMul, universe, universe)
      case Bls12_381_G2_multiScalarMul => two(m.bls12_381_G2_multiScalarMul, universe, universe)
      case InsertCoin                  => four(m.insertCoin, universe, universe, universe, maxDepth)
      case LookupCoin                  => three(m.lookupCoin, universe, universe, maxDepth)
      case UnionValue                  => two(m.unionValue, totalSize, totalSize)
      case ValueContains               => two(m.valueContains, totalSize, totalSize)
      case ValueData                   => single(m.valueData, totalSize)
      case UnValueData                 => single(m.unValueData, nodeCount)
      case ScaleValue                  => two(m.scaleValue, universe, totalSize)
      case MultiIndexArray             => two(m.multiIndexArray, universe, universe)
      case Policies                    => single(m.policies, outerSize)
      case AssetCount                  => single(m.assetCount, totalSize)
      // No cost-model entry: `unimplementedCostingFun` (`Builtins.hs:2513-2526`).
      case KeepPolicies => unimplemented(universe, universe)
      case DropPolicies => unimplemented(universe, universe)

object BuiltinCosting:

  /** The costing of `variant` from `model`, none for a variant 1.70.0.0 does not define (F, G). */
  def of(variant: SemanticsVariant, model: BuiltinCostModel[SatInt]): Option[BuiltinCosting] =
    refinements(variant).map(new BuiltinCosting(model, _))

  /** The measures that differ between the variants of 1.70.0.0: the first argument of
    * `consByteString`, the texts of `appendString`, `equalsString` and `encodeUtf8`, the tag of
    * `constrData` and the amount of `shiftByteString` and `rotateByteString`. Every other argument
    * has the same measure in every variant (the `ensurable` variants D and E take `CInteger` and
    * `CByteString`, whose measures are `Integer` 's and `ByteString` 's,
    * `Default/Universe/Cardano.hs:22-27`).
    */
  private[cost] final case class Refinements(
      consByteStringByte: Measure,
      text: Measure,
      constrDataTag: Measure,
      shift: Measure
  )

  private def refinements(variant: SemanticsVariant): Option[Refinements] = variant match
    case SemanticsVariant.A | SemanticsVariant.B =>
      Some(Refinements(universe, universe, universe, literal))
    case SemanticsVariant.C => Some(Refinements(one, universe, universe, literal))
    case SemanticsVariant.D => Some(Refinements(universe, textBytes, one, one))
    case SemanticsVariant.E => Some(Refinements(one, textBytes, one, one))
    case SemanticsVariant.F | SemanticsVariant.G => None

  /** `makeUnimplementedCostingFun` (`CostingFun/Core.hs:128-132`): 100,000,000,000 CPU and memory
    * whatever the arguments.
    */
  val Unimplemented: Long = 100000000000L

  /** A builtin's argument measures and its costing function. */
  private final case class Costed(measures: Vector[Measure], function: CostingFunction)

  private val unimplementedFunction: CostingFunction = new CostingFunction:
    def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
      CostStream.single(ExBudget.of(Unimplemented, Unimplemented))

  private def unimplemented(m1: Measure, m2: Measure): Costed =
    Costed(Vector(m1, m2), unimplementedFunction)

  private def measured[V](measure: Measure, arguments: Vector[Argument[V]], position: Int)(using
      MachineValue[V]
  ): Costs =
    arguments.lift(position).fold(Costs.Last(SatInt(1)))(measure(_))

  private def single(fun: CostingFun[OneArgument, SatInt], m1: Measure): Costed =
    Costed(
      Vector(m1),
      new CostingFunction:
        def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
          lazy val x = measured(m1, arguments, 0)
          Costs.zip(fun.cpu.run(x), fun.memory.run(x))
    )

  private def two(fun: CostingFun[TwoArguments, SatInt], m1: Measure, m2: Measure): Costed =
    Costed(
      Vector(m1, m2),
      new CostingFunction:
        def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
          lazy val x = measured(m1, arguments, 0)
          lazy val y = measured(m2, arguments, 1)
          Costs.zip(fun.cpu.run(x, y), fun.memory.run(x, y))
    )

  private def three(
      fun: CostingFun[ThreeArguments, SatInt],
      m1: Measure,
      m2: Measure,
      m3: Measure
  ): Costed =
    Costed(
      Vector(m1, m2, m3),
      new CostingFunction:
        def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
          lazy val x = measured(m1, arguments, 0)
          lazy val y = measured(m2, arguments, 1)
          lazy val z = measured(m3, arguments, 2)
          Costs.zip(fun.cpu.run(x, y, z), fun.memory.run(x, y, z))
    )

  /** Four arguments: only the fourth is read by any shape (`ModelFourArguments`). */
  private def four(
      fun: CostingFun[FourArguments, SatInt],
      m1: Measure,
      m2: Measure,
      m3: Measure,
      m4: Measure
  ): Costed =
    Costed(
      Vector(m1, m2, m3, m4),
      new CostingFunction:
        def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
          lazy val u = measured(m4, arguments, 3)
          Costs.zip(fun.cpu.run(u), fun.memory.run(u))
    )

  /** Six arguments, and a constant cost (`ModelSixArguments`): no measure is read. */
  private def six(
      fun: CostingFun[SixArguments, SatInt],
      m1: Measure,
      m2: Measure,
      m3: Measure,
      m4: Measure,
      m5: Measure,
      m6: Measure
  ): Costed =
    Costed(
      Vector(m1, m2, m3, m4, m5, m6),
      new CostingFunction:
        def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream =
          Costs.zip(fun.cpu.run, fun.memory.run)
    )
