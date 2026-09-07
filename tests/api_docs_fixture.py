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
    result.extend(remaining_documents(result, error, session))
    # Synthetic evidence IDs exercise binding only; they are not model citations.
    catalog = evidence_catalog(entries)
    ids = [key for key, e in catalog.items() if e['path'].endswith('/ApiServer.kt')]
    for doc in result:
        doc.update(evidence=ids[:1], unknowns=[])
    return result


def remaining_documents(initial, error, session):
    purchase = deepcopy(initial[2]['operation']['responses']['201']['content']['application/json']['schema']['properties']['request'])
    purchase['properties']['state']['enum'] = ['DRAFT', 'SUBMITTED', 'RETURNED', 'APPROVED']
    purchase['properties']['history']['items']['properties']['action']['enum'] = ['CREATE', 'UPDATE', 'SUBMIT', 'RETURN', 'APPROVE']
    sample = deepcopy(initial[2]['operation']['responses']['201']['content']['application/json']['example'])
    wrapper = obj({'request': purchase})
    path_id = {'in': 'path', 'name': 'id', 'required': True, 'schema': string(), 'description': '作成時の応答から取得した申請ID'}
    mutation_security = [{'sessionCookie': [], 'csrfHeader': []}]
    defs = [
        ('logout', 'ログアウトする',
         'Origin/Refererを検査し、不一致なら403です。両方がなければ通過します。'
         'ログイン済みならCookieとCSRFを確認してサーバー側のセッションを削除します。'
         '未ログインなら匿名CookieとCSRFを照合します。成功時は認証Cookieを消去し、匿名Cookieと新しいCSRFを返します。'
         'CSRFはヘッダー優先で、POST本文による補完もあります。', [403]),
        ('listRequests', '申請一覧を取得する',
         'ログインが必要です。社員には自分の申請だけ、承認者には全員の申請を返します。ページング用パラメーターはありません。', [401]),
        ('getRequest', '申請を取得する',
         'ログインが必要です。社員は自分の申請だけ、承認者は全員の申請を参照できます。存在しないIDや社員が他人の申請を指定した場合は404です。', [401, 404]),
        ('updateRequest', '申請を編集する',
         '社員が自分のDRAFTまたはRETURNEDの申請を編集できます。4項目を全て送信します。PATCHでも部分更新には対応していません。'
         '認証、Origin/CSRF、本文検証、社員の役割、存在と閲覧権限、状態の順に検査します。'
         '他の社員の申請は404、承認者は403、SUBMITTEDまたはAPPROVEDは409です。'
         'CSRFはヘッダーで送信します。PATCH本文からのCSRF補完はありません。数量と単価の積は1億円以下です。', [400, 401, 403, 404, 409]),
        ('submitRequest', '申請を提出・再提出する',
         '社員が自分のDRAFTまたはRETURNEDをSUBMITTEDにします。認証、Origin/CSRF、社員の役割、存在、所有者、状態の順に検査します。'
         '他の社員の申請は403、存在しない申請は404、既にSUBMITTEDまたはAPPROVEDなら409です。本文は不要です。', [401, 403, 404, 409]),
        ('returnRequest', '申請を差し戻す',
         '承認者がSUBMITTEDの申請をRETURNEDにします。認証、Origin/CSRF、コメント、承認者の役割、存在、状態の順に検査します。'
         'commentは前後の空白を除いて1〜1000文字です。社員は403、存在しない申請は404、SUBMITTED以外は409です。', [400, 401, 403, 404, 409]),
        ('approveRequest', '申請を承認する',
         '承認者がSUBMITTEDの申請をAPPROVEDにします。認証、Origin/CSRF、承認者の役割、存在、状態の順に検査します。'
         '社員は403、存在しない申請は404、SUBMITTED以外や二重承認は409です。本文は不要です。', [401, 403, 404, 409]),
    ]
    result = []
    for op_id, summary, description, errors in defs:
        op = {'operationId': op_id, 'summary': summary, 'description': description,
              'parameters': [] if op_id in ('logout', 'listRequests') else [deepcopy(path_id)],
              'security': [{'sessionCookie': []}] if op_id in ('listRequests', 'getRequest') else deepcopy(mutation_security),
              'responses': {'200': response('操作の結果を返します。', deepcopy(wrapper), deepcopy(sample))}}
        for status in errors:
            op['responses'][str(status)] = response({400: '入力を修正してください。', 401: 'ログインしてください。',
                403: 'Cookie・CSRFと役割・所有者を確認してください。', 404: 'IDと閲覧範囲を確認してください。',
                409: '現在の状態を取得し直してください。同じ操作をそのまま繰り返さないでください。'}[status],
                error, {'error': '操作を受け付けられません'})
        if op_id == 'logout':
            op['security'] = deepcopy(initial[1]['operation']['security'])
            op['responses']['200'] = response('セッションを終了し、新しい匿名セッションを返します。', deepcopy(session),
                {'user': None, 'csrfToken': 'EXAMPLE_TOKEN'}, 'pd_sessionをMax-Age=0で消去し、pd_anonを発行。HttpOnly; SameSite=Lax; Path=/')
        elif op_id == 'listRequests':
            op['responses']['200'] = response('権限内の申請をrequests配列で返します。',
                obj({'requests': {'type': 'array', 'items': purchase}}), {'requests': [deepcopy(sample['request'])]})
        elif op_id == 'updateRequest':
            op['requestBody'] = deepcopy(initial[2]['operation']['requestBody'])
            op['requestBody']['content']['application/json']['example']['reason'] = '差戻し内容を踏まえて数量を確認しました'
        elif op_id == 'returnRequest':
            op['requestBody'] = {'required': True, 'content': {'application/json': {
                'schema': obj({'comment': string('差戻し理由。前後の空白を除去', minLength=1, maxLength=1000)}),
                'example': {'comment': '数量を確認してください'}}}}
        result.append({'operationId': op_id, 'operation': op})
    return result
