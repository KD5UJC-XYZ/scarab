// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{TxIn, Utxo}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.era.{EraStake, StoredTxOut, UTxO}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraTxOut}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, UtxoTables}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, MempoolEnv}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.LeiosEb
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** The public Leios testnet's endorser block of slot 1,455,869
  * (`fixtures/leios/testnet/eb-1455869.tsv.gz`, 323 transactions, every one a key-witnessed
  * payment) with the 345 outputs its closure spends that it does not make itself
  * (`eb-1455869-utxo.tsv.gz`), on the testnet's ledger state of slot 2,168,639
  * (`fixtures/leios/state/testnet-2168639`, a state file the reference wrote, whose UTxO set is in
  * tables the fixture does not hold), the recorded outputs its UTxO set. The closure's transactions
  * carry no validity interval and the parameters they meet are the testnet's (its two parameter
  * changes are of the endorser-block limits), so the closure applies on that state at the slot
  * after it.
  */
object RecordedLeiosClosure extends munit.Assertions:
  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)

  private val testnet = Path.of("fixtures", "leios", "testnet")

  private def gunzipped(name: String): Vector[Vector[String]] =
    new String(
      new GZIPInputStream(new ByteArrayInputStream(Files.readAllBytes(testnet.resolve(name))))
        .readAllBytes(),
      StandardCharsets.US_ASCII
    ).linesIterator.map(_.split('\t').toVector).toVector

  private lazy val ebRows = gunzipped("eb-1455869.tsv.gz")

  /** The endorser block's point as the store held it. */
  lazy val point: EbPoint =
    ebRows.collectFirst { case Vector("point", s, h, _) =>
      EbPoint(Word64.clamped(BigInt(s)), EbHash.fromBytes(DijkstraCorpus.hex(h)))
    }.get

  /** The closure as the store returns it (`lookupEbClosure`): each transaction's hash and bytes, in
    * the body's order.
    */
  lazy val stored: Vector[(LeiosTxHash, Bytes)] =
    ebRows.collect { case Vector("tx", _, t) =>
      val raw = DijkstraCorpus.hex(t)
      LeiosEb.hashTx(raw) -> raw
    }

  /** The outputs the closure spends from before it, as the chain made them. */
  lazy val spent: Vector[(TxIn, DijkstraTxOut)] =
    gunzipped("eb-1455869-utxo.tsv.gz").collect { case Vector("utxo", i, o) =>
      DijkstraTxIn
        .decode(DijkstraCorpus.node(DijkstraCorpus.hex(i)))
        .fold(e => fail(e.toString), DijkstraCorpus.txIn) ->
        DijkstraTxOut
          .decode(DijkstraCorpus.node(DijkstraCorpus.hex(o)))
          .fold(e => fail(e.toString), identity)
    }

  /** The node's setup of the testnet's configuration. */
  lazy val setup: NodeSetup =
    val dir = testnet.resolve("config")
    val config = right(
      NodeConfig
        .parse(ReferenceBuild.LeiosB004fe0c)(Files.readString(dir.resolve("config.json")), true)
        .left
        .map(_.message)
    )
    right(
      NodeSetup.assemble(ReferenceBuild.LeiosB004fe0c)(
        config,
        name => Option.when(Files.exists(dir.resolve(name)))(Files.readAllBytes(dir.resolve(name)))
      )
    )

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  /** The testnet's state of slot 2,168,639: the extended state's ledger telescope at its eighth
    * era, `[start, [2, [tip, NewEpochState, transition, bytes]]]`.
    */
  lazy val recordedState: NewEpochState[Dijkstra] =
    val raw = Bytes.wrap(Files.readAllBytes(Path.of("fixtures/leios/state/testnet-2168639/state")))
    val root = right(Cbor.decode(raw, HaskellState.limits))
    val nes = Vector(1, 0, 7, 1, 1, 1).foldLeft(root)((n, i) => items(n)(i))
    right(LeiosStateCodecs.newEpochState[Dijkstra].decode(nes).left.map(_.render))

  /** The recorded state with the spent outputs its UTxO set, their stake its instant stake. */
  lazy val seeded: NewEpochState[Dijkstra] =
    val ls = recordedState.es.ls
    val utxo = UTxO[Dijkstra](spent.toMap)
    val seededLs = ls.copy[Dijkstra](utxo =
      ls.utxo.copy[Dijkstra](
        utxo = UtxoTables(Utxo(spent.map((i, o) => i -> StoredTxOut.dijkstra.toStored(o)).toMap)),
        instantStake = right(EraStake.dijkstra.addInstantStake(utxo, ls.utxo.instantStake))
      )
    )
    recordedState.copy[Dijkstra](es = recordedState.es.copy[Dijkstra](ls = seededLs))

  /** The slot the closure is applied at: the one after the state's. */
  val slot: BigInt = BigInt(2168640)

  /** The pool's environment at [[slot]]. */
  lazy val env: DijkstraPoolEnv =
    DijkstraPoolEnv.of(
      MempoolEnv(
        setup.globals,
        setup.geometry,
        PlutusBackend.default.evaluator,
        PlutusBackend.default.decoder
      ),
      slot
    )
