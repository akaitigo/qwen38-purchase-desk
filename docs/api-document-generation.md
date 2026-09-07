# API利用者向けドキュメントの生成と検証

ソースを読まずにAPIを呼び出せる文書を、Qwenで生成するための仕組みです。セッション取得・ログイン・ログアウト・申請の一覧・作成・取得・編集・提出・差戻し・承認の10操作に対応しています。

現時点では、**文書形式と修正ループの実装、合成データでの検証まで**です。この新形式をQwenが生成できたという実測結果は、まだありません。既存の `qwen-docs.yml` にある11項目の動作説明とは、別の検証です。新しい `api-docs.yml` のGPUジョブは、専用変数を有効にした場合だけ実行します。

## 出力するもの

同じOpenAPIの操作データから、次のファイルを作ります。

|ファイル|内容|
|---|---|
|index.md|対象範囲と文書への入口|
|quickstart.md|匿名セッションの取得、ログイン、最初の申請作成まで|
|authentication.md|認証操作の説明|
|reference.md|入力フィールド、認証条件、応答、エラー、JSON例|
|workflow.md|社員と承認者を切り替え、提出・差戻し・編集・再提出・承認する手順。10操作版で作成|
|openapi.yaml|OpenAPI 3.1の定義|
|examples/*.json|クイックスタートで使う入力例|
|review.json|ソースのハッシュ、根拠、未確認事項、生成物の由来|
|local-checks.json|実アプリとの照合結果。検証コマンドで作成|

Qwenには操作単位のOpenAPI断片と説明文を作らせ、テンプレートで表と手順を組み立てる設計です。テンプレートの手順はCookie jarと応答から取得したCSRFを使います。本文とOpenAPIを別々に生成して合わせる構成にはしていません。

全10操作版と、実測を小さく始めるための認証・申請作成の3操作版を用意しています。表示と動作照合を検証する合成データは、モデルによる生成品質の評価には使いません。

## ローカルで形式と検証器を確認する

Java 17、Python 3.9以上を使用します。Pythonの依存関係は仮想環境へ入れます。バージョンと配布物のハッシュは固定しています。

```bash
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install --require-hashes -r config/api-docs-requirements.txt
./scripts/verify-all.sh
.venv-docs/bin/python tests/test_api_docs.py --smoke-out generated/api-docs-synthetic
.venv-docs/bin/python tests/test_api_docs_pipeline.py --smoke-out generated/api-docs-loop-synthetic
```

出力先は未作成のディレクトリを指定してください。既存候補への上書きは拒否します。再実行時は別の出力先を使えます。

`tests/api_docs_fixture.py` は検証器と表示を確かめるために人為的に作った合成データです。Qwenの出力ではありません。生成物の入口と検証記録にも、その区別を表示します。

照合時には専用の一時DBへ検証用ユーザーを作り、空いているloopbackポートでアプリを起動します。全10操作版は68件のHTTP呼出しで、正常系、認証・入力エラー、他の社員の申請へのアクセス、差戻しからの再申請、二重承認、承認後の変更拒否、履歴の保存、ログアウト後の古いCookieの無効化を確認します。終わったら専用プロセスとDBを片付けます。既存のデモ用DBとプロセスは使いません。

OpenAPIとしては正しくても、成功ステータス、nullable、応答フィールド、Cookieの記載、CSRFの認証条件が誤っていれば不合格になります。ただし、説明文の意味、全ての入力境界、TLS、期限、フォーム形式は、この自動検証だけでは保証しません。

## GPUを使わずにQwenへの入力を準備する

```bash
.venv-docs/bin/python scripts/api_docs.py prepare --out generated/api-docs-prepared
```

ソースは `config/api-docs-source-files.txt` の追跡済みアプリコードだけを読みます。関数の前後や呼出し先を読むため、ファイル全体を渡します。重なる根拠の範囲にはIDと行番号だけを添え、ソース本文を繰り返し送らない構成です。評価用のケース、合成文書、記事の草稿はモデル入力に含めません。上限を超えた場合は切り捨てずに停止します。

出力は操作ごとのRunpod ServerlessリクエストJSONと `manifest.json` です。manifestにはソースのSHA-256、送信データのバイト数、予定ジョブ数を記録します。バイト数はトークン数や費用の見積もりではありません。

このコマンドはRunpodへ送信しません。既定では10件の生成ジョブを想定した入力を用意するだけで、レビューや再生成のジョブ数・費用は含みません。3操作だけを準備する場合は `--operation getSession --operation login --operation createRequest` を付けます。入力内のモデル名 `qwen3.8-27b-fp8` は配備時の別名です。実際に使用した重みの識別には、別途デプロイ設定とモデルの由来を確認する必要があります。

CPUだけで入力トークン数を測る任意のコマンドも用意しています。Python 3.12とuvを使用し、指定リビジョンのトークナイザーだけを取得します。モデルの重みやPyTorchは取得しません。

```bash
uv run --python 3.12 --with transformers==5.3.0 --with jinja2==3.1.6 \
  --with jsonschema==4.23.0 --with openapi-spec-validator==0.7.2 --with PyYAML==6.0.2 \
  python scripts/api_docs_tokens.py --out generated/api-docs-tokens.json
```

2026-09-07のローカル測定では1操作の生成入力が40,716〜40,722トークン、出力枠が6,144トークンでした。使用したトークナイザーは `Qwen/Qwen3.8-27B-FP8` のリビジョン `017b9c7af6b5689d5dd426a76e0bc077eb5ca20a` です。レビューや修正には草稿や指摘が加わるため、これより大きな入力になります。推論サーバー独自の追加分や実際の費用は含みません。

## 保存済みの推論回答を文書に変換する

後の実測では、Runpodの `/status` で取得した完了応答を、操作IDに `.response.json` を付けた名前で保存します。3操作版の場合は次の名前です。全10操作版では `manifest.json` にある全操作のファイルが必要です。

- getSession.response.json
- login.response.json
- createRequest.response.json

```bash
.venv-docs/bin/python scripts/api_docs.py render \
  --prepared generated/api-docs-prepared --responses generated/api-docs-responses \
  --out generated/api-docs-candidate
.venv-docs/bin/python scripts/check_api_docs.py \
  --spec generated/api-docs-candidate/openapi.yaml \
  --out generated/api-docs-candidate/local-checks.json
```

途中で切れた回答、操作の欠落や重複、古いソースの根拠ID、例と型の不一致、外部参照を拒否します。準備後にソースや送信データが変わった場合も拒否します。保存した応答のハッシュと申告モデル名は `receipts.json` に残しますが、応答の保存だけで重みの同一性を証明したことにはしません。

不合格の記録には操作IDと該当フィールドを残し、修正依頼に使います。実際のパスワードやCookieをその記録へコピーしません。生成したシェルやプログラムは実行せず、固定されたHTTPクライアントから自分で起動したアプリだけを呼び出します。

## 生成・検査・レビュー・修正を続けて実行する

`api_docs_pipeline.py` は操作別に生成し、OpenAPIと例の検査、実アプリへの照合、同じQwenの別リクエストによるレビューを行います。誤りがあればその操作だけを修正し、文書全体の形式とAPIフローを再検査します。修正した操作はQwenでも再レビューし、変更していない操作のレビューは保持します。

ジョブ数と時間、修正回数には上限があります。次の修正後のレビュー枠を確保できなければ、修正を始めません。途中で切れた回答や不明な送信結果を自動再送せず、記録を残して停止します。未確認事項が残れば、モデルが指摘なしと答えても `needs_review` になります。

以下は**既存エンドポイントに課金ジョブを送る**コマンドです。エンドポイントと専用キーを環境変数へ設定し、起動・待機・実行を含む予算を確保してから実行します。このスクリプトのジョブ数・時間制限は、アカウント全体の金額上限ではありません。

```bash
.venv-docs/bin/python scripts/api_docs_pipeline.py --live \
  --scope auth-create --max-jobs 8 --max-repairs 1 --total-seconds 720 \
  --out generated/api-docs-live
```

`--scope all` は全10操作です。生成とレビューだけでも20ジョブが必要なため、20未満の上限は送信前に拒否します。たとえば `--max-jobs 24` なら修正・再レビュー用の枠も取れますが、上限内での品質合格を保証するものではありません。各回の回答・候補・実アプリの照合・モデルの指摘は別ファイルへ保存します。

### 「1回の費用」と入力規模の記録

ここで測る1回は、選択したソースから対象APIの文書一式を生成し、検査・レビュー・必要な修正まで行う実行です。モデルへのリクエスト1件とは区別します。`summary.json` に入力ファイル数、UTF-8のバイト数、物理行数、空行を除く行数を記録します。これは選択したアプリ本体の規模で、テストなどを含むリポジトリ全体の規模ではありません。

生成・レビュー・修正ごとに、試みたジョブ数、サーバーから報告された入出力トークン数、待ち時間、実行時間、クライアント側の経過時間を集計します。未報告の数値は欠測として扱います。同じソースを複数回送るため、入力トークンの累計はソース1組のトークン数より大きくなります。

実際の利用料は別途、対象エンドポイントの課金記録と照合します。ジョブの待ち時間にはキュー待ちが含まれる一方、起動やアイドル時間にも課金される場合があるため、ジョブの実行時間だけに単価を掛けた値を実費とはしません。起動・待機・失敗した試行も含め、確認するまでは `billed_usd` を未確定にします。文書が不合格なら、その費用を「公開できる文書が完成した費用」とは報告しません。

## CIの範囲

`api-docs.yml` のofflineジョブはソースの更新時に、アプリのテストとビルド、文書検証器のテスト、合成プレビュー、実アプリとの照合、合成回答での修正ループ、送信用JSONの準備を実行します。成果物は `api-docs-synthetic-...` の名前で7日間保存します。

generateジョブは `RUNPOD_API_DOCS_ENABLED=true` の場合だけ実行します。通常は無効です。有効化には `RUNPOD_ENDPOINT_ID` 変数と `RUNPOD_API_KEY` Secretが必要です。範囲は `RUNPOD_API_DOCS_SCOPE`（既定auth-create）、ジョブ数は `RUNPOD_API_DOCS_MAX_JOBS`（既定8）で指定し、CIの実行上限は720秒、修正は最大1回です。全10操作を選ぶならジョブ数も20以上へ設定します。有効化すると対象ブランチのコード更新でも課金が発生します。検証後は変数を無効化し、専用キーとエンドポイントを片付けてください。

成果物を公開サイトへ自動掲載する処理はありません。CIのartifactはリポジトリへアクセスできる人が取得できるため、公開してよいソースと検証結果だけを扱います。合成データによる表示確認、モデルによる生成成功、文書の公開判断を分けて記録します。新形式のQwen実測は、まず3操作で確認する予定です。
