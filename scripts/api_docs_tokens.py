"""Optional CPU-only input measurement; downloads the pinned tokenizer, no weights."""
import argparse
from collections.abc import Mapping
import hashlib
import json
from pathlib import Path

from api_docs import OPERATIONS, make_payload, save, sources
from api_docs_context import digest, for_operation, load_memory, load_writing_context

MODEL = 'Qwen/Qwen3.8-27B-FP8'
REVISION = '017b9c7af6b5689d5dd426a76e0bc077eb5ca20a'


def count_ids(encoded):
    ids = encoded['input_ids'] if isinstance(encoded, Mapping) else encoded
    if not isinstance(ids, list) or not ids or any(type(value) is not int for value in ids):
        raise ValueError('Expected one unbatched list of token IDs')
    return len(ids)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    out = Path(args.out)
    if out.exists():
        parser.error('Preserve earlier measurements; choose a new output path')
    from transformers import AutoTokenizer, __version__
    tokenizer = AutoTokenizer.from_pretrained(MODEL, revision=REVISION, trust_remote_code=False)
    entries = sources(args.repo)
    context = load_writing_context(Path(args.repo) / 'config/api-docs-writing-context.json')
    memory_path = Path(args.repo) / 'config/api-docs-feedback-memory.json'
    memory = load_memory(memory_path, entries, OPERATIONS) if memory_path.exists() else None
    counts = {}
    for op_id in OPERATIONS:
        payload = make_payload(op_id, entries, context, for_operation(memory, op_id) if memory else [])
        request = payload['input']['openai_input']
        tokens = tokenizer.apply_chat_template(request['messages'], tokenize=True,
                                               add_generation_prompt=True, enable_thinking=False)
        counts[op_id] = {'input_tokens': count_ids(tokens), 'max_output_tokens': request['max_tokens'],
                         'json_bytes': len(json.dumps(payload, ensure_ascii=False).encode()),
                         'payload_sha256': hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()}
    result = {'model': MODEL, 'revision': REVISION, 'tokenizer_class': type(tokenizer).__name__,
              'writing_context_sha256': digest(context),
              'feedback_input_sha256': digest(memory),
              'transformers_version': __version__, 'counts': counts, 'gpu_jobs': 0,
              'note': 'ローカルトークナイザーでのチャット入力数。サーバー独自の追加分、レビュー・修正入力、費用は含まない。'}
    out.parent.mkdir(parents=True, exist_ok=True)
    save(out, result)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
