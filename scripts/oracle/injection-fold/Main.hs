{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE OverloadedStrings #-}
{-# LANGUAGE ScopedTypeVariables #-}
-- SPDX-License-Identifier: Apache-2.0
module Main where
import Cardano.Ledger.Shelley.Genesis (InjectionData, InjectionError(..), foldInjectionData)
import Control.Exception (SomeException, displayException, fromException, try)
import qualified Data.Aeson.Key as K
import qualified Data.Aeson.KeyMap as KM
import qualified Data.Scientific as Sci
import qualified Data.Vector as V
import qualified Data.Text as T
import qualified Data.Aeson as A
import Data.Aeson ((.=), (.:), (.:?))
import Data.Aeson.Types (parseEither)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy.Char8 as BL
import Data.IORef
import Data.Text (Text)
import System.FS.API (HasFS(..), MountPoint(..), FsError)
import System.FS.IO (ioHasFS)
import Data.Word (Word64)
import System.Timeout (timeout)
import System.IO (hSetBuffering, stdout, BufferMode(LineBuffering))

data Work = Visit A.Value | Key Text
flatten :: A.Value -> [A.Value]
flatten rootValue = go [Visit rootValue] [] where
  row = A.Array . V.fromList
  tag = A.String
  go [] !acc = reverse acc
  go (Key key:rest) !acc = go rest (row [tag "key",tag key]:acc)
  go (Visit v:rest) !acc = case v of
    A.Null -> go rest (row [tag "null"]:acc)
    A.Bool b -> go rest (row [tag "bool",A.Bool b]:acc)
    A.String t -> go rest (row [tag "string",tag t]:acc)
    A.Number n -> go rest (row [tag "number",tag (T.pack $ show $ Sci.coefficient n),tag (T.pack $ show $ Sci.base10Exponent n)]:acc)
    A.Array xs -> go (map Visit (V.toList xs) ++ rest) (row [tag "array",A.toJSON (V.length xs)]:acc)
    A.Object fields -> go (concatMap (\(k,x) -> [Key (K.toText k),Visit x]) (KM.toList fields) ++ rest) (row [tag "object",A.toJSON (KM.size fields)]:acc)


data Case = Case Text A.Value Text (Maybe Word64)
instance A.FromJSON Case where
  parseJSON = A.withObject "Case" $ \o -> Case <$> o .: "name" <*> o .: "source" <*> o .: "mode" <*> o .:? "shortRead"

run :: Case -> IO A.Value
run (Case name source mode shortRead) = do
  events <- newIORef ([] :: [A.Value])
  let record x = modifyIORef' events (x:)
      base = ioHasFS (MountPoint "/inputs")
      fs = base {
        hOpen = \p m -> do
          record (A.object ["open" .= show p])
          hOpen base p m,
        hClose = \h -> do
          record (A.object ["close" .= True])
          hClose base h,
        hGetSome = \h n -> do
          bs <- hGetSome base h (maybe n (min n) shortRead)
          record (A.object ["requested" .= n, "returned" .= BS.length bs])
          pure bs
      }
      perform :: (A.FromJSON k, A.FromJSONKey k, Ord k, A.ToJSON k, A.FromJSON v, A.ToJSON v) => Either String (InjectionData k v) -> IO A.Value
      perform parsed = case parsed of
        Left err -> pure (A.object ["sourceParseError" .= err])
        Right injection -> A.toJSON . reverse <$> foldInjectionData fs injection (flip (:)) []
      execute = if mode == "integers" then
          perform (parseEither A.parseJSON source :: Either String (InjectionData Integer Integer))
        else perform (parseEither A.parseJSON source :: Either String (InjectionData Text A.Value))
  result <- try execute
  history <- reverse <$> readIORef events
  let payload = case result of
        Right value -> ["result" .= value, "resultEvents" .= flatten value]
        Left (err :: SomeException) -> case fromException err of
          Just (InjectionHashMismatch path expected actual) ->
            ["error" .= ("hash" :: Text), "path" .= show path, "expected" .= expected, "actual" .= actual]
          Just (InjectionParseError path detail) ->
            ["error" .= ("parse" :: Text), "path" .= show path, "detail" .= detail]
          Just e -> ["error" .= ("injection" :: Text), "detail" .= show (e :: InjectionError)]
          Nothing -> case fromException err of
            Just e -> ["error" .= ("filesystem" :: Text), "detail" .= displayException (e :: FsError)]
            Nothing -> ["error" .= ("unexpected" :: Text), "detail" .= displayException err]
  pure $ A.object (["name" .= name, "events" .= history] ++ payload)

main :: IO ()
main = do
  hSetBuffering stdout LineBuffering
  input <- BL.getContents
  mapM_ one (filter (not . BL.null) (BL.lines input))
  where
    one line = case A.eitherDecode line of
      Left err -> BL.putStrLn (A.encode (A.object ["inputError" .= err]))
      Right value@(Case name _ _ _) -> do
        result <- timeout 5000000 (run value)
        BL.putStrLn $ A.encode $ maybe (A.object ["name" .= name, "probeTimeout" .= True]) id result
