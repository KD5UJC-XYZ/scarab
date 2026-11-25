// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{CborRead, Credential as FlatCredential, RewardAccount, TxIn, Voter}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraCert,
  DijkstraProposal,
  DijkstraPurpose,
  DijkstraPurposes,
  DijkstraTxIn,
  DijkstraVoter,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.AsIxItem
import xyz.kd5ujc.scarab.ledger.rules.Convert

/** The Dijkstra transactions the repository holds, as the era-type suites read them: the corpus of
  * the Leios build's verdicts (`fixtures/reference/leios-testnet-blocks`, its transactions alone
  * and its blocks), the public Leios testnet's blocks (`fixtures/leios/testnet`), the reference
  * cluster's Conway to Dijkstra chain and its submitted transaction (`fixtures/dijkstra-devnet`),
  * the transactions of the script-context fixtures (`fixtures/reference/leios-testnet-contexts` and
  * `leios-testnet-chain-contexts`, the latter recorded on the testnet's chain, each with the
  * outputs its inputs spend), those of the reference's UTXOW cases
  * (`fixtures/reference/dijkstra-rules`, each with its UTxO set) and the Dijkstra mempool forms of
  * the crossing corpus (`fixtures/reference/dijkstra-crossing`).
  */
object DijkstraCorpus extends Assertions:
  def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  def node(raw: Bytes): Node =
    CborRead.decode(raw, V12.nestedLimits).fold(e => fail(e.toString), identity)

  def jsonLines(path: String): Vector[Json] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => parser.parse(l).fold(e => fail(e.message), identity))

  def json(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(e => fail(e.message), identity)

  def str(j: Json, name: String): String =
    j.hcursor.get[String](name).fold(e => fail(s"$name: ${e.message}"), identity)

  def tsv(path: String): Vector[Array[String]] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .drop(1)
      .filterNot(l => l.startsWith("#") || l.isEmpty)
      .map(_.split('\t'))

  /** The corpus of the Leios build's verdicts: its transactions alone and its blocks. */
  lazy val leiosCorpus: Vector[Json] = jsonLines(
    "fixtures/reference/leios-testnet-blocks/cases.jsonl"
  )

  /** The public Leios testnet's blocks: each row's name and bytes. */
  lazy val testnetBlocks: Vector[(String, Bytes)] =
    tsv("fixtures/leios/testnet/blocks-rc-2026w44.tsv").map(p => (p(0), hex(p(5))))

  /** The devnet chain's Dijkstra blocks: each block's name and bytes. */
  lazy val devnetBlocks: Vector[(String, Bytes)] =
    tsv("fixtures/dijkstra-devnet/blocks.tsv")
      .filter(_(0) == "8")
      .map(p => (s"devnet block ${p(1)}", hex(p(4))))

  /** The devnet's submitted transaction, as a client submits it. */
  lazy val devnetSubmitted: Bytes =
    """"cborHex"\s*:\s*"([0-9a-f]+)"""".r
      .findFirstMatchIn(Files.readString(Path.of("fixtures/dijkstra-devnet/transaction.json")))
      .map(m => hex(m.group(1)))
      .getOrElse(fail("no cborHex"))

  /** The script-context fixture's transactions, written by hand on the testnet's build. */
  lazy val contextCases: Vector[Json] =
    json("fixtures/reference/leios-testnet-contexts/cases.json").asArray
      .getOrElse(fail("not an array"))

  /** The transactions recorded on the testnet's chain, with the outputs they spend. */
  lazy val chainContextCases: Vector[Json] =
    json("fixtures/reference/leios-testnet-chain-contexts/cases.json").hcursor
      .get[Vector[Json]]("cases")
      .fold(e => fail(e.message), identity)

  /** The reference's UTXOW cases, each with its transaction, parameters and UTxO set. */
  lazy val utxowCases: Vector[Json] =
    jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl").filter(j =>
      str(j, "kind") == "utxow"
    )

  /** The Dijkstra mempool forms of the crossing corpus, distinct. */
  lazy val crossingForms: Vector[Bytes] =
    jsonLines("fixtures/reference/dijkstra-crossing/cases.jsonl")
      .flatMap(c =>
        Vector("rematch", "translateEra").flatMap(k =>
          c.hcursor.downField("reference").downField(k).get[String]("mempool").toOption
        )
      )
      .distinct
      .map(hex)

  /** Bytes read as one item of CBOR, none for bytes that are not one. */
  /** The items of a Dijkstra block's transactions, each the bytes the block holds it as, read from
    * the block's tree (`[8, [header, [transactions, ...]]]`); none when the block is not one.
    */
  def blockItems(raw: Bytes): Vector[Bytes] =
    def items(n: Node): Vector[Node] = n.value match
      case xyz.kd5ujc.scarab.codec.Value.Arr(xs) => xs
      case _                                     => Vector.empty
    CborRead
      .decode(raw, xyz.kd5ujc.scarab.codec.Cbor.Limits.Unbounded)
      .toOption
      .toVector
      .flatMap(root =>
        items(root).lift(1).toVector.flatMap(items(_).lift(1)).flatMap(items(_).headOption)
      )
      .flatMap(items(_).map(_.original))

  def cbor(raw: Bytes): Option[Node] = CborRead.decode(raw, V12.nestedLimits).toOption

  /** A transaction input as the block decoder reads one: its id and index. */
  def encodeTxIn(in: xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn): Bytes =
    def uint(n: Long): String =
      if n < 24 then f"$n%02x"
      else if n < 256 then f"18$n%02x"
      else if n < 65536 then f"19$n%04x"
      else f"1a$n%08x"
    hex(s"825820${in.id.hex}${uint(in.index.toLong)}")

  /** The testnet's parameters, as its genesis files give them at protocol 12: the deposits of its
    * Shelley and Conway genesis, which no governance action changed.
    */
  def testnetPParams
      : xyz.kd5ujc.scarab.ledger.era.PParams[xyz.kd5ujc.scarab.ledger.state.Dijkstra] =
    era(testnetStored)

  /** The testnet's parameters as the node stores them ([[testnetPParams]]). */
  def testnetStored: xyz.kd5ujc.scarab.ledger.ProtocolParams =
    val shelley = json("fixtures/leios/testnet/config/shelley-genesis.json").hcursor
      .downField("protocolParams")
    val conway = json("fixtures/leios/testnet/config/conway-genesis.json").hcursor
    def num(c: io.circe.ACursor): BigInt = c.as[BigInt].fold(e => fail(e.message), identity)
    val stored = xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture.genesis
      .upgrade(xyz.kd5ujc.scarab.ledger.TestLedger.params(12))
      .copy(
        keyDeposit = num(shelley.downField("keyDeposit")),
        poolDeposit = num(shelley.downField("poolDeposit")),
        drepDeposit = num(conway.downField("dRepDeposit")),
        govActionDeposit = num(conway.downField("govActionDeposit"))
      )
    stored

  /** Stored parameters as Dijkstra's. */
  def era(
      stored: xyz.kd5ujc.scarab.ledger.ProtocolParams
  ): xyz.kd5ujc.scarab.ledger.era.PParams[xyz.kd5ujc.scarab.ledger.state.Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.PParams[xyz.kd5ujc.scarab.ledger.state.Dijkstra](
      DijkstraPParams.fromStored(stored).fold(e => fail(e.toString), identity)
    )

  /** The parameters of a UTXOW case, on those of protocol 12 with the Dijkstra genesis, as the
    * reference's oracle set them (`DijkstraRulesOracleSuite` reads them the same way).
    */
  def utxowPParams(
      c: Json
  ): xyz.kd5ujc.scarab.ledger.era.PParams[xyz.kd5ujc.scarab.ledger.state.Dijkstra] =
    era(utxowStored(c))

  /** The parameters of a UTXOW case as the node stores them ([[utxowPParams]]). */
  def utxowStored(c: Json): xyz.kd5ujc.scarab.ledger.ProtocolParams =
    import xyz.kd5ujc.scarab.codec.Rational
    import xyz.kd5ujc.scarab.plutus.ExUnits
    val p = c.hcursor.downField("params")
    def num(c: io.circe.ACursor): BigInt = c.as[BigInt].fold(e => fail(e.message), identity)
    def ratio(c: io.circe.ACursor): Rational = Rational(num(c.downN(0)), num(c.downN(1)))
    def units(c: io.circe.ACursor): ExUnits = ExUnits(num(c.downN(0)), num(c.downN(1)))
    val stored = xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraFixture.genesis
      .upgrade(xyz.kd5ujc.scarab.ledger.TestLedger.params(12))
      .copy(
        protocolMajor = num(p.downField("major")),
        maxBlockExUnits = units(p.downField("maxBlockExUnits")),
        maxRefScriptSizePerBlock = num(p.downField("maxRefScriptSizePerBlock")),
        maxRefScriptSizePerTx = num(p.downField("maxRefScriptSizePerTx")),
        keyDeposit = num(p.downField("keyDeposit")),
        poolDeposit = num(p.downField("poolDeposit")),
        drepDeposit = num(p.downField("drepDeposit")),
        govActionDeposit = num(p.downField("govActionDeposit")),
        govActionLifetime = num(p.downField("govActionLifetime")),
        eMax = num(p.downField("eMax")),
        minPoolCost = num(p.downField("minPoolCost")),
        minFeeA = num(p.downField("minFeeA")),
        minFeeB = num(p.downField("minFeeB")),
        maxTxSize = num(p.downField("maxTxSize")),
        maxValueSize = num(p.downField("maxValSize")),
        coinsPerUTxOByte = num(p.downField("coinsPerUTxOByte")),
        maxTxExUnits = units(p.downField("maxTxExUnits")),
        collateralPercentage = num(p.downField("collateralPercentage")),
        maxCollateralInputs = num(p.downField("maxCollateralInputs")),
        minFeeRefScriptCostPerByte = Rational(num(p.downField("minFeeRefScriptCostPerByte"))),
        refScriptCostStride = num(p.downField("refScriptCostStride")),
        refScriptCostMultiplier = ratio(p.downField("refScriptCostMultiplier")),
        priceMem = ratio(p.downField("prices").downN(0)),
        priceSteps = ratio(p.downField("prices").downN(1))
      )
    stored

  /** What a UTXOW refusal of the reference records that the UTxO functions compute: the values
    * consumed and produced, the minimum fee, the keys not witnessed, the scripts missing and the
    * extraneous ones. Read from each failure's `EncCBOR` at version 12 (`[0, [6, [consumed,
    * produced]]]`, `[0, [5, [fee, minimum]]]`, `[2, keys]`, `[3, scripts]` and `[9, scripts]`).
    */
  enum Recorded:
    case ValueNotConserved(
        supplied: xyz.kd5ujc.scarab.ledger.era.MultiAssetValue,
        expected: xyz.kd5ujc.scarab.ledger.era.MultiAssetValue
    )
    case FeeTooSmall(
        supplied: xyz.kd5ujc.scarab.ledger.state.Coin,
        expected: xyz.kd5ujc.scarab.ledger.state.Coin
    )
    case MissingVKeyWitnesses(keys: Set[Bytes])
    case MissingScriptWitnesses(scripts: Set[Bytes])
    case ExtraneousScriptWitnesses(scripts: Set[Bytes])
    case Other

  /** A failure of a UTXOW refusal of the reference, as [[Recorded]]. */
  def utxowRecord(n: Node): Recorded =
    import xyz.kd5ujc.scarab.codec.Value as V
    def items(v: Node): Vector[Node] = v.value match
      case V.Arr(xs)       => xs
      case V.Tag(_, inner) => items(inner)
      case other           => fail(s"not an array: $other")
    def uint(v: Node): BigInt = v.value match
      case V.UInt(x) => x
      case other     => fail(s"not an unsigned integer: $other")
    def value(v: Node): xyz.kd5ujc.scarab.ledger.era.MultiAssetValue =
      val d = xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraValue
        .decode(v)
        .fold(e => fail(e.toString), identity)
      xyz.kd5ujc.scarab.ledger.era.MultiAssetValue
        .of(xyz.kd5ujc.scarab.ledger.Value(d.coin, d.assets))
    def bytes(v: Node): Bytes = v.value match
      case V.ByteString(b) => b
      case other           => fail(s"not a byte string: $other")
    items(n) match
      case Vector(outer, inner) if uint(outer) == 0 =>
        items(inner) match
          case Vector(tag, mismatch) =>
            (uint(tag).toInt, items(mismatch)) match
              case (6, Vector(s, e)) => Recorded.ValueNotConserved(value(s), value(e))
              case (5, Vector(s, e)) =>
                Recorded.FeeTooSmall(
                  xyz.kd5ujc.scarab.ledger.state.Coin(uint(s)),
                  xyz.kd5ujc.scarab.ledger.state.Coin(uint(e))
                )
              case _ => Recorded.Other
          case _ => Recorded.Other
      case Vector(outer, keys) if uint(outer) == 2 =>
        Recorded.MissingVKeyWitnesses(items(keys).map(bytes).toSet)
      case Vector(outer, scripts) if uint(outer) == 3 =>
        Recorded.MissingScriptWitnesses(items(scripts).map(bytes).toSet)
      case Vector(outer, scripts) if uint(outer) == 9 =>
        Recorded.ExtraneousScriptWitnesses(items(scripts).map(bytes).toSet)
      case _ => Recorded.Other

  /** A certificate as the era reads it from the bytes `raw`: the flat decoder's, at the reference's
    * types ([[DijkstraProcedures.cert]]), as Dijkstra's body reads each.
    */
  def eraCert(raw: Bytes): DijkstraTxCert =
    val n = node(raw)
    val flat = DijkstraCert.decode(n).fold(e => fail(e.toString), identity)
    DijkstraProcedures.cert(flat, Right(n)).fold(e => fail(e.toString), identity)

  /** Proposals as the era reads them from the bytes `raw` of a set of them: the flat decoder's, at
    * the reference's types ([[DijkstraProcedures.proposal]]), as Dijkstra's body reads each.
    */
  def eraProposals(
      raw: Bytes
  ): xyz.kd5ujc.scarab.ledger.era.OSet[
    xyz.kd5ujc.scarab.ledger.era.ProposalProcedure[xyz.kd5ujc.scarab.ledger.state.Dijkstra]
  ] =
    val flat = DijkstraProposal.set(node(raw)).fold(e => fail(e.toString), identity)
    xyz.kd5ujc.scarab.ledger.era.OSet.decoded(
      flat.map(p => DijkstraProcedures.proposal(p).fold(e => fail(e.toString), identity))
    )

  /** Votes as the era reads them from the bytes `raw` of a map of them: the flat decoder's, at the
    * reference's types ([[DijkstraProcedures.votes]]), as Dijkstra's body reads them.
    */
  def eraVotes(
      raw: Bytes
  ): xyz.kd5ujc.scarab.ledger.era.VotingProcedures[xyz.kd5ujc.scarab.ledger.state.Dijkstra] =
    DijkstraProcedures.votes(
      xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraVoting
        .procedures(node(raw))
        .fold(e => fail(e.toString), identity)
    )

  /** No votes, as a body without the field reads them. */
  val noVotes
      : xyz.kd5ujc.scarab.ledger.era.VotingProcedures[xyz.kd5ujc.scarab.ledger.state.Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.VotingProcedures(scala.collection.immutable.SortedMap.empty)

  // ---- a purpose's item as both resolutions can say it, the flat rules' and the era types'

  def txIn(i: DijkstraTxIn): TxIn = TxIn(i.id, BigInt(i.index))

  def voter(v: DijkstraVoter): Voter = v match
    case DijkstraVoter.Committee(c) => Voter.CommitteeHot(c)
    case DijkstraVoter.DRep(c)      => Voter.DRepVoter(c)
    case DijkstraVoter.StakePool(p) => Voter.Pool(p)

  def keyOf(c: FlatCredential): Option[Bytes] = c match
    case FlatCredential.Key(h)    => Some(h)
    case FlatCredential.Script(_) => None

  /** The key that signs a certificate (`getVKeyWitnessDijkstraTxCert`, `TxCert.hs:342-351`,
    * cardano-ledger 9df9f98c).
    */
  def certKey(c: DijkstraCert): Option[Bytes] = c match
    case DijkstraCert.Register(c, _)                 => keyOf(c)
    case DijkstraCert.Unregister(c, _)               => keyOf(c)
    case DijkstraCert.Delegate(c, _)                 => keyOf(c)
    case DijkstraCert.RegisterDelegate(c, _, _)      => keyOf(c)
    case DijkstraCert.RegisterPool(p)                => Some(p.operator)
    case DijkstraCert.RetirePool(p, _)               => Some(p)
    case DijkstraCert.AuthorizeCommitteeHotKey(c, _) => keyOf(c)
    case DijkstraCert.ResignCommitteeColdKey(c, _)   => keyOf(c)
    case DijkstraCert.RegisterDRep(c, _, _)          => keyOf(c)
    case DijkstraCert.UnregisterDRep(c, _)           => keyOf(c)
    case DijkstraCert.UpdateDRep(c, _)               => keyOf(c)

  /** What a redeemer key names, as both resolutions can say it. */
  enum Item:
    case Input(in: TxIn)
    case Policy(policy: Bytes)
    case Cert(key: Option[Bytes], script: Option[Bytes])
    case Account(account: RewardAccount)
    case ByVoter(voter: Voter)
    case Proposal(proposal: DijkstraProposal)
    case Guard(script: Bytes)

  /** The item a redeemer key names, by the flat decoder's own resolution
    * ([[DijkstraPurposes.inverse]]): its tag, index and item.
    */
  def flatItem(p: DijkstraPurpose): (Int, BigInt, Item) = p match
    case DijkstraPurpose.Spending(ix, in) => (0, BigInt(ix), Item.Input(txIn(in)))
    case DijkstraPurpose.Minting(ix, pol) => (1, BigInt(ix), Item.Policy(pol))
    case DijkstraPurpose.Certifying(ix, c) =>
      (2, BigInt(ix), Item.Cert(certKey(c), DijkstraPurposes.certificateScript(c)))
    case DijkstraPurpose.Withdrawing(ix, a)   => (3, BigInt(ix), Item.Account(a))
    case DijkstraPurpose.Voting(ix, v)        => (4, BigInt(ix), Item.ByVoter(voter(v)))
    case DijkstraPurpose.Proposing(ix, p)     => (5, BigInt(ix), Item.Proposal(p))
    case DijkstraPurpose.Guarding(ix, script) => (6, BigInt(ix), Item.Guard(script))

  def eraItem(p: DijkstraPlutusPurpose[AsIxItem]): (Int, BigInt, Item) =
    import DijkstraPlutusPurpose.*
    val c = DijkstraTxCert.instance
    p match
      case DijkstraSpending(AsIxItem(ix, in))    => (0, ix.value, Item.Input(in))
      case DijkstraMinting(AsIxItem(ix, policy)) => (1, ix.value, Item.Policy(policy))
      case DijkstraCertifying(AsIxItem(ix, cert)) =>
        (2, ix.value, Item.Cert(c.getVKeyWitnessTxCert(cert), c.getScriptWitnessTxCert(cert)))
      case DijkstraWithdrawing(AsIxItem(ix, a)) =>
        (3, ix.value, Item.Account(Convert.rewardAccountBack(a)))
      case DijkstraVoting(AsIxItem(ix, v))    => (4, ix.value, Item.ByVoter(v))
      case DijkstraProposing(AsIxItem(ix, p)) => (5, ix.value, Item.Proposal(p.read))
      case DijkstraGuarding(AsIxItem(ix, h))  => (6, ix.value, Item.Guard(h.bytes))
