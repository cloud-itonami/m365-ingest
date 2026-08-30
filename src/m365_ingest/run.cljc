(ns m365_ingest.run
  "取り込み 1 ページ。**gate が先、cursor は最後。**

   この repo は 2026-08 まで descriptor と gate だけを持ち、executor を持たな
   かった（docs/adr/0001）。ここが executor だが、gate を迂回する経路は増やして
   いない —— 判断は `murakumo/cell-plan` のまま、ここがやるのは
   『通ったなら、そのページの分だけ effect を並べる』ことだけである。

   ## 2 つの関数に分かれているのが設計

   `step` は effect を**返す**。`land` は sink が実際に書いた件数を受けて cursor
   を動かす。1 本にすると『書く前に cursor を進める』が書けてしまい、それが
   `importer.cursor` の docstring が言う恒久的で静かな穴になる。

   ## 取り込んだ mail は record の top-level に置かない

   `docs/identity-claims.edn` の gap `:record/caller-can-forge-actor-did` —
   `records-for` は base より入力 record を後に merge するので、入力に
   `:actorDid` があれば勝つ。取り込むのは**外部から届いたメール**であり、
   Graph の JSON がその key を持っていれば、gate の出力の中で actor を騙れる。
   だから canonical record は `:imported` の下に 1 段落として置く。
   衝突しうる key を持てない形にしてあり、`run-test` が敵対的な message で撃つ。"
  (:require [importer.cursor :as cursor]
            [importer.plan :as plan]
            [m365_ingest.graph :as graph]
            [m365_ingest.murakumo :as m]
            [m365_ingest.source :as source]))

(def cell
  "delta 取り込みが属する cell。gate の 7 本はこの cell の required-gates。"
  :delta-sync-all-users)

(def collection (first (:collections (get m/cell-specs cell))))

(def reserved
  "gate 自身が意味を与える key。取り込んだデータがこれを名乗ってはならない。"
  #{:actorDid :computedAt :legacyCell :phase :requestId :actorBoundary
    :scaffold :constitutionalStatus :$type})

(defn envelope
  "1 件の canonical record を、gate が出す atproto record の形に包む。

   包むことが安全性である: `:imported` の下に 1 段落とすので、外から来た key は
   構造的に top level へ届かない。"
  [c record now]
  {:rkey (m/safe-rkey (str (:importer.cursor/key c) "-" (or (:mail/id record) "unknown")))
   :streamKey (:importer.cursor/key c)
   :source (:importer.source/id source/source)
   :importedAt now
   :imported record})

(defn envelope-problems
  "封筒が予約 key を top level に持っていないこと。構造上起きないが、
   `envelope` が変わったときに気づくためにここで測れるようにしてある。"
  [e]
  (vec (filter #(contains? e %) reserved)))

(defn step
  "1 ページ分の計画。**cursor は動かさない。**

   opts: {:cursor :attestations :response :request-id :computed-at}

   返り値の `:outcome` は 3 値。gate が通らなかったときは `:unmeasured` であって
   『0 件を同期した』ではない —— 前者は測れておらず、後者は測って空だった、で
   意味が違う。"
  [{:keys [cursor attestations response request-id computed-at]}]
  (let [verdict (m/cell-plan cell {:attestations attestations
                                   :request-id request-id
                                   :computed-at computed-at})]
    (if (= :blocked (:status verdict))
      {:outcome :unmeasured
       :gate verdict
       :plan (plan/plan {:stream (:importer.cursor/stream cursor)
                         :key (:importer.cursor/key cursor)
                         :items nil :next-token nil :outcome :unmeasured
                         :note (str "gate blocked: missing "
                                    (pr-str (:missing-gates verdict)))})
       :effects []
       :tombstones []}
      (let [{:keys [plan tombstones incremental-token]} (graph/page cursor response)
            records (:importer.plan/items plan)
            ;; gate は『出してよいか』を決める。件数を並べるのはここ。effect の
            ;; :actor は put-record-effect が actor-did に固定するので、
            ;; 取り込んだ内容から来ることはない。
            effects (mapv (fn [r]
                            (let [e (envelope cursor r computed-at)]
                              (m/put-record-effect collection (:rkey e) e)))
                          records)]
        (cond-> {:outcome (:importer.plan/outcome plan)
                 :gate verdict
                 :plan plan
                 :effects effects
                 :tombstones tombstones}
          incremental-token (assoc :incremental-token incremental-token))))))

(defn land
  "sink が書いた件数を受けて cursor を動かす（動かさない判断も含む）。

   `sink-report` は sink 自身の報告であって、`step` が返した effect の数ではない。
   後者を渡すと、この repo が防ごうとしている穴をこの関数が自分で開ける。"
  [step-result c sink-report now]
  (plan/commit (:plan step-result) c sink-report now))

(defn resync-needed?
  "provider が cursor を無効にしたか。source が `:resync-on` で名前を挙げてある
   条件なので、これは事故ではなく計画された経路である。"
  [step-result]
  (boolean (:importer.plan/resync-required? (:plan step-result))))

(defn after-resync
  [c now]
  (cursor/reset c :resync-required now))
