// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.util.Locale
import xyz.kd5ujc.scarab.uplc.Signature.Notation.*

/** The builtin functions of plutus 1.70.0.0 (`507cab8c`; 1.71.0.0 has the same list and tags), the
  * reference's `DefaultFun`, in its declaration order
  * (`plutus-core/plutus-core/src/PlutusCore/Default/Builtins.hs:90-229`). Each case carries its
  * Flat tag, from the `Flat DefaultFun` instance (`Builtins.hs:2566-2674`, 7 bits,
  * `builtinTagWidth`, 2556-2557), and its [[Signature]], read from the type of its denotation in
  * `toBuiltinMeaning` (`Builtins.hs:1092-2528`). The Scaladoc of a case gives the lines of its
  * declaration, its tag and its meaning in that file. Which builtins a script may use is
  * [[Availability]]'s.
  */
enum DefaultFun(val flatTag: Int, val signature: Signature):
  /** `Builtins.hs:92` (declaration), `2569` (Flat tag), `1099` (meaning). */
  case AddInteger extends DefaultFun(0, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:93` (declaration), `2570` (Flat tag), `1114` (meaning). */
  case SubtractInteger extends DefaultFun(1, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:94` (declaration), `2571` (Flat tag), `1129` (meaning). */
  case MultiplyInteger extends DefaultFun(2, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:95` (declaration), `2572` (Flat tag), `1144` (meaning). */
  case DivideInteger extends DefaultFun(3, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:96` (declaration), `2573` (Flat tag), `1159` (meaning). */
  case QuotientInteger extends DefaultFun(4, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:97` (declaration), `2574` (Flat tag), `1174` (meaning). */
  case RemainderInteger extends DefaultFun(5, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:98` (declaration), `2575` (Flat tag), `1189` (meaning). */
  case ModInteger extends DefaultFun(6, Signature(0)(integer, integer)(integer))

  /** `Builtins.hs:99` (declaration), `2576` (Flat tag), `1204` (meaning). */
  case EqualsInteger extends DefaultFun(7, Signature(0)(integer, integer)(bool))

  /** `Builtins.hs:100` (declaration), `2577` (Flat tag), `1211` (meaning). */
  case LessThanInteger extends DefaultFun(8, Signature(0)(integer, integer)(bool))

  /** `Builtins.hs:101` (declaration), `2578` (Flat tag), `1226` (meaning). */
  case LessThanEqualsInteger extends DefaultFun(9, Signature(0)(integer, integer)(bool))

  /** `Builtins.hs:103` (declaration), `2579` (Flat tag), `1242` (meaning). */
  case AppendByteString extends DefaultFun(10, Signature(0)(bytestring, bytestring)(bytestring))

  /** `Builtins.hs:104` (declaration), `2580` (Flat tag), `1258` (meaning). */
  case ConsByteString extends DefaultFun(11, Signature(0)(integer, bytestring)(bytestring))

  /** `Builtins.hs:105` (declaration), `2581` (Flat tag), `1309` (meaning). */
  case SliceByteString
      extends DefaultFun(12, Signature(0)(integer, integer, bytestring)(bytestring))

  /** `Builtins.hs:106` (declaration), `2582` (Flat tag), `1324` (meaning). */
  case LengthOfByteString extends DefaultFun(13, Signature(0)(bytestring)(integer))

  /** `Builtins.hs:107` (declaration), `2583` (Flat tag), `1331` (meaning). */
  case IndexByteString extends DefaultFun(14, Signature(0)(bytestring, integer)(integer))

  /** `Builtins.hs:108` (declaration), `2584` (Flat tag), `1351` (meaning). */
  case EqualsByteString extends DefaultFun(15, Signature(0)(bytestring, bytestring)(bool))

  /** `Builtins.hs:109` (declaration), `2585` (Flat tag), `1366` (meaning). */
  case LessThanByteString extends DefaultFun(16, Signature(0)(bytestring, bytestring)(bool))

  /** `Builtins.hs:110` (declaration), `2586` (Flat tag), `1381` (meaning). */
  case LessThanEqualsByteString extends DefaultFun(17, Signature(0)(bytestring, bytestring)(bool))

  /** `Builtins.hs:112` (declaration), `2587` (Flat tag), `1397` (meaning). */
  case Sha2_256 extends DefaultFun(18, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:113` (declaration), `2588` (Flat tag), `1412` (meaning). */
  case Sha3_256 extends DefaultFun(19, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:114` (declaration), `2589` (Flat tag), `1427` (meaning). */
  case Blake2b_256 extends DefaultFun(20, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:115` (declaration), `2590` (Flat tag), `1442` (meaning). */
  case VerifyEd25519Signature
      extends DefaultFun(21, Signature(0)(bytestring, bytestring, bytestring)(bool))

  /** `Builtins.hs:116` (declaration), `2621` (Flat tag), `1479` (meaning). */
  case VerifyEcdsaSecp256k1Signature
      extends DefaultFun(52, Signature(0)(bytestring, bytestring, bytestring)(bool))

  /** `Builtins.hs:117` (declaration), `2622` (Flat tag), `1487` (meaning). */
  case VerifySchnorrSecp256k1Signature
      extends DefaultFun(53, Signature(0)(bytestring, bytestring, bytestring)(bool))

  /** `Builtins.hs:119` (declaration), `2591` (Flat tag), `1507` (meaning). */
  case AppendString extends DefaultFun(22, Signature(0)(string, string)(string))

  /** `Builtins.hs:120` (declaration), `2592` (Flat tag), `1536` (meaning). */
  case EqualsString extends DefaultFun(23, Signature(0)(string, string)(bool))

  /** `Builtins.hs:121` (declaration), `2593` (Flat tag), `1563` (meaning). */
  case EncodeUtf8 extends DefaultFun(24, Signature(0)(string)(bytestring))

  /** `Builtins.hs:122` (declaration), `2594` (Flat tag), `1588` (meaning). */
  case DecodeUtf8 extends DefaultFun(25, Signature(0)(bytestring)(string))

  /** `Builtins.hs:124` (declaration), `2595` (Flat tag), `1612` (meaning). */
  case IfThenElse extends DefaultFun(26, Signature(1)(bool, opaque(a), opaque(a))(a))

  /** `Builtins.hs:126` (declaration), `2596` (Flat tag), `1620` (meaning). */
  case ChooseUnit extends DefaultFun(27, Signature(1)(unit, opaque(a))(a))

  /** `Builtins.hs:128` (declaration), `2597` (Flat tag), `1630` (meaning). */
  case Trace extends DefaultFun(28, Signature(1)(string, opaque(a))(a))

  /** `Builtins.hs:130` (declaration), `2598` (Flat tag), `1638` (meaning). */
  case FstPair extends DefaultFun(29, Signature(2)(pair(a, b))(a))

  /** `Builtins.hs:131` (declaration), `2599` (Flat tag), `1650` (meaning). */
  case SndPair extends DefaultFun(30, Signature(2)(pair(a, b))(b))

  /** `Builtins.hs:133` (declaration), `2600` (Flat tag), `1663` (meaning). */
  case ChooseList extends DefaultFun(31, Signature(2)(list(a), opaque(b), opaque(b))(b))

  /** `Builtins.hs:134` (declaration), `2601` (Flat tag), `1677` (meaning). */
  case MkCons extends DefaultFun(32, Signature(1)(a, list(a))(list(a)))

  /** `Builtins.hs:135` (declaration), `2602` (Flat tag), `1696` (meaning). */
  case HeadList extends DefaultFun(33, Signature(1)(list(a))(a))

  /** `Builtins.hs:136` (declaration), `2603` (Flat tag), `1708` (meaning). */
  case TailList extends DefaultFun(34, Signature(1)(list(a))(list(a)))

  /** `Builtins.hs:137` (declaration), `2604` (Flat tag), `1721` (meaning). */
  case NullList extends DefaultFun(35, Signature(1)(list(a))(bool))

  /** `Builtins.hs:143` (declaration), `2605` (Flat tag), `1732` (meaning). */
  case ChooseData
      extends DefaultFun(
        36,
        Signature(1)(data, opaque(a), opaque(a), opaque(a), opaque(a), opaque(a))(a)
      )

  /** `Builtins.hs:144` (declaration), `2606` (Flat tag), `1745` (meaning). */
  case ConstrData extends DefaultFun(37, Signature(0)(integer, list(data))(data))

  /** `Builtins.hs:145` (declaration), `2607` (Flat tag), `1760` (meaning). */
  case MapData extends DefaultFun(38, Signature(0)(list(pair(data, data)))(data))

  /** `Builtins.hs:146` (declaration), `2608` (Flat tag), `1767` (meaning). */
  case ListData extends DefaultFun(39, Signature(0)(list(data))(data))

  /** `Builtins.hs:147` (declaration), `2609` (Flat tag), `1774` (meaning). */
  case IData extends DefaultFun(40, Signature(0)(integer)(data))

  /** `Builtins.hs:148` (declaration), `2610` (Flat tag), `1781` (meaning). */
  case BData extends DefaultFun(41, Signature(0)(bytestring)(data))

  /** `Builtins.hs:149` (declaration), `2611` (Flat tag), `1788` (meaning). */
  case UnConstrData extends DefaultFun(42, Signature(0)(data)(pair(integer, list(data))))

  /** `Builtins.hs:150` (declaration), `2612` (Flat tag), `1799` (meaning). */
  case UnMapData extends DefaultFun(43, Signature(0)(data)(list(pair(data, data))))

  /** `Builtins.hs:151` (declaration), `2613` (Flat tag), `1810` (meaning). */
  case UnListData extends DefaultFun(44, Signature(0)(data)(list(data)))

  /** `Builtins.hs:152` (declaration), `2614` (Flat tag), `1821` (meaning). */
  case UnIData extends DefaultFun(45, Signature(0)(data)(integer))

  /** `Builtins.hs:153` (declaration), `2615` (Flat tag), `1832` (meaning). */
  case UnBData extends DefaultFun(46, Signature(0)(data)(bytestring))

  /** `Builtins.hs:154` (declaration), `2616` (Flat tag), `1843` (meaning). */
  case EqualsData extends DefaultFun(47, Signature(0)(data, data)(bool))

  /** `Builtins.hs:155` (declaration), `2620` (Flat tag), `1850` (meaning). */
  case SerialiseData extends DefaultFun(51, Signature(0)(data)(bytestring))

  /** `Builtins.hs:160` (declaration), `2617` (Flat tag), `1858` (meaning). */
  case MkPairData extends DefaultFun(48, Signature(0)(data, data)(pair(data, data)))

  /** `Builtins.hs:161` (declaration), `2618` (Flat tag), `1865` (meaning). */
  case MkNilData extends DefaultFun(49, Signature(0)(unit)(list(data)))

  /** `Builtins.hs:162` (declaration), `2619` (Flat tag), `1875` (meaning). */
  case MkNilPairData extends DefaultFun(50, Signature(0)(unit)(list(pair(data, data))))

  /** `Builtins.hs:165` (declaration), `2623` (Flat tag), `1886` (meaning). */
  case Bls12_381_G1_add extends DefaultFun(54, Signature(0)(g1, g1)(g1))

  /** `Builtins.hs:166` (declaration), `2624` (Flat tag), `1894` (meaning). */
  case Bls12_381_G1_neg extends DefaultFun(55, Signature(0)(g1)(g1))

  /** `Builtins.hs:167` (declaration), `2625` (Flat tag), `1901` (meaning). */
  case Bls12_381_G1_scalarMul extends DefaultFun(56, Signature(0)(integer, g1)(g1))

  /** `Builtins.hs:168` (declaration), `2626` (Flat tag), `1951` (meaning). */
  case Bls12_381_G1_equal extends DefaultFun(57, Signature(0)(g1, g1)(bool))

  /** `Builtins.hs:169` (declaration), `2629` (Flat tag), `1933` (meaning). */
  case Bls12_381_G1_hashToGroup extends DefaultFun(60, Signature(0)(bytestring, bytestring)(g1))

  /** `Builtins.hs:170` (declaration), `2627` (Flat tag), `1918` (meaning). */
  case Bls12_381_G1_compress extends DefaultFun(58, Signature(0)(g1)(bytestring))

  /** `Builtins.hs:171` (declaration), `2628` (Flat tag), `1925` (meaning). */
  case Bls12_381_G1_uncompress extends DefaultFun(59, Signature(0)(bytestring)(g1))

  /** `Builtins.hs:173` (declaration), `2630` (Flat tag), `1959` (meaning). */
  case Bls12_381_G2_add extends DefaultFun(61, Signature(0)(g2, g2)(g2))

  /** `Builtins.hs:174` (declaration), `2631` (Flat tag), `1967` (meaning). */
  case Bls12_381_G2_neg extends DefaultFun(62, Signature(0)(g2)(g2))

  /** `Builtins.hs:175` (declaration), `2632` (Flat tag), `1974` (meaning). */
  case Bls12_381_G2_scalarMul extends DefaultFun(63, Signature(0)(integer, g2)(g2))

  /** `Builtins.hs:176` (declaration), `2633` (Flat tag), `2025` (meaning). */
  case Bls12_381_G2_equal extends DefaultFun(64, Signature(0)(g2, g2)(bool))

  /** `Builtins.hs:177` (declaration), `2636` (Flat tag), `2007` (meaning). */
  case Bls12_381_G2_hashToGroup extends DefaultFun(67, Signature(0)(bytestring, bytestring)(g2))

  /** `Builtins.hs:178` (declaration), `2634` (Flat tag), `1991` (meaning). */
  case Bls12_381_G2_compress extends DefaultFun(65, Signature(0)(g2)(bytestring))

  /** `Builtins.hs:179` (declaration), `2635` (Flat tag), `1998` (meaning). */
  case Bls12_381_G2_uncompress extends DefaultFun(66, Signature(0)(bytestring)(g2))

  /** `Builtins.hs:181` (declaration), `2637` (Flat tag), `2033` (meaning). */
  case Bls12_381_millerLoop extends DefaultFun(68, Signature(0)(g1, g2)(mlResult))

  /** `Builtins.hs:182` (declaration), `2638` (Flat tag), `2041` (meaning). */
  case Bls12_381_mulMlResult extends DefaultFun(69, Signature(0)(mlResult, mlResult)(mlResult))

  /** `Builtins.hs:183` (declaration), `2639` (Flat tag), `2051` (meaning). */
  case Bls12_381_finalVerify extends DefaultFun(70, Signature(0)(mlResult, mlResult)(bool))

  /** `Builtins.hs:185` (declaration), `2640` (Flat tag), `2059` (meaning). */
  case Keccak_256 extends DefaultFun(71, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:186` (declaration), `2641` (Flat tag), `2074` (meaning). */
  case Blake2b_224 extends DefaultFun(72, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:188` (declaration), `2642` (Flat tag), `2093` (meaning). */
  case IntegerToByteString extends DefaultFun(73, Signature(0)(bool, integer, integer)(bytestring))

  /** `Builtins.hs:189` (declaration), `2643` (Flat tag), `2103` (meaning). */
  case ByteStringToInteger extends DefaultFun(74, Signature(0)(bool, bytestring)(integer))

  /** `Builtins.hs:191` (declaration), `2644` (Flat tag), `2119` (meaning). */
  case AndByteString extends DefaultFun(75, Signature(0)(bool, bytestring, bytestring)(bytestring))

  /** `Builtins.hs:192` (declaration), `2645` (Flat tag), `2135` (meaning). */
  case OrByteString extends DefaultFun(76, Signature(0)(bool, bytestring, bytestring)(bytestring))

  /** `Builtins.hs:193` (declaration), `2646` (Flat tag), `2151` (meaning). */
  case XorByteString extends DefaultFun(77, Signature(0)(bool, bytestring, bytestring)(bytestring))

  /** `Builtins.hs:194` (declaration), `2647` (Flat tag), `2167` (meaning). */
  case ComplementByteString extends DefaultFun(78, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:195` (declaration), `2648` (Flat tag), `2184` (meaning). */
  case ReadBit extends DefaultFun(79, Signature(0)(bytestring, integer)(bool))

  /** `Builtins.hs:196` (declaration), `2649` (Flat tag), `2199` (meaning). */
  case WriteBits extends DefaultFun(80, Signature(0)(bytestring, list(integer), bool)(bytestring))

  /** `Builtins.hs:197` (declaration), `2650` (Flat tag), `2230` (meaning). */
  case ReplicateByte extends DefaultFun(81, Signature(0)(integer, integer)(bytestring))

  /** `Builtins.hs:199` (declaration), `2651` (Flat tag), `2237` (meaning). */
  case ShiftByteString extends DefaultFun(82, Signature(0)(bytestring, integer)(bytestring))

  /** `Builtins.hs:200` (declaration), `2652` (Flat tag), `2252` (meaning). */
  case RotateByteString extends DefaultFun(83, Signature(0)(bytestring, integer)(bytestring))

  /** `Builtins.hs:201` (declaration), `2653` (Flat tag), `2267` (meaning). */
  case CountSetBits extends DefaultFun(84, Signature(0)(bytestring)(integer))

  /** `Builtins.hs:202` (declaration), `2654` (Flat tag), `2282` (meaning). */
  case FindFirstSetBit extends DefaultFun(85, Signature(0)(bytestring)(integer))

  /** `Builtins.hs:204` (declaration), `2655` (Flat tag), `2297` (meaning). */
  case Ripemd_160 extends DefaultFun(86, Signature(0)(bytestring)(bytestring))

  /** `Builtins.hs:206` (declaration), `2656` (Flat tag), `2314` (meaning). */
  case ExpModInteger extends DefaultFun(87, Signature(0)(integer, integer, integer)(integer))

  /** `Builtins.hs:207` (declaration), `2657` (Flat tag), `2328` (meaning). */
  case DropList extends DefaultFun(88, Signature(1)(integer, list(a))(list(a)))

  /** `Builtins.hs:209` (declaration), `2658` (Flat tag), `2378` (meaning). */
  case LengthOfArray extends DefaultFun(89, Signature(1)(array(a))(integer))

  /** `Builtins.hs:210` (declaration), `2659` (Flat tag), `2386` (meaning). */
  case ListToArray extends DefaultFun(90, Signature(1)(list(a))(array(a)))

  /** `Builtins.hs:211` (declaration), `2660` (Flat tag), `2395` (meaning). */
  case IndexArray extends DefaultFun(91, Signature(1)(array(a), integer)(a))

  /** `Builtins.hs:213` (declaration), `2661` (Flat tag), `2410` (meaning). */
  case Bls12_381_G1_multiScalarMul extends DefaultFun(92, Signature(0)(list(integer), list(g1))(g1))

  /** `Builtins.hs:214` (declaration), `2662` (Flat tag), `2418` (meaning). */
  case Bls12_381_G2_multiScalarMul extends DefaultFun(93, Signature(0)(list(integer), list(g2))(g2))

  /** `Builtins.hs:216` (declaration), `2663` (Flat tag), `2426` (meaning). */
  case InsertCoin
      extends DefaultFun(94, Signature(0)(bytestring, bytestring, integer, value)(value))

  /** `Builtins.hs:217` (declaration), `2664` (Flat tag), `2439` (meaning). */
  case LookupCoin extends DefaultFun(95, Signature(0)(bytestring, bytestring, value)(integer))

  /** `Builtins.hs:218` (declaration), `2665` (Flat tag), `2446` (meaning). */
  case UnionValue extends DefaultFun(96, Signature(0)(value, value)(value))

  /** `Builtins.hs:219` (declaration), `2666` (Flat tag), `2453` (meaning). */
  case ValueContains extends DefaultFun(97, Signature(0)(value, value)(bool))

  /** `Builtins.hs:220` (declaration), `2667` (Flat tag), `2461` (meaning). */
  case ValueData extends DefaultFun(98, Signature(0)(value)(data))

  /** `Builtins.hs:221` (declaration), `2668` (Flat tag), `2468` (meaning). */
  case UnValueData extends DefaultFun(99, Signature(0)(data)(value))

  /** `Builtins.hs:222` (declaration), `2669` (Flat tag), `2475` (meaning). */
  case ScaleValue extends DefaultFun(100, Signature(0)(integer, value)(value))

  /** `Builtins.hs:224` (declaration), `2670` (Flat tag), `2482` (meaning). */
  case MultiIndexArray extends DefaultFun(101, Signature(1)(array(a), list(integer))(list(a)))

  /** `Builtins.hs:225` (declaration), `2671` (Flat tag), `2499` (meaning). */
  case Policies extends DefaultFun(102, Signature(0)(value)(list(bytestring)))

  /** `Builtins.hs:226` (declaration), `2672` (Flat tag), `2506` (meaning). */
  case AssetCount extends DefaultFun(103, Signature(0)(value)(integer))

  /** `Builtins.hs:227` (declaration), `2673` (Flat tag), `2513` (meaning). */
  case KeepPolicies extends DefaultFun(104, Signature(0)(list(bytestring), value)(value))

  /** `Builtins.hs:228` (declaration), `2674` (Flat tag), `2520` (meaning). */
  case DropPolicies extends DefaultFun(105, Signature(0)(list(bytestring), value)(value))

  /** The name in the textual syntax: the constructor's name with its first letter in lower case
    * (`instance Pretty DefaultFun`, `Builtins.hs:232-239`).
    */
  lazy val name: String =
    val constructor = toString
    constructor.substring(0, 1).toLowerCase(Locale.ROOT) + constructor.substring(1)

  /** The number of arguments the builtin takes once forced. */
  def arity: Int = signature.arity

  /** The number of `force`s the builtin takes before its first argument. */
  def forces: Int = signature.forces

object DefaultFun:
  private lazy val byTag: Map[Int, DefaultFun] = values.iterator.map(f => f.flatTag -> f).toMap
  private lazy val byName: Map[String, DefaultFun] = values.iterator.map(f => f.name -> f).toMap

  /** The builtin of a Flat tag; the decoder refuses any other tag (`Builtins.hs:2784`). */
  def fromFlatTag(tag: Int): Option[DefaultFun] = byTag.get(tag)

  /** The builtin of a name of the textual syntax. */
  def fromName(name: String): Option[DefaultFun] = byName.get(name)
