# 提出から承認まで

EMPLOYEEとAPPROVERの2ユーザーをREADMEのseed手順で登録してください。
生成物のディレクトリでbash、curl、jqを使います。JSONの内容は実行前に確認してください。
社員用と承認者用のCookieを別々に保存し、作成応答のIDを後の操作へ引き継ぎます。

```bash
set -euo pipefail
BASE_URL=http://127.0.0.1:8080
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
umask 077
login_as() {
  local actor="$1" username password csrf
  read -r -p "$actor のユーザー名: " username
  read -r -s -p 'パスワード: ' password; echo
  curl --fail-with-body -sS -c "$TMP_DIR/$actor.cookies" "$BASE_URL/api/session" > "$TMP_DIR/$actor.session"
  csrf=$(jq -er '.csrfToken' "$TMP_DIR/$actor.session")
  jq --arg username "$username" --arg password "$password" \
    '.username=$username | .password=$password' examples/login.json > "$TMP_DIR/$actor.login"
  curl --fail-with-body -sS -b "$TMP_DIR/$actor.cookies" -c "$TMP_DIR/$actor.cookies" \
    -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" \
    --data-binary @"$TMP_DIR/$actor.login" "$BASE_URL/api/login" > "$TMP_DIR/$actor.auth"
}
call_api() {
  local actor="$1" method="$2" path="$3" input="${4:-}" csrf
  csrf=$(jq -er '.csrfToken' "$TMP_DIR/$actor.auth")
  local args=(--fail-with-body -sS -X "$method" -b "$TMP_DIR/$actor.cookies"
    -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf")
  if [[ -n "$input" ]]; then args+=(--data-binary "@$input"); fi
  curl "${args[@]}" "$BASE_URL$path"
}
login_as employee
login_as approver
call_api employee POST /api/requests examples/createRequest.json > "$TMP_DIR/request.json"
REQUEST_ID=$(jq -er '.request.id' "$TMP_DIR/request.json")
[[ "$REQUEST_ID" =~ ^[A-Za-z0-9_-]+$ ]]
# 提出
call_api employee POST "/api/requests/$REQUEST_ID/submit" | jq .
# 差戻し
call_api approver POST "/api/requests/$REQUEST_ID/return" examples/returnRequest.json | jq .
# 修正
call_api employee PATCH "/api/requests/$REQUEST_ID" examples/updateRequest.json | jq .
# 再提出
call_api employee POST "/api/requests/$REQUEST_ID/submit" | jq .
# 承認
call_api approver POST "/api/requests/$REQUEST_ID/approve" | jq .
# 最終状態の確認
call_api employee GET "/api/requests/$REQUEST_ID" | jq .
```

作成は201、上の提出・差戻し・編集・再提出・承認・取得は200を期待します。
最後のrequest.stateがAPPROVEDで、historyに各操作が順に残ることを確認してください。
各操作の入力制約と、401・403・404・409などの条件は[リファレンス](reference.md)を参照してください。
この手順とプログラムの照合結果は別に記録しています。自動照合の合格だけで本番利用を保証するものではありません。
