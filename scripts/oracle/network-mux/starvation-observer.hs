-- SPDX-License-Identifier: Apache-2.0
-- Appended only to the pinned Test.Mux in the disposable offline source tree.
-- Keep the original Uneven generator, implication and response1/response1 wiring unchanged.
exportStarvationCases :: FilePath -> Int -> Int -> IO ()
exportStarvationCases path count seed = withFile path WriteMode $ \outputHandle ->
  forM_ [0 .. count - 1] $ \ordinal -> do
    let caseSeed = seed + ordinal
        size = 30 + ordinal `mod` 11
        sample@(Uneven response0 response1) = unGen arbitrary (mkQCGen caseSeed) size
        request = DummyPayload $ BL.replicate 4 0xa
        settings = stdArgs { chatty = False, maxSuccess = 1, maxDiscardRatio = 1,
                             maxShrinks = 0, replay = Just (mkQCGen caseSeed, size) }
    observed <- newTVarIO []
    verdict <- quickCheckWithResult settings $ once $
      prop_mux_starvationObserved (\es -> atomically $ writeTVar observed es) sample
    trace <- readTVarIO observed
    let outcome = case verdict of
          Test.QuickCheck.Success {} -> "pass"
          Test.QuickCheck.GaveUp {}  -> "precondition-discard"
          _          -> "upstream-failure"
    hPutStrLn outputHandle $ intercalate "\t"
      [ "mux-starvation-raw-v1", show caseSeed, show ordinal, show size
      , hex (unDummyPayload response0), hex (unDummyPayload response1)
      , wire (MsgReq request), wire (MsgResp response0), wire (MsgResp response1)
      , intercalate "," [show n | Mx.MiniProtocolNum n <- trace]
      , outcome ]
  where
    hex = concatMap (pad . (`showHex` "")) . BL.unpack
    pad [digit] = ['0', digit]
    pad digits = digits
    wire :: MsgReqResp DummyPayload DummyPayload -> String
    wire = hex . serialise
