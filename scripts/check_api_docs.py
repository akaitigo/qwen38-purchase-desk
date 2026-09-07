"""Compare generated JSON examples and response schemas with an owned local app.

No arbitrary target URL or generated executable is accepted. Each run seeds a
disposable database, starts its own loopback server, then removes both on exit.
"""
import argparse
from contextlib import contextmanager
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from jsonschema import Draft202012Validator
from openapi_spec_validator import validate as validate_openapi
import yaml

from api_docs import OPERATIONS, reject_references, save


@contextmanager
def local_app(repo):
    repo = Path(repo).resolve()
    jar = repo / 'build/libs/purchase-desk-1.0.0.jar'
    if not jar.is_file():
        raise ValueError('Build the app with ./dev build first')
    env = dict(os.environ)
    env.pop('PURCHASE_PUBLIC_ORIGIN', None)
    password = secrets.token_urlsafe(24)
    with tempfile.TemporaryDirectory(prefix='api-docs-') as root:
        root = Path(root)
        users = root / 'users.json'
        save(users, [{'username': 'docs_employee', 'password': password, 'role': 'EMPLOYEE'},
                     {'username': 'docs_approver', 'password': password, 'role': 'APPROVER'}])
        users.chmod(0o600)
        base = ['java', '-jar', str(jar)]
        subprocess.run(base + ['seed', '--db', str(root / 'app.db'), '--users-file', str(users)],
                       check=True, timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)
        with (root / 'server.log').open('w') as log:
            process = subprocess.Popen(base + ['serve', '--host', '127.0.0.1', '--port', '0', '--db', str(root / 'app.db')],
                                       stdout=subprocess.PIPE, stderr=log, env=env)
            try:
                deadline = time.monotonic() + 30
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    while time.monotonic() < deadline:
                        if not selector.select(timeout=min(1, max(0, deadline - time.monotonic()))):
                            continue
                        line = process.stdout.readline().decode()
                        match = re.search(r'listening on http://127\.0\.0\.1:(\d+)/', line)
                        if match:
                            yield 'http://127.0.0.1:' + match.group(1), password
                            return
                        if process.poll() is not None:
                            raise RuntimeError('Local app exited before readiness')
                raise TimeoutError('Local app readiness deadline exceeded')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                process.stdout.close()


class Client:
    def __init__(self, url):
        self.url = url
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies))

    def call(self, op_id, body=None, csrf=None, origin=None):
        method, path = OPERATIONS[op_id]
        headers = {'Content-Type': 'application/json'}
        if csrf is not None:
            headers['X-CSRF-Token'] = csrf
        if origin is not None:
            headers['Origin'] = origin
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.url + path, data=data, headers=headers, method=method.upper())
        try:
            response = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            if response.headers.get_content_type() != 'application/json':
                raise AssertionError('API response is not JSON')
            return response.status, response.headers, json.loads(response.read(1000000))


def operation(spec, op_id):
    method, path = OPERATIONS[op_id]
    return spec['paths'][path][method]


class ContractMismatch(AssertionError):
    """Sanitized feedback suitable for a later model repair request."""
    def __init__(self, case, op_id, reason, field=None):
        self.feedback = {'case': case, 'operationId': op_id, 'reason': reason, 'field': field or []}
        super().__init__(case + ': ' + reason)


def check_response(case, op_id, schema, body):
    errors = list(Draft202012Validator(schema).iter_errors(body))
    if errors:
        raise ContractMismatch(case, op_id, 'response_schema_mismatch', list(errors[0].absolute_path))

    def covered(value, definition, path):
        if isinstance(value, dict):
            for name, item in value.items():
                if name not in definition.get('properties', {}):
                    raise ContractMismatch(case, op_id, 'response_field_not_documented', path + [name])
                covered(item, definition['properties'][name], path + [name])
        elif isinstance(value, list):
            for index, item in enumerate(value):
                covered(item, definition.get('items', {}), path + [index])
    covered(body, schema, [])


def run_checks(spec, repo):
    reject_references(spec)
    validate_openapi(spec)
    expected_security = {
        'getSession': set(),
        'login': {frozenset(('anonymousCookie', 'csrfHeader')), frozenset(('sessionCookie', 'csrfHeader'))},
        'createRequest': {frozenset(('sessionCookie', 'csrfHeader'))},
    }
    for op_id, expected in expected_security.items():
        declared = {frozenset(s) for s in operation(spec, op_id)['security']}
        if declared != expected:
            raise ContractMismatch('authentication_contract', op_id, 'security_requirements_mismatch', ['security'])
    rows = []

    def expect(client, case, op_id, status, **kwargs):
        actual, headers, body = client.call(op_id, **kwargs)
        if actual != status:
            raise ContractMismatch(case, op_id, 'unexpected_http_status')
        response = operation(spec, op_id)['responses'].get(str(actual))
        if response is None:
            raise ContractMismatch(case, op_id, 'observed status is missing from document')
        schema = response['content']['application/json']['schema']
        check_response(case, op_id, schema, body)
        if headers.get_all('Set-Cookie') and not any(h.lower() == 'set-cookie' for h in response.get('headers', {})):
            raise ContractMismatch(case, op_id, 'set_cookie_not_documented', ['responses', str(actual), 'headers'])
        for name, header in response.get('headers', {}).items():
            # Set-Cookie is conditional on session creation; assert it at the
            # initial-session and successful-login checkpoints below.
            values = headers.get_all(name, [])
            for value in values:
                Draft202012Validator(header['schema']).validate(value)
        rows.append({'case': case, 'operationId': op_id, 'status': actual, 'response_schema_matched': True})
        return headers, body

    with local_app(repo) as (url, password):
        anonymous = Client(url)
        headers, session = expect(anonymous, 'anonymous_session', 'getSession', 200)
        assert session['user'] is None and session['csrfToken']
        assert 'pd_anon' in {c.name for c in anonymous.cookies}
        assert headers.get_all('Set-Cookie')
        csrf = session['csrfToken']
        _, repeated = expect(anonymous, 'reuse_anonymous_session', 'getSession', 200)
        assert repeated['csrfToken'] == csrf
        login = dict(operation(spec, 'login')['requestBody']['content']['application/json']['example'])
        login.update(username='docs_employee', password=password)
        create = operation(spec, 'createRequest')['requestBody']['content']['application/json']['example']
        for op_id, body in [('login', login), ('createRequest', create)]:
            Draft202012Validator(operation(spec, op_id)['requestBody']['content']['application/json']['schema']).validate(body)
        expect(anonymous, 'login_without_csrf', 'login', 403, body=login)
        expect(anonymous, 'login_invalid_json_before_csrf', 'login', 400, body='not an object')
        expect(anonymous, 'login_origin_before_json', 'login', 403, body='not an object', origin='https://invalid.example')
        expect(anonymous, 'login_wrong_password', 'login', 401, body={**login, 'password': 'deliberately-wrong'}, csrf=csrf)
        expect(anonymous, 'login_wrong_password_and_csrf', 'login', 403, body={**login, 'password': 'deliberately-wrong'}, csrf='invalid')
        expect(anonymous, 'login_cross_origin', 'login', 403, body=login, csrf=csrf, origin='https://invalid.example')
        expect(Client(url), 'create_unauthenticated_before_validation', 'createRequest', 401, body={})
        headers, logged_in = expect(anonymous, 'login_success', 'login', 200, body=login, csrf=csrf)
        assert logged_in['user'] == {'username': 'docs_employee', 'role': 'EMPLOYEE'}
        assert logged_in['csrfToken'] != csrf
        names = {c.name for c in anonymous.cookies}
        assert 'pd_session' in names and 'pd_anon' not in names
        assert any('Max-Age=86400' in value for value in headers.get_all('Set-Cookie', []))
        csrf = logged_in['csrfToken']
        _, current = expect(anonymous, 'authenticated_session', 'getSession', 200)
        assert current == logged_in
        expect(anonymous, 'create_without_csrf', 'createRequest', 403, body=create)
        expect(anonymous, 'create_cross_origin', 'createRequest', 403, body=create, csrf=csrf, origin='https://invalid.example')
        expect(anonymous, 'create_missing_fields', 'createRequest', 400, body={}, csrf=csrf)
        expect(anonymous, 'create_total_limit', 'createRequest', 400, body={**create, 'quantity': 1000, 'unitPriceYen': 1000000}, csrf=csrf)
        _, created = expect(anonymous, 'create_example', 'createRequest', 201, body=create, csrf=csrf)
        item = created['request']
        assert item['ownerUsername'] == 'docs_employee' and item['id']
        assert item['state'] == 'DRAFT' and len(item['history']) == 1
        assert item['history'][0]['action'] == 'CREATE'
        assert item['totalYen'] == create['quantity'] * create['unitPriceYen']
        assert item['itemName'] == create['itemName'].strip() and item['reason'] == create['reason']
        approver = Client(url)
        _, session = expect(approver, 'approver_session', 'getSession', 200)
        _, session = expect(approver, 'approver_login', 'login', 200,
                            body={**login, 'username': 'docs_approver'}, csrf=session['csrfToken'])
        expect(approver, 'approver_cannot_create', 'createRequest', 403, body=create, csrf=session['csrfToken'])
        expect(approver, 'validation_before_role', 'createRequest', 400, body={}, csrf=session['csrfToken'])
    return {'status': 'passed', 'scope': list(OPERATIONS), 'checks': rows,
            'human_review_required': True, 'publication_authorized': False,
            'limitations': ['文言・説明の意味、全入力境界、フォーム、期限、TLS、残り7操作は未検証。',
                            '合成データでの合格はモデルによる生成成功を示さない。']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.')
    parser.add_argument('--spec', required=True)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    try:
        spec = yaml.safe_load(Path(args.spec).read_text())
        result = run_checks(spec, args.repo)
    except Exception as error:
        # No live Cookie/password/response dumps in distributable artifacts.
        save(args.out, {'status': 'failed', 'error_type': type(error).__name__,
                        'feedback': error.feedback if isinstance(error, ContractMismatch) else None,
                        'publication_authorized': False})
        raise SystemExit('API comparison failed: ' + type(error).__name__)
    save(args.out, result)
    print('Passed ' + str(len(result['checks'])) + ' local HTTP checks')


if __name__ == '__main__':
    main()
