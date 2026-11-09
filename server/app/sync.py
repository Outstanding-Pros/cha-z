"""차량 ↔ 폰 동기화 (SPEC '데이터 모델과 API' 의 Supabase 를 로컬로 대신한다 — T10).

| SPEC (Supabase)            | 여기                                   |
|----------------------------|----------------------------------------|
| devices/pairings/results   | SQLite data/sync.db                    |
| pairing-create / -claim    | POST /sync/pairing-create, /sync/pairing-claim |
| deliver                    | POST /sync/results (완성본 업로드 → result.delivered) |
| share-link (7일)           | POST /sync/share → /s/{token}          |
| Realtime device:{id}       | SSE GET /sync/events?deviceId=         |
| Storage signed URL         | HMAC 서명 + 만료 URL /sync/file/{id}   |

차량·폰은 서로 직접 통신하지 않고 이 서버를 거친다. 폰은 같은 네트워크에서 PC 의 LAN 주소로 접속한다.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import logging
import os
import secrets
import socket
import sqlite3
import time
import uuid
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse
from pydantic import BaseModel, Field

log = logging.getLogger("photolive.sync")

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "sync"
FILES = DATA / "results"
FILES.mkdir(parents=True, exist_ok=True)
DB_PATH = DATA / "sync.db"
PORT = int(os.getenv("PORT", "8787"))

PAIRING_TTL = 10 * 60          # 페어링 토큰 10분
FILE_URL_TTL = 24 * 3600       # 서명 URL 하루
SHARE_TTL = 7 * 24 * 3600      # 공유 링크 7일

router = APIRouter()


# ---------- 저장소 ----------

def _db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    return con


def init_db() -> None:
    with _db() as con:
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, kind TEXT, created_at REAL);
            CREATE TABLE IF NOT EXISTS pairings (
                id TEXT PRIMARY KEY, car_device_id TEXT, phone_device_id TEXT, token TEXT UNIQUE,
                status TEXT, expires_at REAL, created_at REAL);
            CREATE TABLE IF NOT EXISTS results (
                id TEXT PRIMARY KEY, car_device_id TEXT, session_ids TEXT, layout TEXT, kind TEXT,
                path TEXT, filters TEXT, source_kinds TEXT, created_on TEXT, created_at REAL);
            CREATE TABLE IF NOT EXISTS shares (token TEXT PRIMARY KEY, result_id TEXT, expires_at REAL);
            """
        )


init_db()


def _secret() -> bytes:
    env = os.getenv("SYNC_SECRET")
    if env:
        return env.encode()
    f = DATA / "secret"
    if not f.exists():
        f.write_text(secrets.token_hex(32))
    return f.read_text().strip().encode()


def _sign(result_id: str, exp: int) -> str:
    return hmac.new(_secret(), f"{result_id}:{exp}".encode(), hashlib.sha256).hexdigest()[:32]


def public_base() -> str:
    """폰이 접속할 주소. PUBLIC_BASE_URL 이 없으면 이 PC 의 LAN IP."""
    env = os.getenv("PUBLIC_BASE_URL")
    if env:
        return env.rstrip("/")
    ip = "127.0.0.1"
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))  # 실제로 보내지 않음, 경로만 계산
        ip = s.getsockname()[0]
        s.close()
    except OSError:
        pass
    return f"http://{ip}:{PORT}"


def _file_url(result_id: str) -> str:
    exp = int(time.time()) + FILE_URL_TTL
    return f"/sync/file/{result_id}?exp={exp}&sig={_sign(result_id, exp)}"


def _ensure_device(con: sqlite3.Connection, device_id: str, kind: str) -> None:
    con.execute("INSERT OR IGNORE INTO devices(id, kind, created_at) VALUES (?,?,?)", (device_id, kind, time.time()))


def _paired_phones(con: sqlite3.Connection, car_id: str) -> list[str]:
    rows = con.execute(
        "SELECT DISTINCT phone_device_id FROM pairings WHERE car_device_id=? AND status='claimed'", (car_id,)
    ).fetchall()
    return [r["phone_device_id"] for r in rows]


def _car_of(con: sqlite3.Connection, phone_id: str) -> str | None:
    r = con.execute(
        "SELECT car_device_id FROM pairings WHERE phone_device_id=? AND status='claimed' ORDER BY created_at DESC LIMIT 1",
        (phone_id,),
    ).fetchone()
    return r["car_device_id"] if r else None


def _result_json(row: sqlite3.Row) -> dict:
    return {
        "id": row["id"],
        "sessionIds": json.loads(row["session_ids"]),
        "layout": row["layout"],
        "kind": row["kind"],
        "filters": json.loads(row["filters"] or "[]"),
        "sourceKinds": json.loads(row["source_kinds"] or "[]"),
        "createdOn": row["created_on"],
        "createdAt": row["created_at"],
        "url": _file_url(row["id"]),
    }


# ---------- 실시간 이벤트 (Realtime 채널 device:{id} 대체) ----------

_subscribers: dict[str, set[asyncio.Queue]] = {}


async def publish(device_id: str, event: str, data: dict) -> None:
    for q in list(_subscribers.get(device_id, ())):
        await q.put((event, data))


@router.get("/sync/events")
async def events(deviceId: str, request: Request):
    q: asyncio.Queue = asyncio.Queue()
    _subscribers.setdefault(deviceId, set()).add(q)

    async def stream():
        try:
            yield "event: ready\ndata: {}\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    event, data = await asyncio.wait_for(q.get(), timeout=15)
                    yield f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
                except asyncio.TimeoutError:
                    yield ": ping\n\n"  # 연결 유지
        finally:
            _subscribers.get(deviceId, set()).discard(q)

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})


# ---------- 페어링 ----------

class PairingCreate(BaseModel):
    carDeviceId: str = Field(min_length=8, max_length=64)


@router.post("/sync/pairing-create")
def pairing_create(body: PairingCreate):
    token = secrets.token_urlsafe(9)
    now = time.time()
    with _db() as con:
        _ensure_device(con, body.carDeviceId, "car")
        con.execute(
            "INSERT INTO pairings(id, car_device_id, token, status, expires_at, created_at) VALUES (?,?,?,?,?,?)",
            (str(uuid.uuid4()), body.carDeviceId, token, "pending", now + PAIRING_TTL, now),
        )
    url = f"{public_base()}/app/?pair={token}"
    return {"token": token, "url": url, "expiresAt": now + PAIRING_TTL}


class PairingClaim(BaseModel):
    token: str = Field(min_length=4, max_length=64)
    phoneDeviceId: str = Field(min_length=8, max_length=64)


@router.post("/sync/pairing-claim")
async def pairing_claim(body: PairingClaim):
    with _db() as con:
        row = con.execute("SELECT * FROM pairings WHERE token=?", (body.token,)).fetchone()
        if not row:
            raise HTTPException(404, "페어링 코드를 찾을 수 없어요")
        if row["status"] == "claimed" and row["phone_device_id"] != body.phoneDeviceId:
            raise HTTPException(409, "이미 다른 폰이 연결했어요. 차량 화면에서 새 QR을 띄워 주세요")
        if row["status"] != "claimed" and row["expires_at"] < time.time():
            raise HTTPException(410, "QR이 만료됐어요. 차량 화면에서 새 QR을 띄워 주세요")
        _ensure_device(con, body.phoneDeviceId, "phone")
        con.execute("UPDATE pairings SET status='claimed', phone_device_id=? WHERE id=?", (body.phoneDeviceId, row["id"]))
        car = row["car_device_id"]
    await publish(car, "pairing.claimed", {"phoneDeviceId": body.phoneDeviceId})
    return {"carDeviceId": car, "paired": True}


@router.get("/sync/pairing-status")
def pairing_status(carDeviceId: str):
    with _db() as con:
        phones = _paired_phones(con, carDeviceId)
    return {"paired": bool(phones), "phones": len(phones)}


# ---------- 완성본 전송 (deliver) ----------

@router.post("/sync/results")
async def upload_result(
    carDeviceId: str = Form(...),
    meta: str = Form(..., description="Result JSON: id, sessionIds, layout, kind, filters, sourceKinds, createdOn"),
    file: UploadFile = File(...),
):
    m = json.loads(meta)
    rid = str(m.get("id") or uuid.uuid4())[:100]
    data = await file.read()
    if len(data) > 15 * 1024 * 1024:
        raise HTTPException(413, "파일이 너무 커요")
    ext = ".mp4" if m.get("kind") == "VIDEO" else ".jpg"
    path = FILES / f"{hashlib.sha256(rid.encode()).hexdigest()[:24]}{ext}"
    path.write_bytes(data)
    with _db() as con:
        _ensure_device(con, carDeviceId, "car")
        con.execute(
            "INSERT OR REPLACE INTO results VALUES (?,?,?,?,?,?,?,?,?,?)",
            (
                rid, carDeviceId, json.dumps(m.get("sessionIds", [])), m.get("layout", ""), m.get("kind", "PHOTO"),
                str(path.name), json.dumps(m.get("filters", [])), json.dumps(m.get("sourceKinds", [])),
                m.get("createdOn", "CAR"), time.time(),
            ),
        )
        row = con.execute("SELECT * FROM results WHERE id=?", (rid,)).fetchone()
        phones = _paired_phones(con, carDeviceId)
    payload = _result_json(row)
    for p in phones:
        await publish(p, "result.delivered", payload)
    log.info("result %s 업로드 → 폰 %d대에 알림", rid, len(phones))
    return {"id": rid, "deliveredTo": len(phones)}


@router.get("/sync/results")
def list_results(phoneDeviceId: str):
    with _db() as con:
        car = _car_of(con, phoneDeviceId)
        if not car:
            raise HTTPException(404, "연결된 차량이 없어요")
        rows = con.execute("SELECT * FROM results WHERE car_device_id=? ORDER BY created_at DESC LIMIT 50", (car,)).fetchall()
    return {"carDeviceId": car, "results": [_result_json(r) for r in rows]}


@router.get("/sync/file/{result_id}")
def get_file(result_id: str, exp: int, sig: str):
    if exp < time.time() or not hmac.compare_digest(sig, _sign(result_id, exp)):
        raise HTTPException(403, "링크가 만료됐어요")
    with _db() as con:
        row = con.execute("SELECT path FROM results WHERE id=?", (result_id,)).fetchone()
    if not row:
        raise HTTPException(404)
    return FileResponse(FILES / row["path"], headers={"Cache-Control": "private, max-age=3600"})


# ---------- 공유 링크 (share-link, 7일) ----------

class ShareRequest(BaseModel):
    resultId: str
    phoneDeviceId: str


@router.post("/sync/share")
def share(body: ShareRequest):
    with _db() as con:
        car = _car_of(con, body.phoneDeviceId)
        row = con.execute("SELECT car_device_id FROM results WHERE id=?", (body.resultId,)).fetchone()
        if not row or row["car_device_id"] != car:
            raise HTTPException(404)
        token = secrets.token_urlsafe(8)
        con.execute("INSERT INTO shares VALUES (?,?,?)", (token, body.resultId, time.time() + SHARE_TTL))
    return {"url": f"{public_base()}/s/{token}", "expiresInDays": 7}


def _share_row(token: str) -> sqlite3.Row:
    with _db() as con:
        row = con.execute(
            "SELECT r.path FROM shares s JOIN results r ON r.id = s.result_id WHERE s.token=? AND s.expires_at>?",
            (token, time.time()),
        ).fetchone()
    if not row:
        raise HTTPException(404, "만료되었거나 없는 링크예요")
    return row


@router.get("/s/{token}.jpg")
def share_image(token: str):
    return FileResponse(FILES / _share_row(token)["path"])


@router.get("/s/{token}", response_class=HTMLResponse)
def share_page(token: str):
    _share_row(token)
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>차~즈</title>
<meta property="og:title" content="차~즈로 찍은 드라이브 사진"><meta property="og:image" content="/s/{token}.jpg">
<style>body{{margin:0;background:#0B0C0E;color:#fff;font-family:system-ui,sans-serif;text-align:center}}
img{{max-width:100%;max-height:85vh;margin-top:16px;border-radius:12px}}p{{color:#9AA0A6}}</style></head>
<body><img src="/s/{token}.jpg" alt="차~즈 사진"><p>차~즈 · 7일 동안 볼 수 있는 링크예요</p></body></html>"""
