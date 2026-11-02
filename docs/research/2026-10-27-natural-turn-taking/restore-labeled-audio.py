"""Restore only the original public corpus bytes; reject any changed row or digest."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((HERE / 'labeled-corpus.json').read_text())
    for item in manifest['audio']:
        target = args.output / (item['id'] + '.flac')
        if target.exists():
            audio = target.read_bytes()
        else:
            query = urllib.parse.urlencode({'dataset': manifest['dataset'], 'config': manifest['config'],
                                           'split': manifest['split'], 'offset': item['row_index'], 'length': 1})
            with urllib.request.urlopen('https://datasets-server.huggingface.co/rows?' + query, timeout=40) as response:
                row = json.load(response)['rows'][0]['row']
            assert row['id'] == item['id'] and row['endpoint_bool'] == item['label']
            assert row['language'] == 'zho' and row['synthetic']
            with urllib.request.urlopen(row['audio'][0]['src'], timeout=40) as response:
                audio = response.read()
        assert len(audio) == item['bytes'], item['id']
        assert hashlib.sha256(audio).hexdigest() == item['sha256'], item['id']
        if not target.exists():
            target.write_bytes(audio)
        print(json.dumps({'id': item['id'], 'bytes': len(audio), 'sha256': item['sha256']}), flush=True)


if __name__ == '__main__':
    main()
