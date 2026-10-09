{-# LANGUAGE TypeApplications #-}
module Main where
import Cardano.Chain.Slotting (EpochSlots(..))
import qualified Cardano.Network.NodeToClient.Version as N
import Cardano.Protocol.Crypto (StandardCrypto)
import Ouroboros.Consensus.Cardano.Node (protocolClientInfoCardano)
import Ouroboros.Consensus.Node.ProtocolInfo (ProtocolClientInfo(..))
import System.Environment (getArgs)
import System.Exit (die)
import Adapter
import StrictJson
import Transport

main :: IO ()
main = do
  args <- getArgs
  if not (null args) then die "query adapter takes JSON stdin only" else pure ()
  req <- readInput 16384 >>= either die pure . parseRequest
  let config = pClientInfoCodecConfig (protocolClientInfoCardano @StandardCrypto (EpochSlots (byronSlots req)))
      versionData = N.NodeToClientVersionData {N.networkMagic = N.NetworkMagic (magic req), N.query = False}
  captured <- captureAt config versionData (exactWireVersion req) (socketPath req) (requestedPoint req)
  either die writeOutput (captured >>= captureEnvelope req)
