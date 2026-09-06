# Purchase Desk: 備品購入申請・承認アプリ

Kotlin/JVM 17、SQLite、サーバー側で生成するHTMLで実装する。社員2名と承認者1名が使う仮想業務アプリ。
まだ実装のない仕様から作る。外部API・LLM呼出し・決済・実メール送信は不要。日本語の画面にする。

## 利用者と業務

社員は品名、数量、単価（円の整数）、購入理由を入力して下書きを保存し、申請する。
承認者は申請を承認、または理由を添えて差し戻す。差戻し後は社員が編集・再申請できる。
状態は DRAFT → SUBMITTED → APPROVED、または SUBMITTED → RETURNED → SUBMITTED。
APPROVEDは終端。社員は自分の申請だけ閲覧でき、承認者は全申請を閲覧できる。
承認者による申請作成・編集は不要。社員による承認・差戻しは禁止。
操作履歴には操作者、時刻（UTC ISO 8601）、操作種別、差戻し理由を保存する。
二重送信や同時承認で、承認が複数回記録されてはならない。

## 入出力の契約

外からの受入試験のため、以下のインターフェースは固定する。ビジネスロジック・HTML画面は自由に設計してよい。

### CLI（リポジトリ直下の実行可能な ./dev）

- `./dev test`: 全テストを実行。失敗なら非0。テストを省略して成功を返さない。
- `./dev build`: 依存取得・コンパイル・配布物作成を完了し0で終了。
- `./dev seed --db PATH --users-file PATH`: JSON配列の `{username,password,role}` を読み、テスト用アカウントを作る。roleはEMPLOYEEまたはAPPROVER。既存の申請を削除しない。パスワードはソルト付きの適切なハッシュで保存。
- `./dev serve --host 127.0.0.1 --port PORT --db PATH`: フォアグラウンドで起動。SIGTERMで終了できる。渡したDBだけを使う。
- `./dev backup --db PATH --output PATH`: 停止中のDBを、別のパスでそのまま起動できるSQLiteファイルへ退避する。

Java17を前提とし、Gradle用bootstrapは同梱済み。ビルドした後のserveは不要な依存取得をしない。
ユーザー名・パスワードの固定値をコードへ埋め込まない。READMEでアカウント準備、起動、使い方、テスト、バックアップ・復元、停止方法を説明する。

### JSON API

Content-Typeはapplication/json。IDは空でない文字列（英数字・ハイフン・アンダースコアのみ）。
ログイン状態はCookieで管理し、HttpOnlyとSameSiteを設定。HTTPSではSecureを使い、localhostのHTTPでも試験できること。
`GET /api/session`は未ログインでも200で `{user:null,csrfToken:"..."}` を返し、必要なCookieを設定する。
ログイン後は `{user:{username,role},csrfToken:"..."}`。有効なセッションに対応するCSRFトークンを返す。

すべての更新系API（ログイン・ログアウトも含む）は `X-CSRF-Token` ヘッダーを検証する。
HTMLフォームでは同等のhiddenフィールド等でCSRFを検証してよい。Originが付いた要求は同一originかも検証する。

| メソッド・パス | 入力JSON | 成功応答 |
|---|---|---|
| POST /api/login | username, password | 200。認証後のsessionとcsrfToken |
| POST /api/logout | {} | 200。旧セッションを無効化 |
| GET /api/requests | なし | 200 `{requests:[...]}`。閲覧可能な申請だけ |
| POST /api/requests | itemName, quantity, unitPriceYen, reason | 201 `{request:...}` |
| GET /api/requests/ID | なし | 200 `{request:...}` |
| PATCH /api/requests/ID | itemName, quantity, unitPriceYen, reason | 200 `{request:...}` |
| POST /api/requests/ID/submit | {} | 200 `{request:...}` |
| POST /api/requests/ID/return | comment | 200 `{request:...}` |
| POST /api/requests/ID/approve | {} | 200 `{request:...}` |

申請オブジェクトは `id, ownerUsername, itemName, quantity, unitPriceYen, totalYen, reason, state, history` を含む。
historyは時系列順で、各要素は `action, actorUsername, at, comment`。commentは不要時空文字可。
actionはCREATE、UPDATE、SUBMIT、RETURN、APPROVE。成功した操作だけを記録し、重複承認は記録しない。
合計金額はquantity×unitPriceYenをサーバーで計算する。クライアントのtotalYenやownerUsername、state指定で上書きできてはならない。

入力の制限：itemNameは前後空白除去後1〜100文字、reasonは1〜1000文字、quantityは整数1〜1000、unitPriceYenは整数0〜1,000,000、合計は100,000,000円以下。差戻しcommentは1〜1000文字。
境界を超える値、小数、整数演算のオーバーフロー、不正なJSONを受理しない。

エラーは `{error:"読める説明"}` を返す。
未認証401、認証情報不正401、権限不足403（他人のIDは404でも可）、入力不正400または422、状態競合409。
エラー応答時はDBの内容を変更しない。DB内部情報、スタックトレース、パスワードを応答へ含めない。

### ブラウザー画面

- `/login`、`/requests`、`/requests/new`、`/requests/ID` を用意する。未ログインの画面アクセスはログインへ案内する。
- フォームの入力欄に日本語ラベルを付け、ログイン、保存、申請、差戻し、再申請、承認、ログアウトが画面から操作できる。
- 入力エラーでは理由を表示し、パスワード以外の入力を保持する。
- 一覧に申請状態と未処理の申請を分かるように表示し、詳細から履歴を読める。
- ユーザー入力は適切にエスケープし、HTMLやJavaScriptとして実行しない。
- HTML画面とAPIで同じ権限・状態遷移・入力検証を使う。

## 完成条件

社員Aが下書き作成→申請→承認者による差戻し→社員Aが修正→再申請→承認まで操作できる。
他の社員はその申請にアクセスできない。再起動後も保存済みデータが残り、バックアップから復元できる。
テスト、README、起動可能な配布物を揃える。未実装を成功したことにしない。
