// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Eval
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{Constant, PlutusData, Text}
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, MachineValue}

/** The size of a builtin's argument as its costing function reads it, the reference's
  * `ExMemoryUsage` (`PlutusCore/Evaluation/Machine/ExMemoryUsage.hs`, plutus 1.70.0.0 `507cab8c`):
  * a stream of costs whose sum is the size, flattened from the reference's `CostRose` in pre-order
  * (`flattenCostRose`, lines 132-159). Which measure an argument has is the Haskell type its
  * builtin's denotation takes it as in a semantics variant ([[BuiltinCosting]]):
  * [[Measure.universe]] for a type's own instance, or one of the wrappers that change it.
  *
  * Every measure is total. One that reads a particular type ([[Measure.textBytes]], for instance)
  * gives the argument's own measure ([[Measure.universe]]) to any other: the unlifting has already
  * given each argument its parameter's type, so that case does not arise.
  */
trait Measure:
  def apply[V](argument: Argument[V])(using MachineValue[V]): Costs

object Measure:
  private def single(cost: Long): Costs = Costs.Last(SatInt(cost))

  /** A type's own measure. A constant has the measure of its type ([[constant]]: `instance
    * ExMemoryUsage (Some (ValueOf uni))`, lines 237-239, also for `SomeConstant`,
    * `Builtin/Polymorphism.hs:164-167`); any other value of the machine is 1, the CEK machine's
    * measure of a value that is not a constant (`instance ExMemoryUsage (CekValue ...)`,
    * `Cek/Internal.hs:751-759`).
    */
  val universe: Measure = new Measure:
    def apply[V](argument: Argument[V])(using value: MachineValue[V]): Costs = argument match
      case Argument.Con(c)    => constant(c)
      case Argument.Opaque(v) => value.constantOf(v).fold(single(1))(constant)

  /** 1 whatever the value: `Int`, `Word8` and `Word64` (lines 267-273, 343-345), the types of
    * arguments taken as indices, widths, bytes and constructor tags in some variants.
    */
  val one: Measure = new Measure:
    def apply[V](argument: Argument[V])(using MachineValue[V]): Costs = single(1)

  /** `TextCostedByByteLength` (lines 336-341): a quarter of the length of the text's UTF-8
    * encoding, rounded towards zero.
    */
  val textBytes: Measure = refining { case Constant.String(t) => single(t.utf8Length / 4L) }

  /** `IntegerCostedLiterally` (lines 304-308): the absolute value of the integer, at most
    * `maxBound`.
    */
  val literal: Measure = refining { case Constant.Integer(n) => Costs.Last(SatInt.clamp(n.abs)) }

  /** `NumBytesCostedAsNumWords` (lines 287-291): the number of 8-byte words that hold `|n|` bytes,
    * `((|n| - 1) div 8) + 1` with the division rounding down, so 0 for 0; at most `maxBound`.
    */
  val words: Measure = refining { case Constant.Integer(n) =>
    val m = n.abs - 1
    Costs.Last(SatInt.clamp((m - m.mod(8)) / 8 + 1))
  }

  /** `ValueTotalSize` (lines 420-424), also `Value`'s own measure: the number of entries. */
  val totalSize: Measure = universe

  /** `ValueOuterSize` (lines 427-431): the number of currencies. */
  val outerSize: Measure = refining { case Constant.Value(v) => single(v.entries.size.toLong) }

  /** `ValueMaxDepth` (lines 452-462): the sum of `integerLog2 n + 1` (0 for 0) over the number of
    * currencies and the size of the largest inner map.
    */
  val maxDepth: Measure = refining { case Constant.Value(v) =>
    single(depth(v.entries.size) + depth(v.maxInnerSize))
  }

  /** `DataNodeCount` (lines 399-412): 1 for every node of the `Data`, in pre-order. */
  val nodeCount: Measure = refining { case Constant.Data(d) =>
    flatten(d)(_ => SatInt(1))
  }

  private def refining(measure: PartialFunction[Constant, Costs]): Measure = new Measure:
    def apply[V](argument: Argument[V])(using value: MachineValue[V]): Costs = argument match
      case Argument.Con(c) => measure.applyOrElse(c, constant)
      case other           => universe(other)

  private def depth(size: Int): Long = (32 - Integer.numberOfLeadingZeros(size)).toLong

  /** The measure of a constant by its type (the `ExMemoryUsage` instance of each type of the
    * universe, `ExMemoryUsage.hs:199-497`).
    */
  def constant(c: Constant): Costs = c match
    case Constant.Integer(n)              => Costs.Last(integer(n))
    case Constant.ByteString(b)           => Costs.Last(bytes(b.size))
    case Constant.String(t)               => text(t)
    case Constant.Unit                    => single(1)
    case Constant.Bool(_)                 => single(1)
    case Constant.Data(d)                 => data(d)
    case Constant.Bls12_381_G1_Element(_) => single(G1Words)
    case Constant.Bls12_381_G2_Element(_) => single(G2Words)
    case Constant.Bls12_381_MlResult(_)   => single(MlResultWords)
    case Constant.Value(v)                => single(v.totalSize.toLong)
    case Constant.List(_, items)          => list(items.size)
    case Constant.Array(_, items)         => single(items.size.toLong)
    // Pairs are only ever costed by constant functions (lines 199-205).
    case Constant.Pair(_, _) => Costs.Last(SatInt.maxValue)

  /** `memoryUsageInteger` (lines 247-256): 1 for 0, else the number of 64-bit words of `|n|`,
    * `integerLog2 |n| div 64 + 1`.
    */
  def integer(n: BigInt): SatInt =
    if n.signum == 0 then SatInt(1) else SatInt((n.abs.bitLength - 1) / 64 + 1L)

  /** A byte string (lines 319-324): `((n - 1) quot 8) + 1`, so 1 for the empty one. */
  def bytes(size: Int): SatInt = SatInt((size - 1) / 8 + 1L)

  /** A text (lines 326-334): its number of characters in pieces of 100, the last one of 1 to 100 (0
    * only for the empty text).
    */
  def text(t: Text): Costs = chunks(t.codePoints, last = n => n <= Chunk)

  /** A list (lines 221-227): its length in pieces of 100, the last one of 0 to 99, so a list of a
    * multiple of 100 elements ends with a 0 (`dropN @100` succeeds on exactly 100 elements).
    */
  def list(length: Int): Costs = chunks(length, last = n => n < Chunk)

  private val Chunk = 100

  // `CostRose 100 [go rest]`: a piece of 100 and the rest, measured when it is reached.
  private def chunks(length: Int, last: Int => Boolean): Costs =
    if last(length) then single(length.toLong)
    else Costs.Cons(SatInt(Chunk.toLong), Eval.later(chunks(length - Chunk, last)))

  /** `Data` (lines 382-394): 4 for every node plus the size of an integer or byte string in it, in
    * pre-order (a map's keys and values alternate).
    */
  def data(d: PlutusData): Costs =
    flatten(d) {
      case PlutusData.I(n) => SatInt(4) + integer(n)
      case PlutusData.B(b) => SatInt(4) + bytes(b.size)
      case _               => SatInt(4) + SatInt.zero
    }

  /** The children of a node not yet walked: the fields of a constructor or the items of a list from
    * `next`, or a map's keys and values in turn from position `next` of `2 * size`.
    */
  private enum Pending:
    case Items(items: Vector[PlutusData], next: Int)
    case Entries(entries: Vector[(PlutusData, PlutusData)], next: Int)

  private def childrenOf(d: PlutusData): Option[Pending] = d match
    case PlutusData.Constr(_, fields) if fields.nonEmpty => Some(Pending.Items(fields, 0))
    case PlutusData.List(items) if items.nonEmpty        => Some(Pending.Items(items, 0))
    case PlutusData.Map(entries) if entries.nonEmpty     => Some(Pending.Entries(entries, 0))
    case _                                               => None

  @tailrec private def nextNode(stack: List[Pending]): Option[(PlutusData, List[Pending])] =
    stack match
      case Nil => None
      case Pending.Items(items, i) :: rest =>
        items.lift(i) match
          case Some(d) => Some((d, Pending.Items(items, i + 1) :: rest))
          case None    => nextNode(rest)
      case Pending.Entries(entries, i) :: rest =>
        entries.lift(i / 2) match
          case Some((k, v)) =>
            Some((if i % 2 == 0 then k else v, Pending.Entries(entries, i + 1) :: rest))
          case None => nextNode(rest)

  /** The pre-order walk of `flattenCostRose` (lines 132-159): a node's cost, then its children's
    * walks in order, then the rest, each node measured when the stream reaches it. The pending
    * children are on the heap, so a `Data` as deep as its script is walked in constant stack, and
    * one whose shared nodes unfold to an exponential size only as far as it is forced.
    */
  private def flatten(root: PlutusData)(cost: PlutusData => SatInt): Costs =
    def walk(node: PlutusData, stack: List[Pending]): Costs =
      nextNode(childrenOf(node).fold(stack)(_ :: stack)) match
        case None                => Costs.Last(cost(node))
        case Some((next, after)) => Costs.Cons(cost(node), Eval.later(walk(next, after)))
    walk(root, Nil)

  /** `memSizeBytes div 8` of a G1 point, 144 bytes (`g1ElementCost`, lines 472-477;
    * `Crypto/BLS12_381/G1.hs:201-203,250-251`).
    */
  val G1Words: Long = 18L

  /** A G2 point, 288 bytes (`g2ElementCost`, lines 481-486; `Crypto/BLS12_381/G2.hs:207-208`). */
  val G2Words: Long = 36L

  /** A Miller-loop result, 576 bytes (`mlResultElementCost`, lines 490-495;
    * `Crypto/BLS12_381/Pairing.hs:104`).
    */
  val MlResultWords: Long = 72L
