// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.Assertions

/** The Dijkstra genesis of the public Leios testnet (`fixtures/leios/testnet/config`). */
object DijkstraFixture extends Assertions:
  val genesis: DijkstraGenesis =
    parser
      .parse(Files.readString(Path.of("fixtures/leios/testnet/config/dijkstra-genesis.json")))
      .left
      .map(_.message)
      .flatMap(j => DijkstraGenesis.read(j).left.map(_.message))
      .fold(fail(_), identity)
