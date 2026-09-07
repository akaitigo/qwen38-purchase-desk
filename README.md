# 備品購入申請・承認アプリ（Purchase Desk）

Kotlin/JVM + SQLite で構築した備品購入の申請・承認システム。
JSON API と HTML 画面の両方を提供し、権限管理・CSRF 保護・状態遷移・二重承認防止・保存・復元を実装しています。

ソースからAPI利用者向けの文書を作る検証も進めています。現在は認証と申請作成の3操作について、[文書の生成形式と実アプリとの照合](docs/api-document-generation.md)を実装しています。Qwenによる新形式の生成は、まだ実測していません。

## 要件

- Java 17（OpenJDK 17）
- `curl` / `unzip`（`./gradlew` は初回実行時に Gradle 配布版をダウンロードして解凍するため）
- ネットワーク接続（初回実行時に Gradle 配布版と Maven の依存関係を取得するため）

## クイックスタート

### 1. テストの実行

```bash
./dev test
```

全テストが成功すれば「BUILD SUCCESSFUL」になります。

### 2. ビルド

```bash
./dev build
```

`build/libs/purchase-desk-1.0.0.jar` が生成されます（fat jar）。

### 3. ユーザーの作成（シード）

`users.json` を用意します（配列形式）：

```json
[
  {"username": "alice", "password": "alice-password", "role": "EMPLOYEE"},
  {"username": "boss",  "password": "boss-password",  "role": "APPROVER"}
]
```

実行：

```bash
./dev seed --db data/app.db --users-file users.json
```

出力例：

```
作成: 2件, 既存: 0件
```

再実行しても既存ユーザーは上書きされません（同一ロールならスキップ、ロールが違えばエラー）。
シードはユーザーの作成のみを行い、既存の申請データには一切触れません。

### 4. サーバーの起動

```bash
./dev serve --host 127.0.0.1 --port 8080 --db data/app.db
```

出力：

```
Purchase Desk listening on http://127.0.0.1:8080/ (db=data/app.db)
```

ブラウザで `http://127.0.0.1:8080/` を開くとログイン画面にリダイレクトされます。

停止するには `Ctrl+C`（SIGINT）または `SIGTERM` を送ります。

#### HTTPS（公開オリジンの設定）

アプリ自身は TLS を行わず、信頼できるローカルの逆プロキシ（TLS 終端）の背後でプレーン HTTP として動作します。
公開環境で使う場合は、`--public-origin` オプションまたは `PURCHASE_PUBLIC_ORIGIN` 環境変数で、
プロキシが外部から見せる https のオリジンを指定します：

```bash
./dev serve --host 127.0.0.1 --port 8080 --db data/app.db \
  --public-origin https://purchases.example.com
# または
PURCHASE_PUBLIC_ORIGIN=https://purchases.example.com ./dev serve --host 127.0.0.1 --port 8080 --db data/app.db
```

- 未指定（デフォルト）：ループバック HTTP 用の動作。セッション Cookie に `Secure` フラグは付与されません。
- 指定時：`Secure` フラグ付き Cookie を発行し、Origin ヘッダーの一致検証（scheme / host / port の一致）を強制します。
- `X-Forwarded-*` などの転送ヘッダーは信頼せず、指定した `--public-origin` のみ基準とします。

### 5. バックアップ

```bash
./dev backup --db data/app.db --output backup/app-$(date +%Y%m%d).db
```

出力は単一の自己完結 SQLite ファイルです。

### バックアップの復元

サーバーを停止してから復元します。復元先は**新しいディレクトリ**を使用してください。
稼働中の DB には WAL / SHM サイドカーファイル（`app.db-wal`、`app.db-shm`）が存在するため、
既存の `app.db` をそのまま上書きすると古い WAL 内容と混ざって破損するおそれがあります。
バックアップ出力は VACUUM 済みの自己完結ファイルなので、別ディレクトリに置けばそのまま起動できます。

```bash
# 1. サーバーを停止（Ctrl+C）
# 2. 復元先に新しいディレクトリを作り、同じファイル名でコピー
mkdir -p restored
cp backup/app-20260906.db restored/app.db
# 3. 復元先を --db で指定して起動
./dev serve --host 127.0.0.1 --port 8080 --db restored/app.db
```

## CLI コマンド一覧

| コマンド   | 説明                                             |
|-----------|--------------------------------------------------|
| `test`    | 全テストを実行（`gradlew test`）                  |
| `build`   | jar をビルド（`gradlew build -x test`）          |
| `seed`    | ユーザーを JSON から作成（`--db`, `--users-file`） |
| `serve`   | HTTP サーバー起動（`--host`, `--port`, `--db`）  |
| `backup`  | SQLite バックアップ（`--db`, `--output`）         |

## JSON API

### 認証

| メソッド | パス           | 説明                     |
|---------|----------------|--------------------------|
| GET     | `/api/session` | 現在のセッション情報    |
| POST    | `/api/login`   | ログイン（CSRF 必須）    |
| POST    | `/api/logout`  | ログアウト（CSRF 必須）  |

### 申請 CRUD

| メソッド | パス                        | 説明                    |
|---------|-----------------------------|-------------------------|
| GET     | `/api/requests`             | 一覧（権限に応じる）    |
| POST    | `/api/requests`             | 作成（社員のみ）        |
| GET     | `/api/requests/{id}`        | 詳細（権限に応じる）    |
| PATCH   | `/api/requests/{id}`        | 更新（所有者・DRAFT/RETURNED のみ） |
| POST    | `/api/requests/{id}/submit` | 提出（所有者）          |
| POST    | `/api/requests/{id}/return` | 差し戻し（承認者）      |
| POST    | `/api/requests/{id}/approve`| 承認（承認者）          |

### エラーコード

| ステータス | 意味                        |
|-----------|-----------------------------|
| 400       | 入力が不正                  |
| 401       | 未認証（ログインが必要）    |
| 403       | 権限不足 / CSRF トークン不正 |
| 404       | 申請が見つからない          |
| 405       | メソッド未対応              |
| 409       | 状態遷移の競合（二重承認等） |

### 申請オブジェクト

```json
{
  "id": "abc123def4567890",
  "ownerUsername": "alice",
  "itemName": "プリンタ用紙",
  "quantity": 5,
  "unitPriceYen": 1200,
  "totalYen": 6000,
  "reason": "消耗品",
  "state": "APPROVED",
  "history": [
    {"action": "CREATE",  "actorUsername": "alice", "at": "2024-01-01T00:00:00Z", "comment": ""},
    {"action": "SUBMIT",  "actorUsername": "alice", "at": "2024-01-01T00:01:00Z", "comment": ""},
    {"action": "APPROVE", "actorUsername": "boss",  "at": "2024-01-01T00:02:00Z", "comment": ""}
  ]
}
```

### 状態遷移

```
DRAFT → SUBMITTED → APPROVED（終端）
         ↓
      RETURNED →（編集・再提出）→ SUBMITTED
```

- 社員：DRAFT/RETURNED 状態のみ編集・提出可能
- 承認者：SUBMITTED 状態のみ承認・差し戻し可能
- 承認済みの申請は変更不可（409）

### CSRF 保護

- JSON API：`X-CSRF-Token` ヘッダー
- HTML フォーム：隠しフィールド `csrfToken`
- `GET /api/session` で現在のトークン取得
- ログイン時は匿名トークンを提出（ログイン後セッショントークンに切替）

## 入力バリデーション

| フィールド     | 制約                         |
|---------------|------------------------------|
| itemName      | 1–100 文字（前後空白除去）   |
| quantity      | 1–1000（整数のみ）           |
| unitPriceYen  | 0–1,000,000（整数のみ）      |
| reason        | 1–1000 文字                  |
| totalYen      | quantity × unitPriceYen ≤ 100,000,000（サーバーで計算） |

小数・ブール・オーバーフローはすべて 400 で拒否されます。

## テスト構成

| ファイル                          | 内容                                  |
|-----------------------------------|---------------------------------------|
| `ValidationTest`                  | 入力バリデーション（境界値・型）      |
| `HashingTest`                     | パスワードハッシュ・トークン生成      |
| `StateMachineTest`                | 状態遷移・二重承認・履歴の順序        |
| `ServicePermissionTest`           | 権限（社員/承認者）・404/403/409       |
| `HttpIntegrationTest`             | 実 HTTP サーバーでの全フロー          |
| `SeedTest`                        | シードの冪等性・申請データ保持         |
| `BackupRestoreTest`               | バックアップの完全性・WAL 独立性       |

## ディレクトリ構成

```
src/main/kotlin/com/example/purchasedesk/
├── Main.kt           # CLI エントリポイント
├── cli/Seed.kt       # ユーザーシード
├── data/Database.kt  # SQLite ストレージ層
├── domain/           # ドメインモデル・バリデーション・ハッシュ
├── http/             # HTTP サーバー・API・HTML 描画
└── service/Service.kt # ビジネスロジック（API と HTML の共有層）

src/test/kotlin/com/example/purchasedesk/
├── TestSupport.kt           # テスト用 SQLite テンポラリ DB
├── BackupRestoreTest.kt
├── cli/SeedTest.kt
├── data/StateMachineTest.kt
├── domain/HashingTest.kt
├── domain/ValidationTest.kt
├── http/HttpIntegrationTest.kt
└── service/ServicePermissionTest.kt
```

## セキュリティ

- パスワード：PBKDF2WithHmacSHA256（120,000 回イテレーション、ランダムソルト）
- セッション：`SecureRandom` による 43 文字 URL-safe トークン
- CSRF：セッション毎に独立したトークン、ヘッダーまたはフォームフィールドで検証
- HTML エスケープ：ユーザー提供値はすべて `esc()` でエスケープ
- 単一コネクション + ReentrantLock + `BEGIN IMMEDIATE` で状態遷移をアトミックに保証

### 検証範囲と利用上の注意

- 本プロジェクトの検証はすべて**ループバック（127.0.0.1）上での動作確認**です。
- セッション Cookie は HttpOnly / SameSite 付きです。
  `--public-origin` / `PURCHASE_PUBLIC_ORIGIN` を指定すると `Secure` フラグも付与されます（未指定のループバック HTTP では付与されません）。
  HTTPS 環境では信頼できる逆プロキシによる TLS 終端が必要です。
- 単一プロセス・単一コネクションの SQLite 設計であり、マルチインスタンスや負荷分散は未対応です。
- 上記を踏まえ、**本環境は本番運用を前提としたものではありません**。
