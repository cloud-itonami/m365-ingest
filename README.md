# m365-ingest

**Microsoft 365 テナントのメールボックスを収集する actor の *descriptor* と、その
書き込みを止める *deny-by-default gate*。収集そのものは、ここには無い。**

`did:web:m365-ingest.etzhayyim.com`（名乗り・**DNS に存在しない**）·
`did:web:etzhayyim.com:actor:m365-ingest`（解決する方・**この repo の誰も名乗っていない**）

`m365-ingest` という名前は主題（Microsoft 365 の取り込み）を言うが、**この repo が
何であるか**は言わない。読み始める前に、この 2 つを分けて持ってほしい:

| | ここにあるか |
|---|---|
| actor が**何を名乗り、何を要求し、どの pipeline を持つと宣言しているか** | **ある**（`actor-manifest.jsonld` / `.well-known/did.json`） |
| **gate**（attestation が揃わなければ effect を 1 つも出さない判断） | **ある**（`src/m365_ingest/murakumo.cljc`、130 行） |
| **Graph の delta 応答を canonical record に翻訳し、gate を通して effect を並べる executor** | **ある**（2026-08-30 追加。`src/m365_ingest/{source,graph,run}.cljc`） |
| OAuth・token 取得・HTTP を送る主体・Worker・cron・sink | **無い** |

**ここには動くサービスは無い。** 2026-08-30 に executor が入ったが、それが返すのも
やはり **effect の列**である —— `run/step` は Graph の応答を受け取って
`{:op :mst/put-record ...}` を並べるところまでで、HTTP を送る者も、token を持つ者も、
書き込む者もこの repo に居ない。credential に到達する経路が 1 本も無いので、
**この repo の review に「鍵を漏らしていないか」という項目は要らない。**

executor が足したのは判断ではなく順序である: `run/step` が effect を返し、
`run/land` が **sink 自身が書いた件数**を受けて初めて cursor を動かす。1 本の関数に
すると「書く前に cursor を進める」が書けてしまい、それは次の delta が二度と触れない
恒久的で静かな穴になる（`kotoba-lang/importer` の `cursor/advance` が拒む形）。

## `CLAUDE.md` はここに無いものを説明している

`CLAUDE.md` は T1 実行系（Graph API のページング・token キャッシュ・`*/15` の delta
sync・`wrangler secret M365_CLIENT_SECRET`）を書いているが、**その実装はこの repo に
存在しない**。2026-05-21 に etzhayyim monorepo の `20-actors/m365-ingest` から
descriptor だけを写した snapshot で、codemod は未着手（`MIGRATION-TODO.md` の 6 項目は
全部 `[ ]` のまま。この数は test で固定してある）。

**`CLAUDE.md` の手順を実行しない。** `cd` する先も `wrangler` の対象もここには無い。
経緯は [docs/adr/0001](docs/adr/0001-descriptor-and-gate-not-an-executor.md)。

## 確かめる

散文ではなく実行で確かめられる。

```bash
nbb --classpath src:test:../../kotoba-lang/importer/src:../../kotoba-lang/connector/src run_tests.cljs             # 構造・gate・executor・固定値（network 不要）
nbb --classpath src:test:../../kotoba-lang/importer/src:../../kotoba-lang/connector/src run_tests.cljs --network   # 上記 + 名乗りを実際に解決しに行く
```

どちらも 65 tests、`--network` 無しで 292 assertions・有りで 316 assertions。最後に
`m365-ingest actor: all green` が出れば緑。手順は
[docs/operator-quickstart.md](docs/operator-quickstart.md)。

**この README 自身も検査対象である。** 下に書いてある数（cell 9 / gate 7 / 交差 0）と
2 つの DID は `test/m365_ingest/docs_test.cljs` が実体と突き合わせるので、実体が動けば
README が赤くなる。quickstart が名指しする `.cljs` の実在も同じ場所で守っている ——
**踏めない手順を書けない**ようにするため。

## ここにあるもの

| ファイル | 役割 |
|---|---|
| `src/m365_ingest/murakumo.cljc` | **判断はここだけ。** 9 cell × 7 gate の deny-by-default |
| `src/m365_ingest/source.cljc` | 何を追うかの宣言（cursor の種類・失効条件・governance）。`importer.model` が検査する |
| `src/m365_ingest/graph.cljc` | Graph の応答 → provider に依らない page。純関数、credential 無し |
| `src/m365_ingest/run.cljc` | gate → effect（`step`）と、sink の報告 → cursor（`land`）。**この 2 つを 1 本にしない** |
| `actor-manifest.jsonld` | actor 宣言。3 pipeline（cron×2 / xrpc×1）、6 capability、3 requiredLoop |
| `.well-known/did.json` | DID document。**配信されていない**（Pages 無効）し、live 文書とも中身が違う |
| `docs/identity-claims.edn` | 下の表の**実測値を固定したもの**。test の期待値 |
| `test/` | gate（緩む方向 / きつくなる方向の両方）・descriptor 本体・executor・network 実測 |
| `run_tests.cljs` | 上記の runner。nbb + `cljs.test` |
| `CLAUDE.md` | **ここに無い実行系の説明**。上記の断り書きを読むこと |
| `MIGRATION-TODO.md` / `NOTICE` / `.nojekyll` | 未着手の codemod / 出所・ライセンス / Pages の残骸 |

## gate が守っているもの

```clojure
(m/cell-plan :shinka {:attestations {}})
;; => {:status :blocked :missing-gates [7 本] :effects []}   ← :records も持たない
```

7 本の baseline gate は **AND**（1 本欠けても blocked）。`{:no-probing-baseline false}`
のような**明示的な否定は attest とみなさない**（キーの有無ではなく値を見る）。
blocked な plan は `:effects` が空なだけでなく `:records` も持たない —— 「gate を
通っていない書き込みを、gate の出力から組み立てられる」状態を作らないため。

attestation は set / keyword map / string map / string の set の 4 形を受ける。
どれか 1 本落ちると**その形で attest している呼び出し側だけが黙って全 blocked** になる
（安全側に倒れるので事故に見えず、気づきにくい）—— だから生存側も test で撃ってある。

## 3 つの割れ（いずれも既知・未解決。測って固定してある）

2026-08-08 実測。`docs/identity-claims.edn` が正本で、test が突き合わせる。

**1. 名乗る DID が存在しない。** substrate（`actor-did`）と manifest（`@id`）は
`did:web:m365-ingest.etzhayyim.com` で**一致している** —— 兄弟 3 repo で唯一。だが
そのホストは DNS に無い（apex の `etzhayyim.com` は解決するので、この label だけが無い）。
**揃っているのに、揃っている先が無い。** gate が発行する effect は全て、誰にも解決
できない DID に帰属する。

**2. 解決する DID を、この repo の誰も名乗っていない。**
`.well-known/did.json` だけが `did:web:etzhayyim.com:actor:m365-ingest` を名乗り、
これだけが 200 を返す。しかも**返ってくる文書は commit した bytes と違う**
（PDS が `pds.etzhayyim.com`(530) と `pds.aozora.app`(生きている) で食い違い、
`alsoKnownAs` は commit 側 4 件 / live 側 空、suite も別）。**commit 側は死んだ
endpoint を指している。** どちらを正とするかはこの repo が決められない（live 文書を
発行しているのは etzhayyim.com 側）。

**3. gate が書く先を、descriptor の誰も宣言していない（交差 0）。**
substrate は `com.etzhayyim.m365-ingest.*` に 9 collection、manifest は
`com.etzhayyim.apps.m365Ingest.*` / `com.etzhayyim.apps.standard.*` に 7 NSID。
**重なりは 1 つも無い。**

⚠ **揃える先は substrate 側である。** live DID document の `_meta.primaryLexicon` が
`com.etzhayyim.m365-ingest` を名乗っており、substrate の prefix と一致する。素朴に
「substrate を manifest に合わせる」と、一見 割れが解消したように見えて**権威から
遠ざかる**。この向き違いは mutation
`:m365-ingest/substrate-lexicon-aligned-to-the-manifest` が止める。

## 直すと赤くなる test がある（それは「戻せ」ではない）

`docs/identity-claims.edn` の `:gaps` は「測って、**直していない**」ものの記録で、
test はその現状を固定している。したがって**穴を塞ぐと該当 test が赤くなる**:

| gap | 何が起きるか |
|---|---|
| `:rkey/reserved-passthrough` | AT の予約 rkey `.` / `..` を素通しする |
| `:record/caller-can-forge-actor-did` | effect の帰属は固定だが、**record 本体の `:actorDid` は呼び出し側が上書きできる** |
| `:manifest/dangling-docs` | `complianceDocs` 2 件 + `CHARTER-RIDER.md` が repo に無い（0/3） |
| `:migration/todo-unstarted` | `MIGRATION-TODO.md` の 6 項目が未着手（`[ ]` 6 / `[x]` 0） |
| `:ns/underscore` | ns 名が `m365_ingest.murakumo`（慣用形は `-`）。兄弟も同形なので単独では直さない |

赤くなったときの正しい対応は元に戻すことではなく、**測り直して
`docs/identity-claims.edn` と この README を同時に更新すること**。EDN だけ直すと
README が嘘のまま残る。

## 出所

etzhayyim monorepo `20-actors/m365-ingest` → `etzhayyim/com-etzhayyim-m365-ingest`
→ 現在の正本 `cloud-itonami/m365-ingest`。`alsoKnownAs` が指す source は 1 世代古い。
Apache-2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE` 参照。Rider 本体は
この repo に無い —— 上記 `:manifest/dangling-docs`）。
