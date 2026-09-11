(ns m365_ingest.graph
  "Microsoft Graph の delta 応答を、provider に依らない page に翻訳する。純関数。

   ここには credential も HTTP client も無い。request map を返し、response map を
   受けるだけで、送るのは host である（`connector.ports/IHttp` と同じ形なので、
   `com-microsoft-graph` connector と同じ transport を 1 つ注いで両方が動く）。

   ## Graph 固有で、間違えると静かに壊れる 3 つ

   **`@odata.nextLink` と `@odata.deltaLink` は同じ場所に来ない。** 前者は
   『この run にまだ続きがある』、後者は『この run は終わり、次回はここから』。
   両方を「次のトークン」として同じ変数に入れると、途中のページで backfill が
   終わったことになり、残りは二度と読まれない。ここでは phase を分けて返す。

   **`@removed` は record ではない。** delta ページには削除が混じる。record と
   して扱えば消えたはずのメールが corpus に残り、無視すれば retention と
   削除要求に応えられない。tombstone として別に返す。

   **410 は crash ではない。** `resyncRequired` は cursor が失効したという
   provider からの正常な返答で、source が `:resync-on` で名前を挙げてある。
   例外にすると、計画された経路が事故として記録される。"
  (:require [clojure.string :as str]
            [importer.normalize :as n]
            [importer.plan :as plan]))

(def base-url "https://graph.microsoft.com/v1.0")

(defn- g [m & ks] (some #(or (get m %) (get m (name %)) (get m (keyword %))) ks))

(defn delta-request
  "次に投げる request map。credential は付けない —— host が付ける。

   token を持つ cursor はそれを URL としてそのまま使う: Graph が返す deltaLink /
   nextLink は完全な URL であり、query を組み直すと `$deltatoken` を落として
   **静かに全件読み直す**。"
  [c {:keys [mailbox folder-id page-size]}]
  (if-let [t (:importer.cursor/token c)]
    {:connector.http/method :get :connector.http/url t}
    {:connector.http/method :get
     :connector.http/url (str base-url "/users/" mailbox
                              "/mailFolders/" folder-id "/messages/delta")
     :connector.http/query (cond-> {} page-size (assoc "$top" (str page-size)))}))

(defn- epoch-ms [s]
  (when-not (str/blank? (str s))
    #?(:clj (try (.toEpochMilli (java.time.Instant/parse (str s))) (catch Exception _ nil))
       :cljs (let [n (js/Date.parse (str s))] (when-not (js/isNaN n) n)))))

(defn removed? [item] (some? (or (get item "@removed") (get item (keyword "@removed")))))

(defn tombstone
  [item]
  {:tombstone/provider-id (g item :id)
   :tombstone/reason (or (g (or (get item "@removed") (get item (keyword "@removed"))) :reason) "deleted")})

(defn message
  "Graph の message を canonical mail にする。

   `internetMessageId` を使うのが要点 —— これが RFC 5322 の Message-ID なので、
   同じメールが Gmail 側から来ても 1 件になる。Graph の `id` を identity にすると
   移行した会社の全員が二重に入る。"
  [item]
  (n/mail {:message-id (g item :internetMessageId)
           :cid (g item :id)             ; header が無いときの最後の拠り所
           :thread (g item :conversationId)
           :subject (g item :subject)
           :at (epoch-ms (g item :receivedDateTime))
           :from (g item :from :sender)
           :to (g item :toRecipients)
           :cc (g item :ccRecipients)
           :snippet (g item :bodyPreview)}))

(defn- error-code [body]
  (let [e (or (get body "error") (get body :error))]
    (str (or (get e "code") (get e :code)))))

(defn page
  "response -> {:plan ... :tombstones [...] :incremental-token ...}

   `:incremental-token` は deltaLink が来たときだけ入る。backfill を終えて
   `importer.cursor/promote` に渡すのはこれで、nextLink ではない。"
  [c response]
  (let [status (:connector.http/status response)
        body (:connector.http/body response)
        stream (:importer.cursor/stream c)
        k (:importer.cursor/key c)]
    (cond
      (= 410 status)
      {:plan (plan/plan {:stream stream :key k :items nil :next-token nil
                         :outcome :failed :resync-required? true
                         :note (str "Graph said " (error-code body)
                                    "; the cursor is invalid and this stream needs a full read")})
       :tombstones []}

      (or (nil? status) (>= status 500) (= 429 status))
      ;; 到達できなかった。0 件の :synced ではない。
      {:plan (plan/plan {:stream stream :key k :items nil :next-token nil
                         :outcome :unmeasured
                         :note (str "Graph answered " (pr-str status)
                                    "; nothing was read, which is not the same as nothing being there")})
       :tombstones []}

      (not= 200 status)
      {:plan (plan/plan {:stream stream :key k :items nil :next-token nil
                         :outcome :failed
                         :note (str "Graph answered " status " " (error-code body))})
       :tombstones []}

      :else
      (let [items (or (get body "value") (get body :value) [])
            {dead true live false} (group-by removed? items)
            records (mapv message live)
            next-link (or (get body "@odata.nextLink") (get body (keyword "@odata.nextLink")))
            delta-link (or (get body "@odata.deltaLink") (get body (keyword "@odata.deltaLink")))]
        (cond-> {:plan (plan/plan {:stream stream :key k
                                   :items records
                                   ;; deltaLink しか無い = この run は終わり。
                                   ;; next-token nil が backfill の exhausted を立てる。
                                   :next-token next-link
                                   :outcome :synced
                                   :watermark (n/watermark-of records)})
                 :tombstones (mapv tombstone dead)}
          delta-link (assoc :incremental-token delta-link))))))
