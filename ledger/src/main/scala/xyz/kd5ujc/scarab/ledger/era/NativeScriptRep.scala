// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.{Bytes, Node, TreeFold}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  ByteStrings,
  CborRead,
  DecodeField,
  DecodeSite,
  LedgerDecodeError,
  NativeScript as FlatNativeScript
}

/** The language of a native script: Shelley's `MultiSig`, or `Timelock` from Allegra, which adds
  * the two time locks (cardano-ledger f649f975
  * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Scripts.hs:94-105`,
  * `eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs:179-187`).
  */
sealed trait NativeForm

object NativeForm:
  sealed abstract class MultiSig extends NativeForm
  sealed abstract class Timelock extends NativeForm

  /** The decoder's form of each language ([[FlatNativeScript.Form]]). */
  trait Of[F <: NativeForm]:
    def form: FlatNativeScript.Form

  given multiSig: Of[MultiSig] with
    def form: FlatNativeScript.Form = FlatNativeScript.Form.MultiSig
  given timelock: Of[Timelock] with
    def form: FlatNativeScript.Form = FlatNativeScript.Form.Timelock

/** A native script of language `F` with the bytes of each of its levels as they arrived: the
  * reference's `MultiSig era` and `Timelock era` are `MemoBytes` of their raw form at every level
  * (`newtype Timelock era = MkTimelock (MemoBytes (TimelockRaw era))`, `Allegra/Scripts.hs:253`),
  * so a sub-script read from a script has its own bytes (`getRequireAllOfTimelock`,
  * `Allegra/Scripts.hs:432-433`). Each level's bytes are a window of one copy the script owns, so a
  * script as deep as a block holds one copy of them and keeps nothing else it was read with alive.
  *
  * Equal when the bytes and the scripts are (`instance Eq (MemoBytes t)`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/MemoBytes/Internal.hs:182-183`); the comparison of
  * the scripts is stack-safe ([[FlatNativeScript]]).
  *
  * @param raw
  *   the script's encoding as it arrived
  * @param script
  *   the script, as the rules evaluate it ([[FlatNativeScript.eval]])
  * @param children
  *   the sub-scripts of `AllOf`, `AnyOf` and `NOfK`, in order, each with its own bytes
  */
final class NativeScriptRep[F <: NativeForm] private (
    val raw: Bytes,
    val script: FlatNativeScript,
    val children: Vector[NativeScriptRep[F]]
):
  /** `hashScript`: blake2b-224 of the native tag `0` and the bytes (`nativeMultiSigTag`,
    * `Allegra/Scripts.hs:336`; `hashScript`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:603-608`).
    */
  lazy val hash: Bytes = Blake2b.hash224.hash(NativeScriptRep.nativeTag ++ raw)

  override def equals(that: Any): Boolean = that match
    case o: NativeScriptRep[?] => raw == o.raw && script == o.script
    case _                     => false
  override def hashCode(): Int = raw.hashCode

object NativeScriptRep:
  private val nativeTag: Bytes = Bytes(Vector(0.toByte))

  given [F <: NativeForm]: CanEqual[NativeScriptRep[F], NativeScriptRep[F]] = CanEqual.derived

  /** A native script of language `F` from its encoding, read by the era decoder's native-script
    * reader ([[FlatNativeScript.decode]]), each level given the bytes it was read from. The walk
    * keeps its stack on the heap, as the decoder's does.
    *
    * The script owns one copy of its bytes, and each level's bytes are a window of that copy: a
    * script kept from a transaction or a block does not keep the rest of it alive, and the levels
    * of a deep script add no copies.
    */
  def decode[F <: NativeForm](n: Node)(using
      f: NativeForm.Of[F],
      strings: ByteStrings
  ): Either[FlatNativeScript.DecodeError, NativeScriptRep[F]] =
    FlatNativeScript
      .decode(n, f.form)
      .flatMap(script =>
        TreeFold.foldE[(Node, FlatNativeScript), NativeScriptRep[F], FlatNativeScript.DecodeError](
          (n, script)
        )(level(Owned(n.original)))
      )

  /** The root's bytes copied once, and each level's bytes as a window of the copy: a level's bytes
    * are a window of the same input as the root's, at an offset from the root's start.
    */
  private final class Owned(root: Bytes):
    private val copy = root.compact
    def of(level: Bytes): Bytes =
      if level.unsafeArray eq root.unsafeArray then
        val from = level.unsafeOffset - root.unsafeOffset
        copy.slice(from, from + level.size)
      else level.compact

  // A level of a script the decoder has read: its sub-scripts are the items of the list it was read
  // from (index 1 of `AllOf` and `AnyOf`, 2 of `NOfK`), one per sub-script, in order, so the items
  // and the decoded sub-scripts pair one to one.
  private def level[F <: NativeForm](owned: Owned)(at: (Node, FlatNativeScript)): Either[
    FlatNativeScript.DecodeError,
    TreeFold.Step[(Node, FlatNativeScript), NativeScriptRep[F], FlatNativeScript.DecodeError]
  ] =
    val (node, script) = at
    def branch(index: Int, subs: Vector[FlatNativeScript]) =
      items(node, index).map(nodes =>
        TreeFold.Step.Branch(
          nodes.zip(subs),
          (reps: Vector[NativeScriptRep[F]]) =>
            Right(new NativeScriptRep[F](owned.of(node.original), script, reps))
        )
      )
    script match
      case FlatNativeScript.AllOf(subs)   => branch(1, subs)
      case FlatNativeScript.AnyOf(subs)   => branch(1, subs)
      case FlatNativeScript.NOfK(_, subs) => branch(2, subs)
      case FlatNativeScript.Sig(_) | FlatNativeScript.InvalidBefore(_) |
          FlatNativeScript.InvalidHereafter(_) =>
        Right(
          TreeFold.Step.Leaf(new NativeScriptRep[F](owned.of(node.original), script, Vector.empty))
        )

  private def items(
      node: Node,
      index: Int
  ): Either[FlatNativeScript.DecodeError, Vector[Node]] =
    CborRead
      .array(node, DecodeField.NativeScript)
      .flatMap(
        _.lift(index)
          .toRight(
            LedgerDecodeError.MissingField(DecodeField.NativeScript, DecodeSite.Index(index))
          )
      )
      .flatMap(CborRead.array(_, DecodeField.Scripts))
      .left
      .map(FlatNativeScript.DecodeError.Malformed(_))

/** Shelley's native script language, without the time locks (`MultiSig era`). */
type MultiSig = NativeScriptRep[NativeForm.MultiSig]

/** The native script language from Allegra (`Timelock era`). */
type Timelock = NativeScriptRep[NativeForm.Timelock]
