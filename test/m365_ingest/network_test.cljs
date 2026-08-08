(ns m365_ingest.network-test
  "docs/identity-claims.edn に固定した測定値を、**実際に取りに行って**照合する。

   既定では走らない（`nbb run_tests.cljs --network` で有効）。network を要る検査を
   既定にすると、回線が落ちているだけで赤くなり、赤の意味が薄まる。
   一方これを持たないと、claims は『誰も確かめていない散文』に戻る ——
   だから消さずに、明示的に呼べる場所に置く。

   curl を使うのは、claims の :measured-how がまさに curl の invocation だから。
   同じ道具で測って同じ道具で照合する。"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cljs.reader :as reader]
            [m365_ingest.didweb :as didweb]
            ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def enabled?
  (boolean (some #{"--network"} (vec *command-line-args*))))

(def repo-root (.cwd js/process))

(def claims
  (reader/read-string (.readFileSync fs (path/join repo-root "docs/identity-claims.edn") "utf8")))

(defn- curl [& args]
  (let [r (cp/spawnSync "curl" (clj->js (vec args)) #js {:encoding "utf8" :shell false})]
    (str (.-stdout r))))

(defn- http-status
  "curl の %{http_code}。**0 は『接続そのものが失敗』**（DNS 不在・TLS 不成立）
   であって、空応答でも 0 件でもない。"
  [url]
  (js/parseInt (str/trim (curl "-sL" "--max-time" "15" "-o" "/dev/null" "-w" "%{http_code}" url)) 10))

(defn- fetch-json [url]
  (let [body (curl "-sL" "--max-time" "15" url)]
    (try (js->clj (js/JSON.parse body)) (catch :default _ nil))))

(defn- committed-did-doc []
  (js->clj (js/JSON.parse (.readFileSync fs (path/join repo-root ".well-known/did.json") "utf8"))))

(defn- claim-by-id [id]
  (first (filter #(= id (:id %)) (:claims claims))))

(deftest each-claim-resolves-exactly-as-the-claims-file-records
  "測定値と実測が食い違ったら赤。**直った側にも落ちる** —— 例えば
   m365-ingest.etzhayyim.com のホストが生えたら 0 → 200 で赤くなり、
   claims を測り直せという意味になる。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (doseq [c (:claims claims)]
      (testing (str (:id c) " " (:resolves-to c))
        (is (= (:http (:measured c)) (http-status (:resolves-to c))))))))

(deftest each-surface-responds-exactly-as-the-claims-file-records
  "配信面（GitHub Pages・@context・両 PDS・appview）も同じ扱い。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (doseq [s (:surfaces claims)]
      (testing (str (:id s) " " (:url s))
        (is (= (:http (:measured s)) (http-status (:url s))))))))

(deftest the-did-this-repo-actually-names-does-not-resolve-at-all
  "**この repo の identity の要点。** substrate と manifest が一致して名乗る DID は
   DNS に存在しない —— 揃っているのに、揃っている先が無い。
   ホストが生えたら赤くなり、claims を測り直せという意味になる。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [c (claim-by-id :did/substrate)]
      (is (= 0 (:http (:measured c))) "claims 側の記録が 0 でなくなっている")
      (is (= 0 (http-status (:resolves-to c)))
          "repo が名乗る DID が解決するようになった。claims を測り直すこと"))))

(deftest the-did-that-resolves-returns-a-document-that-claims-that-same-did
  "200 が返ってくるだけでは足りない —— **返ってきた document の id が、
   その URL を導いた DID と同じ**でなければ、did:web としては無効である。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [c (claim-by-id :did/committed)
          doc (fetch-json (:resolves-to c))]
      (is (some? doc) "DID document が JSON として読めない")
      (is (= (:did c) (get doc "id")))
      (is (= (:resolves-to c) (didweb/did->document-url (get doc "id")))))))

(deftest the-resolving-did-is-one-no-file-in-this-repo-names
  "解決する DID を、substrate も manifest も名乗っていない。**唯一名乗って
   いるのは、どこにも publish されていない .well-known/did.json だけ。**
   どれかが名乗り始めたら赤くなる（それは改善なので、claims を更新する）。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [resolving (:did (claim-by-id :did/committed))
          substrate (:did (claim-by-id :did/substrate))
          manifest  (:did (claim-by-id :did/manifest))]
      (is (not= resolving substrate))
      (is (not= resolving manifest)))))

(deftest the-resolving-document-is-not-the-one-this-repo-committed
  "id は解決するのに、解決先の document は commit したものと違う
   （commit 側は死んだ PDS を指している）。

   揃ったら赤くなる —— そのときは claims の :did/committed を
   :serves-committed-bytes? true に測り直すという意味。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [c (claim-by-id :did/committed)
          live (fetch-json (:resolves-to c))]
      (is (= (:serves-committed-bytes? (:measured c))
             (= live (committed-did-doc)))))))

(deftest the-live-document-still-declares-the-primary-lexicon-the-claims-record
  "substrate の collection prefix の**権威**がここにある
   （docs/identity-claims.edn の :lexicon/:authority-primary-lexicon）。
   権威が動いたら、substrate 側の正しさの根拠も動く。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [c (claim-by-id :did/committed)
          live (fetch-json (:resolves-to c))]
      (is (= (:authority-primary-lexicon (:lexicon claims))
             (get-in live ["_meta" "primaryLexicon"]))))))

(deftest the-pds-the-committed-document-points-at-is-still-the-dead-one
  "commit 側 did.json の service endpoint が、claims が記録した
   :pds/committed（530）と同じホストを指し続けていること。
   誰かが endpoint だけ直したらここで赤くなる。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [pds (->> (get (committed-did-doc) "service")
                   (filter #(= "AtprotoPersonalDataServer" (get % "type")))
                   first
                   (#(get % "serviceEndpoint")))
          recorded (:url (first (filter #(= :pds/committed (:id %)) (:surfaces claims))))]
      (is (= recorded pds)
          (str "commit 側 PDS " pds " が claims の " recorded " と別ホストになった")))))

(deftest the-committed-document-is-published-nowhere
  "`.well-known/did.json` を repo に置いたことと、それが配信されていることは別。
   Pages が有効になったら赤くなり、:publication を測り直せという意味になる。"
  (if-not enabled?
    (println "   (skip: --network 無し)")
    (let [pages (first (filter #(= :pages (:id %)) (:surfaces claims)))]
      (is (false? (:github-pages-enabled? (:publication claims))))
      (is (= (:http (:measured pages)) (http-status (:url pages)))))))
