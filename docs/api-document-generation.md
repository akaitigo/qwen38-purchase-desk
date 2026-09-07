# API利用者向けドキュメントの生成と検証

ソースを読まずにAPIを呼び出せる文書を、Qwenで生成するための仕組みです。まず `GET /api/session`、`POST /api/login`、`POST /api/requests` の3操作を対象にしています。

現時点では、**形式の実装と合成データによる検証まで**です。この新形式をQwenが生成できたという実測結果は、まだありません。既存の `qwen-docs.yml` にある11項目の動作説明とは、別の検証です。新しい `api-docs.yml` はGPUを呼び出しません。

## 出力するもの

同じOpenAPIの操作データから、次のファイルを作ります。

|ファイル|内容|
|---|---|
|index.md|対象範囲と文書への入口|
|quickstart.md|匿名セッションの取得、ログイン、最初の申請作成まで|
|authentication.md|認証操作の説明|
|reference.md|入力フィールド、認証条件、応答、エラー、JSON例|
|openapi.yaml|OpenAPI 3.1の定義|
|examples/*.json|クイックスタートで使う入力例|
|review.json|ソースのハッシュ、根拠、未確認事項、生成物の由来|
|local-checks.json|実アプリとの照合結果。検証コマンドで作成|

Qwenには操作単位のOpenAPI断片と説明文を作らせ、テンプレートで表と手順を組み立てる設計です。テンプレートの手順はCookie jarと応答から取得したCSRFを使います。本文とOpenAPIを別々に生成して合わせる構成にはしていません。

まだ申請の提出・差戻し・再申請・承認を含めた10操作のガイドは作っていません。モデルによるレビューと修正も、この新形式には未接続です。

## ローカルで形式と検証器を確認する

Java 17、Python 3.9以上を使用します。Pythonの依存関係は仮想環境へ入れます。バージョンと配布物のハッシュは固定しています。

```bash
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install --require-hashes -r config/api-docs-requirements.txt
./scripts/verify-all.sh
.venv-docs/bin/python tests/test_api_docs.py --smoke-out generated/api-docs-synthetic
```

出力先は未作成のディレクトリを指定してください。既存候補への上書きは拒否します。再実行時は別の出力先を使えます。

`tests/api_docs_fixture.py` は検証器と表示を確かめるために人為的に作った合成データです。Qwenの出力ではありません。生成物の入口と検証記録にも、その区別を表示します。

照合時には専用の一時DBへ検証用ユーザーを作り、空いているloopbackポートでアプリを起動します。正常な呼出しに加え、CSRF欠落、パスワード誤り、Origin不一致、未認証、入力エラー、合計金額超過、承認者による作成を確認します。終わったら専用プロセスとDBを片付けます。既存のデモ用DBとプロセスは使いません。

OpenAPIとしては正しくても、成功ステータス、nullable、応答フィールド、Cookieの記載、CSRFの認証条件が誤っていれば不合格になります。ただし、説明文の意味、全ての入力境界、TLS、期限、フォーム形式、残りの7操作は、この自動検証だけでは保証しません。

## GPUを使わずにQwenへの入力を準備する

```bash
.venv-docs/bin/python scripts/api_docs.py prepare --out generated/api-docs-prepared
```

ソースは `config/api-docs-source-files.txt` の追跡済みアプリコードだけを読みます。評価用のケース、合成文書、記事の草稿はモデル入力に含めません。上限を超えた場合は切り捨てずに停止します。

出力は操作ごとのRunpod ServerlessリクエストJSONと `manifest.json` です。manifestにはソースのSHA-256、送信データのバイト数、予定ジョブ数を記録します。バイト数はトークン数や費用の見積もりではありません。モデルのトークナイザーによる測定は未実施です。

このコマンドはRunpodへ送信しません。3件の生成ジョブを想定した入力を用意するだけで、レビューや再生成のジョブ数・費用は含みません。入力内のモデル名 `qwen3.8-27b-fp8` は配備時の別名です。実際に使用した重みの識別には、別途デプロイ設定とモデルの由来を確認する必要があります。

## 保存済みの推論回答を文書に変換する

後の実測では、Runpodの `/status` で取得した完了応答を、操作ごとに次の名前で保存します。

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

不合格の記録には操作IDと該当フィールドを残し、今後の修正依頼に使えるようにしています。実際のパスワードやCookieをその記録へコピーしません。生成したシェルやプログラムは実行せず、固定されたHTTPクライアントから自分で起動したアプリだけを呼び出します。

## CIの範囲

`api-docs.yml` はソースの更新時に、アプリのテストとビルド、文書検証器のテスト、合成プレビューの生成、実アプリとの照合、送信用JSONの準備を実行します。成果物は `api-docs-synthetic-...` の名前で7日間保存します。推論用の秘密情報も、Runpodへの送信処理もありません。

合格した成果物も自動公開はしません。合成データによる表示確認、モデルによる生成成功、文書の公開判断を分けて記録します。次の実測では、保存済み回答の変換と照合が通るかを確かめてから、新形式のレビュー・修正ループをつなぎます。
