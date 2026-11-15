// SPDX-License-Identifier: Apache-2.0
import java.io.File

/** The parsers of STR-2 (docs/design-rules.md): the main sources whose code reads text from outside the process
  * (CLI arguments, the environment, configuration, genesis and topology files, file names, the operating
  * system's text, wire text), in which `-Wconf` silences the wart StringControlFlow (STR-3). By file, not by
  * package: ledger keeps its genesis parsing beside the rules. A source joins only as a parser; a decision
  * on a retained String elsewhere is fixed instead, or kept with `@SuppressWarnings` and its reason.
  */
object StringParsers {
  /** Crypto: the operating system's and the native library's text (`/proc/cpuinfo`, `os.name`, resource names). */
  private val crypto: Seq[String] = Seq(
    "crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/bls/Blst.scala"
  )

  /** Ledger: the genesis files, the injection of its parameters, JSON, and the persisted-format version text. */
  private val ledger: Seq[String] = Seq(
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/GenesisCostModels.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/GenesisPoolFields.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/GenesisState.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/GovState.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/NetworkIdentity.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/ProtocolParams.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/injection/InjectionLexer.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/json/StrictBase64.scala",
    "ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/GenesisStart.scala"
  )

  /** Models-gen: the CDDL parser and the generator of Scala source; models: the text keys of CBOR maps. */
  private val models: Seq[String] = Seq(
    "models-gen/src/main/scala/xyz/kd5ujc/scarab/models/gen/CddlParser.scala",
    "models-gen/src/main/scala/xyz/kd5ujc/scarab/models/gen/Emitter.scala",
    "models-gen/src/main/scala/xyz/kd5ujc/scarab/models/gen/Schema.scala",
    "models/src/main/scala/xyz/kd5ujc/scarab/models/runtime/Codec.scala"
  )

  /** Network and protocol: socket paths and the operating system's error names. */
  private val networkAndProtocol: Seq[String] = Seq(
    "network/src/main/scala/xyz/kd5ujc/scarab/network/LocalSocket.scala",
    "protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/ReferenceBuild.scala",
    "protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/SystemError.scala"
  )

  /** Node: the command line, the configuration, topology and log settings, the query server's parameters, key
    * files, `/proc`, and the commands the node ships (`node.tools`).
    */
  private val node: Seq[String] = Seq(
    "node/src/main/scala/xyz/kd5ujc/scarab/node/CommandLine.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/LedgerDbSettings.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/LeiosSettings.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/Main.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/MemoryReport.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/NodeConfig.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/NodeRootProvider.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/Query.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/StateDump.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/Topology.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/forge/KesFiles.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/forge/KeyFiles.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/log/LogSettings.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/log/Severity.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/metrics/Collectors.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/CaptureMain.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/HaskellImportCommand.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/LedgerSnapshotExportCommand.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/LedgerSnapshotImportCommand.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/LeiosCaptureMain.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/MithrilBootstrapCommand.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/PlutusCases.scala",
    "node/src/main/scala/xyz/kd5ujc/scarab/node/tools/SclsCommand.scala"
  )

  /** Plutus and uplc: builtin and cost-model parameter names, and the textual syntax of UPLC. */
  private val plutusAndUplc: Seq[String] = Seq(
    "plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/PlutusVm.scala",
    "uplc/src/main/scala/xyz/kd5ujc/scarab/uplc/DefaultFun.scala",
    "uplc/src/main/scala/xyz/kd5ujc/scarab/uplc/cost/EvaluationContext.scala",
    "uplc/src/main/scala/xyz/kd5ujc/scarab/uplc/text/UplcParser.scala"
  )

  /** Storage: file and directory names, manifests, Mithril artifacts and the format text of the stores. */
  private val storage: Seq[String] = Seq(
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/CheckpointBinding.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/ImmutableStore.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/LedgerSnapshotExport.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/LedgerSnapshotImport.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/LedgerSnapshots.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/RocksUtxoStore.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotFormat.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotMigration.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotPoint.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/StateBacking.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/VolatileStore.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/haskell/HaskellImmutableReader.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/haskell/state/AncillaryManifest.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/haskell/state/SnapshotMeta.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/mithril/MithrilClient.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/scls/SclsEntrySorter.scala",
    "storage/src/main/scala/xyz/kd5ujc/scarab/storage/scls/SclsRecord.scala"
  )

  val files: Seq[String] = crypto ++ ledger ++ models ++ networkAndProtocol ++ node ++ plutusAndUplc ++ storage

  private val special = "\\.[]{}()*+?^$|".toSet

  /** The characters that end a filter or its condition in `-Wconf`, whatever the regex says. */
  private val delimiters = Map(':' -> "\\x3a", '&' -> "\\x26", ',' -> "\\x2c")

  private def literal(text: String): String =
    text.flatMap(c => delimiters.getOrElse(c, if (special(c)) s"\\$c" else c.toString))

  /** One `-Wconf` per file, its regex anchored at both ends to the file under `root`, so that it matches that
    * file only: the path as the compiler matches it (`toPath.toAbsolutePath.toUri.normalize.getRawPath`), with
    * the characters `-Wconf` splits on written as hex escapes. A file that is not there fails the build when it
    * loads. A `:` in a filter's regex breaks the parsing of every `-Wconf`, so `.` stands for the `:` of the
    * message's `[wartremover:StringControlFlow]`.
    */
  def options(root: File): Seq[String] =
    files.map { path =>
      require(new File(root, path).isFile, s"StringParsers lists $path, which is not a file")
      val source = literal(new File(root, path).toPath.toAbsolutePath.toUri.normalize.getRawPath)
      s"-Wconf:src=^$source$$&msg=wartremover.StringControlFlow:s"
    }
}
