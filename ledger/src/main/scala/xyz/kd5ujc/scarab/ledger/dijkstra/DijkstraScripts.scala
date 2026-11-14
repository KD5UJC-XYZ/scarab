// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Node, TreeFold}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Credential, DecodeField, LedgerDecodeError, NativeScript}

import V12.{Result, given}

/** A Plutus language of the Dijkstra era, with the tag that prefixes its scripts' hashes
  * (`plutusLanguageTag`) and the protocol version from which a block may carry it (`guardPlutus`,
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Language.hs:643-651`, cardano-ledger
  * 9df9f98c): every one is allowed at version 12, Plutus V4 from 12 on.
  */
enum DijkstraLanguage(val hashTag: Int):
  case PlutusV1 extends DijkstraLanguage(1)
  case PlutusV2 extends DijkstraLanguage(2)
  case PlutusV3 extends DijkstraLanguage(3)
  case PlutusV4 extends DijkstraLanguage(4)

/** A script of the Dijkstra era, with the bytes its hash is taken over. */
enum DijkstraScript:
  /** A native script, kept as the bytes it was read from (`DijkstraNativeScript` memoizes them). */
  case Native(raw: Bytes)

  /** A Plutus script: its language and the bytes of the program (`PlutusBinary`, the content of the
    * byte string, its chunks joined).
    */
  case Plutus(language: DijkstraLanguage, code: Bytes)

  /** `hashScript`: Blake2b-224 of the language's prefix and the script's original bytes
    * (`alonzoScriptPrefixTag`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:574-580`).
    */
  def hash: Bytes = this match
    case Native(raw)        => Blake2b.hash224.hash(Bytes.wrap(Array(0.toByte)) ++ raw)
    case Plutus(lang, code) => Blake2b.hash224.hash(Bytes.wrap(Array(lang.hashTag.toByte)) ++ code)

object DijkstraScript:
  /** `DijkstraNativeScriptRaw`
    * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:295-319`, 9df9f98c):
    * `decodeRecordSum` of a signature `[0, keyhash]`, all of `[1, [* script]]`, any of
    * `[2, [* script]]`, `[3, int, [* script]]`, a validity start `[4, slot]` or end `[5, slot]`,
    * and a guard `[6, credential]`; the nested scripts are read by `decodeStrictSeq`, a definite or
    * an indefinite list. `m` is an `Int`. Nesting is unbounded, so the script is read on the heap
    * ([[TreeFold]], ADR 0011). The script is kept as its original bytes, which its hash is over.
    */
  def native(n: Node): Result[DijkstraScript] =
    def leaf(read: Result[?]): Result[Vector[Node]] = read.as(Vector.empty)
    TreeFold
      .foldE[Node, Unit, LedgerDecodeError](n) { node =>
        // each branch gives the nested scripts, read after the script's own fields
        V12
          .sum[Vector[Node]](node, DecodeField.NativeScript) {
            case t if t == 0 => (2, xs => leaf(V12.bytesN(xs(1), 28, DecodeField.ScriptKeyHash)))
            case t if t == 1 || t == 2 => (2, xs => V12.list(xs(1), DecodeField.Scripts))
            case t if t == 3 =>
              (
                3,
                xs => V12.int64(xs(1), DecodeField.ScriptN) *> V12.list(xs(2), DecodeField.Scripts)
              )
            case t if t == 4 => (2, xs => leaf(V12.word64(xs(1), DecodeField.InvalidBefore)))
            case t if t == 5 => (2, xs => leaf(V12.word64(xs(1), DecodeField.InvalidHereafter)))
            case t if t == 6 => (2, xs => leaf(Credential.decode(xs(1), DecodeField.ScriptKeyHash)))
          }
          .map(children =>
            if children.isEmpty then TreeFold.Step.Leaf(())
            else TreeFold.Step.Branch(children, _ => Right(()))
          )
      }
      .as(Native(n.original))

  /** A Plutus script of `language` as a witness set or auxiliary data holds it:
    * `decodePlutusScript` reads `PlutusBinary`, a byte string (`decodeByteArray`, chunked from
    * version 12), and `mkPlutusScript` takes every Dijkstra language (`Alonzo/Scripts.hs:695-702`,
    * `Dijkstra/Scripts.hs:487-492`). The program is not deserialised when a block is decoded.
    */
  def plutus(n: Node, language: DijkstraLanguage): Result[DijkstraScript] =
    V12.bytes(n, DecodeField.PlutusScript).map(code => Plutus(language, code))

  /** `AlonzoScript` from version 12 (`Alonzo/Scripts.hs:727-764`): `decodeRecordSum` of a native
    * script `[0, script]` or a Plutus script `[1..4, bytes]` of PlutusV1 to PlutusV4. A reference
    * script of an output is one (`decodeCIC "Script"`).
    */
  def script(n: Node): Result[DijkstraScript] =
    V12.sum(n, DecodeField.Script) {
      case t if t == 0 => (2, xs => native(xs(1)))
      case t if t == 1 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV1))
      case t if t == 2 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV2))
      case t if t == 3 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV3))
      case t if t == 4 => (2, xs => plutus(xs(1), DijkstraLanguage.PlutusV4))
    }

  /** `upgradeTimelock` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:436-444`,
    * 9df9f98c): a Conway native script as Dijkstra's, rebuilt through the pattern synonyms, whose
    * constructors memoize a fresh encoding at Dijkstra's version (`mkMemoizedEra`,
    * `MemoBytes/Internal.hs:299-300`; `Scripts.hs:617-664`) rather than the bytes the script was
    * read from. The encoding is `EncCBOR DijkstraNativeScriptRaw` (`Scripts.hs:284-293`): each
    * constructor a definite array of its tag and fields, `m` an `Int` and a slot a `Word64` in
    * their shortest heads, and the nested scripts a `StrictSeq` (`encodeStrictSeq`,
    * `Binary/Encoding/Encoder.hs:458-467, 512-518`), definite up to 23 elements and indefinite
    * above, each in its own new encoding. Its hash, over these bytes, differs from the Conway one
    * when the script was read in another form. Nesting is unbounded, so the walk keeps its stack on
    * the heap (ADR 0011).
    */
  def upgradeTimelock(script: NativeScript): Bytes =
    enum Item:
      case Script(script: NativeScript)
      case Break
    def head(major: Int, n: BigInt): Array[Byte] =
      val m = major << 5
      def be(bytes: Int): Array[Byte] =
        n.toByteArray.reverse.padTo(bytes, 0.toByte).take(bytes).reverse
      if n < 24 then Array((m | n.toInt).toByte)
      else if n < 0x100 then Array((m | 24).toByte) ++ be(1)
      else if n < 0x10000 then Array((m | 25).toByte) ++ be(2)
      else if n <= 0xffffffffL then Array((m | 26).toByte) ++ be(4)
      else Array((m | 27).toByte) ++ be(8)
    // an array of `length` items whose first is the constructor's tag
    def start(length: Int, tag: Int): Array[Byte] = Array(length.toByte, tag.toByte)
    def int(n: BigInt): Array[Byte] = if n >= 0 then head(0, n) else head(1, -1 - n)
    def nested(ss: Vector[NativeScript]): (Array[Byte], List[Item]) =
      val items = ss.toList.map(Item.Script(_))
      if ss.size <= 23 then (head(4, ss.size), items) else (Array(0x9f.toByte), items :+ Item.Break)
    @tailrec def go(stack: List[Item], out: List[Array[Byte]]): List[Array[Byte]] = stack match
      case Nil                => out
      case Item.Break :: rest => go(rest, Array(0xff.toByte) :: out)
      case Item.Script(s) :: rest =>
        s match
          case NativeScript.Sig(h) =>
            go(rest, (start(0x82, 0) ++ head(2, h.size) ++ h.toArray) :: out)
          case NativeScript.AllOf(ss) =>
            val (open, items) = nested(ss)
            go(items ++ rest, (start(0x82, 1) ++ open) :: out)
          case NativeScript.AnyOf(ss) =>
            val (open, items) = nested(ss)
            go(items ++ rest, (start(0x82, 2) ++ open) :: out)
          case NativeScript.NOfK(m, ss) =>
            val (open, items) = nested(ss)
            go(items ++ rest, (start(0x83, 3) ++ int(m) ++ open) :: out)
          case NativeScript.InvalidBefore(slot) =>
            go(rest, (start(0x82, 4) ++ head(0, slot)) :: out)
          case NativeScript.InvalidHereafter(slot) =>
            go(rest, (start(0x82, 5) ++ head(0, slot)) :: out)
    Bytes.wrap(go(List(Item.Script(script)), Nil).reverse.toArray.flatten)
end DijkstraScript
