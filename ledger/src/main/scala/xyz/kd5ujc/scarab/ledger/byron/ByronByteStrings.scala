// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import xyz.kd5ujc.scarab.ledger.ByteStrings

/** Byron's values are read by their `DecCBOR` instances at `byronProtVer`, decoder version 1
  * (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Version.hs:162-163`, cardano-ledger
  * f649f975), below the version from which a chunked byte string is read ([[ByteStrings]]).
  */
private[byron] given ByteStrings = ByteStrings.Definite
