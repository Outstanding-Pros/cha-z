"""폰 PWA 용 자체 서명 인증서 (T11).

폰 브라우저는 보안 컨텍스트(HTTPS)에서만 카메라(getUserMedia)·화면 꺼짐 방지(Wake Lock)를 허용한다.
LAN 주소로 접속하는 개발 서버라 공인 인증서를 받을 수 없으므로 자체 서명 인증서를 만들고,
폰에서는 처음 한 번 인증서 경고를 허용한다. 키·인증서는 data/tls/ (gitignore) 에만 둔다.
"""
from __future__ import annotations

import datetime as dt
import ipaddress
import json
import logging
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

log = logging.getLogger("photolive.tls")

TLS_DIR = Path(__file__).resolve().parent.parent / "data" / "tls"


def ensure_cert(ips: list[str]) -> tuple[Path, Path]:
    """ips 가 모두 SAN 에 들어 있는 인증서를 돌려준다. 없거나 IP 가 바뀌었으면 새로 만든다."""
    TLS_DIR.mkdir(parents=True, exist_ok=True)
    cert_f, key_f, meta_f = TLS_DIR / "cert.pem", TLS_DIR / "key.pem", TLS_DIR / "meta.json"
    want = sorted(set(ips) | {"127.0.0.1"})
    if cert_f.exists() and key_f.exists() and meta_f.exists():
        try:
            if json.loads(meta_f.read_text())["ips"] == want:
                return cert_f, key_f
        except (ValueError, KeyError):
            pass

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Cha-z local server")])
    now = dt.datetime.now(dt.timezone.utc)
    san = [x509.DNSName("localhost")] + [x509.IPAddress(ipaddress.ip_address(i)) for i in want]
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=397))
        .add_extension(x509.SubjectAlternativeName(san), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(key, hashes.SHA256())
    )
    key_f.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    cert_f.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    meta_f.write_text(json.dumps({"ips": want}))
    log.info("자체 서명 인증서 생성 (IP %d개)", len(want))
    return cert_f, key_f
