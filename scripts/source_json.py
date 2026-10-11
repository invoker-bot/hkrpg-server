"""JSON source identities compatible with Gson JsonElement, preserving number tokens."""
import hashlib
import json


class OriginalInteger(int):
    def __new__(cls, token):
        value = super().__new__(cls, token)
        value.json_token = token
        return value


class OriginalFloat(float):
    def __new__(cls, token):
        value = super().__new__(cls, token)
        value.json_token = token
        return value


def loads(data):
    def reject_constant(token):
        raise ValueError('Non-JSON number: ' + token)
    return json.loads(data, parse_int=OriginalInteger, parse_float=OriginalFloat,
                      parse_constant=reject_constant)


def canonical(value):
    if isinstance(value, dict):
        # Java TreeSet<String> sorts UTF-16 code units.
        keys = sorted(value, key=lambda key: key.encode('utf-16-be', 'surrogatepass'))
        return '{' + ','.join(canonical(key) + ':' + canonical(value[key]) for key in keys) + '}'
    if isinstance(value, list):
        return '[' + ','.join(canonical(item) for item in value) + ']'
    if isinstance(value, (OriginalInteger, OriginalFloat)):
        return value.json_token
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).replace(
        '\u2028', '\\u2028').replace('\u2029', '\\u2029')


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()
