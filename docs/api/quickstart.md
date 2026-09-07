# 最初の申請を作る

アプリをローカルで起動し、READMEのseed手順でEMPLOYEEユーザーを登録してください。
以下はbash、curl、jqを使います。生成物のディレクトリで実行してください。
`BASE_URL`は自分で起動したアプリのURLに合わせます。
パスワード、Cookie、CSRFトークンをリポジトリへ保存しないでください。

```bash
BASE_URL=http://127.0.0.1:8080
set -euo pipefail
read -r -p '登録した社員のユーザー名: ' API_USER
read -r -s -p 'パスワード: ' API_PASSWORD; echo
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
umask 077
# 1. 匿名Cookieと対応するCSRFトークンを取得
curl --fail-with-body -sS -c "$TMP_DIR/cookies" "$BASE_URL/api/session" > "$TMP_DIR/session.json"
CSRF=$(jq -er '.csrfToken' "$TMP_DIR/session.json")
# 2. 登録した資格情報でログイン。以降は応答の新しいCSRFを使う
jq --arg username "$API_USER" --arg password "$API_PASSWORD" \
  '.username=$username | .password=$password' examples/login.json > "$TMP_DIR/login.json"
curl --fail-with-body -sS -b "$TMP_DIR/cookies" -c "$TMP_DIR/cookies" \
  -H 'Content-Type: application/json' -H "X-CSRF-Token: $CSRF" \
  --data-binary @"$TMP_DIR/login.json" "$BASE_URL/api/login" > "$TMP_DIR/logged-in.json"
CSRF=$(jq -er '.csrfToken' "$TMP_DIR/logged-in.json")
# 3. サンプルの品名・数量・単価・理由を確認して申請を作成
curl --fail-with-body -sS -b "$TMP_DIR/cookies" -H 'Content-Type: application/json' \
  -H "X-CSRF-Token: $CSRF" --data-binary @examples/createRequest.json \
  "$BASE_URL/api/requests" > "$TMP_DIR/request.json"
jq '.request' "$TMP_DIR/request.json"
```

社員としてのログインは200、申請作成は201の応答を期待します。
途中で失敗した場合は続けず、リファレンスの該当ステータスを確認してください。
この手順は固定テンプレートで、JSONの入力例は構造化された文書データから作成しています。
CIはこのシェルを実行せず、同じ順序でHTTPクライアントから呼び出して照合します。
