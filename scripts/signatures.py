"""Verify detached signatures using only the existing public release key."""
import hashlib
from pathlib import Path
import subprocess

FINGERPRINT = '1B07CFB6CD172EF8B1E078A7BC3EFBDB5650D775'

def verify(directory, names, output):
    output.mkdir(parents=True)
    public = Path(__file__).resolve().parents[1] / 'verification/signing-public-key.asc'
    keyring = output / 'public-key.gpg'
    subprocess.run(['gpg', '--no-options', '--batch', '--dearmor', '--output', str(keyring), str(public)], check=True, capture_output=True)
    receipts = {}
    for name in names:
        status = subprocess.run(['gpgv', '--keyring', str(keyring.resolve()), '--status-fd', '1', str(directory / (name + '.asc')), str(directory / name)], check=True, capture_output=True, text=True)
        valid = [line.split()[2] for line in status.stdout.splitlines() if line.startswith('[GNUPG:] VALIDSIG ')]
        assert valid == [FINGERPRINT], 'Unexpected signing identity'
        (output / (name + '.verification.txt')).write_text(status.stdout + status.stderr)
        receipts[name] = {'fingerprint': FINGERPRINT, 'signature_sha256': hashlib.sha256((directory / (name + '.asc')).read_bytes()).hexdigest()}
    return receipts
