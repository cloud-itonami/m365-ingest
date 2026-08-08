# operator quickstart

この repo は **descriptor + gate** であって実行系ではない（[README](../README.md) /
[ADR-0001](adr/0001-descriptor-and-gate-not-an-executor.md)）。だから operator の仕事は
「起動する」ことではなく、**名乗っていることが今も本当かを確かめる**ことである。
所要 1〜2 分（`--network` を含めて 3 分）。

必要なもの: `nbb`（ClojureScript on Node）と `curl`。**この repo に依存パッケージは
無い** —— `deps.edn` も `package.json` も持たないので、install する物は何も無い。

## 1. 取得する

```bash
west update --fetch smart m365-ingest
cd orgs/cloud-itonami/m365-ingest
```

west を使わないなら `git clone git@github.com:cloud-itonami/m365-ingest` でよい。
なお **remote 名は `origin` ではなく `cloud-itonami`**（west が付ける名前）なので、
west 経由の checkout で `git fetch origin` は通らない。

## 2. 構造・gate・固定値を検査する（network 不要）

```bash
nbb --classpath src:test run_tests.cljs
```

期待される最後の 3 行:

```
Ran 48 tests containing 231 assertions.
0 failures, 0 errors.

mode: offline
m365-ingest actor: all green
```

見ているもの:

- **gate が緩む方向**（安全側）— 無 attest で `:ready` にならない / 7 本の gate が
  AND である / `false` を attest とみなさない / blocked な plan が `:records` を
  持ち歩かない / effect が他 actor に帰属しない / 宣言外の collection に書かない
- **gate がきつくなる方向**（生存側）— attestation の 4 形（set / keyword map /
  string map / string の set）を全部受ける。1 本落ちると**その形で attest している
  呼び出し側だけが黙って全 blocked** になり、安全側なので事故に見えない
- **descriptor 本体** — `actor-manifest.jsonld` の step が宣言外の capability を
  呼んでいないこと（およびその逆＝使われない過剰付与が無いこと）、cron が 5 field で
  あること、`did.json` の `service[].id` が自分の DID の fragment であること
- **固定値** — cell 9 / gate 7 / pipeline 3 / capability 6 などの census が実体と一致。
  count は**両方向に**落ちる（増えても減っても赤）
- **`docs/identity-claims.edn` の `:resolves-to` が解決規則から導けること** ——
  手書きの URL は「存在しない URL を測って 404 だと報告する」ので、`didweb` の
  規則から導出して突き合わせる

## 3. 名乗りを実際に解決しに行く

```bash
nbb --classpath src:test run_tests.cljs --network
```

`Ran 48 tests containing 255 assertions.` / `mode: offline + network` になる
（増えた 24 assertion が実測ぶん）。curl で各 DID / 配信面を引き、
`docs/identity-claims.edn` の `:measured` と突き合わせる。

⚠ **`0 failures` は「全部健全」という意味ではない。** この repo の identity は
3 つに割れており（README 参照）、**割れていること自体が固定値として記録されている**。
緑なのは「実測が固定値と一致した」という意味でしかない。

## 4. 検査が本当に噛むかを確かめる（任意・数分）

緑を見ているだけでは、テストが静かに噛まなくなったことに気づけない。
superproject 側の mutation runner が、**壊して赤くなること**を確かめる:

```bash
cd <superproject root>
nbb scripts/maturity-loop/run.cljs --only m365-ingest
```

期待は `噛む=16 噛まない=0 エラー=0 skip=0`。使い捨て worktree を west の pin から
切って 16 通りに壊すので、**共有 checkout には触れない**。`噛まない` が 1 つでも
出たら、それは「そのテストはその不変条件を守っていない」という具体的な TODO である。

## 5. gate を手で撃ってみる（任意・5 秒）

```bash
nbb --classpath src -e '(require (quote [m365_ingest.murakumo :as m]))
  (let [blocked (m/cell-plan :shinka {:attestations {}})
        ready   (m/cell-plan :shinka {:attestations (into #{} m/common-gates) :request-id "req-1"})]
    (println "blocked:" (:status blocked) "effects" (count (:effects blocked)) "missing" (count (:missing-gates blocked)))
    (println "ready:  " (:status ready)   "effects" (count (:effects ready))   "->" (:collection (first (:effects ready)))))'
```

```
blocked: :blocked effects 0 missing 7
ready:   :ready effects 1 -> com.etzhayyim.m365-ingest.shinka
```

**`:ready` は「書いた」ではない。** 返るのは `{:op :mst/put-record ...}` という
data であって、それを実行する者はこの repo に居ない。

## 赤くなったら

**壊れたとは限らない。直ったのかもしれない。** この repo の test の多くは
「測って、直していない」現状（`docs/identity-claims.edn` の `:gaps`）を固定している
ので、穴を塞ぐと赤くなる。どちらの場合もやることは同じ:

1. 失敗行を読む（固定値と実測値の両方が出る）
2. `docs/identity-claims.edn` の `:measured` を実測に合わせ、`:measured-at` を更新
3. **`README.md` の該当記述も直す** —— これが本体。EDN だけ直すと README が嘘のまま残る
4. 何がどちらへ動いたかを commit message に書く

## やらないこと

- **`CLAUDE.md` の手順を実行しない。** あれは T1 実行系（Graph API のページング・
  `wrangler secret M365_CLIENT_SECRET`・`*/15` の delta sync）の説明で、**その実装は
  この repo に無い**。`cd` する先も deploy 対象も存在しない。
- **`.well-known/did.json` を編集して「直った」としない。** このファイルは live DID
  document の source ではない（配信文書と中身が食い違っていることを実測済み）。
  ここを変えても配信は変わらない。
- **substrate の collection prefix を manifest 側（`com.etzhayyim.apps.m365Ingest.*`）に
  合わせない。** 割れが解消したように見えて**権威から遠ざかる** —— live DID document の
  `_meta.primaryLexicon` は substrate 側の `com.etzhayyim.m365-ingest` である。
- **`MIGRATION-TODO.md` のチェックを、作業せずに埋めない。** `[ ]` / `[x]` の数は
  `:gaps` に固定してあり、埋めれば test が赤くなって「測り直せ」と言う。
