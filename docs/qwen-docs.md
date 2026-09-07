# Qwenによるソース説明文書の生成

Runpodの公式vLLM worker v2.26.0に、選択したソースを非同期ジョブとして渡します。
Qwen Codeによる編集ではなく、推論APIでの文書生成です。元のアプリの制作過程とは別の追加検証です。

`config/docs-source-files.txt` の4ファイルを対象に、日本語のAPI説明と根拠ファイル・識別子を生成します。
生成物はGitHub ActionsのArtifactsに7日間保存します。生成内容はレビュー前の草稿です。
形式と参照先の存在は検査しますが、説明の正確さ・網羅性は人がソースと照合する必要があります。

## 実行設定

- RunpodにQwen/Qwen3.8-27B-FP8を提供するqueue-based endpointを用意する。
- 推論APIで使うモデル名を `qwen3.8-27b-fp8` に設定する。
- GitHub Actions variable `RUNPOD_ENDPOINT_ID` にendpoint IDを設定する。
- 対象endpointだけを呼べるキーをActions secret `RUNPOD_API_KEY` に設定する。
- Actions variable `RUNPOD_DOCS_ENABLED` を `true` にするとCIが有効になる。

mainのアプリソース・選択ファイル一覧・実行スクリプト・workflowの変更で起動します。
導入検証の間だけ `feat/qwen-docs-ci` のpushも対象にします。
課金を伴うため、初期状態と検証後は `RUNPOD_DOCS_ENABLED=false` にします。
最小worker数0・最大1から始め、予算を監視し、試験後はendpoint削除とキー無効化を行ってください。
CIはGPU作成やチャージを行いません。

ジョブの待機は最大15分、モデル実行は最大5分、TTLは20分です。TTLは費用上限ではありません。
結果不明の送信を自動再送しません。キャンセル要求が失敗した場合は `metadata.json` に記録します。
CIを強制終了した場合はRunpod側のジョブ状態を確認してください。

## 課金なしの確認

```sh
python3 -m unittest discover -s tests -p test_serverless_docs.py -v
python3 scripts/serverless_docs.py --repo . --files config/docs-source-files.txt \
  --out /tmp/purchase-docs-check --dry-run
```

出力先は未作成のディレクトリを指定します。dry-runでは通信せず、対象コミットとファイルハッシュを記録します。
実行時は `--dry-run` を外し、上記の環境変数を設定します。

仕様： https://docs.runpod.io/serverless/endpoints/send-requests
