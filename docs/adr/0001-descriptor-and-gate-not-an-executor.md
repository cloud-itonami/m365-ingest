# ADR-0001 — この repo は descriptor と gate であって、executor ではない

- **status**: accepted
- **date**: 2026-08-08

## 文脈

`m365-ingest` という名前は主題（Microsoft 365 の取り込み）を言うが、**この repo が
何であるか**は言わない。実際、`CLAUDE.md` は 15 分ごとの delta sync・Graph API の
ページング・token キャッシュ・`wrangler secret M365_CLIENT_SECRET` を持つ T1 実行系を
記述しており、読んだ者は「収集が動いている repo」だと受け取る。

**その実装はここに存在しない。** この repo の全ファイルは 10 個ほどで、コードは
`src/m365_ingest/murakumo.cljc`（130 行）1 本だけ。Graph client も OAuth も Worker も
cron の実行主体も無い。`CLAUDE.md` は 2026-05-21 に etzhayyim monorepo の
`20-actors/m365-ingest` から descriptor だけを写した際に一緒に来た文書で、
`MIGRATION-TODO.md` の 6 項目（3rd-party SDK の置換・DID bind 等）は今日まで全部
`[ ]` のままである。

同じ取り違えは兄弟 repo でも起きている: `cloud-itonami/cargo` の
`actor-manifest.test.ts` は `toHaveLength(8)` と書かれたまま**一度も実行されず**、
その間に pipeline は 10 本に増え、`@id` と `did.json` の id は別々の DID に割れていた。
どちらも報告されなかった。**「実装があるように読める文書」と「誰も走らせない検査」は
同じ事故の両面**である。

## 決定

**この repo を descriptor surface + deny-by-default gate として位置づけ、そう名乗る。**

1. **`README.md` の冒頭で名乗る。** 何がここに有り（descriptor / gate）、何が無いか
   （Graph client / OAuth / Worker / 実行主体）を表で先に出す。superproject の規則
   「名前が機能を示さない repo は README の冒頭で名乗る」の適用。
2. **`CLAUDE.md` は削除も改稿もしない。** あれは移行元の実行系の仕様として価値がある。
   代わりに README と quickstart が「ここに無いものの説明である」と明示し、
   **手順を実行しないこと**を「やらないこと」として書く。
3. **operator の仕事を「起動」ではなく「確かめる」と定義する。**
   `docs/operator-quickstart.md` は全 step が実際に踏める形にし、期待出力を書く。
4. **`:ready` を「書いた」と読ませない。** `cell-plan` が返す `:effects` は
   `{:op :mst/put-record ...}` という data であり、実行する者はこの repo に居ない。
   quickstart の step 5 でそれを目で見せる。

## なぜ `CLAUDE.md` を直さないのか

直す先が無いからである。あの文書が記述する executor は「まだ書かれていない」のか
「別 repo に在る」のか、この repo からは決められない（`complianceDocs` が指す 2 件と
`CHARTER-RIDER.md` も同様に不在で、別 repo なのか未移行なのか不明）。**分からない
ことを分かったように書き換えるより、境界を宣言して読み手を止める方が正しい。**
不在は `docs/identity-claims.edn` の `:gaps` に `:manifest/dangling-docs` /
`:migration/todo-unstarted` として測定値付きで固定してあり、状況が動けば test が
赤くなって「測り直せ」と言う。

## 帰結

- 読み手は 3 行目までに「ここに収集は無い」を知る。
- `CLAUDE.md` の手順を実行しようとして `cd` 先が無いことに気づく、という時間の
  溶かし方が起きない。
- **文書が黙って古くなる経路が減る。** README が主張する構造（cell 9 / gate 7 /
  交差 0 / 2 つの DID）と、quickstart が名指しする script の実在・引用する
  `Ran N tests` は `test/m365_ingest/docs_test.cljs` が実体と突き合わせる。
  **踏めない手順を書けない。**
- 一方で **README の散文そのものは機械検査されない。** 数値と DID と境界宣言の
  存在は test が守るが、「何が無いか」を*正しく*述べているかは人が守る。
  例えば Graph client が後から追加されても、README が「無い」と書き続けることは
  できてしまう。これは残った穴として認めておく。

## 改訂 2026-08-30 — executor は入った。gate が先であることは変わらない

`kotoba-lang/importer` を kernel として、`src/m365_ingest/{source,graph,run}.cljc` が
入った。**このタイトルは半分が古くなった** —— この repo はもう executor を持つ。

古くならなかった方が、この ADR が実際に守っていたものである:

- **判断は今も `murakumo.cljc` だけにある。** `run/step` は `cell-plan` を先に呼び、
  `:blocked` なら effect 0 本・`:unmeasured` を返す。gate を迂回する経路は増えていない。
- **credential に到達する経路は今も 1 本も無い。** `graph.cljc` は request map を
  返し response map を受けるだけで、送るのは host である。
- **書き込む主体は今もここに居ない。** `run/step` が返すのは effect の列で、
  `run/land` は sink 自身の報告を受けて初めて cursor を動かす。

`CLAUDE.md` が記述している T1 実行系（OAuth・token キャッシュ・`*/15` の cron・
`wrangler secret`）は**依然として存在しない**ので、上の断り書きはそのまま有効である。
入ったのは「Graph の応答をどう読み、どの順で cursor を動かすか」であって、
「Graph を叩く主体」ではない。

そして本 ADR が最後に残した穴 ——

> 例えば Graph client が後から追加されても、README が「無い」と書き続けることは
> できてしまう。これは残った穴として認めておく。

—— は、まさにこの改訂で踏まれた。README の表と冒頭の断り書きは同じ commit で
実体に合わせてある。**穴は塞がっていない**（次に何かが足されたとき、また人が
README を直すしかない）が、少なくとも一度目は落ちなかった側で通っている。

## 関連

- `docs/identity-claims.edn` — 実測値の正本
- `scripts/maturity-loop/mutations.edn`（superproject）の `:m365-ingest/*` 16 件 ——
  test が本当に噛むかの検査
- 兄弟の同型 ADR: `cloud-itonami/handotai-actor` の
  `docs/adr/0001-descriptor-surface-not-implementation.md`
