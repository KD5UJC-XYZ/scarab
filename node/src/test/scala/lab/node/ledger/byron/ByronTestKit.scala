// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger.byron

import java.security.{KeyPairGenerator, PrivateKey, Signature}
import java.security.spec.NamedParameterSpec
import lab.cbor.{Bytes, Cbor}
import lab.node.{ByronDelegation, ByronGenesis}
import lab.node.ledger.TxIn

/** Builders for synthetic Byron data: CBOR, Ed25519 keys (extended: key then a chain code),
  * addresses, transactions and genesis files. Signatures are real, made with the JDK.
  */
object ByronTestKit:
  /** Definite-length CBOR builders over `Bytes`. */
  object Cb:
    private def head(major: Int, n: Long): Bytes =
      val m = major << 5
      if n < 24 then ByronCbor.of(m | n.toInt)
      else if n < 256 then ByronCbor.of(m | 24, n.toInt)
      else if n < 65536 then ByronCbor.of(m | 25, (n >> 8).toInt & 0xff, n.toInt & 0xff)
      else
        ByronCbor.of(
          m | 26,
          (n >> 24).toInt & 0xff,
          (n >> 16).toInt & 0xff,
          (n >> 8).toInt & 0xff,
          n.toInt & 0xff
        )
    def uint(n: Long): Bytes = head(0, n)
    def bytes(b: Bytes): Bytes = head(2, b.size) ++ b
    def text(s: String): Bytes = head(3, s.length) ++ Bytes.fromArray(s.getBytes("UTF-8"))
    def arr(items: Bytes*): Bytes = head(4, items.size) ++ ByronCbor.concat(items*)
    def indef(items: Bytes*): Bytes =
      ByronCbor.of(0x9f) ++ ByronCbor.concat(items*) ++ ByronCbor.of(0xff)
    def map(entries: (Bytes, Bytes)*): Bytes =
      head(5, entries.size) ++ ByronCbor.concat(entries.flatMap((k, v) => Seq(k, v))*)
    def tag24(b: Bytes): Bytes = ByronCbor.of(0xd8, 0x18) ++ bytes(b)
    val `true`: Bytes = ByronCbor.of(0xf5)
    val `false`: Bytes = ByronCbor.of(0xf4)

  /** An extended key: the Ed25519 public key and a 32-byte chain code. */
  final class Key(val pub: Bytes, val chainCode: Bytes, private val secret: PrivateKey):
    def extended: Bytes = pub ++ chainCode
    def keyHash: Bytes = ByronCbor.keyHash(extended)
    def sign(message: Bytes): Bytes =
      val s = Signature.getInstance("Ed25519")
      s.initSign(secret)
      s.update(message.toArray)
      Bytes.fromArray(s.sign())

  private val generator =
    val g = KeyPairGenerator.getInstance("Ed25519")
    g.initialize(NamedParameterSpec.ED25519)
    g

  def key(chainCodeSeed: Int = 0): Key =
    val pair = generator.generateKeyPair()
    val encoded = pair.getPublic.getEncoded
    Key(
      Bytes.fromArray(encoded.takeRight(32)),
      Bytes.fromArray(Array.fill(32)(chainCodeSeed.toByte)),
      pair.getPrivate
    )

  def address(key: Key, magic: Option[Long]): Bytes =
    ByronAddress.make(ByronAddress.VerKey, key.extended, magic)

  def redeemAddress(key: Key, magic: Option[Long]): Bytes =
    ByronAddress.make(ByronAddress.Redeem, key.pub, magic)

  def txIn(in: TxIn): Bytes =
    Cb.arr(Cb.uint(0), Cb.tag24(Cb.arr(Cb.bytes(in.id), Cb.uint(in.index.toLong))))

  /** An encoded transaction, its id and its encoding of the spent inputs. */
  def tx(
      inputs: Seq[TxIn],
      outputs: Seq[(Bytes, Long)],
      attributes: Seq[(Long, Bytes)] = Nil
  ): Bytes =
    Cb.arr(
      Cb.indef(inputs.map(txIn)*),
      Cb.indef(outputs.map((a, v) => Cb.arr(a, Cb.uint(v)))*),
      Cb.map(attributes.map((k, v) => Cb.uint(k) -> Cb.bytes(v))*)
    )

  def txId(encodedTx: Bytes): Bytes = lab.Blake2b.hash256.hash(encodedTx)

  def signTx(config: ByronLedger.Config, kind: Int, key: Key, redeem: Boolean, id: Bytes): Bytes =
    val message = ByronSig.tag(kind, config.magic) ++ ByronCbor.of(0x58, 0x20) ++ id
    val signature = key.sign(message)
    val publicKey = if redeem then key.pub else key.extended
    Cb.arr(
      Cb.uint(if redeem then 2 else 0),
      Cb.tag24(Cb.arr(Cb.bytes(publicKey), Cb.bytes(signature)))
    )

  /** A witnessed transaction (`TxAux`), decoded. */
  def aux(
      config: ByronLedger.Config,
      inputs: Seq[(TxIn, Key, Boolean)],
      outputs: Seq[(Bytes, Long)]
  ): ByronTxAux =
    val encoded = tx(inputs.map(_._1), outputs)
    val id = txId(encoded)
    val witnesses = inputs.map { (_, k, redeem) =>
      signTx(config, if redeem then ByronSig.SignRedeemTx else ByronSig.SignTx, k, redeem, id)
    }
    decodeAux(Cb.arr(encoded, Cb.indef(witnesses*)))

  def decodeAux(encoded: Bytes): ByronTxAux =
    Cbor
      .decode(encoded)
      .flatMap(ByronTx.decodeAux)
      .fold(e => throw new AssertionError(e), identity)

  val params: ByronParams = ByronParams(
    scriptVersion = 0,
    slotDurationMs = 1000,
    maxBlockSize = 2000000,
    maxHeaderSize = 2000000,
    maxTxSize = 4096,
    maxProposalSize = 700,
    mpcThd = BigInt("20000000000000"),
    heavyDelThd = BigInt("300000000000"),
    updateVoteThd = BigInt("1000000000000"),
    updateProposalThd = BigInt("100000000000000"),
    updateProposalTtl = 10000,
    softforkRule = SoftforkRule(
      BigInt("900000000000000"),
      BigInt("600000000000000"),
      BigInt("50000000000000")
    ),
    txFeePolicy = TxFeePolicy(BigInt("155381000000000"), BigInt("43946000000")),
    unlockStakeEpoch = BigInt("18446744073709551615")
  )

  val Magic = 4242L

  /** A genesis with `genesis` keys, each delegating to the key at the same index of `delegates`. */
  def genesis(
      genesisKeys: Seq[Key],
      delegates: Seq[Key],
      avvm: Seq[(Bytes, BigInt)] = Nil,
      nonAvvm: Seq[(Bytes, BigInt)] = Nil,
      k: Int = 10,
      magic: Long = Magic
  ): ByronGenesis =
    val magicBytes = ByronCbor.uint(magic)
    val certs = genesisKeys.zip(delegates).map { (g, d) =>
      val epoch = ByronCbor.uint(0)
      val signed = ByronCbor.bytes(ByronCbor.concat(ByronCbor.of(0x30, 0x30), d.extended, epoch))
      ByronDelegation(
        0,
        g.extended,
        d.extended,
        g.sign(ByronSig.tag(ByronSig.SignCertificate, magicBytes) ++ signed)
      )
    }
    ByronGenesis(
      Bytes.fromArray(Array.fill(32)(7.toByte)),
      magic,
      k,
      1000,
      certs.toVector.sortBy(_.issuer.hex),
      genesisKeys.map(_.keyHash).sortBy(_.hex).toVector,
      params,
      avvm.toVector,
      nonAvvm.toVector
    )
