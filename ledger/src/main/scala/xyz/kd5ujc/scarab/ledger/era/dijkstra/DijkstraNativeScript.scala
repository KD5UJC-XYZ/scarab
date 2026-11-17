// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node, TreeFold}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{CborRead, Credential, DecodeField, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.V12
import xyz.kd5ujc.scarab.ledger.era.EraTxError
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTimelock

/** Dijkstra's native script: `DijkstraNativeScript era`, a `MemoBytes` of its raw form at every
  * level (`newtype DijkstraNativeScript era = MkDijkstraNativeScript (MemoBytes
  * (DijkstraNativeScriptRaw era))`, cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:327`). It is not the time-lock
  * language of Allegra to Conway: its raw form adds a seventh constructor, the guard
  * (`DijkstraRequireGuard (Credential Guard)`, `Scripts.hs:270-277`), so it is a type of its own,
  * and no Conway time lock can hold a guard.
  *
  * What a level memoizes depends on how it was made, as in the reference
  * ([[DijkstraNativeScript.Memo]]): a script read from bytes memoizes them, but its sub-scripts are
  * read with no bytes (`decodeNoBytesDijkstraNativeScript`, `MemoBytes t mempty def`,
  * `Scripts.hs:321-325`), so a decoded sub-script's original bytes are empty and its memoized hash
  * is the default; a translated script is built through the constructors, each level memoizing its
  * own new encoding (`mkMemoizedEra`). Equal, and ordered, by the memoized hash (`instance Eq
  * (DijkstraNativeScript era)`, `Scripts.hs:331-337`): two scripts with bytes by their bytes, any
  * two decoded sub-scripts equal, and a decoded sub-script unequal to a script with bytes. Made
  * only by [[DijkstraNativeScript.decode]] and [[DijkstraNativeScript.translate]].
  *
  * @param memo
  *   what the level memoizes: its bytes, or none for a decoded sub-script
  * @param form
  *   the constructor and its fields, with the sub-scripts
  */
final class DijkstraNativeScript private (
    val memo: DijkstraNativeScript.Memo,
    val form: DijkstraNativeScript.Form
):
  /** `originalBytes`: the memoized bytes, empty for a decoded sub-script. */
  def raw: Bytes = memo match
    case DijkstraNativeScript.Memo.Of(bytes) => bytes
    case DijkstraNativeScript.Memo.NoBytes   => Bytes.empty

  /** `hashScript`: blake2b-224 of the native tag `0` and the original bytes
    * (`alonzoScriptPrefixTag`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:574-580`,
    * cardano-ledger 9df9f98c), of the tag alone for a decoded sub-script.
    */
  lazy val hash: Bytes = Blake2b.hash224.hash(DijkstraNativeScript.nativeTag ++ raw)

  /** `getRequireSignature` (`getDijkstraRequireSignature`, `Scripts.hs:620-622`, cardano-ledger
    * 9df9f98c).
    */
  def getRequireSignature: Option[Bytes] = form match
    case DijkstraNativeScript.Form.RequireSignature(h) => Some(h)
    case DijkstraNativeScript.Form.RequireAllOf(_) | DijkstraNativeScript.Form.RequireAnyOf(_) |
        DijkstraNativeScript.Form.RequireMOf(_, _) | DijkstraNativeScript.Form.TimeStart(_) |
        DijkstraNativeScript.Form.TimeExpire(_) | DijkstraNativeScript.Form.RequireGuard(_) =>
      None

  /** `getRequireAllOf` (`getDijkstraRequireAllOf`, `Scripts.hs:628-630`, cardano-ledger 9df9f98c).
    */
  def getRequireAllOf: Option[Vector[DijkstraNativeScript]] = form match
    case DijkstraNativeScript.Form.RequireAllOf(ss) => Some(ss)
    case DijkstraNativeScript.Form.RequireSignature(_) | DijkstraNativeScript.Form.RequireAnyOf(_) |
        DijkstraNativeScript.Form.RequireMOf(_, _) | DijkstraNativeScript.Form.TimeStart(_) |
        DijkstraNativeScript.Form.TimeExpire(_) | DijkstraNativeScript.Form.RequireGuard(_) =>
      None

  /** `getRequireAnyOf` (`getDijkstraRequireAnyOf`, `Scripts.hs:636-638`, cardano-ledger 9df9f98c).
    */
  def getRequireAnyOf: Option[Vector[DijkstraNativeScript]] = form match
    case DijkstraNativeScript.Form.RequireAnyOf(ss) => Some(ss)
    case DijkstraNativeScript.Form.RequireSignature(_) | DijkstraNativeScript.Form.RequireAllOf(_) |
        DijkstraNativeScript.Form.RequireMOf(_, _) | DijkstraNativeScript.Form.TimeStart(_) |
        DijkstraNativeScript.Form.TimeExpire(_) | DijkstraNativeScript.Form.RequireGuard(_) =>
      None

  /** `getRequireMOf` (`getDijkstraRequireMOf`, `Scripts.hs:644-647`, cardano-ledger 9df9f98c): the
    * count is an `Int`, a 64-bit signed value, negative allowed.
    */
  def getRequireMOf: Option[(BigInt, Vector[DijkstraNativeScript])] = form match
    case DijkstraNativeScript.Form.RequireMOf(m, ss) => Some((BigInt(m), ss))
    case DijkstraNativeScript.Form.RequireSignature(_) | DijkstraNativeScript.Form.RequireAllOf(_) |
        DijkstraNativeScript.Form.RequireAnyOf(_) | DijkstraNativeScript.Form.TimeStart(_) |
        DijkstraNativeScript.Form.TimeExpire(_) | DijkstraNativeScript.Form.RequireGuard(_) =>
      None

  /** `getTimeStart` (`getDijkstraTimeStart`, `Scripts.hs:652-654`, cardano-ledger 9df9f98c): a
    * `Word64` slot.
    */
  def getTimeStart: Option[BigInt] = form match
    case DijkstraNativeScript.Form.TimeStart(slot) => Some(slot)
    case DijkstraNativeScript.Form.RequireSignature(_) | DijkstraNativeScript.Form.RequireAllOf(_) |
        DijkstraNativeScript.Form.RequireAnyOf(_) | DijkstraNativeScript.Form.RequireMOf(_, _) |
        DijkstraNativeScript.Form.TimeExpire(_) | DijkstraNativeScript.Form.RequireGuard(_) =>
      None

  /** `getTimeExpire` (`getDijkstraTimeExpire`, `Scripts.hs:659-661`, cardano-ledger 9df9f98c): a
    * `Word64` slot.
    */
  def getTimeExpire: Option[BigInt] = form match
    case DijkstraNativeScript.Form.TimeExpire(slot) => Some(slot)
    case DijkstraNativeScript.Form.RequireSignature(_) | DijkstraNativeScript.Form.RequireAllOf(_) |
        DijkstraNativeScript.Form.RequireAnyOf(_) | DijkstraNativeScript.Form.RequireMOf(_, _) |
        DijkstraNativeScript.Form.TimeStart(_) | DijkstraNativeScript.Form.RequireGuard(_) =>
      None

  /** `getRequireGuard` (`getDijkstraRequireGuard`, `Scripts.hs:666-668`, cardano-ledger 9df9f98c),
    * which Dijkstra's script class answers with
    * ([[xyz.kd5ujc.scarab.ledger.era.DijkstraEraScript]]).
    */
  def getRequireGuard: Option[Credential] = form match
    case DijkstraNativeScript.Form.RequireGuard(c) => Some(c)
    case DijkstraNativeScript.Form.RequireSignature(_) | DijkstraNativeScript.Form.RequireAllOf(_) |
        DijkstraNativeScript.Form.RequireAnyOf(_) | DijkstraNativeScript.Form.RequireMOf(_, _) |
        DijkstraNativeScript.Form.TimeStart(_) | DijkstraNativeScript.Form.TimeExpire(_) =>
      None

  /** The script as the flat output keeps a native reference script, the tree of the time-lock
    * language ([[xyz.kd5ujc.scarab.ledger.NativeScript]]), when it holds no guard: each of
    * Dijkstra's other forms is one of that language's, field for field. A script with a guard at
    * any level has none, as the time-lock language has no guard; its bytes, which the output keeps,
    * hold it (#54). The nesting is unbounded, so the walk keeps its stack on the heap
    * ([[TreeFold]]).
    */
  def timelock: Option[xyz.kd5ujc.scarab.ledger.NativeScript] =
    import xyz.kd5ujc.scarab.ledger.NativeScript as T
    import DijkstraNativeScript.Form
    type Step = TreeFold.Step[DijkstraNativeScript, T, Unit]
    def branch(ss: Vector[DijkstraNativeScript])(make: Vector[T] => T): Either[Unit, Step] =
      Right(TreeFold.Step.Branch(ss, ts => Right(make(ts))))
    TreeFold
      .foldE[DijkstraNativeScript, T, Unit](this)(s =>
        s.form match
          case Form.RequireSignature(h) => Right(TreeFold.Step.Leaf(T.Sig(h)))
          case Form.RequireAllOf(ss)    => branch(ss)(T.AllOf(_))
          case Form.RequireAnyOf(ss)    => branch(ss)(T.AnyOf(_))
          case Form.RequireMOf(m, ss)   => branch(ss)(T.NOfK(BigInt(m), _))
          case Form.TimeStart(slot)     => Right(TreeFold.Step.Leaf(T.InvalidBefore(slot)))
          case Form.TimeExpire(slot)    => Right(TreeFold.Step.Leaf(T.InvalidHereafter(slot)))
          case Form.RequireGuard(_)     => Left(())
      )
      .toOption

  /** `evalDijkstraNativeScript` (`Scripts.hs:594-615`, cardano-ledger 9df9f98c): whether the script
    * holds for a transaction with the key hashes `signers` of its key witnesses, the validity
    * interval from `start` to `end` and the guards `guards` of its body
    * (`validateDijkstraNativeScript`, `Dijkstra/Tx.hs:434-437`). A validity start holds when the
    * interval starts at or after it (`lteNegInfty`) and a validity end when the interval ends at or
    * before it (`ltePosInfty`), neither for an unbounded side; `m` of a list holds when at least
    * `m` of its scripts hold, so a non-positive `m` always does (`isValidMOf`); a guard holds when
    * it is among the body's guards. The reference stops at the first settled script, which changes
    * no value. The nesting is unbounded, so the walk keeps its stack on the heap ([[TreeFold]]).
    */
  def eval(
      signers: Set[Bytes],
      start: Option[BigInt],
      end: Option[BigInt],
      guards: Set[Credential]
  ): Boolean =
    import DijkstraNativeScript.Form
    type Step = TreeFold.Step[DijkstraNativeScript, Boolean, Nothing]
    def leaf(holds: Boolean): Step = TreeFold.Step.Leaf(holds)
    def branch(ss: Vector[DijkstraNativeScript])(holds: Vector[Boolean] => Boolean): Step =
      TreeFold.Step.Branch(ss, vs => Right(holds(vs)))
    TreeFold.fold[DijkstraNativeScript, Boolean](this)(s =>
      s.form match
        case Form.RequireSignature(h) => leaf(signers.contains(h))
        case Form.RequireAllOf(ss)    => branch(ss)(_.forall(identity))
        case Form.RequireAnyOf(ss)    => branch(ss)(_.exists(identity))
        case Form.RequireMOf(m, ss)   => branch(ss)(vs => m <= vs.count(identity).toLong)
        case Form.TimeStart(lock)     => leaf(start.exists(lock <= _))
        case Form.TimeExpire(lock)    => leaf(end.exists(_ <= lock))
        case Form.RequireGuard(c)     => leaf(guards.contains(c))
    )

  // the memoized hash compared: the bytes', or the default for no bytes, which no bytes hash to
  override def equals(that: Any): Boolean = that match
    case o: DijkstraNativeScript => memo == o.memo
    case _                       => false
  override def hashCode(): Int = memo.hashCode

  // the bytes, not the tree: a script nests as deep as a block is long
  override def toString: String = memo match
    case DijkstraNativeScript.Memo.Of(bytes) => s"DijkstraNativeScript(${bytes.size} bytes)"
    case DijkstraNativeScript.Memo.NoBytes   => "DijkstraNativeScript(no bytes)"

object DijkstraNativeScript:
  private val nativeTag: Bytes = Bytes(Vector(0.toByte))

  /** What a level of a script memoizes (`MemoBytes`, `libs/cardano-ledger-core/src/Cardano/Ledger/
    * MemoBytes/Internal.hs`, cardano-ledger 9df9f98c): its bytes, or, for a sub-script read from
    * bytes, none (`MemoBytes t mempty def`, `Scripts.hs:321-325`).
    */
  enum Memo derives CanEqual:
    case Of(bytes: Bytes)
    case NoBytes

  /** `DijkstraNativeScriptRaw` (`Scripts.hs:270-277`, cardano-ledger 9df9f98c). */
  enum Form:
    case RequireSignature(keyHash: Bytes)
    case RequireAllOf(scripts: Vector[DijkstraNativeScript])
    case RequireAnyOf(scripts: Vector[DijkstraNativeScript])

    /** `DijkstraRequireMOf !Int`: 64-bit signed. */
    case RequireMOf(required: Long, scripts: Vector[DijkstraNativeScript])
    case TimeStart(slot: BigInt)
    case TimeExpire(slot: BigInt)
    case RequireGuard(credential: Credential)

  given CanEqual[DijkstraNativeScript, DijkstraNativeScript] = CanEqual.derived

  /** Dijkstra's native-script decoder: `DecCBOR (Annotator (DijkstraNativeScriptRaw era))`
    * (`Scripts.hs:295-319`, cardano-ledger 9df9f98c) at Dijkstra's decoder version 12, as the block
    * decoder of the Leios build reads it
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.native]]): `decodeRecordSum` of a
    * signature `[0, keyhash]`, all of `[1, [* script]]`, any of `[2, [* script]]`,
    * `[3, int, [* script]]`, a validity start `[4, slot]` or end `[5, slot]`, and a guard
    * `[6, credential]`; the nested scripts a definite or an indefinite list. The nesting is
    * unbounded, so the walk keeps its stack on the heap ([[TreeFold]], ADR 0011).
    */
  def decode(n: Node): Either[EraTxError, DijkstraNativeScript] =
    read(n).leftMap(EraTxError.Decode(_))

  private[dijkstra] def read(n: Node): Either[LedgerDecodeError, DijkstraNativeScript] =
    readWith(Kept.NoBytes)(n)

  /** What a level read from bytes keeps: its bytes, or none. */
  private enum Kept:
    case Bytes
    case NoBytes

  /** The script of `n`, its root memoizing its bytes and its sub-scripts what `subScripts` says. */
  private def readWith(subScripts: Kept)(
      n: Node
  ): Either[LedgerDecodeError, DijkstraNativeScript] =
    val owned = Owned(n.original)
    TreeFold.foldE[(Node, Kept), DijkstraNativeScript, LedgerDecodeError]((n, Kept.Bytes))(
      level(owned, subScripts)
    )

  /** A Conway native script carried into Dijkstra (`upgradeTimelock`, `Scripts.hs:436-444`,
    * cardano-ledger 9df9f98c): rebuilt through the constructors, each of which memoizes a fresh
    * encoding at Dijkstra's version (`mkMemoizedEra`), so a script read in another form has new
    * bytes and a new hash. The encoding is the one the Leios build's translation writes
    * ([[xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.upgradeTimelock]]), read back here so each
    * level has its bytes; Dijkstra reads every Conway script, so a refusal is a defect of the
    * encoder. Every level memoizes its encoding, the sub-scripts too, as the constructors that
    * build it do; each is a window of the root's, which embeds them.
    */
  def translate(t: ConwayTimelock): Either[EraTxError, DijkstraNativeScript] =
    CborRead
      .decode(xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraScript.upgradeTimelock(t.rep.script))
      .flatMap(readWith(Kept.Bytes))
      .leftMap(EraTxError.TranslationReadBack(_))

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

  private def level(owned: Owned, subScripts: Kept)(
      at: (Node, Kept)
  ): Either[
    LedgerDecodeError,
    TreeFold.Step[(Node, Kept), DijkstraNativeScript, LedgerDecodeError]
  ] =
    import V12.given
    val (node, kept) = at
    val memo = kept match
      case Kept.Bytes   => Memo.Of(owned.of(node.original))
      case Kept.NoBytes => Memo.NoBytes
    def leaf(form: Form) = TreeFold.Step.Leaf(new DijkstraNativeScript(memo, form))
    def branch(subs: Vector[Node])(form: Vector[DijkstraNativeScript] => Form) =
      TreeFold.Step.Branch[(Node, Kept), DijkstraNativeScript, LedgerDecodeError](
        subs.map((_, subScripts)),
        scripts => Right(new DijkstraNativeScript(memo, form(scripts)))
      )
    V12.sum[TreeFold.Step[(Node, Kept), DijkstraNativeScript, LedgerDecodeError]](
      node,
      DecodeField.NativeScript
    ) {
      case t if t == 0 =>
        (
          2,
          xs =>
            V12
              .bytesN(xs(1), 28, DecodeField.ScriptKeyHash)
              .map(h => leaf(Form.RequireSignature(h)))
        )
      case t if t == 1 =>
        (2, xs => V12.list(xs(1), DecodeField.Scripts).map(branch(_)(Form.RequireAllOf(_))))
      case t if t == 2 =>
        (2, xs => V12.list(xs(1), DecodeField.Scripts).map(branch(_)(Form.RequireAnyOf(_))))
      case t if t == 3 =>
        (
          3,
          xs =>
            for
              m <- V12.int64(xs(1), DecodeField.ScriptN)
              subs <- V12.list(xs(2), DecodeField.Scripts)
            yield branch(subs)(Form.RequireMOf(m, _))
        )
      case t if t == 4 =>
        (2, xs => V12.word64(xs(1), DecodeField.InvalidBefore).map(s => leaf(Form.TimeStart(s))))
      case t if t == 5 =>
        (
          2,
          xs => V12.word64(xs(1), DecodeField.InvalidHereafter).map(s => leaf(Form.TimeExpire(s)))
        )
      case t if t == 6 =>
        (
          2,
          xs =>
            Credential.decode(xs(1), DecodeField.ScriptKeyHash).map(c => leaf(Form.RequireGuard(c)))
        )
    }
