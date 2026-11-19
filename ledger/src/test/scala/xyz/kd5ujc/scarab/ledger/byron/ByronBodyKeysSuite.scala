// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Block,
  BlockTransport,
  BlockTransportError,
  DecodeField,
  Header,
  LedgerDecodeError
}
import xyz.kd5ujc.scarab.ledger.DecodeSite.Qualifier

/** The verification keys of a fetched Byron block's body are read as the reference reads them, with
  * `decodeBytesCanonical` (`decCBORXPub`, cardano-ledger f649f975
  * `eras/byron/crypto/src/Cardano/Crypto/Signing/VerificationKey.hs:96-97`): the block decoder
  * (`decodeByronBlock`, ouroboros-consensus 82ecba32
  * `ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Ledger/Serialisation.hs:190-198`)
  * reads the body (`Block/Body.hs:81-88`), whose transaction witnesses
  * (`UTxO/TxWitness.hs:118-128`), delegation certificates (`Delegation/Certificate.hs:209-218`),
  * update proposal (`Update/Proposal.hs:157-173`) and votes (`Update/Vote.hs:199-209`) read each
  * key with the `DecCBOR VerificationKey` instance (#1219).
  *
  * Each case writes one key of a recorded block with its length in two bytes (`59 00 40`, where the
  * shortest form is `58 40`) and puts the block to [[BlockTransport.check]], which a block fetched
  * from a peer goes through before its body proof and the ledger: the body does not decode. The
  * blocks are mainnet's of epoch 20, slot 1 (`fixtures/mainnet/byron-tx-blocks.hex`), whose
  * transaction has a verification-key witness, and preprod's of epoch 0, slot 2,163
  * (`fixtures/preprod/blocks.hex`), whose body holds the proposal for protocol 1.0.0 and seven
  * votes. No Byron body in the fixtures holds a delegation certificate, so the certificate in the
  * header of preprod's first main block is put in that block's body: its body proof then differs,
  * and the block is refused by the proof alone while its keys are in their shortest form.
  */
class ByronBodyKeysSuite extends FunSuite:
  private val epochSlots = BigInt(21600)

  private def hex(s: String): Bytes = Bytes.fromHex(s.trim).fold(e => fail(e.toString), identity)

  private def line(path: String, index: Int): Bytes =
    hex(Files.readAllLines(Path.of(path)).get(index))

  private def decode(raw: Bytes): Node =
    Cbor.decode(raw, Cbor.Limits.Unbounded).fold(e => fail(e.message), identity)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"array expected: $other")

  private def content(n: Node): Bytes = n.value match
    case Value.ByteString(b) => b
    case other               => fail(s"byte string expected: $other")

  /** The header, body and extra data of a main block `[1, [header, body, extra]]`. */
  private def parts(raw: Bytes): Vector[Node] = items(items(decode(raw))(1))

  /** `raw` with `item`, one of its own items, written as `value`. */
  private def splice(raw: Bytes, item: Node, value: Bytes): Bytes =
    raw.take(item.offset) ++ value ++ raw.drop(item.offset + item.original.size)

  /** The byte string `n` holds, its length written in two bytes: `59 00 40` for a key. */
  private def wide(n: Node): Bytes =
    val b = content(n)
    Bytes(Vector(0x59.toByte, (b.size >> 8).toByte, b.size.toByte)) ++ b

  private def list(xs: Bytes*): Bytes =
    xs.foldLeft(Bytes(Vector((0x80 | xs.size).toByte)))(_ ++ _)

  private def header(raw: Bytes): Header =
    Block.decode(raw, epochSlots).fold(e => fail(e.message), _.header)

  private def refusedKey(field: DecodeField, key: Node) =
    Left(
      BlockTransportError.ByronBodyDecode(
        LedgerDecodeError.Expected(
          field / Qualifier.Canonical,
          LedgerDecodeError.Shape.Bytes,
          key.value
        )
      )
    )

  private val keyBlock = line("fixtures/mainnet/byron-tx-blocks.hex", 1)
  private val updateBlock = line("fixtures/preprod/blocks.hex", 2)
  private val firstMain = line("fixtures/preprod/blocks.hex", 1)

  /** A witness `[0, #6.24(bytes .cbor [key, signature])]` of the first transaction of `keyBlock`,
    * its key and its signature.
    */
  private val (witness, witnessKey, witnessSignature) =
    val w = items(items(items(items(parts(keyBlock)(1))(0))(0))(1))(0)
    val pair = items(w)(1).value match
      case Value.Tag(t, inner) if t == 24 => items(decode(content(inner)))
      case other                          => fail(s"tag 24 expected: $other")
    (w, pair(0), pair(1))

  /** `keyBlock` with its first witness's key and signature as `key` and `signature` write them. */
  private def rewitnessed(key: Node => Bytes, signature: Node => Bytes): Bytes =
    val inner = list(key(witnessKey), signature(witnessSignature))
    val tag = items(witness)(0).original
    splice(keyBlock, witness, list(tag, hex("d818") ++ ByronCbor.bytes(inner)))

  private val update = items(parts(updateBlock)(1))(3)
  private val proposal = items(items(update)(0))(0)
  private val vote = items(items(update)(1))(0)

  /** The delegation certificate of the header of preprod's first main block. */
  private val certificate =
    val signature = items(items(parts(firstMain)(0))(3))(3)
    items(items(items(signature)(1))(0))

  /** Preprod's first main block with `certificate`'s fields as `field` writes them, in its body. */
  private def certified(field: (Node, Int) => Bytes): Bytes =
    val delegation = items(parts(firstMain)(1))(2)
    assertEquals(delegation.original, hex("9fff"))
    val written = list(certificate.zipWithIndex.map(field.tupled)*)
    splice(firstMain, delegation, hex("9f") ++ written ++ hex("ff"))

  private def body(raw: Bytes): (Bytes, Bytes) =
    val p = parts(raw)
    (p(1).original, p(2).original)

  test("the recorded blocks pass the transport check, keys in their shortest form") {
    Vector(keyBlock, updateBlock, firstMain).foreach { raw =>
      val block = Block.decode(raw, epochSlots).fold(e => fail(e.message), identity)
      assertEquals(BlockTransport.check(block.header, raw), Right(block))
    }
    Vector(witnessKey, items(proposal)(5), items(vote)(0), certificate(1), certificate(2))
      .foreach(key => assertEquals(key.original.take(2), hex("5840")))
  }

  test("a body holding the recorded certificate, keys in their shortest form, decodes") {
    val raw = certified((n, _) => n.original)
    val (b, extra) = body(raw)
    assertEquals(ByronTransport.check(b, extra, boundary = false), Right(()))
    assertEquals(
      BlockTransport.check(header(firstMain), raw),
      Left(
        BlockTransportError.ByronBodyProof(ByronError.ProofMismatch(ByronError.Proof.Delegation))
      )
    )
  }

  private val wideKeys: Vector[(String, Bytes, Bytes, DecodeField, Node)] = Vector(
    (
      "a transaction witness's key",
      keyBlock,
      rewitnessed(wide, _.original),
      DecodeField.WitnessKey,
      witnessKey
    ),
    (
      "a delegation certificate's issuer",
      firstMain,
      certified((n, i) => if i == 1 then wide(n) else n.original),
      DecodeField.DelegationKeySignature,
      certificate(1)
    ),
    (
      "a delegation certificate's delegate",
      firstMain,
      certified((n, i) => if i == 2 then wide(n) else n.original),
      DecodeField.DelegationKeySignature,
      certificate(2)
    ),
    (
      "an update proposal's issuer",
      updateBlock,
      splice(updateBlock, items(proposal)(5), wide(items(proposal)(5))),
      DecodeField.ProposalIssuer,
      items(proposal)(5)
    ),
    (
      "an update vote's voter",
      updateBlock,
      splice(updateBlock, items(vote)(0), wide(items(vote)(0))),
      DecodeField.Voter,
      items(vote)(0)
    )
  )

  wideKeys.foreach { (name, original, raw, field, key) =>
    test(s"a fetched block whose key is not in its shortest form does not decode: $name") {
      assertEquals(BlockTransport.check(header(original), raw), refusedKey(field, key))
    }
  }

  // a `Signature`, a redeem key and a hash are read with plain `decodeBytes`
  // (`Signing/Signature.hs:129-130`, `Crypto/Orphans.hs:89-92`, `Hashing.hs:157-165`,
  // cardano-ledger f649f975): any width of length
  test("a body whose signatures or proposal id are not in their shortest form decodes") {
    val redeemBlock = line("fixtures/mainnet/byron-tx-blocks.hex", 0)
    val redeemWitness = items(items(items(items(parts(redeemBlock)(1))(0))(0))(1))(0)
    val redeemPair = items(redeemWitness)(1).value match
      case Value.Tag(t, inner) if t == 24 => items(decode(content(inner)))
      case other                          => fail(s"tag 24 expected: $other")
    val redeemed = splice(
      redeemBlock,
      redeemWitness,
      list(
        items(redeemWitness)(0).original,
        hex("d818") ++ ByronCbor.bytes(list(wide(redeemPair(0)), wide(redeemPair(1))))
      )
    )
    Vector(
      "a witness's signature" -> rewitnessed(_.original, wide),
      "a redeem witness's key and signature" -> redeemed,
      "a certificate's signature" -> certified((n, i) => if i == 3 then wide(n) else n.original),
      "a proposal's signature" -> splice(updateBlock, items(proposal)(6), wide(items(proposal)(6))),
      "a vote's signature" -> splice(updateBlock, items(vote)(3), wide(items(vote)(3))),
      "a vote's proposal id" -> splice(updateBlock, items(vote)(1), wide(items(vote)(1)))
    ).foreach { (name, raw) =>
      val (b, extra) = body(raw)
      assertEquals(ByronTransport.check(b, extra, boundary = false), Right(()), name)
    }
  }
