(ns m365_ingest.didweb
  "did:web / AT handle → document URL の解決規則。**test 側の道具**であって
   substrate ではない（この repo の src/ は murakumo gate だけを持つ）。

   なぜ test 側に置くか: docs/identity-claims.edn の `:resolves-to` を
   **手で書かせないため**。手書きの URL は、解決規則を 1 箇所間違えただけで
   『存在しない URL を測って 404 だと報告する』—— 測定は動いているように
   見えるので、間違いに気づく手がかりが無い。claims の URL は必ずここから
   導出し、突き合わせる。

   兄弟 repo `cloud-itonami/jp-ashiba-actor` の同名 file と同じ規則。
   共有ライブラリに切り出していないのは、`.well-known` の非対称という
   仕様の一点だけを持つ 40 行で、依存を 1 本増やす方が高くつくため。
   3 つ目の repo が要るようになったら抽出する。"
  (:require [kotoba.lang.text :as str]))

(defn did->document-url
  "did:web の DID から、DID document が置かれているべき URL を導く。
   did:web でないもの・空のものは nil。

   規則（W3C did:web）: method-specific id の ':' を '/' に置き換えて https:// を
   付ける。**path 成分が無いときだけ** `/.well-known/` を挟む —— この非対称が
   唯一の落とし穴で、この repo の 2 つの DID はちょうどその両側にある:

     did:web:m365-ingest.etzhayyim.com        → /.well-known/did.json  （path 無し）
     did:web:etzhayyim.com:actor:m365-ingest  → /actor/m365-ingest/did.json（path 有り）

   **host のポート percent-encoding（`did:web:localhost%3A8080`）は扱わない。**
   この repo のどの claim もポートを持たないため。素通しにすると気づけないので、
   『claim に % が現れたら落ちる』test を repo-test 側に置いてこの前提を守る。"
  [did]
  (when (and (string? did) (str/starts-with? did "did:web:"))
    (let [msi (subs did (count "did:web:"))
          segs (remove str/blank? (str/split msi #":"))]
      (when (seq segs)
        (let [host (first segs)
              path (rest segs)]
          (if (seq path)
            (str "https://" host "/" (str/join "/" path) "/did.json")
            (str "https://" host "/.well-known/did.json")))))))

(defn at-handle->did-url
  "AT Protocol の handle（`at://<host>`）から、その handle が DID を宣言している
   べき URL を導く。did:web とは別の規則なので did->document-url に混ぜない。"
  [handle]
  (when (and (string? handle) (str/starts-with? handle "at://"))
    (let [host (subs handle (count "at://"))]
      (when-not (str/blank? host)
        (str "https://" host "/.well-known/atproto-did")))))

(defn resolution-url
  "claim の :did から :resolves-to を導く。https:// の claim（移行元 repo など）は
   それ自身が URL なのでそのまま返す。did:web でも at:// でも https でもなければ nil
   （例: rad: の CID —— HTTP で解決する対象ではない）。"
  [did]
  (cond
    (not (string? did))                 nil
    (str/starts-with? did "https://")   did
    :else (or (did->document-url did) (at-handle->did-url did))))
