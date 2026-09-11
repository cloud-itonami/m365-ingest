(ns m365_ingest.source
  "この actor が取り込む source を、`kotoba-lang/importer` の descriptor として
   宣言する。

   ## なぜ actor-manifest.jsonld とは別にこれが要るか

   manifest は JSON-LD で『何を名乗るか』を書いており、cursor がどれだけ生きるか
   / それが切れたとき何が起きるかを書く場所を持たない。**その 2 つが運用の形を
   決める**ので、ここで宣言して `importer.model` に検査させる。

   ## governance は 2 箇所に書かれる。だから突き合わせる

   PII tier も retention も manifest 側に既にある。ここに二度書けば、片方だけ
   動いたときに黙って割れる —— この repo が identity について実際に踏んだ形
   （substrate と manifest が別の DID を名乗る）そのものである。
   `source-test` が manifest の値と 1 件ずつ突き合わせるので、割れたら赤くなる。"
  (:require [importer.cursor :as cursor]
            [importer.model :as im]))

(def source-id "com.microsoft.m365")

(def governance
  ;; actor-manifest.jsonld の "governance" と同じ値。test が突き合わせる。
  (im/governance {:classification :restricted
                  :pii-tier 3
                  :retention-days 2555
                  :consent-required? true
                  :purpose "security:BEC-detection + compliance:audit + internal:knowledge-extraction"}))

(def source
  (im/validated
   (-> (im/source source-id "Microsoft 365"
                  {:summary "テナントのメールボックスを Microsoft Graph の delta で追う。"
                   :origin-domain "microsoft.com"
                   :tenant "etzhayyim"
                   ;; 呼び方を既に知っている repo。ここでは繰り返さない。
                   :connector-id "com.microsoft.graph"
                   :docs-url "https://learn.microsoft.com/graph/delta-query-messages"
                   :governance governance})

       ;; Graph の mail delta は **フォルダごと**に張る。1 メールボックスに 1 本
       ;; ではないので、cursor の key はメールボックスではなく (mailbox, folder)
       ;; になる —— Google 側が 3 API に 3 本持つのと、数え方の軸が違う。
       (im/add-stream
        :mail
        {:cursor-style :delta-token
         :description "1 mailbox の 1 folder。@odata.deltaLink を次回の起点にする。"
         ;; Graph は deltaLink の有効期限を公表していない。:unknown は測定した
         ;; 『分からない』であって、書き忘れではない（absent なら model が赤くする）。
         :history-retention :unknown
         ;; 名前を挙げてあるので、410 は crash ではなく計画された経路になる。
         :resync-on #{:token-expired :resync-required :gone}})

       (im/add-stream
        :folders
        {:cursor-style :none
         :description "mailFolders の一覧。delta を張る先を決めるためだけに読む。"
         :history-retention :unbounded
         :backfill? true}))))

(defn mail-cursor
  "1 mailbox の 1 folder に 1 本。"
  [mailbox folder-id]
  (cursor/cursor source :mail (str mailbox "/" folder-id)))
