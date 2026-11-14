{-# LANGUAGE DataKinds #-}
{-# LANGUAGE OverloadedStrings #-}
-- SPDX-License-Identifier: Apache-2.0
-- The reference's answer to the GetInterpreter query for a chain that starts in Byron (#118):
-- `interpretQueryHardFork` answers `mkInterpreter (hardForkSummary cfg st)` and encodes it with
-- `Serialise` (ouroboros-consensus 82ecba32 `HardFork/Combinator/Ledger/Query.hs:529, 562`);
-- `hardForkSummary` is `reconstructSummary` over the era parameters of each era and the eras the
-- state has passed. The era parameters are the production `byronEraParams` and `shelleyEraParams`
-- of the genesis files given on the command line.
module Main where

import qualified Codec.Serialise as Serialise
import Control.Monad.Except (runExceptT)
import Data.Aeson (Value, eitherDecodeFileStrict, encode, object, (.=))
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.SOP.BasicFunctors (K (..))
import Data.SOP.Counting (Exactly (..))
import Data.SOP.Telescope (Telescope (..))
import Cardano.Chain.Genesis (mkConfigFromFile, readGenesisData, unGenesisHash)
import Cardano.Crypto.ProtocolMagic (RequiresNetworkMagic (..))
import Cardano.Ledger.Shelley.Genesis (ShelleyGenesis)
import Cardano.Slotting.Slot (EpochNo (..), SlotNo (..), WithOrigin (..))
import Ouroboros.Consensus.Byron.Ledger.Ledger (byronEraParams)
import Ouroboros.Consensus.HardFork.Combinator.State.Infra (reconstructSummary)
import Ouroboros.Consensus.HardFork.Combinator.State.Types
import Ouroboros.Consensus.HardFork.History
import Ouroboros.Consensus.Shelley.Ledger.Ledger (shelleyEraParams)
import System.Environment (getArgs)

-- | The eras of `CardanoEras` (`Cardano/Block.hs:245-257`): Byron, Shelley, Allegra, Mary, Alonzo,
-- Babbage, Conway and Dijkstra.
type Eras = '[(), (), (), (), (), (), (), ()]

hex :: BL.ByteString -> String
hex = BL.unpack . Builder.toLazyByteString . Builder.lazyByteStringHex

main :: IO ()
main = do
  [byronFile, shelleyFile, output] <- getArgs
  -- The configuration as the node builds it from the file; the expected hash is the file's own.
  (_, hash) <- either (error . show) id <$> runExceptT (readGenesisData byronFile)
  byron <- either (error . show) byronEraParams <$> runExceptT (mkConfigFromFile RequiresMagic byronFile (unGenesisHash hash))
  shelley <- either error shelleyEraParams <$> (eitherDecodeFileStrict shelleyFile :: IO (Either String ShelleyGenesis))
  let shape :: Shape Eras
      shape = Shape $ ExactlyCons byron $ ExactlyCons shelley $ ExactlyCons shelley $ ExactlyCons shelley $
        ExactlyCons shelley $ ExactlyCons shelley $ ExactlyCons shelley $ ExactlyCons shelley ExactlyNil
      -- Byron ends at epoch 4 and every later era up to Conway begins there.
      shelleyStart = mkUpperBound byron initBound (EpochNo 4)
      past b e = K (Past b e)
      inConway :: HardForkState (K ()) Eras
      inConway = HardForkState $ TS (past initBound shelleyStart) $ TS (past shelleyStart shelleyStart) $
        TS (past shelleyStart shelleyStart) $ TS (past shelleyStart shelleyStart) $
        TS (past shelleyStart shelleyStart) $ TS (past shelleyStart shelleyStart) $
        TZ (Current shelleyStart (K ()))
      inByron :: HardForkState (K ()) Eras
      inByron = HardForkState $ TZ (Current initBound (K ()))
      case_ :: String -> String -> HardForkState (K ()) Eras -> TransitionInfo -> Value
      case_ name scenario st info =
        let summary = reconstructSummary shape info st
         in object
              [ "name" .= name
              , "scenario" .= scenario
              , "transition" .= show info
              , "summary" .= show summary
              , "interpreter" .= hex (Serialise.serialise (mkInterpreter summary))
              ]
      tip = TransitionUnknown . At . SlotNo
      cases =
        [ case_ "conway-tip-100000000" "Byron to epoch 4, Shelley to Babbage empty, Conway from epoch 4" inConway (tip 100000000)
        , case_ "byron-origin" "still in Byron, at the origin" inByron (TransitionUnknown Origin)
        , case_ "byron-known-4" "in Byron, the transition to Shelley known at epoch 4" inByron (TransitionKnown (EpochNo 4))
        ]
  BL.writeFile output $ BL.unlines (map encode cases)
