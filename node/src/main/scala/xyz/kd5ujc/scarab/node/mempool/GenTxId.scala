// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, DomainError, Node, Value}
import xyz.kd5ujc.scarab.ledger.{ByteStrings, DecoderVersion}
import xyz.kd5ujc.scarab.ledger.state.{Hash32, ShelleyBasedEra, VocabError}
import xyz.kd5ujc.scarab.protocol.{Era, LocalTxMonitor}

/** `TxId (GenTx ByronBlock)`: the id of one of the four things Byron's mempool holds, each the
  * Blake2b-256 hash (`AbstractHash Blake2b_256`) of what it names
  * (`ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Ledger/Mempool.hs:194-198`,
  * ouroboros-consensus 82ecba32).
  */
enum ByronGenTxId:
  /** A transaction's id, `Utxo.TxId`. */
  case ByronTxId(hash: Hash32)

  /** A delegation certificate's id, `Delegation.CertificateId`. */
  case ByronDlgId(hash: Hash32)

  /** An update proposal's id, `Update.UpId`. */
  case ByronUpdateProposalId(hash: Hash32)

  /** An update vote's id, `Update.VoteId`. */
  case ByronUpdateVoteId(hash: Hash32)

  def hash: Hash32

/** `GenTxId (CardanoBlock c)`, the transaction id a node-to-client client names a transaction by:
  * `HardForkGenTxId` around `OneEraGenTxId`, an era and that era's id
  * (`ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/HardFork/Combinator/Mempool.hs:603-606`,
  * `AcrossEras.hs:128`, ouroboros-consensus 82ecba32).
  */
enum GenTxId:
  case Byron(id: ByronGenTxId)

  /** `ShelleyTxId`, the ledger's `TxId` of a Shelley-based era: the hash of the transaction's body
    * (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:217`,
    * ouroboros-consensus 82ecba32).
    */
  case Shelley(era: ShelleyBasedEra[?], id: Hash32)

  /** What two ids are compared by: the hash alone. The hard-fork combinator orders `OneEraGenTxId`
    * by its hash and ignores the era (`AcrossEras.hs:167-181`), which Cardano's instance does with
    * `compareCardanoGenTxId`: the 32 bytes of the hash, of any of Byron's four ids as of a
    * Shelley-based era's (`Cardano/CanHardFork.hs:280-288`,
    * `Cardano/CanHardFork/OptimizedTxIdComparison.hs:56-84`, ouroboros-consensus 82ecba32).
    */
  def hash: Hash32 = this match
    case Byron(id)      => id.hash
    case Shelley(_, id) => id

object GenTxId:
  /** Where the reference's decoder of a `GenTxId` stops on an item that is not one. */
  enum Refusal extends DomainError:
    /** Not one well-formed CBOR item (text that is not UTF-8, for instance). */
    case Malformed(cause: Cbor.Error)

    /** `decodeNS` reads the era and its id with `enforceSize "decodeNS" 2`: a definite list of two
      * (cborg's `decodeListLen` takes no indefinite list).
      */
    case EraEnvelope

    /** `decodeNS` reads the era index with `decodeWord8`: an unsigned integer up to 255. */
    case EraIndex

    /** An era index past the last of `CardanoEras`, which `nsFromIndex` does not find ("decodeNS:
      * invalid index").
      */
    case UnknownEra(index: Int)

    /** `decodeByronGenTxId` reads its constructor and hash with `enforceSize "GenTxId (ByronBlock
      * cfg)" 2`.
      */
    case ByronEnvelope

    /** `decodeByronGenTxId` reads its constructor with `decodeWord8`. */
    case ByronTag

    /** A constructor past Byron's four (`DecoderErrorUnknownTag "GenTxId (ByronBlock cfg)"`). */
    case UnknownByronTag(tag: Int)

    /** The era's hash is read as a byte string, and the item is not one. */
    case ExpectedBytes(era: Era)

    /** A chunked byte string, where the era's decoder version reads a definite one only. */
    case IndefiniteBytes(era: Era)

    /** A byte string that is not a hash of 32 bytes. */
    case HashLength(era: Era, cause: VocabError)

  private type R[A] = Either[Refusal, A]

  /** No bound but the item's size, which the codec has bounded ([[Cbor.Limits.Unbounded]]). */
  private val Items = Cbor.Limits.Unbounded.copy(allowFloats = true, allowSimpleValues = true)

  /** The id an item of `MsgHasTx` names, read as the reference's codec reads it: the hard-fork
    * combinator's `decodeNodeToClient` for `GenTxId`, `dispatchDecoder` over the eras' own decoders
    * (`HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:258-291, 369-374`; `decodeNS`,
    * `Common.hs:420-433`), every era of `CardanoEras` enabled at every node-to-client version from
    * 16 to 23 (`Cardano/Node.hs:444-454`, ouroboros-consensus 82ecba32):
    *   - Byron: `decodeByronGenTxId` (`Byron/Ledger/Mempool.hs:368-379`,
    *     `Byron/Node/Serialisation.hs:180-182`), a constructor from 0 to 3 and its hash, an
    *     `AbstractHash` read as a byte string of exactly 32 bytes
    *     (`eras/byron/crypto/src/Cardano/Crypto/Hashing.hs:153-167`, cardano-ledger f649f975) at
    *     `byronProtVer`, version 1 (`Binary/Version.hs:162-163`), where a byte string is definite;
    *   - a Shelley-based era: `fromEraCBOR @era` (`Shelley/Node/Serialisation.hs:346-351`), the
    *     ledger's `TxId`, a `SafeHash` and so a hash of 32 bytes (`Hash h a` from `PackedBytes`,
    *     `Decoding/DecCBOR.hs:492-498`, cardano-ledger f649f975) read at the era's lowest protocol
    *     version, which reads a chunked byte string in Dijkstra only ([[ByteStrings]]).
    *
    * Each integer is read in any of its encodings and each list at any width of its length, as
    * cborg's `decodeWord8` and `decodeListLen` read them; neither reads an indefinite list. A float
    * or a simple value is read as an item, so that it is refused where the reference's decoder
    * meets it, after the era.
    */
  def decode(item: Bytes): R[GenTxId] =
    Cbor
      .decode(item, Items)
      .leftMap(Refusal.Malformed(_))
      .flatMap(pair(_, Refusal.EraEnvelope))
      .flatMap { (index, id) =>
        word8(index, Refusal.EraIndex).flatMap(i =>
          Era.fromIndex(BigInt(i)).toRight(Refusal.UnknownEra(i)).flatMap(ofEra(_, id))
        )
      }

  /** What the reference's decoder of a `GenTxId` makes of `input`, the part of an id that has
    * arrived: the steps of [[decode]] in its order, each judged once its token has arrived whole,
    * so a prefix that decoder fails on ends the connection at the byte it fails at (`decodeTxId` of
    * `MsgHasTx`, ouroboros-network c45735a5
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxMonitor/Codec.hs:135-137`):
    *   - `decodeNS`: `enforceSize "decodeNS" 2`, `decodeWord8` and the era's index
    *     (`HardFork/Combinator/Serialisation/Common.hs:420-425`, ouroboros-consensus 82ecba32);
    *   - Byron: `enforceSize "GenTxId (ByronBlock cfg)" 2`, `decodeWord8` and the constructor
    *     (`Byron/Ledger/Mempool.hs:368-378`);
    *   - the hash: a byte string, definite unless the era's decoder version reads a chunked one
    *     ([[ByteStrings]]); its length is judged once it has arrived whole (`packByteString` after
    *     `decCBOR`, `Decoding/DecCBOR.hs:492-497`, cardano-ledger f649f975), which [[decode]] does.
    *
    * A token is its initial byte and the bytes of its argument; cborg judges one only once it has
    * them all, so a token of the wrong kind whose argument has not arrived waits.
    */
  def prefix(input: Bytes): LocalTxMonitor.TxIdPrefix =
    listOfTwo(input, 0, Refusal.EraEnvelope)
      .flatMap(word8(input, _, Refusal.EraIndex))
      .flatMap(index =>
        Era
          .fromIndex(BigInt(index.value))
          .toRight(refused(Refusal.UnknownEra(index.value)))
          .flatMap(idPrefix(input, index.next, _))
      )
      .merge

  /** [[prefix]], as the LocalTxMonitor server asks it. */
  val reader: LocalTxMonitor.TxIdReader = prefix(_)

  private type Step[A] = Either[LocalTxMonitor.TxIdPrefix, A]

  /** A token read whole: its major type, its additional information, its argument (that information
    * below 24) and where the next token starts.
    */
  private final case class Token(major: Int, info: Int, argument: Long, next: Int)

  /** A `Word8` read, and where the next token starts. */
  private final case class Word8(value: Int, next: Int)

  private def refused(refusal: Refusal): LocalTxMonitor.TxIdPrefix =
    LocalTxMonitor.TxIdPrefix.Refused(refusal)

  /** The token at `at`, or `Short` until it has arrived whole. */
  private def token(input: Bytes, at: Int): Step[Token] =
    input
      .lift(at)
      .flatMap { byte =>
        val initial = byte & 0xff
        val info = initial & 31
        val width = info match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => 0
        input
          .bigEndian(at + 1, width)
          .map(argument =>
            Token(initial >> 5, info, if width == 0 then info.toLong else argument, at + 1 + width)
          )
      }
      .toRight(LocalTxMonitor.TxIdPrefix.Short)

  /** `enforceSize _ 2`: the head of a definite list of two. */
  private def listOfTwo(input: Bytes, at: Int, refusal: Refusal): Step[Int] =
    token(input, at).flatMap(t =>
      Either.cond(t.major == 4 && t.info < 28 && t.argument == 2, t.next, refused(refusal))
    )

  /** cborg's `decodeWord8`: an unsigned integer up to 255, at any width of its head. */
  private def word8(input: Bytes, at: Int, refusal: Refusal): Step[Word8] =
    token(input, at).flatMap(t =>
      Either.cond(
        t.major == 0 && t.info < 28 && t.argument >= 0 && t.argument <= 0xff,
        Word8(t.argument.toInt, t.next),
        refused(refusal)
      )
    )

  private def idPrefix(input: Bytes, at: Int, era: Era): Step[LocalTxMonitor.TxIdPrefix] =
    era match
      case Era.Byron =>
        listOfTwo(input, at, Refusal.ByronEnvelope)
          .flatMap(word8(input, _, Refusal.ByronTag))
          .flatMap(constructor =>
            Either.cond(
              constructor.value <= 3,
              constructor.next,
              refused(Refusal.UnknownByronTag(constructor.value))
            )
          )
          .flatMap(hashPrefix(input, _, era, ByteStrings.Definite))
      case Era.Shelley | Era.Allegra | Era.Mary | Era.Alonzo | Era.Babbage | Era.Conway |
          Era.Dijkstra =>
        hashPrefix(input, at, era, ByteStrings.of(DecoderVersion.of(era)))

  /** The head of the hash's byte string; what follows it is judged once it has arrived whole. */
  private def hashPrefix(
      input: Bytes,
      at: Int,
      era: Era,
      strings: ByteStrings
  ): Step[LocalTxMonitor.TxIdPrefix] =
    token(input, at).flatMap(t =>
      if t.major != 2 then Left(refused(Refusal.ExpectedBytes(era)))
      else if t.info != 31 then Right(LocalTxMonitor.TxIdPrefix.Short)
      else
        strings match
          case ByteStrings.Chunked  => Right(LocalTxMonitor.TxIdPrefix.Short)
          case ByteStrings.Definite => Left(refused(Refusal.IndefiniteBytes(era)))
    )

  private def ofEra(era: Era, id: Node): R[GenTxId] = era match
    case Era.Byron    => byron(id).map(Byron(_))
    case Era.Shelley  => shelley(ShelleyBasedEra.SShelley, era, id)
    case Era.Allegra  => shelley(ShelleyBasedEra.SAllegra, era, id)
    case Era.Mary     => shelley(ShelleyBasedEra.SMary, era, id)
    case Era.Alonzo   => shelley(ShelleyBasedEra.SAlonzo, era, id)
    case Era.Babbage  => shelley(ShelleyBasedEra.SBabbage, era, id)
    case Era.Conway   => shelley(ShelleyBasedEra.SConway, era, id)
    case Era.Dijkstra => shelley(ShelleyBasedEra.SDijkstra, era, id)

  private def shelley(witness: ShelleyBasedEra[?], era: Era, id: Node): R[GenTxId] =
    hash(id, era)(using ByteStrings.of(DecoderVersion.of(era))).map(Shelley(witness, _))

  private def byron(n: Node): R[ByronGenTxId] =
    given ByteStrings = ByteStrings.Definite
    pair(n, Refusal.ByronEnvelope).flatMap { (constructor, id) =>
      word8(constructor, Refusal.ByronTag).flatMap {
        case 0   => hash(id, Era.Byron).map(ByronGenTxId.ByronTxId(_))
        case 1   => hash(id, Era.Byron).map(ByronGenTxId.ByronDlgId(_))
        case 2   => hash(id, Era.Byron).map(ByronGenTxId.ByronUpdateProposalId(_))
        case 3   => hash(id, Era.Byron).map(ByronGenTxId.ByronUpdateVoteId(_))
        case tag => Left(Refusal.UnknownByronTag(tag))
      }
    }

  /** The two items of a list of two read with `enforceSize`: definite only. */
  private def pair(n: Node, refusal: Refusal): R[(Node, Node)] = n.value match
    case Value.Arr(Vector(first, second)) if !n.isIndefinite => Right((first, second))
    case Value.Arr(_) | Value.UInt(_) | Value.NInt(_) | Value.ByteString(_) | Value.Text(_) |
        Value.Map(_) | Value.Tag(_, _) | Value.Bool(_) | Value.Null | Value.Simple(_) |
        Value.Float(_, _) =>
      Left(refusal)

  /** An unsigned integer read with cborg's `decodeWord8`: at most 255. */
  private def word8(n: Node, refusal: Refusal): R[Int] = n.value match
    case Value.UInt(v) if v <= 0xff => Right(v.toInt)
    case Value.UInt(_) | Value.NInt(_) | Value.ByteString(_) | Value.Text(_) | Value.Arr(_) |
        Value.Map(_) | Value.Tag(_, _) | Value.Bool(_) | Value.Null | Value.Simple(_) |
        Value.Float(_, _) =>
      Left(refusal)

  /** A hash of 32 bytes, read as a byte string in a form `strings` reads. */
  private def hash(n: Node, era: Era)(using strings: ByteStrings): R[Hash32] = n.value match
    case Value.ByteString(b) =>
      Either
        .cond(strings.reads(n), b, Refusal.IndefiniteBytes(era))
        .flatMap(Hash32.fromBytes(_).leftMap(Refusal.HashLength(era, _)))
    case Value.UInt(_) | Value.NInt(_) | Value.Text(_) | Value.Arr(_) | Value.Map(_) |
        Value.Tag(_, _) | Value.Bool(_) | Value.Null | Value.Simple(_) | Value.Float(_, _) =>
      Left(Refusal.ExpectedBytes(era))
