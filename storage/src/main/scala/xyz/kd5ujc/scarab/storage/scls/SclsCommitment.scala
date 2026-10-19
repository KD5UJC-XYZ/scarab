// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import scala.annotation.tailrec
import org.bouncycastle.crypto.digests.Blake2bDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b

/** The SCLS Blake2b-224 commitments, with logarithmic retained state. Reference:
  * tweag/cardano-cls@8e2bba9442cb388089f53d708cc4aedcb47726ea,
  * scls-format/src/Cardano/SCLS/Internal/Record/Chunk.hs:129-130 (`entryDigest`);
  * merkle-tree-incremental/src/Crypto/Hash/MerkleTree/Incremental/Internal.hs:59-61,145-148
  * (`nodeHash`, `join`) and Incremental.hs:123-128 (`finalize`). Namespace roots contain entry
  * leaves; the global tree hashes namespace roots as new leaves.
  */
object SclsCommitment:
  opaque type Digest = Bytes

  object Digest:
    def fromBytes(bytes: Bytes): Option[Digest] = Option.when(bytes.size == 28)(bytes.compact)

    extension (digest: Digest) def bytes: Bytes = digest

  private val LeafPrefix = Bytes.wrap(Array(1.toByte))
  private val NodePrefix = Bytes.wrap(Array(0.toByte))
  private val Empty: Digest = Blake2b.hash224.hash(Bytes.empty)

  /** The chunk checksum hashes consecutive entry digests, independently of the Merkle tree. Copying
    * the digest state keeps the accumulator immutable without retaining one digest per entry.
    * Bouncy Castle's mutable state stays private to each newly created value.
    */
  final class ChunkHash private[SclsCommitment] (private val state: Blake2bDigest):
    def addEntry(namespace: Bytes, key: Bytes, value: Bytes): ChunkHash =
      val next = new Blake2bDigest(state)
      val leaf = entry(namespace, key, value)
      next.update(leaf.unsafeArray, leaf.unsafeOffset, leaf.size)
      new ChunkHash(next)

    def digest: Digest =
      val next = new Blake2bDigest(state)
      val bytes = new Array[Byte](28)
      next.doFinal(bytes, 0)
      Bytes.wrap(bytes)

  val emptyChunk: ChunkHash = new ChunkHash(new Blake2bDigest(224))

  def entry(namespace: Bytes, key: Bytes, value: Bytes): Digest =
    Blake2b.hash224.hash(LeafPrefix ++ namespace ++ key ++ value)

  private def node(left: Digest, right: Digest): Digest =
    Blake2b.hash224.hash(NodePrefix ++ left ++ right)

  private final case class Subtree(level: Int, digest: Digest)

  final class Merkle private[SclsCommitment] (private val forest: List[Subtree]):
    /** An entry digest already has its namespace and leaf prefix. */
    def addEntry(namespace: Bytes, key: Bytes, value: Bytes): Merkle =
      addLeaf(entry(namespace, key, value))

    /** A namespace root becomes a leaf of the manifest's global tree. */
    def addNamespace(root: Digest): Merkle =
      addLeaf(Blake2b.hash224.hash(LeafPrefix ++ root))

    private def addLeaf(digest: Digest): Merkle =
      @tailrec def carry(tree: Subtree, rest: List[Subtree]): List[Subtree] = rest match
        case older :: tail if older.level == tree.level =>
          carry(Subtree(tree.level + 1, node(older.digest, tree.digest)), tail)
        case _ => tree :: rest
      new Merkle(carry(Subtree(0, digest), forest))

    /** Incomplete right subtrees are promoted without hashing a duplicate leaf. */
    def root: Digest = forest match
      case Nil => Empty
      case newest :: older =>
        older.foldLeft(newest.digest)((right, left) => node(left.digest, right))

  val empty: Merkle = new Merkle(Nil)
