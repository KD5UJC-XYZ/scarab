// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode}
import java.nio.file.Path
import lab.submission.{AdmissionProfile, SignedTransaction}
import ReferenceJson.{Json as J, field, string}
import PlutusResearchIO.{get, read, sha, record, text, num, bool, point, hash}

/** File/IO adapter only. No state hydration, runtime admission or native generation occurs here. */
object PlutusRepeatedServiceCompareMain extends IOApp:
  private[lab] def compare(args: List[String]): J =
    require(args.size == 7, "INITIAL MANIFESTPIN OUTPUT ENDPOINT_EXCHANGE TX1 TX2 RESULT")
    val initial = PlutusResearchIO.initial(Path.of(args(0)), args(1), AdmissionProfile.PlutusV3)
    val output = Path.of(args(2)); val exchange = Path.of(args(3))
    val raw = read(output.resolve("terminal-observation.json"), RepeatedPlutusTerminal.MaxBytes)
    val observation = get(RepeatedPlutusTerminal.read(raw))
    require(
      observation.source == initial.id && observation.manifest.hex == args(1),
      "terminal source pins"
    )
    val pin = observation.pin
    val epochLength = initial.ledger.globals.geometry.epochLength
    require(
      pin.point.slot >= initial.acquisition.anchor.slot &&
        pin.point.slot / epochLength == observation.epoch && observation.epoch >= initial.ledger.globals.epoch &&
        observation.epoch - initial.ledger.globals.epoch <= 8,
      "bounded terminal epoch/point"
    )
    val terminal =
      read(output.resolve("terminal-output-map.cbor"), PlutusServiceRuntime.MaxTerminalBytes)
    val ready = ReferenceJson.parse(read(exchange.resolve("endpoint-ready.json"), 16384))
    val endpoint = PlutusResearchIO.endpoint(exchange, ready, pin.point, initial)
    require(
      endpoint.anchor == pin.point && endpoint.networkMagic == initial.acquisition.networkMagic,
      "terminal acquisition fullpoint/network binding"
    )
    val sources = endpoint.originals
    val whole = sources("original-whole-utxo.cbor")
    val compared = get(
      RepeatedPlutusTerminal.compareComponents(
        observation,
        initial.acquisition.originals("derived-full-epoch-seed.cbor"),
        sources("derived-full-epoch-seed.cbor"),
        sources("original-debug-epoch.cbor"),
        whole,
        terminal,
        sources("original-debug-protocol.cbor"),
        initial.ledger.globals
      )
    )
    val txs = args
      .slice(4, 6)
      .map(path => get(SignedTransaction.checked(read(Path.of(path), 65536))))
      .toVector
    // Reward application may reset the fee pot. Compare exact transaction effects separately;
    // fees and complete supply are compared with the native terminal by the pure core above.
    val identities = PlutusServiceCompareMain.compareEffects(
      initial.ledger.epochComponents.stake.utxo,
      whole,
      terminal,
      txs
    )
    record(
      "schema" -> text("plutus-repeated-service-endpoint-comparison-v1"),
      "terminalPoint" -> point(pin.point),
      "terminalPin" -> PlutusServiceRuntime.pin(pin),
      "sourceJoinId" -> text(initial.id.hex),
      "initialManifestSHA256" -> text(args(1)),
      "terminalObservationSHA256" -> text(sha(raw).hex),
      "outputMapSHA256" -> text(sha(terminal).hex),
      "endpointAcquisitionId" -> text(endpoint.id.hex),
      "endpointWholeUtxoSHA256" -> text(sha(whole).hex),
      "endpointManifestSHA256" -> text(hash(field(ready, "manifestSHA256")).hex),
      "endpointAcquisitionResultSHA256" -> text(hash(field(ready, "acquisitionResultSHA256")).hex),
      "epoch" -> num(compared.epoch),
      "feesBefore" -> num(initial.ledger.epochComponents.pots.fees),
      "feesAfter" -> num(compared.fees),
      "entries" -> num(compared.entries),
      "transactions" -> J.Arr(identities),
      "completeUtxoEqual" -> bool(true),
      "collateralPreserved" -> bool(true),
      "instantaneousStakeEqual" -> bool(true),
      "snapshotsEqual" -> bool(true),
      "epochComponentsEqual" -> bool(true),
      "governanceEqual" -> bool(true),
      "rewardStateEqual" -> bool(true),
      "nonMyopicRawBitsEqual" -> bool(true),
      "representedProtocolEqual" -> bool(true),
      "fullLedgerValidated" -> bool(false),
      "restartSupported" -> bool(false),
      "diagnosticOnly" -> bool(true)
    )

  def run(args: List[String]): IO[ExitCode] = IO
    .blocking(compare(args))
    .flatMap(result => PlutusResearchIO.save(Path.of(args(6)), result))
    .as(ExitCode.Success)
    .handleErrorWith(e =>
      IO.println("PLUTUS_REPEATED_SERVICE_COMPARISON_FAILED: " + e.getMessage).as(ExitCode.Error)
    )
