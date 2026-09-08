(ns m365_ingest.gate-test
  "`src/m365_ingest/murakumo.cljc` の **deny-by-default gate** を固定する。

   この repo で唯一 substrate と呼べるのはこの gate で、`cell-plan` は
   『required-gates が全て attest されていなければ `:status :blocked` にして
   effect を 1 つも出さない』という判断を持つ。**それが緩む方向に壊れると、
   attest されていない actor が record を書けるようになる。**

   にもかかわらず、この repo には 2026-07-18 の rescue commit で murakumo.cljc が
   入ってから **テストが 1 本も無かった**。しかも west の pin は
   その rescue commit の 2 つ手前（9fe6715）で止まっており、成熟度 scan が
   測っていた tree には src/ が存在しなかった —— つまり axis-substrate=0 という
   計測値は『コードが無い』ではなく『pin が古くて見えていない』の意味だった。

   ## この suite が守っているのは 2 種類

   1. **安全側**（緩むと危ない）: 未 attest で ready にならない / 明示 false を
      attest とみなさない / blocked のとき effect が空 / effect が他 actor に
      帰属しない。壊れたら「権限のない書き込みが通る」。
   2. **生存側**（きつくなると気づけない）: attestation を set でも
      keyword map でも string map でも受ける。壊れたら**全部 blocked になる**ので
      安全ではあるが、actor が黙って何もしなくなる。

   どちらも壊れ方が静かなので、fixture で撃って赤くなることを確かめてある
   （`scripts/maturity-loop/mutations.edn` の :m365-ingest/*）。"
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [m365_ingest.murakumo :as m]))

(def all-attested
  "common-gates を全て満たす attestation（set 形）。"
  (into #{} m/common-gates))

(def a-cell
  "代表として撃つ cell。cell ごとの差は :collections と :legacy-cell だけなので、
   個別 cell の検査は『全 cell を回す』deftest 側で担保する。"
  :shinka)

;; ── 安全側 ──────────────────────────────────────────────────────────────────

(deftest every-cell-is-blocked-when-nothing-is-attested
  "**gate の一番外側。** 9 cell のうち 1 つでも無 attest で ready になったら、
   そこが素通り口になる。cell は manifest から機械生成された表なので、
   将来 cell が増えたときに required-gates を書き忘れる形で緩みうる ——
   だから代表 1 件ではなく全件を回す。"
  (doseq [cell (keys m/cell-specs)]
    (testing (str cell)
      (let [plan (m/cell-plan cell {:attestations {}})]
        (is (= :blocked (:status plan)))
        (is (= [] (:effects plan)) "blocked なのに effect が出ている")))))

(deftest every-cell-requires-at-least-the-common-baseline
  "cell を『gate 無し』で足せないこと。:required-gates が空の cell は
   missing-gates が空になるので **無 attest でも :ready になる** ——
   前の deftest はそれを結果として捕まえるが、ここは原因を名指しで止める。"
  (doseq [[cell spec] m/cell-specs]
    (testing (str cell)
      (let [missing (remove (set (:required-gates spec)) m/common-gates)]
        (is (= [] (vec missing))
            (str cell " が baseline gate を要求していない: " (vec missing)))))))

(deftest removing-any-single-required-gate-blocks-the-plan
  "7 本の gate は **AND** であって、多数決でも代表でもない。1 本だけ落ちた
   ときに通ってしまう実装（例: `some` と `every?` の取り違え）をここで止める。"
  (doseq [gate m/common-gates]
    (testing (str "missing " gate)
      (let [plan (m/cell-plan a-cell {:attestations (disj all-attested gate)})]
        (is (= :blocked (:status plan)))
        (is (= [gate] (:missing-gates plan)))
        (is (= [] (:effects plan)))))))

(deftest an-attestation-that-is-explicitly-false-does-not-count-as-attested
  "**この suite で一番静かな壊れ方。** `(contains? attestations gate)` で
   実装すると `{:no-probing-baseline false}` が『attest 済み』になる ——
   `false` は『測ったうえで満たしていない』という最も強い否定なのに、
   キーがあるというだけで通る。gate-value は値を見るので落ちる。ここを固定する。"
  (let [att (into {} (map (fn [g] [g (not= g :no-probing-baseline)]) m/common-gates))
        plan (m/cell-plan a-cell {:attestations att})]
    (is (= :blocked (:status plan)))
    (is (= [:no-probing-baseline] (:missing-gates plan)))
    (is (= [] (:effects plan)))))

(deftest a-blocked-plan-carries-no-records-at-all
  "`:effects []` だけでは足りない。『plan は作るが実行しない』形にすると、
   blocked でも計算済みの record が `:records` に置かれる —— :effects だけ見て
   安心した呼び出し側が :records を拾って書けてしまう。**gate を通っていない
   書き込みが、gate の出力から作れる**状態を作らない。"
  (let [plan (m/cell-plan a-cell {:attestations {} :record {:x 1}})]
    (is (= :blocked (:status plan)))
    (is (= [] (:effects plan)))
    (is (nil? (:records plan)) "blocked plan が record を持ち歩いている")))

(deftest an-unknown-cell-throws-instead-of-planning-nothing
  "打ち間違えた cell 名が nil spec のまま進むと、required-gates が nil ＝
   missing 無し ＝ **:ready** になる。effect は 0 件なので実害は出ないが、
   status だけ見る呼び出し側には『gate を通った』と見える。"
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (m/cell-plan :no-such-cell {:attestations all-attested}))))

(deftest every-emitted-effect-is-attributed-to-this-actor
  "effect の :actor が actor-did 以外になりうると、この gate を通した書き込みが
   別 actor の repo に入る。**呼び出し側が record に :actorDid を混ぜても
   effect の帰属は動かない**ことまで撃つ（record 側は動く —— それは
   identity-claims.edn の :gaps に固定してある既知の穴で、下の deftest が測る）。"
  (doseq [cell (keys m/cell-specs)]
    (testing (str cell)
      (let [plan (m/cell-plan cell {:attestations all-attested
                                    :request-id "req-1"
                                    :record {:actorDid "did:web:someone-else.example"}})]
        (is (= :ready (:status plan)))
        (is (seq (:effects plan)))
        (doseq [e (:effects plan)]
          (is (= m/actor-did (:actor e))
              "effect が別 actor に帰属している"))))))

(deftest every-emitted-effect-is-a-put-record-into-a-declared-collection
  "effect の形を固定する。:op が変わる・宣言外の collection に書く、のどちらも
   『どこに何を書くか』を静かに変える。cell の :collections 以外に書かないこと。"
  (doseq [[cell spec] m/cell-specs]
    (testing (str cell)
      (let [plan (m/cell-plan cell {:attestations all-attested :request-id "req-1"})
            declared (set (:collections spec))]
        (doseq [e (:effects plan)]
          (is (= :mst/put-record (:op e)))
          (is (contains? declared (:collection e))
              (str "宣言外の collection に書いている: " (:collection e)))
          (is (not (str/blank? (:rkey e))) "rkey が空"))))))

;; ── 生存側 ──────────────────────────────────────────────────────────────────

(deftest attestations-are-accepted-as-set-keyword-map-and-string-map
  "gate-value は 4 経路（keyword map / string map / set の keyword / set の string）を
   受ける。どれか 1 本落ちると **その形で attest している呼び出し側だけが黙って
   全 blocked になる** —— 安全側に倒れるので事故には見えず、actor が何もしなく
   なったことにしばらく気づけない。"
  (let [as-set        all-attested
        as-kw-map     (into {} (map (fn [g] [g true]) m/common-gates))
        as-str-map    (into {} (map (fn [g] [(name g) true]) m/common-gates))
        as-str-set    (into #{} (map name m/common-gates))]
    (doseq [[label att] [["set of keywords" as-set]
                         ["keyword map" as-kw-map]
                         ["string map" as-str-map]
                         ["set of strings" as-str-set]]]
      (testing label
        (let [plan (m/cell-plan a-cell {:attestations att :request-id "req-1"})]
          (is (= :ready (:status plan)) (str label " で blocked になった"))
          (is (= [] (:missing-gates plan)))
          (is (seq (:effects plan))))))))

(deftest all-cell-plans-covers-every-cell-and-never-throws
  "`all-cell-plans` は表の全 cell を回す。cell が増えたときにここが落ちるのは
   『表と回し手がずれた』合図。"
  (let [plans (m/all-cell-plans {:attestations all-attested :request-id "req-1"})]
    (is (= (set (keys m/cell-specs)) (set (keys plans))))
    (is (every? #(= :ready (:status %)) (vals plans)))))

;; ── rkey ────────────────────────────────────────────────────────────────────

(deftest safe-rkey-strips-the-did-prefix-and-replaces-unsafe-characters
  "rkey は record のアドレスになる。安全文字集合を**広げる**変更は、既存の入力
   では何も変わらないので気づきにくい（`/` を足すと rkey が階層を作る）。"
  (is (= "m365-ingest.etzhayyim.com" (m/safe-rkey "did:web:m365-ingest.etzhayyim.com")))
  (is (= "a-b" (m/safe-rkey "a/b")) "path separator が rkey に残っている")
  (is (= "a-b" (m/safe-rkey "a b")))
  (is (= "a.b_c~d-e" (m/safe-rkey "a.b_c~d-e")) "安全文字が潰されている"))

(deftest safe-rkey-never-returns-blank
  "空 rkey は『rkey を指定しなかった』と区別が付かない。blank fallback を外しても
   普通の入力では一切挙動が変わらない —— 潰して空になる入力を明示的に撃たない
   限り緑のまま。"
  (is (= "unknown" (m/safe-rkey "")))
  (is (= "unknown" (m/safe-rkey nil)))
  (is (= "unknown" (m/safe-rkey "did:web:")) "prefix を剥がして空になる入力")
  ;; **空白だけの入力は fallback に入らない。** 空白は安全文字でないので先に
  ;; `-` へ置換され、`"   "` は blank ではない `"---"` になる。つまり
  ;; blank 判定は「置換後」に効くのであって「意味のある rkey か」は見ていない。
  ;; 直感に反するので、意図した挙動として固定しておく（この 1 行は実測で
  ;; 書き直した —— 最初は "unknown" を期待して赤くなった）。
  (is (= "---" (m/safe-rkey "   "))))

(deftest safe-rkey-still-passes-through-the-reserved-rkeys
  "**これは『正しい』の固定ではなく、既知の穴の固定である。**
   AT Protocol は rkey `.` と `..` を予約しており、この実装は安全文字集合に
   `.` を含むため両方を素通しする。docs/identity-claims.edn の :gaps に
   `:rkey/reserved-passthrough` として記録してある。

   **直したらこの deftest が赤くなる。** そのときの正しい対応は元に戻すことでは
   なく、この deftest と :gaps を同時に更新すること —— 穴が塞がったことを
   記録に反映させるために、あえて現状を固定している。"
  (is (= "." (m/safe-rkey ".")))
  (is (= ".." (m/safe-rkey ".."))))
