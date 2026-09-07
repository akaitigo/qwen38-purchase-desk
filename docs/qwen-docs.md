# Qwenによるソース説明文書の生成と修正

選択した公開アプリのソースをRunpodのServerless Jobsへ送り、Qwen3.8-27B-FP8で説明文書を生成します。GitHub Actionsで生成、別リクエストによるレビュー、指摘された項目の修正、組み立てた文書全体の再レビュー、成果物の保存まで実行します。

生成とレビューには同じモデルを使います。自動レビューに指摘がなくても、人の確認前の草稿です。文書を自動でコミット・公開したり、モデルの出力をコードとして実行したりしません。

## CIの流れ

1. GPUを使わないテストと合成応答による動作確認を実行します。
2. 有効化されている場合だけ、ソースから11項目の草稿を生成します。
3. 新しいリクエストでソースと草稿を照合させます。項目ごとに「支持できる」「修正が必要」「判断できない」を返させ、形式・項目の網羅・根拠IDをローカルで検査します。
4. 修正が必要な項目だけを、具体的な指摘とともに再生成します。それ以外の項目は保持し、組み立てた文書全体を再レビューします。
5. 未解決でも各段階の回答・指摘・実行記録・最後の草稿をArtifactsへ保存します。

既定は修正1回、最大4ジョブ、全体の待機上限15分です。モデル1ジョブの実行上限は5分です。修正後のチェックに必要なジョブ枠を確保できない場合は、その修正を開始しません。通信結果が不明な送信を自動で再送することもありません。

これらはドル建ての費用上限ではありません。GPU単価・起動時間・アイドル時間を含めた予算管理は別途必要です。実行中のキャンセル要求が失敗した場合は各段階のmetadataに記録します。CIが強制終了した場合はRunpod側のジョブとエンドポイントを確認してください。

## 状態の読み方

|状態|意味|
|---|---|
|automated_review_clear_human_pending|自動レビューの指摘なし。人の確認は未実施。|
|needs_review|指摘・不明点が残る、または修正上限に到達。|
|budget_exhausted_needs_review|パイプラインのジョブ数・時間制限で停止。|
|failed_needs_review|モデルや通信のエラー、不正な回答、途中で切れた回答などで停止。|
|preparation_failed_needs_review|ソース選択や接続設定の準備に失敗。ジョブ未投入。|

指摘なしの場合だけCLIの終了コードは0、それ以外は2です。終了コード0でも正確性の認定や公開承認を意味しません。未確認事項を削除して見かけ上の合格にする処理はありません。修正対象に落とし込めない不明点は人の確認へ回します。

## 設定

Runpodのqueue-based endpointで公式FP8版を提供し、推論API上の名前を `qwen3.8-27b-fp8` にします。既存実測は公式vLLM worker v2.26.0を使用しました。

- Actions variable `RUNPOD_ENDPOINT_ID`: 対象エンドポイント。
- Actions secret `RUNPOD_API_KEY`: 対象エンドポイントだけを呼べるキー。
- Actions variable `RUNPOD_DOCS_ENABLED=true`: 課金を伴うジョブを有効化。

`main` と導入検証用の `feat/qwen-docs-ci` への対象ファイルのpushで起動します。GPUを使わない検証は常時実行し、実測は初期状態・検証後とも無効です。CIはGPU作成やチャージを行いません。試験終了時はエンドポイント削除・一時キー無効化も行ってください。

実測のコマンドは次のとおりです。出力先には未作成のディレクトリを指定します。

```sh
python3 scripts/docs_pipeline.py --repo . --files config/docs-source-files.txt \
  --max-repairs 1 --max-jobs 4 --total-seconds 900 --out generated/qwen-docs
```

`--topic` を複数指定して対象項目を限定できます。CLIでは修正最大2回・ジョブ最大6件まで指定できますが、変更は費用増を伴うため事前に予算を確認してください。

## 課金なしの検証

```sh
python3 -m unittest discover -s tests -p test_serverless_docs.py -v
python3 -m unittest discover -s tests -p test_docs_pipeline.py -v
python3 tests/test_docs_pipeline.py --smoke-out generated/docs-pipeline-offline
```

合成応答で「修正後に指摘がなくなる」「未解決のまま上限で止まる」場合を実行し、実際のパイプラインと同じ経路で成果物を作ります。これはCI配線と停止条件の検証であり、Qwenの生成品質やRunpodへの接続を測定したものではありません。

## 成果物

実測の `qwen-docs-...` と、合成応答の `offline-docs-pipeline-...` を別のArtifactsとして7日間保存します。

- `summary.json`: 対象コミット・ソースハッシュ、ジョブ数、上限、最終状態、最後のレビュー。
- `01-generate/` など: 各段階の応答とジョブID・時間・入力ハッシュ。失敗した段階も残します。
- `initial.json`・`candidate-N.json`: 初回草稿と各修正版。
- `review-N.json`・`repair-input-N.json`: 指摘と修正依頼。
- `document.json`・`DOCUMENT.md`・`evidence.json`: 最後に形式検査を通った草稿と根拠。
- `REVIEW.md`: 人の確認が必要であることの表示。

生成前の失敗では文書ファイルは作れません。修正の応答が不正なら直前の有効な草稿を保持します。

## 根拠と入力範囲

`config/docs-source-files.txt` の4ファイルを対象に、11項目の説明を生成します。ソースを8行ずつ、2行重ねた候補へ分割し、各IDをファイルパス・全文ハッシュ・行範囲に結び付けます。引用文や行番号をモデルに書き写させず、返されたIDから原文を復元します。

これは構文解析ではなく行単位の分割です。関数の途中で切れることがあり、引用が存在しても説明を裏付けるとは限りません。現在のCIは4ファイル全体を入力に使い、コード範囲の自動絞り込みは実装していません。重複する行もあるため、入力トークン削減の仕組みではありません。

初回生成とレビューは思考モードを無効にし、出力上限を4096トークンにしています。修正では思考モードと `reasoning_effort=low` を使い、思考を含む出力上限を6144トークンにしています。途中で切れた結果は採用しません。

レビュー理由は1項目180文字以内に制限し、コード断片の引用を避けます。説明本文・条件・例外のすべてを検査させます。形式の制約で意味の正確さを保証できるわけではありません。

## 個別の再検査・修正

既存の `scripts/serverless_docs.py` は1ジョブの生成や保存済み回答の再検査に使えます。

```sh
python3 scripts/serverless_docs.py --repo . --files config/docs-source-files.txt \
  --response saved-response.json --out generated/replay
```

`--repair-review review.json` は `sources`（path・sha256の配列）、`claims`（対象の元説明）、`findings`（項目名と具体的な指摘）を受け取ります。同じ対象を `--topic` で指定してください。ソースの変更や対象項目の不一致は送信前に拒否します。`--thinking` で低い思考量を指定できます。各回は別の出力先へ保存してください。

## 検証の到達点

2026-09-07、単発生成を行う旧CIはRunpod呼出しから11項目のArtifacts保存まで成功しました。[実行記録](https://github.com/akaitigo/qwen38-purchase-desk/actions/runs/34085940336/attempts/2)。ただし生成文書にはAPIとHTMLの混同などがありました。

続く手動レビューを伴う8回の試行では、入力範囲を絞る、思考量を変える、正しい項目を保持するといった方法で主要な誤りを修正できました。今回追加した自動レビュー・修正パイプラインのFP8での品質は、その結果とは分けて評価する必要があります。合成応答のテストを、モデルによる自動修正の成功実績には数えません。

最初の自動ループ実測では、初回生成は完了しましたが、レビューが4096トークンをすべて思考に使い、評価本文を返さず停止しました。CIは失敗として元の草稿と応答を保存しました。[実行記録](https://github.com/akaitigo/qwen38-purchase-desk/actions/runs/34091190387/attempts/2)。この結果を受けてレビューの思考モードを無効化し、修正側の出力枠を拡大しています。この調整だけで文書の正確性が改善したとは判断できません。

参考: [RunpodのジョブAPI](https://docs.runpod.io/serverless/endpoints/send-requests)、[vLLMの構造化出力](https://docs.vllm.ai/en/stable/features/structured_outputs/)。
