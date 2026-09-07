# Qwenによるソース説明文書の生成

Runpodの公式vLLM worker v2.26.0に、選択したソースを非同期ジョブとして渡します。
Qwen Codeによる編集ではなく、推論APIでの文書生成です。元のアプリの制作過程とは別の追加検証です。

`config/docs-source-files.txt` の4ファイルを対象に、11項目の日本語の説明と、根拠ファイル・識別子・ソースの抜粋を生成します。
生成物はGitHub ActionsのArtifactsに7日間保存します。生成内容はレビュー前の草稿です。
APIとHTMLのログアウトは別項目として扱います。説明と引用を並べた文書をローカルで組み立て、`claims.json` と `REVIEW.md` も保存します。
項目の不足・重複、引用と識別子の存在は検査しますが、説明の正確さ・網羅性は人がソースと照合する必要があります。

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

## 保存済み応答の再検査

```sh
python3 scripts/serverless_docs.py --repo . --files config/docs-source-files.txt \
  --response saved-response.json --out /tmp/purchase-docs-replay
```

このモードはRunpodへ接続せず、新形式の応答を検査します。以前の自由形式の応答は受け付けません。引用が存在するだけでは説明の正確さは証明できず、意味の確認は未実施として記録します。

## 検証状況

2026-09-07の初回Actions実行では、Runpodからの応答取得まで動作しましたが、識別子の出力形式が合わずCIは失敗しました。その後の直接呼出しでは形式検査に合格したものの、APIとHTMLの挙動の混同などがありました。
今回の改善は、説明ごとの引用と確認項目を要求するものです。オフラインの検証は実施していますが、この新形式でのQwen生成とActionsの成功はまだ確認していません。現在CIは無効で、実測用endpointと一時secretは削除済みです。

## 項目を絞った改善試験

```sh
python3 scripts/serverless_docs.py --repo . --files config/docs-source-files.txt \
  --topic APIログアウト --topic HTMLログアウト --dry-run --out /tmp/logout-plan
```

`--topic` は複数指定できます。省略時は11項目すべてが対象です。実行1回につき送信するジョブは1件で、失敗項目の自動再送はしません。対象外の項目まで確認済みとしないよう、文書とmetadataに範囲を記録します。再検査にも同じ`--topic`を指定してください。入力ソース4ファイルは引き続き全て渡すため、入力トークンの削減効果は主張しません。

出力はvLLMのJSON Schema形式で指定します。対象項目・パス・識別子・引用長を制約し、返却後にも引用の実在と項目の重複を検査します。サーバーがこの形式を受け付けない場合、自動的に旧形式へ戻したり再送したりしません。使用するRunpod workerとモデルでの互換性・生成品質は次の実測対象です。

次の実測は、まず上記2項目を1ジョブで生成し、APIだけが匿名セッションを発行する点を正しく説明できるか確認します。形式合格、ソースとの一致、日本語、待機・実行時間、利用トークンを別々に記録します。成功した場合に11項目へ広げます。

参考: [vLLMの構造化出力](https://docs.vllm.ai/en/stable/features/structured_outputs/)。スキーマへの適合は、説明内容の正しさを保証しません。
