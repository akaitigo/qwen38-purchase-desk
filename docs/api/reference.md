# APIリファレンス

## GET /api/session

セッション状態の取得

現在のセッション状態を取得します。認証済みセッションが存在する場合はユーザー情報とCSRFトークンを返し、未認証の場合は匿名セッションのCSRFトークンを返します。匿名セッションが存在しない場合は新しい匿名セッションを作成し、pd_anon Cookieをセットします。

認証: このエンドポイントは認証不要です。pd_session Cookieが有効な場合は認証済みセッションとして扱われ、pd_anon Cookieが有効な場合は匿名セッションとして扱われます。どちらのCookieも存在しない場合は新しい匿名セッションが作成されます。

CSRF: このエンドポイントはCSRFヘッダー(X-CSRF-Token)を要求しません。レスポンスのcsrfTokenフィールドは、以降の変更操作でX-CSRF-Tokenヘッダーとして送信する必要があります。

Origin/Referer: このエンドポイントはOrigin/Refererヘッダーの検証を行いません。

エラー: サーバー内部エラーが発生した場合、500エラーが返されます。

### 認証条件

ログインは不要です。Cookieがある場合の動作は上の説明を参照してください。

### 応答 200（application/json）

セッション状態の取得成功。認証済みセッションの場合はユーザー情報とCSRFトークンを返し、未認証の場合は匿名セッションのCSRFトークンを返します。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| user | object / null | 必須 | 認証済みセッションの場合はユーザー情報、未認証の場合はnull。 | — |
| user.username | string | 親がオブジェクトの場合に必須 | ユーザー名。 | — |
| user.role | string | 親がオブジェクトの場合に必須 | ユーザーの役割。EMPLOYEEまたはAPPROVER。 | {"enum": ["EMPLOYEE", "APPROVER"]} |
| csrfToken | string | 必須 | 以降の変更操作でX-CSRF-Tokenヘッダーとして送信するCSRFトークン。 | — |

```json
{
  "user": {
    "username": "YOUR_USERNAME",
    "role": "EMPLOYEE"
  },
  "csrfToken": "EXAMPLE_TOKEN"
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | 匿名セッションが存在しない場合にのみ設定されます。pd_anon Cookieに新しい匿名セッショントークンが設定されます。HttpOnly、SameSite=Lax、Path=/が設定されます。publicOriginが設定されている場合はSecureフラグも付与されます。 |

### 応答 500（application/json）

サーバー内部エラー。予期しない例外が発生した場合に返されます。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## POST /api/login

ユーザー認証（ログイン）

ユーザー名とパスワードを認証し、成功すると新しいセッションCookie（pd_session）とCSRFトークンを返します。

【認証・役割】
- 認証はCookieベースです。ログイン前（匿名）またはログイン中（認証済み）のいずれの場合でも、CSRFトークンが必要です。
- 匿名の場合：Cookie「pd_anon」と、そのセッションに紐づくCSRFトークンをヘッダー「X-CSRF-Token」で送信する必要があります。
- 認証済みの場合：Cookie「pd_session」と、そのセッションに紐づくCSRFトークンをヘッダー「X-CSRF-Token」で送信する必要があります。

【検査順序】
1. メソッドがPOSTであることを確認。
2. Origin/RefererヘッダーによるSame-Originチェック。
3. JSON本文の解析。
4. CSRFトークンの検証（セッションの有無に応じて匿名セッションまたは認証済みセッションのトークンと照合）。
5. ユーザー名とパスワードの照合。

【Origin/Referer欠落時】
- Originを優先し、Originがない場合はRefererを確認します。両方のヘッダーがない場合に限り、Same-Originチェックを省略します。

【エラーの原因と対処】
- 401: ユーザー名またはパスワードが不正です。
- 403: CSRFトークンが不正、またはOriginが一致しません。
- 405: メソッドがPOSTではありません。
- 400: JSONの形式が正しくありません。
- 500: サーバー内部エラー。

【Cookie】
- 成功時、`pd_session` Cookieが設定されます（Max-Age=86400、HttpOnly、SameSite=Lax、Secureは設定に応じて付与）。
- 匿名セッションからログインした場合、`pd_anon` Cookieはクリアされます（Max-Age=0）。

【補足】
- CSRFトークンはヘッダー「X-CSRF-Token」で送信してください。

### 認証条件

次のいずれかの組合せを送信します。

- Cookie `pd_anon` と ヘッダー `X-CSRF-Token`
- Cookie `pd_session` と ヘッダー `X-CSRF-Token`

### パラメーター

```json
[
  {
    "name": "X-CSRF-Token",
    "in": "header",
    "required": false,
    "description": "CSRFトークン。ヘッダーまたはフォームフィールド「csrfToken」のいずれかで送信可能。",
    "schema": {
      "type": "string"
    }
  }
]
```

### リクエスト（application/json）

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| username | string | 必須 | ユーザー名。必須。 | — |
| password | string | 必須 | パスワード。必須。 | — |

```json
{
  "username": "YOUR_USERNAME",
  "password": "YOUR_PASSWORD"
}
```

### 応答 200（application/json）

ログイン成功。新しいセッションCookieとCSRFトークンを返します。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| user | object | 必須 |  | — |
| user.username | string | 親がオブジェクトの場合に必須 | ユーザー名。 | — |
| user.role | string | 親がオブジェクトの場合に必須 | ユーザーの役割。 | {"enum": ["EMPLOYEE", "APPROVER"]} |
| csrfToken | string | 必須 | 新しいセッションに紐づくCSRFトークン。 | — |

```json
{
  "user": {
    "username": "YOUR_USERNAME",
    "role": "EMPLOYEE"
  },
  "csrfToken": "EXAMPLE_TOKEN"
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | セッションCookie（pd_session）を設定します。匿名セッションからログインした場合はpd_anonもクリアされます。 |

### 応答 400（application/json）

JSONの形式が正しくありません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "JSONの形式が正しくありません。"
}
```

### 応答 401（application/json）

ユーザー名またはパスワードが違います。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "ユーザー名またはパスワードが違います。"
}
```

### 応答 403（application/json）

CSRFトークンが不正、またはOriginが一致しません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "CSRFトークンが不正です。"
}
```

### 応答 405（application/json）

メソッドがサポートされていません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "メソッドがサポートされていません。"
}
```

### 応答 500（application/json）

サーバー内部エラーです。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## POST /api/requests

購入申請を作成する

購入申請をDRAFT（下書き）状態で作成します。

【認証と認可】
- 認証: `pd_session` Cookie に有効なセッショントークンが必要です。無効または欠落の場合は 401 が返ります。
- 認可: ユーザーのロールが `EMPLOYEE`（社員）である必要があります。`APPROVER`（承認者）が呼び出すと 403 が返ります。

【CSRF 保護】
- 変更操作であるため、CSRF トークンの検証が行われます。
- `X-CSRF-Token` ヘッダーに、現在のセッションに紐付けられた CSRF トークンを送信する必要があります。トークンが不正または欠落の場合は 403 が返ります。
- 同一オリジンチェック（Origin/Referer ヘッダー）も実施されます。Origin ヘッダーが優先され、欠落時は Referer ヘッダーが参照されます。両方が欠落している場合はチェックがスキップされます。ヘッダーが存在する場合、サーバーの期待するオリジンと一致しない場合は 403 が返ります。

【入力データ】
- `itemName`: 品名。前後の空白を除いた長さが1〜100文字。
- `quantity`: 数量。1 以上、1000 以下の整数。
- `unitPriceYen`: 単価（円）。0 以上、1,000,000 以下の整数。
- `reason`: 購入理由。空白以外で 1 文字以上、1000 文字以下。
- 合計金額（quantity * unitPriceYen）が 100,000,000 円を超えると 400 が返ります。

【エラー】
- 400: 入力値の検証エラー（形式、範囲、合計金額の上限超過など）。
- 401: ログインしていない、またはセッションが無効。
- 403: 権限不足（承認者が呼び出した場合）、CSRF トークン不正、またはオリジン不一致。
- 500: サーバー内部エラー。

【検査順序】
認証、同一オリジン、CSRF、本文の解析と入力値、EMPLOYEEロールの順に検査します。たとえば承認者が不正な入力を送った場合、ロール確認より前に400となることがあります。

### 認証条件

Cookie `pd_session` と ヘッダー `X-CSRF-Token` を送信します。

### リクエスト（application/json）

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| itemName | string | 必須 | 品名。前後の空白は除去されてから長さが検証されます。 | {"minLength": 1, "maxLength": 100} |
| quantity | integer | 必須 | 数量。整数のみ許可。 | {"minimum": 1, "maximum": 1000} |
| unitPriceYen | integer | 必須 | 単価（円）。整数のみ許可。 | {"minimum": 0, "maximum": 1000000} |
| reason | string | 必須 | 購入理由。空白のみは不可。 | {"minLength": 1, "maxLength": 1000} |

```json
{
  "itemName": "ノートPC",
  "quantity": 2,
  "unitPriceYen": 150000,
  "reason": "開発環境の更新のため"
}
```

### 応答 201（application/json）

申請が正常に作成されました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| request | object | 必須 |  | — |
| request.id | string | 親がオブジェクトの場合に必須 | 申請の一意な識別子。 | — |
| request.ownerUsername | string | 親がオブジェクトの場合に必須 | 申請者のユーザー名。 | — |
| request.itemName | string | 親がオブジェクトの場合に必須 | 品名。 | — |
| request.quantity | integer | 親がオブジェクトの場合に必須 | 数量。 | — |
| request.unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円）。 | — |
| request.totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円）。quantity * unitPriceYen で計算されます。 | — |
| request.reason | string | 親がオブジェクトの場合に必須 | 購入理由。 | — |
| request.state | string | 親がオブジェクトの場合に必須 | 申請の状態。作成直後は常に DRAFT です。 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| request.history | array | 親がオブジェクトの場合に必須 | 履歴エントリの配列。作成直後は CREATE エントリが 1 つ含まれます。 | — |
| request.history[].action | string | 親がオブジェクトの場合に必須 | アクションの種類。 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| request.history[].actorUsername | string | 親がオブジェクトの場合に必須 | アクションを実行したユーザー名。 | — |
| request.history[].at | string | 親がオブジェクトの場合に必須 | アクションの実行時刻（UTC、ISO 8601 形式）。 | {"format": "date-time"} |
| request.history[].comment | string | 親がオブジェクトの場合に必須 | コメント。CREATE アクションでは空文字列です。 | — |

```json
{
  "request": {
    "id": "1234567890abcdef",
    "ownerUsername": "YOUR_USERNAME",
    "itemName": "ノートPC",
    "quantity": 2,
    "unitPriceYen": 150000,
    "totalYen": 300000,
    "reason": "開発環境の更新のため",
    "state": "DRAFT",
    "history": [
      {
        "action": "CREATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T10:00:00Z",
        "comment": ""
      }
    ]
  }
}
```

### 応答 400（application/json）

入力値の検証に失敗しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "「quantity」は 1〜1000 の範囲で入力してください。"
}
```

### 応答 401（application/json）

認証が必要です。ログインしていないか、セッションが無効です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 403（application/json）

アクセスが拒否されました。権限不足、CSRF トークン不正、またはオリジン不一致。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "社員のみが申請を作成・編集・提出できます。"
}
```

### 応答 500（application/json）

サーバー内部エラーが発生しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## POST /api/logout

ログアウト

ログアウト

現在のセッションを終了し、新しい匿名セッションを発行します。

【認証・役割】
- 認証済みセッション（pd_session Cookie）または匿名セッション（pd_anon Cookie）のいずれかが必要です。
- 認証済みセッションの場合、X-CSRF-Token ヘッダーにセッションに紐づく CSRF トークンを送信する必要があります。
- 匿名セッションの場合、pd_anon Cookie と X-CSRF-Token ヘッダー（またはフォームの csrfToken）が匿名セッションに紐づくトークンと一致する必要があります。

【検査順序】
1. メソッドが POST であることを確認します。異なる場合は 405 を返します。
2. Origin または Referer ヘッダーによる Same-Origin チェックを行います。Origin ヘッダーが存在しない場合のみ Referer を参照し、両方が存在しない場合はチェックをスキップします。存在する場合、リクエストの Origin/Referer がサーバーの期待するオリジン（Host ヘッダーまたは設定された public-origin）と一致しない場合は 403 を返します。
3. 認証済みセッション（pd_session）が存在するか確認します。
   - 存在する場合、X-CSRF-Token がセッションの CSRF トークンと一致するか確認します。不一致の場合は 403 を返します。一致する場合、セッションを削除します。
   - 存在しない場合、匿名セッション（pd_anon）を確認します。pd_anon Cookie が存在しない場合、または X-CSRF-Token が匿名セッションの CSRF トークンと一致しない場合は 403 を返します。
4. 新しい匿名セッション（トークンと CSRF トークン）を作成し、pd_anon Cookie として設定します。
5. pd_session Cookie をクリア（Max-Age=0）します。
6. 新しい匿名セッションの CSRF トークンを含む JSON を返します。

【エラーの原因と対処】
- 403: Origin/Referer が一致しない、または CSRF トークンが不正です。正しい Origin ヘッダーと、/api/session から取得した最新の CSRF トークンを X-CSRF-Token ヘッダーに設定してください。
- 405: メソッドが POST ではありません。POST メソッドを使用してください。
- 500: サーバー内部エラーです。

### 認証条件

次のいずれかの組合せを送信します。

- Cookie `pd_session` と ヘッダー `X-CSRF-Token`
- Cookie `pd_anon` と ヘッダー `X-CSRF-Token`

### パラメーター

```json
[
  {
    "name": "X-CSRF-Token",
    "in": "header",
    "required": true,
    "description": "CSRF トークン。/api/session から取得した最新のトークンを使用します。",
    "schema": {
      "type": "string"
    }
  }
]
```

### 応答 200（application/json）

ログアウト成功。新しい匿名セッションが発行されました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| user | null | 必須 | ユーザー情報。ログアウト後は常に null です。 | — |
| csrfToken | string | 必須 | 新しい匿名セッションの CSRF トークン。 | — |

```json
{
  "user": null,
  "csrfToken": "EXAMPLE_TOKEN"
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | pd_session Cookie をクリアし、新しい pd_anon Cookie を設定します。 |

### 応答 403（application/json）

Origin が一致しない、または CSRF トークンが不正です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "CSRFトークンが不正です。"
}
```

### 応答 405（application/json）

メソッドがサポートされていません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "メソッドがサポートされていません。"
}
```

### 応答 500（application/json）

サーバー内部エラーです。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## GET /api/requests

購入申請一覧を取得する

認証済みセッションのユーザーが閲覧可能な購入申請を、IDの降順に取得します。

【認証とロール】
- 認証: `pd_session` Cookie に有効なセッショントークンを保持している必要があります。Cookieが欠落しているか、DBに存在しない（無効）トークンの場合は 401 が返されます。
- ロールによる可視性:
  - `EMPLOYEE`（社員）: 自分が作成した（`ownerUsername` が自分）申請のみが返されます。
  - `APPROVER`（承認者）: 全申請が返されます。

【CSRF・Origin/Referer】
- 本操作は GET メソッドであり、データを変更しないため、CSRF トークン（`X-CSRF-Token` ヘッダー）や Origin/Referer ヘッダーの検証は行われません。
- したがって、これらのヘッダーが欠落していてもエラーにはなりません。

【エラーの原因と対処】
- 401: セッション Cookie が存在しない、またはDBに存在しない（無効）場合。対処: ログインし、有効なセッション Cookie を取得してください。
- 500: サーバー内部エラー。対処: サーバーログを確認してください。

【補足】
- レスポンスには、各申請の履歴（history）も含まれます。

### 認証条件

Cookie `pd_session` を送信します。

### 応答 200（application/json）

購入申請の一覧が正常に取得されました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| requests | array | 必須 | 購入申請の配列。IDの降順に並べられています。 | — |
| requests[].id | string | 親がオブジェクトの場合に必須 | 申請の一意な識別子。UUIDv7スタイルの文字列。 | — |
| requests[].ownerUsername | string | 親がオブジェクトの場合に必須 | 申請を作成したユーザー名。 | — |
| requests[].itemName | string | 親がオブジェクトの場合に必須 | 購入する品物の名称。1文字以上100文字以下。 | — |
| requests[].quantity | integer | 親がオブジェクトの場合に必須 | 購入数量。1以上1000以下。 | {"format": "int64", "minimum": 1, "maximum": 1000} |
| requests[].unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円）。0以上1,000,000以下。 | {"format": "int64", "minimum": 0, "maximum": 1000000} |
| requests[].totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円）。quantity * unitPriceYen の値。100,000,000以下。 | {"format": "int64", "minimum": 0, "maximum": 100000000} |
| requests[].reason | string | 親がオブジェクトの場合に必須 | 購入理由。1文字以上1000文字以下。 | — |
| requests[].state | string | 親がオブジェクトの場合に必須 | 申請の状態。 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| requests[].history | array | 親がオブジェクトの場合に必須 | 申請の履歴。時間順（古い順）に並べられています。 | — |
| requests[].history[].action | string | 親がオブジェクトの場合に必須 | 履歴のアクション種別。 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| requests[].history[].actorUsername | string | 親がオブジェクトの場合に必須 | アクションを実行したユーザー名。 | — |
| requests[].history[].at | string | 親がオブジェクトの場合に必須 | アクションの実行日時（UTC、ISO 8601形式、秒単位）。 | — |
| requests[].history[].comment | string | 親がオブジェクトの場合に必須 | アクションに関するコメント。差し戻し（RETURN）の場合に差し戻し理由が入ります。それ以外は空文字列。 | — |

```json
{
  "requests": [
    {
      "id": "1234567890abcdef",
      "ownerUsername": "YOUR_USERNAME",
      "itemName": "ノートPC",
      "quantity": 1,
      "unitPriceYen": 150000,
      "totalYen": 150000,
      "reason": "業務用として必要",
      "state": "DRAFT",
      "history": [
        {
          "action": "CREATE",
          "actorUsername": "YOUR_USERNAME",
          "at": "2023-10-27T10:00:00Z",
          "comment": ""
        }
      ]
    }
  ]
}
```

### 応答 401（application/json）

認証エラー。セッション Cookie が存在しない、またはDBに存在しない（無効）です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 500（application/json）

サーバー内部エラー。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## GET /api/requests/{id}

購入申請の取得

指定したIDの購入申請を取得します。

【認証】
- 認証済みセッションが必要です。Cookie `pd_session` に有効なセッショントークンを含めてください。
- 認証されていない場合、またはセッションが無効な場合は 401 を返します。

【権限】
- 承認者（APPROVER）はすべての申請を取得できます。
- 社員（EMPLOYEE）は自分が作成した申請のみ取得できます。
- 権限のない申請を取得しようとすると 404 を返します。

【IDの形式】
- IDは英数字、アンダースコア、ハイフンで構成される必要があります。
- 形式が不正なIDは 404 を返します。

【エラー】
- 401: 認証されていない、またはセッションが無効。
- 404: 申請が存在しない、または権限がない。
- 500: サーバー内部エラー。

### 認証条件

Cookie `pd_session` を送信します。

### パラメーター

```json
[
  {
    "name": "id",
    "in": "path",
    "required": true,
    "description": "取得する購入申請のID。英数字、アンダースコア、ハイフンで構成される文字列。",
    "schema": {
      "type": "string",
      "pattern": "^[A-Za-z0-9_-]+$"
    }
  }
]
```

### 応答 200（application/json）

申請の取得に成功しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| request | object | 必須 |  | — |
| request.id | string | 親がオブジェクトの場合に必須 | 申請の一意な識別子。 | — |
| request.ownerUsername | string | 親がオブジェクトの場合に必須 | 申請を作成したユーザー名。 | — |
| request.itemName | string | 親がオブジェクトの場合に必須 | 購入する物品の名前。 | — |
| request.quantity | integer | 親がオブジェクトの場合に必須 | 購入数量。 | {"format": "int64"} |
| request.unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円）。 | {"format": "int64"} |
| request.totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円）。 | {"format": "int64"} |
| request.reason | string | 親がオブジェクトの場合に必須 | 購入理由。 | — |
| request.state | string | 親がオブジェクトの場合に必須 | 申請の状態。 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| request.history | array | 親がオブジェクトの場合に必須 | 申請の履歴。 | — |
| request.history[].action | string | 親がオブジェクトの場合に必須 | 履歴のアクション種別。 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| request.history[].actorUsername | string | 親がオブジェクトの場合に必須 | アクションを実行したユーザー名。 | — |
| request.history[].at | string | 親がオブジェクトの場合に必須 | アクションの実行時刻（ISO 8601形式）。 | — |
| request.history[].comment | string | 親がオブジェクトの場合に必須 | アクションに関するコメント。 | — |

```json
{
  "request": {
    "id": "1234567890abcdef",
    "ownerUsername": "YOUR_USERNAME",
    "itemName": "ノートPC",
    "quantity": 1,
    "unitPriceYen": 150000,
    "totalYen": 150000,
    "reason": "業務用",
    "state": "DRAFT",
    "history": [
      {
        "action": "CREATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-01T12:00:00Z",
        "comment": ""
      }
    ]
  }
}
```

### 応答 401（application/json）

認証されていない、またはセッションが無効です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 404（application/json）

申請が存在しない、または権限がありません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "申請が見つかりません。（ID: 1234567890abcdef）"
}
```

### 応答 500（application/json）

サーバー内部エラーです。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## PATCH /api/requests/{id}

購入申請を更新する

DRAFTまたはRETURNED状態の購入申請の品名、数量、単価、購入理由を更新します。更新は申請の所有者（ownerUsername）がEMPLOYEEロールでログインしている場合のみ許可されます。APPROVERロールによる更新は403、別の社員が所有する申請への更新は404になります。SUBMITTEDまたはAPPROVED状態の申請は更新不可で409エラーになります。

認証はpd_session Cookieによるセッション認証が必要です。変更操作のため、X-CSRF-Tokenヘッダーにセッションに紐づくCSRFトークンを送信する必要があります。また、OriginまたはRefererヘッダーがリクエストのHostヘッダー（または設定されたpublicOrigin）と一致するsame-originチェックが行われます。Originを優先し、Originがない場合はRefererを確認します。両方がない場合はsame-originチェックを省略しますが、CSRFトークンの検証は行われます。

リクエストボディはJSON形式で、itemName、quantity、unitPriceYen、reasonの4フィールドが必須です。idフィールドを指定した場合は、パスの{id}と一致する必要があります。不一致の場合は400エラーになります。totalYenはサーバー側でquantity × unitPriceYenとして自動計算され、100,000,000円を超える場合は400エラーになります。

成功時は更新後の申請情報がJSONで返されます。

認証、同一オリジン、CSRF、本文の解析と入力値、EMPLOYEEロールの順に検査し、その後に申請の存在・閲覧権限と状態を確認します。権限や状態のエラーより先に入力不正の400を返す場合があります。

### 認証条件

Cookie `pd_session` と ヘッダー `X-CSRF-Token` を送信します。

### パラメーター

```json
[
  {
    "name": "id",
    "in": "path",
    "required": true,
    "description": "更新対象の購入申請のID。英数字、ハイフン、アンダースコアで構成される文字列です。",
    "schema": {
      "type": "string",
      "pattern": "^[A-Za-z0-9_-]+$"
    }
  }
]
```

### リクエスト（application/json）

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| id | string | 省略可 | 申請ID。省略可能。指定した場合はパスの{id}と一致する必要があります。 | — |
| itemName | string | 必須 | 品名。前後の空白を除去して1文字以上、100文字以下で入力します。 | {"minLength": 1, "maxLength": 100} |
| quantity | integer | 必須 | 数量。1以上、1000以下の整数です。 | {"minimum": 1, "maximum": 1000} |
| unitPriceYen | integer | 必須 | 単価（円）。0以上、1,000,000以下の整数です。 | {"minimum": 0, "maximum": 1000000} |
| reason | string | 必須 | 購入理由。空白以外で1文字以上、1000文字以下で入力します。 | {"minLength": 1, "maxLength": 1000} |

```json
{
  "itemName": "ノートPC",
  "quantity": 2,
  "unitPriceYen": 150000,
  "reason": "開発環境の更新のため"
}
```

### 応答 200（application/json）

申請が正常に更新されました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| request | object | 必須 |  | — |
| request.id | string | 親がオブジェクトの場合に必須 | 申請の一意なID。 | — |
| request.ownerUsername | string | 親がオブジェクトの場合に必須 | 申請者のユーザー名。 | — |
| request.itemName | string | 親がオブジェクトの場合に必須 | 品名。 | — |
| request.quantity | integer | 親がオブジェクトの場合に必須 | 数量。 | — |
| request.unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円）。 | — |
| request.totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円）。quantity × unitPriceYenで計算されます。 | — |
| request.reason | string | 親がオブジェクトの場合に必須 | 購入理由。 | — |
| request.state | string | 親がオブジェクトの場合に必須 | 申請の状態。DRAFT、SUBMITTED、APPROVED、RETURNEDのいずれかです。 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| request.history | array | 親がオブジェクトの場合に必須 | 申請の履歴。作成順に並べられます。 | — |
| request.history[].action | string | 親がオブジェクトの場合に必須 | 操作の種類。CREATE、UPDATE、SUBMIT、RETURN、APPROVEのいずれかです。 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| request.history[].actorUsername | string | 親がオブジェクトの場合に必須 | 操作を行ったユーザー名。 | — |
| request.history[].at | string | 親がオブジェクトの場合に必須 | 操作の時刻（UTC、ISO 8601形式）。 | — |
| request.history[].comment | string | 親がオブジェクトの場合に必須 | コメント。差し戻し理由などが記録されます。 | — |

```json
{
  "request": {
    "id": "1a2b3c4d5e6f7890",
    "ownerUsername": "YOUR_USERNAME",
    "itemName": "ノートPC",
    "quantity": 2,
    "unitPriceYen": 150000,
    "totalYen": 300000,
    "reason": "開発環境の更新のため",
    "state": "DRAFT",
    "history": [
      {
        "action": "CREATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2024-01-15T10:30:00Z",
        "comment": ""
      },
      {
        "action": "UPDATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2024-01-15T11:00:00Z",
        "comment": ""
      }
    ]
  }
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | セッションCookie（pd_session）が再発行される場合があります。Max-Age=86400（24時間）で設定されます。 |

### 応答 400（application/json）

リクエストの検証に失敗しました。原因：JSON形式が不正、必須フィールドの欠落、値の範囲外、合計金額の上限超過、IDの不一致など。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ（日本語）。 | — |

```json
{
  "error": "「quantity」は 1〜1000 の範囲で入力してください。"
}
```

### 応答 401（application/json）

認証が必要です。pd_session Cookieがない、またはセッショントークンが無効です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ（日本語）。 | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 403（application/json）

CSRFトークン不一致、Originの検査失敗、またはEMPLOYEE以外のロールによる操作です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ（日本語）。 | — |

```json
{
  "error": "この申請を編集する権限がありません。"
}
```

### 応答 404（application/json）

申請が存在しない、または別の社員が所有する申請です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ（日本語）。 | — |

```json
{
  "error": "申請が見つかりません。（ID: 1a2b3c4d5e6f7890）"
}
```

### 応答 409（application/json）

状態の競合が発生しました。SUBMITTEDまたはAPPROVED状態の申請は更新できません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ（日本語）。 | — |

```json
{
  "error": "承認済みの申請は変更できません。"
}
```

### 応答 500（application/json）

サーバー内部エラーが発生しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ（日本語）。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## POST /api/requests/{id}/submit

申請を提出する

下書き（DRAFT）または差し戻し済み（RETURNED）の申請を提出済み（SUBMITTED）に変更します。

【認証と認可】
- 認証: 有効なセッションCookie（pd_session）が必要です。Cookieがない、またはセッショントークンが無効な場合、401エラーが返されます。
- 認可: 申請の所有者（ownerUsername）である社員（EMPLOYEE）のみが実行可能です。承認者（APPROVER）や他の社員の申請に対して実行しようとすると、403エラーが返されます。

【CSRF保護】
- 変更操作のため、CSRFトークンの検証が行われます。
- ヘッダー `X-CSRF-Token` に、現在のセッションに紐付けられたCSRFトークンを指定する必要があります。
- トークンが不正または欠落の場合、403エラーが返されます。

【Origin/Refererチェック】
- 変更操作のため、OriginまたはRefererヘッダーによる同一オリジンチェックが行われます。
- Originを優先し、Originがない場合はRefererを確認します。両方がない場合に限りチェックを省略します。
- ヘッダーが存在する場合、リクエストのオリジンがサーバーの期待するオリジン（デフォルトはHostヘッダー由来、または設定されたpublicOrigin）と一致する必要があります。一致しない場合、403エラーが返されます。

【状態遷移】
- 申請の状態が DRAFT または RETURNED の場合のみ成功します。
- 申請の状態が SUBMITTED または APPROVED の場合、409エラーが返されます。

【エラー処理】
- 401: 認証エラー（セッション無効）
- 403: 認可エラー（所有者でない、またはCSRF/Originチェック失敗）
- 404: 申請が見つからない
- 409: 状態遷移の競合（現在の状態では提出できない）
- 500: サーバー内部エラー

### 認証条件

Cookie `pd_session` と ヘッダー `X-CSRF-Token` を送信します。

### パラメーター

```json
[
  {
    "name": "id",
    "in": "path",
    "required": true,
    "description": "提出する申請のID",
    "schema": {
      "type": "string",
      "pattern": "^[A-Za-z0-9_-]+$"
    }
  }
]
```

### 応答 200（application/json）

申請が正常に提出されました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| request | object | 必須 |  | — |
| request.id | string | 親がオブジェクトの場合に必須 | 申請の一意な識別子 | — |
| request.ownerUsername | string | 親がオブジェクトの場合に必須 | 申請を作成したユーザー名 | — |
| request.itemName | string | 親がオブジェクトの場合に必須 | 購入品名（1〜100文字） | — |
| request.quantity | integer | 親がオブジェクトの場合に必須 | 数量 | {"format": "int64", "minimum": 1, "maximum": 1000} |
| request.unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円） | {"format": "int64", "minimum": 0, "maximum": 1000000} |
| request.totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円） | {"format": "int64", "minimum": 0, "maximum": 100000000} |
| request.reason | string | 親がオブジェクトの場合に必須 | 購入理由（1〜1000文字） | — |
| request.state | string | 親がオブジェクトの場合に必須 | 申請の状態 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| request.history | array | 親がオブジェクトの場合に必須 | 申請の履歴（時系列順） | — |
| request.history[].action | string | 親がオブジェクトの場合に必須 | アクションの種類 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| request.history[].actorUsername | string | 親がオブジェクトの場合に必須 | アクションを実行したユーザー名 | — |
| request.history[].at | string | 親がオブジェクトの場合に必須 | アクション実行時刻（UTC, ISO 8601） | {"format": "date-time"} |
| request.history[].comment | string | 親がオブジェクトの場合に必須 | コメント（差分戻し理由など） | — |

```json
{
  "request": {
    "id": "1234567890abcdef",
    "ownerUsername": "YOUR_USERNAME",
    "itemName": "ノートPC",
    "quantity": 1,
    "unitPriceYen": 150000,
    "totalYen": 150000,
    "reason": "業務用として購入希望",
    "state": "SUBMITTED",
    "history": [
      {
        "action": "CREATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T10:00:00Z",
        "comment": ""
      },
      {
        "action": "SUBMIT",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T11:00:00Z",
        "comment": ""
      }
    ]
  }
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | セッションCookie（pd_session）が更新される場合があります。 |

### 応答 401（application/json）

認証エラー。有効なセッションCookieがありません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 403（application/json）

認可エラー。申請の所有者でない、またはCSRFトークン/Originチェックに失敗しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ | — |

```json
{
  "error": "この申請を提出する権限がありません。"
}
```

### 応答 404（application/json）

申請が見つかりません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ | — |

```json
{
  "error": "申請が見つかりません。（ID: 1234567890abcdef）"
}
```

### 応答 409（application/json）

状態遷移の競合。現在の状態では提出できません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ | — |

```json
{
  "error": "この操作は現在の状態（提出済み）では行えません。"
}
```

### 応答 500（application/json）

サーバー内部エラー。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## POST /api/requests/{id}/return

提出済みの購入申請を差し戻す

承認者（APPROVER）が、提出済み（SUBMITTED）の購入申請を差し戻し済み（RETURNED）に変更します。

【認証と変更操作の検査】
- 有効なpd_session Cookieと、そのセッションのX-CSRF-Tokenヘッダーが必要です。
- Originを優先し、Originがない場合はRefererを確認します。両方がない場合は同一オリジンの検査を省略します。ヘッダーがある場合は、Hostから求めたオリジンまたは設定されたpublicOriginと一致する必要があります。
- Cookieがない、またはセッショントークンが無効なら401です。サーバー側ではセッションの作成日時による期限判定を行っていません。

【本文と処理の順序】
認証、同一オリジンの検査、CSRFの検査、JSON本文の解析、commentの検証の順に進みます。その後、APPROVERロールであることを確認し、申請を取得して状態を変更します。commentは必須で、前後の空白を除いた長さが1〜1000文字である必要があります。
成功すると状態がRETURNEDになり、RETURNの操作履歴に差し戻し理由を保存します。

【失敗時の対処】
- 400: JSONやcommentが不正です。本文の形式と差し戻し理由を確認してください。
- 401: ログインしていない、またはセッションが無効です。ログインし直してください。
- 403: 変更操作の検査に失敗した、または承認者ではありません。Cookie、CSRF、送信元、ユーザーの役割を確認してください。
- 404: 申請がありません。IDを確認してください。
- 409: SUBMITTED以外の状態です。申請の現在の状態を確認してください。
- 500: サーバー内部エラーです。サーバーログを確認してください。

### 認証条件

Cookie `pd_session` と ヘッダー `X-CSRF-Token` を送信します。

### パラメーター

```json
[
  {
    "name": "id",
    "in": "path",
    "required": true,
    "description": "差し戻す購入申請の ID。英数字、ハイフン、アンダースコアで構成される文字列です。",
    "schema": {
      "type": "string",
      "pattern": "^[A-Za-z0-9_-]+$"
    }
  }
]
```

### リクエスト（application/json）

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| comment | string | 必須 | 差し戻しの理由。トリム後、1文字以上1000文字以下である必要があります。 | {"minLength": 1, "maxLength": 1000} |

```json
{
  "comment": "予算超過のため、数量を減らして再提出してください。"
}
```

### 応答 200（application/json）

差し戻しが成功しました。申請の状態は RETURNED に変更され、履歴に RETURN エントリが追加されます。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| request | object | 必須 |  | — |
| request.id | string | 親がオブジェクトの場合に必須 | 申請の一意な ID。 | — |
| request.ownerUsername | string | 親がオブジェクトの場合に必須 | 申請者のユーザー名。 | — |
| request.itemName | string | 親がオブジェクトの場合に必須 | 購入する品名。 | — |
| request.quantity | integer | 親がオブジェクトの場合に必須 | 購入数量。 | {"format": "int64"} |
| request.unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円）。 | {"format": "int64"} |
| request.totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円）。quantity * unitPriceYen。 | {"format": "int64"} |
| request.reason | string | 親がオブジェクトの場合に必須 | 購入理由。 | — |
| request.state | string | 親がオブジェクトの場合に必須 | 申請の現在の状態。この操作後は常に RETURNED です。 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| request.history | array | 親がオブジェクトの場合に必須 | 申請の履歴エントリの配列。時系列順。 | — |
| request.history[].action | string | 親がオブジェクトの場合に必須 | 履歴のアクション種別。 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| request.history[].actorUsername | string | 親がオブジェクトの場合に必須 | アクションを実行したユーザー名。 | — |
| request.history[].at | string | 親がオブジェクトの場合に必須 | アクションの実行日時（UTC、ISO 8601 形式）。 | — |
| request.history[].comment | string | 親がオブジェクトの場合に必須 | アクションに関するコメント。RETURN アクションの場合は差し戻し理由が入ります。 | — |

```json
{
  "request": {
    "id": "1234567890abcdef",
    "ownerUsername": "YOUR_USERNAME",
    "itemName": "ノートパソコン",
    "quantity": 1,
    "unitPriceYen": 150000,
    "totalYen": 150000,
    "reason": "業務用",
    "state": "RETURNED",
    "history": [
      {
        "action": "CREATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T10:00:00Z",
        "comment": ""
      },
      {
        "action": "SUBMIT",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T11:00:00Z",
        "comment": ""
      },
      {
        "action": "RETURN",
        "actorUsername": "APPROVER_USER",
        "at": "2023-10-27T12:00:00Z",
        "comment": "予算超過のため、数量を減らして再提出してください。"
      }
    ]
  }
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | セッション Cookie の更新（必要に応じて）。 |

### 応答 400（application/json）

リクエストボディの形式が不正、または comment フィールドの検証に失敗しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "差し戻し理由を1文字以上、1000文字以下で入力してください。"
}
```

### 応答 401（application/json）

認証に失敗しました。セッション Cookie が無効または欠落しています。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 403（application/json）

認可に失敗しました。承認者でない、CSRF トークンが不正、または Origin が一致しません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "承認者のみがこの操作を行えます。"
}
```

### 応答 404（application/json）

指定された ID の申請が存在しません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "申請が見つかりません。（ID: 1234567890abcdef）"
}
```

### 応答 409（application/json）

申請が現在 SUBMITTED 状態ではありません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "この操作は現在の状態（下書き）では行えません。"
}
```

### 応答 500（application/json）

サーバー内部エラーが発生しました。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```

## POST /api/requests/{id}/approve

提出済みの購入申請を承認する

提出済み（SUBMITTED）の購入申請を承認し、状態を承認済み（APPROVED）へ遷移させます。

【認証と認可】
- 認証: 有効なセッション Cookie（pd_session）が必要です。未ログインまたはセッションが無効な場合は 401 を返します。
- 認可: 承認操作は APPROVER（承認者）ロールのユーザーのみ実行可能です。EMPLOYEE（社員）ロールのユーザーが実行した場合は 403 を返します。

【CSRF 保護】
- 変更操作のため、CSRF トークンの検証が行われます。
- リクエストヘッダー `X-CSRF-Token` に、現在のセッションに紐付いた CSRF トークンを指定する必要があります。
- トークンが不正または欠落している場合、または Origin/Referer ヘッダーによる同一オリジンチェックに失敗した場合は 403 を返します。

【状態遷移】
- 対象の申請が「提出済み（SUBMITTED）」状態である必要があります。
- 下書き（DRAFT）、承認済み（APPROVED）、差し戻し済み（RETURNED）の申請に対しては 409 を返します。

【エラー処理】
- 401: ログインが必要です。
- 403: 承認者権限がない、または CSRF トークンが不正です。
- 404: 指定された ID の申請が見つかりません。
- 409: 申請の現在の状態では承認操作を実行できません。
- 500: サーバー内部エラー。

### 認証条件

Cookie `pd_session` と ヘッダー `X-CSRF-Token` を送信します。

### パラメーター

```json
[
  {
    "name": "id",
    "in": "path",
    "required": true,
    "description": "承認対象の購入申請の ID。英数字、ハイフン、アンダースコアで構成される文字列です。",
    "schema": {
      "type": "string",
      "pattern": "^[A-Za-z0-9_-]+$"
    }
  }
]
```

### 応答 200（application/json）

申請が正常に承認されました。承認後の申請情報が返されます。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| request | object | 必須 |  | — |
| request.id | string | 親がオブジェクトの場合に必須 | 申請の一意な識別子。 | — |
| request.ownerUsername | string | 親がオブジェクトの場合に必須 | 申請を作成したユーザー名。 | — |
| request.itemName | string | 親がオブジェクトの場合に必須 | 購入する品名。 | — |
| request.quantity | integer | 親がオブジェクトの場合に必須 | 購入数量。 | {"format": "int64"} |
| request.unitPriceYen | integer | 親がオブジェクトの場合に必須 | 単価（円）。 | {"format": "int64"} |
| request.totalYen | integer | 親がオブジェクトの場合に必須 | 合計金額（円）。 | {"format": "int64"} |
| request.reason | string | 親がオブジェクトの場合に必須 | 購入理由。 | — |
| request.state | string | 親がオブジェクトの場合に必須 | 申請の現在の状態。このレスポンスでは常に "APPROVED" です。 | {"enum": ["DRAFT", "SUBMITTED", "APPROVED", "RETURNED"]} |
| request.history | array | 親がオブジェクトの場合に必須 | 申請の履歴。時系列順に並べられます。 | — |
| request.history[].action | string | 親がオブジェクトの場合に必須 | 履歴のアクション種別。 | {"enum": ["CREATE", "UPDATE", "SUBMIT", "RETURN", "APPROVE"]} |
| request.history[].actorUsername | string | 親がオブジェクトの場合に必須 | アクションを実行したユーザー名。 | — |
| request.history[].at | string | 親がオブジェクトの場合に必須 | アクションの実行日時（UTC）。 | {"format": "date-time"} |
| request.history[].comment | string | 親がオブジェクトの場合に必須 | アクションに関するコメント。承認アクションでは通常空文字列です。 | — |

```json
{
  "request": {
    "id": "1234567890abcdef",
    "ownerUsername": "YOUR_USERNAME",
    "itemName": "ノートPC",
    "quantity": 1,
    "unitPriceYen": 150000,
    "totalYen": 150000,
    "reason": "業務用として購入希望",
    "state": "APPROVED",
    "history": [
      {
        "action": "CREATE",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T10:00:00Z",
        "comment": ""
      },
      {
        "action": "SUBMIT",
        "actorUsername": "YOUR_USERNAME",
        "at": "2023-10-27T11:00:00Z",
        "comment": ""
      },
      {
        "action": "APPROVE",
        "actorUsername": "APPROVER_USER",
        "at": "2023-10-27T12:00:00Z",
        "comment": ""
      }
    ]
  }
}
```

応答ヘッダー:

| 名前 | 説明 |
|---|---|
| Set-Cookie | セッション Cookie の更新や維持のために設定される場合があります（実装により変動）。 |

### 応答 401（application/json）

認証エラー。ログインが必要です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "ログインが必要です。"
}
```

### 応答 403（application/json）

認可エラーまたは CSRF 検証エラー。承認者権限がない、または CSRF トークンが不正です。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "承認者のみがこの操作を行えます。"
}
```

### 応答 404（application/json）

指定された ID の申請が見つかりません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "申請が見つかりません。（ID: 1234567890abcdef）"
}
```

### 応答 409（application/json）

状態競合エラー。申請の現在の状態では承認操作を実行できません。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "この操作は現在の状態（下書き）では行えません。"
}
```

### 応答 500（application/json）

サーバー内部エラー。

| フィールド | 型 | 必須性 | 説明 | 制約 |
|---|---|---|---|---|
| error | string | 必須 | エラーメッセージ。 | — |

```json
{
  "error": "サーバー内部エラーです。"
}
```
