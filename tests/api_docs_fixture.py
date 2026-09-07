"""Hand-authored synthetic renderer fixture. NEVER sent to the model as input.

This exercises the pipeline, not Qwen's ability to infer an API contract.
"""
from copy import deepcopy
from api_docs import evidence_catalog


def obj(properties, required=None):
    return {'type': 'object', 'properties': properties,
            'required': list(properties) if required is None else required, 'additionalProperties': False}


def string(description='', **kwargs):
    return {'type': 'string', 'description': description, **kwargs}


def response(description, schema, example, cookie=None):
    value = {'description': description, 'content': {
        'application/json': {'schema': schema, 'example': example}}}
    if cookie:
        value['headers'] = {'Set-Cookie': {'description': cookie, 'schema': {'type': 'string'}}}
    return value


def documents(entries):
    user = obj({'username': string('ユーザー名'), 'role': string('役割', enum=['EMPLOYEE', 'APPROVER'])})
    session = obj({'user': {**user, 'type': ['object', 'null']}, 'csrfToken': string('Cookieに対応するCSRFトークン')})
    login_session = obj({'user': user, 'csrfToken': string('ログイン後の新しいCSRFトークン')})
    error = obj({'error': string('エラーの説明。固定のエラーコードとして扱わない')})
    new_request = obj({
        'itemName': string('前後の空白を除去した品名。空白のみは不可', minLength=1, maxLength=100),
        'quantity': {'type': 'integer', 'minimum': 1, 'maximum': 1000, 'description': '数量'},
        'unitPriceYen': {'type': 'integer', 'minimum': 0, 'maximum': 1000000, 'description': '単価（円）'},
        'reason': string('購入理由。空白のみは不可。前後の空白は保存される', minLength=1, maxLength=1000),
    })
    request_example = {'itemName': 'USBハブ', 'quantity': 2, 'unitPriceYen': 3000, 'reason': '接続端子を増やすため'}
    history = obj({'action': string(enum=['CREATE']), 'actorUsername': string(), 'at': string(), 'comment': string()})
    purchase = obj({**deepcopy(new_request['properties']), 'id': string(), 'ownerUsername': string(),
                    'totalYen': {'type': 'integer', 'description': 'サーバー計算の合計（円）'},
                    'state': string(enum=['DRAFT']), 'history': {'type': 'array', 'items': history}})
    created = {'request': {**request_example, 'id': 'EXAMPLE_ID', 'ownerUsername': 'YOUR_USERNAME',
                          'totalYen': 6000, 'state': 'DRAFT', 'history': [
                              {'action': 'CREATE', 'actorUsername': 'YOUR_USERNAME',
                               'at': '2026-09-07T00:00:00Z', 'comment': ''}]}}
    security = [{'anonymousCookie': [], 'csrfHeader': []}, {'sessionCookie': [], 'csrfHeader': []}]
    result = [
        {'operationId': 'getSession', 'operation': {
            'operationId': 'getSession', 'summary': 'セッション情報を取得する',
            'description': '未ログインならuserはnullです。初回はpd_anon CookieとCSRFトークンを発行します。'
                           '有効なCookieがあれば同じセッションを返します。ログイン済みならuserにユーザー名と役割を返します。',
            'parameters': [], 'security': [], 'responses': {
                '200': response('セッション情報。新規の匿名セッション時のみCookieを発行します。', session,
                                {'user': None, 'csrfToken': 'EXAMPLE_TOKEN'}, 'pd_anon。HttpOnly; SameSite=Lax; Path=/')},
        }},
        {'operationId': 'login', 'operation': {
            'operationId': 'login', 'summary': 'ログインする', 'parameters': [], 'security': security,
            'description': '先にGET /api/sessionで取得したCookieとCSRFトークンを送ります。'
                           'OriginがあればOrigin、なければRefererを検査し、不一致なら403です。両方なければこの検査を通過します。'
                           '本文のJSONを読み、CSRFを検査してから資格情報を照合します。CSRFが不正ならパスワードが違っていても403です。'
                           '成功時はpd_session Cookieと新しいcsrfTokenへ切り替えます。匿名Cookieは消去されます。'
                           '既にログイン済みの場合はそのCookieとCSRFを使います。CSRFはヘッダーを優先し、なければPOSTのJSON本文のcsrfTokenも参照します。'
                           'CookieにはMax-Age=86400が付きますが、サーバー側には作成日時による期限判定がありません。',
            'requestBody': {'required': True, 'content': {'application/json': {
                'schema': obj({'username': string('登録したユーザー名。前後の空白を除去'), 'password': string('登録したパスワード')}),
                'example': {'username': 'YOUR_USERNAME', 'password': 'YOUR_PASSWORD'}}}},
            'responses': {
                '200': response('ログイン成功。以後は新しいCookieとCSRFを使用します。', login_session,
                                {'user': {'username': 'YOUR_USERNAME', 'role': 'EMPLOYEE'}, 'csrfToken': 'EXAMPLE_TOKEN'},
                                'pd_session; Max-Age=86400; HttpOnly; SameSite=Lax; Path=/。匿名CookieはMax-Age=0で消去。'),
                '400': response('JSONとして解析できない本文。JSONを修正して再送してください。', error, {'error': 'JSONが不正です'}),
                '401': response('CSRF検査後、資格情報が一致しない場合。ユーザー名とパスワードを確認してください。', error, {'error': 'ログインに失敗しました'}),
                '403': response('Origin不一致、Cookieの欠落、またはCSRF不正。セッションを取得し直し、対応するCookieとCSRFを送ってください。', error, {'error': 'CSRFトークンが不正です。'}),
            },
        }},
        {'operationId': 'createRequest', 'operation': {
            'operationId': 'createRequest', 'summary': '備品購入申請を作成する', 'parameters': [],
            'security': [{'sessionCookie': [], 'csrfHeader': []}],
            'description': 'EMPLOYEEとしてログインして実行します。認証、Origin/CSRF、入力値、役割の順に検査します。'
                           'OriginがあればOrigin、なければRefererを検査し、両方なければ通過します。'
                           'JSONを使う基本形を示します。フォーム形式も受理します。数量・単価は数字のみの文字列でも受理します。'
                           'CSRFはヘッダー優先で、なければPOST本文のcsrfTokenを参照します。'
                           '数量×単価は100,000,000円以下にしてください。作成直後はDRAFTでCREATE履歴が1件付きます。'
                           'totalYen、id、ownerUsername、state、historyはサーバーが決めます。',
            'requestBody': {'required': True, 'content': {'application/json': {
                'schema': new_request, 'example': request_example}}},
            'responses': {
                '201': response('申請を作成しました。requestの中に申請を返します。', obj({'request': purchase}), created),
                '400': response('JSON、必須項目、型、文字数、値の範囲、または合計金額が不正です。入力を修正してください。', error, {'error': '入力が不正です'}),
                '401': response('未認証です。ログインしてください。入力内容に先立って認証を検査します。', error, {'error': '認証が必要です'}),
                '403': response('Origin/CSRFが不正、または有効な入力をAPPROVERが送った場合。CookieとCSRF、利用者の役割を確認してください。', error, {'error': '操作できません'}),
            },
        }},
    ]
    # Synthetic evidence IDs exercise binding only; they are not model citations.
    catalog = evidence_catalog(entries)
    ids = [key for key, e in catalog.items() if e['path'].endswith('/ApiServer.kt')]
    for doc in result:
        doc.update(evidence=ids[:1], unknowns=[])
    return result
