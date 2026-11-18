// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** Byron's four kinds of `MempoolPayload`, each read out of a recorded block, and forms of them
  * that the reference's decoders read or refuse. A Byron main block is
  * `[1, [header, body, extra]]`, its header's consensus data
  * `[slot, leader key, difficulty, [2, [certificate, signature]]]` and its body
  * `[transactions, ssc, certificates, [proposals, votes]]`.
  *
  *   - `fixtures/mainnet/byron-tx-blocks.hex`: the transaction of the block at slot 3,313, the
  *     redemption of an AVVM balance under a redeem witness, and that of the block at epoch 20,
  *     slot 1, under a verification-key witness;
  *   - `fixtures/preprod/blocks.hex`: the delegation certificate in the header of the first main
  *     block, and the proposal for protocol 1.0.0 and its first vote in the block of epoch 0, slot
  *     2,163 (no Byron block body of either network in the fixtures holds a certificate).
  */
object ByronPayloads:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => sys.error(e.toString), identity)

  private def blocks(path: String): Vector[Node] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map(l =>
        Cbor.decode(hex(l.trim), Cbor.Limits.Unbounded).fold(e => sys.error(e.message), identity)
      )

  def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => sys.error(s"not a list: $other")

  private def bytesOf(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case other               => sys.error(s"not a byte string: $other")

  /** The header and body of a main block `[1, [header, body, extra]]`. */
  private def main(block: Node): (Node, Node) =
    items(block) match
      case Vector(_, inner) =>
        items(inner) match
          case Vector(header, body, _) => (header, body)
          case other                   => sys.error(s"not a main block: ${other.size} items")
      case other => sys.error(s"not a block: ${other.size} items")

  private val mainnet = blocks("fixtures/mainnet/byron-tx-blocks.hex")
  private val preprod = blocks("fixtures/preprod/blocks.hex")

  private def transactions(block: Node): Vector[Node] = items(items(main(block)._2)(0))

  /** The transaction (`TxAux`) of mainnet's block at slot 3,313, under a redeem witness. */
  val redeemTx: Node = transactions(mainnet(0))(0)

  /** The transaction (`TxAux`) of mainnet's block at epoch 20, slot 1, under a verification-key
    * witness.
    */
  val keyTx: Node = transactions(mainnet(1))(0)

  /** The delegation certificate of the header of preprod's first main block. */
  val certificate: Node =
    val consensus = items(main(preprod(1))._1)(3)
    items(items(items(consensus)(3))(1))(0)

  private val update = items(items(main(preprod(2))._2)(3))

  /** Preprod's proposal for protocol 1.0.0. */
  val proposal: Node = items(update(0))(0)

  /** The first vote for it. */
  val vote: Node = items(update(1))(0)

  /** `MempoolPayload` of the kind `constructor` around `inner`: `[constructor, inner]`. */
  def payload(constructor: Int, inner: Bytes): Bytes = hex(f"82$constructor%02x") ++ inner

  /** A node-to-client `GenTx` of Byron: `[0, payload]`. */
  def genTx(payload: Bytes): Bytes = hex("8200") ++ payload

  /** The submission of `inner` as the payload of kind `constructor`. */
  def submitted(constructor: Int, inner: Bytes): Bytes = genTx(payload(constructor, inner))

  /** The valid payloads, one of each kind, by name. */
  val valid: Vector[(String, Bytes)] = Vector(
    "transaction (redeem witness)" -> payload(0, redeemTx.original),
    "transaction (key witness)" -> payload(0, keyTx.original),
    "delegation certificate" -> payload(1, certificate.original),
    "update proposal" -> payload(2, proposal.original),
    "update vote" -> payload(3, vote.original)
  )

  /** A definite list of `xs`, its head in its shortest form (fewer than 24 items). */
  def list(xs: Bytes*): Bytes =
    xs.foldLeft(Bytes(Vector((0x80 | xs.size).toByte)))(_ ++ _)

  /** A definite byte string of `b`, its length in its shortest form. */
  def bstr(b: Bytes): Bytes = Bytes(TxKit.bstr(b))

  /** The byte string `n` holds with its length written in two bytes (`59 00 40` for 64): a head
    * that is not the shortest for a length below 256.
    */
  def wide(n: Node): Bytes =
    val b = bytesOf(n)
    Bytes(Vector(0x59.toByte, (b.size >> 8).toByte, b.size.toByte)) ++ b

  /** The tag, key and signature of each witness of `TxAux` `[tx, witnesses]`: a witness is `[tag,
    * #6.24(bytes .cbor [key, signature])]`.
    */
  def witnesses(aux: Node): Vector[(Node, Node, Node)] =
    items(items(aux)(1)).map { w =>
      items(w) match
        case Vector(tag, wrapped) =>
          wrapped.value match
            case Value.Tag(t, inner) if t == 24 =>
              val pair = Cbor
                .decode(bytesOf(inner), Cbor.Limits.Unbounded)
                .fold(e => sys.error(e.message), identity)
              items(pair) match
                case Vector(k, s) => (tag, k, s)
                case other        => sys.error(s"a witness of ${other.size} items")
            case other => sys.error(s"not tag 24: $other")
        case other => sys.error(s"a witness of ${other.size} items")
    }

  /** `TxAux` `[tx, witnesses]` with each witness's key and signature as `key` and `signature` write
    * them.
    */
  def rewitnessed(aux: Node)(key: Node => Bytes, signature: Node => Bytes): Bytes =
    val rebuilt = witnesses(aux).map((tag, k, s) =>
      list(tag.original, hex("d818") ++ bstr(list(key(k), signature(s))))
    )
    list(items(aux)(0).original, list(rebuilt*))

  /** A list of `n`'s items with the item at `index` written as `replaced`. */
  def replacing(n: Node, index: Int, replaced: Bytes): Bytes =
    list(items(n).zipWithIndex.map((x, i) => if i == index then replaced else x.original)*)

  /** A list `depth` levels deep around an empty list: `81 81 ... 80`. */
  def nested(depth: Int): Bytes =
    Bytes(Vector.fill(depth)(0x81.toByte) :+ 0x80.toByte)
