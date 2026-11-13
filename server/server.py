#!/usr/bin/env python3
"""aiphonepeek 收数据的小服务：单文件，只用 Python 3.8+ 标准库。

接口
  GET  /health                    存活检查（不要口令）
  POST /report                    壳按天补报的手机使用记录  {source, date, apps{pkg:{label,total_sec}}, sessions[]}
  POST /location                  壳报的位置  {latitude, longitude, accuracy_m, timestamp_ms, battery_level, charging, trigger, source}
  GET  /footprint?date=YYYY-MM-DD 现在在哪、今天待过哪、手机用了什么、最近的切换经过，带一段给 AI 读的 text
  GET  /places                    已命名的地方
  POST /places/name               给地方起名/改名/删名  {lat,lng,name} | {place_id,name} | {place_id,name:""}

口令（必填）：PEEK_TOKEN（或 --token），除 /health 外都要带  Authorization: Bearer <口令>。只在本机试用可加 --no-token。
高德（可选）：设了 AMAP_KEY（或 --amap-key）才会把坐标换成 GCJ-02 并反查地名；没设就显示坐标。
数据存在 --data 目录（默认 ./data）：usage.json、locations.jsonl、named_places.json、regeo_cache.json。
时间按服务器本机时区算「今天」。轨迹留 30 天，使用记录留 90 天。

运行：  python3 server.py --host 127.0.0.1 --port 8787
"""
from __future__ import annotations

import argparse
import hmac
import json
import math
import os
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

NAME_RADIUS_M = 150      # 起过名字的地方，这么近就算同一处
STAY_RADIUS_M = 150      # 连续几笔都在这么近，算「待在一处」
STAY_MIN_S = 10 * 60     # 待够 10 分钟才算停留
KEEP_LOC_DAYS = 30
KEEP_USAGE_DAYS = 90
MAX_BODY = 4 * 1024 * 1024

LOCK = threading.RLock()


# ───────────────────────── 坐标：WGS-84 → GCJ-02（可选，国内地图用） ─────────────────────────
_A, _EE = 6378245.0, 0.00669342162296594323


def _out_of_china(lat, lng):
    return not (73.66 < lng < 135.05 and 3.86 < lat < 53.55)


def _tlat(x, y):
    r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    r += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return r


def _tlng(x, y):
    r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    r += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    r += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return r


def wgs_to_gcj(lat, lng):
    if _out_of_china(lat, lng):
        return lat, lng
    dlat, dlng = _tlat(lng - 105.0, lat - 35.0), _tlng(lng - 105.0, lat - 35.0)
    rad = lat / 180.0 * math.pi
    magic = 1 - _EE * math.sin(rad) ** 2
    sq = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((_A * (1 - _EE)) / (magic * sq) * math.pi)
    dlng = (dlng * 180.0) / (_A / sq * math.cos(rad) * math.pi)
    return lat + dlat, lng + dlng


def dist_m(a_lat, a_lng, b_lat, b_lng):
    r = 6371000.0
    p1, p2 = math.radians(a_lat), math.radians(b_lat)
    dp, dl = p2 - p1, math.radians(b_lng - a_lng)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# ───────────────────────── 存取 ─────────────────────────
class Store:
    def __init__(self, data_dir: Path, amap_key: str = "", who: str = "用户"):
        self.dir = data_dir
        self.dir.mkdir(parents=True, exist_ok=True)
        self.usage_file = data_dir / "usage.json"
        self.loc_file = data_dir / "locations.jsonl"
        self.names_file = data_dir / "named_places.json"
        self.geo_cache_file = data_dir / "regeo_cache.json"
        self.amap_key = amap_key
        self.who = who
        self._pruned_day = ""
        # 启动时先按保留期清一遍，不等下一次写入
        with LOCK:
            self.prune()
            self.prune_usage()

    def _read_json(self, p: Path, default):
        try:
            return json.loads(p.read_text(encoding="utf-8"))
        except Exception:
            return default

    def _write_json(self, p: Path, v):
        tmp = p.with_name(p.name + ".%s.tmp" % uuid.uuid4().hex[:6])
        tmp.write_text(json.dumps(v, ensure_ascii=False, indent=1), encoding="utf-8")
        tmp.replace(p)

    # ---- 使用记录 ----
    def save_report(self, body: dict) -> dict:
        apps_in = body.get("apps")
        if not isinstance(apps_in, dict) or not apps_in:
            raise ValueError("apps 必须是非空对象（按天整份覆盖，空的会把那天清掉，所以不收）")
        day = body.get("date")
        if not isinstance(day, str):
            raise ValueError("date 必填，YYYY-MM-DD")
        datetime.strptime(day, "%Y-%m-%d")
        now = datetime.now().isoformat(timespec="seconds")
        apps = {}
        for pkg, info in apps_in.items():
            if not isinstance(info, dict):
                continue
            try:
                sec = int(info.get("total_sec", 0))
            except (TypeError, ValueError):
                continue
            if sec < 0:
                continue
            apps[str(pkg)[:120]] = {"label": str(info.get("label") or pkg)[:60], "total_sec": sec}
        sessions = []
        sess_in = body.get("sessions")
        if isinstance(sess_in, list):
            for x in sess_in[:2000]:
                try:
                    a, b = int(x["start_ms"]), int(x["end_ms"])
                except Exception:
                    continue
                if b > a:
                    sessions.append({"pkg": str(x.get("pkg") or "")[:120],
                                     "label": str(x.get("label") or x.get("pkg") or "")[:60],
                                     "start_ms": a, "end_ms": b})
        if not apps:
            raise ValueError("apps 里没有一条合格的记录")
        with LOCK:
            data = self._read_json(self.usage_file, {})
            # 按天整份覆盖：壳每次都是补报完整的一天
            data[day] = {"apps": apps, "sessions": sessions, "source": str(body.get("source") or "unknown")[:80], "updated": now}
            cut = (datetime.now() - timedelta(days=KEEP_USAGE_DAYS)).strftime("%Y-%m-%d")
            data = {k: v for k, v in data.items() if k >= cut}
            self._write_json(self.usage_file, data)
        return {"ok": True, "date": day, "accepted": len(apps), "sessions": len(sessions), "received_at": now}

    def _usage_cut(self) -> str:
        return (datetime.now() - timedelta(days=KEEP_USAGE_DAYS)).strftime("%Y-%m-%d")

    def prune_usage(self):
        if not self.usage_file.exists():
            return
        data = self._read_json(self.usage_file, {})
        cut = self._usage_cut()
        kept = {k: v for k, v in data.items() if k >= cut}
        if len(kept) != len(data):
            self._write_json(self.usage_file, kept)

    def usage_day(self, day: str) -> dict:
        # 过了保留期的不给读，哪怕文件里还没清掉
        if day < self._usage_cut():
            return {}
        return self._read_json(self.usage_file, {}).get(day) or {}

    # ---- 位置 ----
    def save_location(self, body: dict) -> dict:
        lat, lng = body.get("latitude"), body.get("longitude")
        if isinstance(lat, bool) or isinstance(lng, bool) or not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
            raise ValueError("latitude/longitude required as numbers")
        if not (-90 <= lat <= 90 and -180 <= lng <= 180):
            raise ValueError("lat/lng out of range")
        ts = body.get("timestamp_ms")
        if not isinstance(ts, (int, float)):
            ts = int(time.time() * 1000)
        batt = body.get("battery_level")
        acc = body.get("accuracy_m")
        entry = {
            "ts_ms": int(ts), "lat": float(lat), "lng": float(lng),
            "accuracy_m": float(acc) if isinstance(acc, (int, float)) else None,
            "provider": body.get("provider"),
            "battery": int(batt) if isinstance(batt, (int, float)) else None,
            "charging": bool(body.get("charging")),
            "trigger": str(body.get("trigger") or "unknown")[:30],
            "source": str(body.get("source") or "")[:80],
            "received_at": datetime.now().isoformat(timespec="seconds"),
        }
        with LOCK:
            with self.loc_file.open("a", encoding="utf-8") as f:
                f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            self.prune()
        return {"ok": True, "stored": True, "trigger": entry["trigger"]}

    def prune(self):
        """轨迹只留 30 天：一天最多整理一次。"""
        today = datetime.now().strftime("%Y-%m-%d")
        if self._pruned_day == today or not self.loc_file.exists():
            return
        self._pruned_day = today
        cut = (time.time() - KEEP_LOC_DAYS * 86400) * 1000
        keep = []
        for line in self.loc_file.read_text(encoding="utf-8").splitlines():
            try:
                if json.loads(line).get("ts_ms", 0) >= cut:
                    keep.append(line)
            except Exception:
                pass
        tmp = self.loc_file.with_name("locations.jsonl.prune.tmp")
        tmp.write_text("".join(l + "\n" for l in keep), encoding="utf-8")
        tmp.replace(self.loc_file)

    def all_points(self) -> list:
        out, seen = [], set()
        if not self.loc_file.exists():
            return out
        # 过了保留期的点不给读，哪怕文件里还没清掉
        cut = (time.time() - KEEP_LOC_DAYS * 86400) * 1000
        for line in self.loc_file.read_text(encoding="utf-8").splitlines():
            try:
                e = json.loads(line)
                lat, lng = e["lat"], e["lng"]
            except Exception:
                continue
            t = e.get("ts_ms") or 0
            if t < cut:
                continue
            # 优先用上报到达时间当「在这儿」的时刻（定位缓存可能是几分钟前的）；同一个定位被报两次只算一笔
            rt = e.get("received_at")
            try:
                t2 = datetime.fromisoformat(rt).timestamp() * 1000 if rt else t
            except Exception:
                t2 = t
            key = (t, round(lat, 5), round(lng, 5), int(t2 // 60000))
            if key in seen:
                continue
            seen.add(key)
            out.append({"t": t2, "lat": lat, "lng": lng, "acc": e.get("accuracy_m"),
                        "batt": e.get("battery"), "charging": e.get("charging")})
        out.sort(key=lambda p: p["t"])
        return out

    def points(self, day: str) -> list:
        start = datetime.strptime(day, "%Y-%m-%d")
        a, b = start.timestamp() * 1000, (start + timedelta(days=1)).timestamp() * 1000
        allp = self.all_points()
        out = [p for p in allp if a <= p["t"] < b]
        # 夜里手机睡了没报，早上第一笔跟昨天最后一笔在同一处，就当一直在那儿：今天这一段从 0 点算
        prev = [p for p in allp if p["t"] < a]
        if out and prev and dist_m(prev[-1]["lat"], prev[-1]["lng"], out[0]["lat"], out[0]["lng"]) <= STAY_RADIUS_M:
            out.insert(0, dict(out[0], t=a, carried=True))
        # 过去的某天：第二天第一笔还在同一处，这天的最后一段算到 24 点
        nxt = [p for p in allp if p["t"] >= b]
        if out and nxt and dist_m(out[-1]["lat"], out[-1]["lng"], nxt[0]["lat"], nxt[0]["lng"]) <= STAY_RADIUS_M:
            out.append(dict(out[-1], t=b - 60000, carried=True))
        return out

    def stay_since(self, lat, lng, until_ms) -> float:
        """从 until_ms 往回找：一直在这附近（中间没去过别处）最早从几点开始。跨天也算。"""
        since = until_ms
        for p in reversed([q for q in self.all_points() if q["t"] <= until_ms]):
            if dist_m(lat, lng, p["lat"], p["lng"]) > STAY_RADIUS_M:
                break
            since = p["t"]
        return since

    def stays(self, pts):
        """连续几笔都在 150 米内算一段；够 10 分钟的叫停留。最后一段不管多久都给（那是「现在」）。"""
        segs = []
        for p in pts:
            if segs:
                s = segs[-1]
                if dist_m(s["lat"], s["lng"], p["lat"], p["lng"]) <= STAY_RADIUS_M:
                    s["end"] = p["t"]
                    s["n"] += 1
                    if (p.get("acc") or 999) < (s.get("acc") or 999):   # 中心往精度好的点挪
                        s.update(lat=p["lat"], lng=p["lng"], acc=p.get("acc"))
                    continue
            segs.append({"start": p["t"], "end": p["t"], "lat": p["lat"], "lng": p["lng"], "acc": p.get("acc"), "n": 1})
        return [s for i, s in enumerate(segs) if s["end"] - s["start"] >= STAY_MIN_S * 1000 or i == len(segs) - 1]

    # ---- 地名 ----
    def names(self) -> list:
        return self._read_json(self.names_file, [])

    def name_for(self, lat, lng):
        best, bd = None, 1e9
        for n in self.names():
            d = dist_m(lat, lng, n["lat"], n["lng"])
            if d <= n.get("radius", NAME_RADIUS_M) and d < bd:
                best, bd = n, d
        return best

    def regeo(self, lat, lng) -> str:
        """没配高德 key：直接显示坐标（原始 WGS-84）。配了：换 GCJ-02 后反查，结果缓存。"""
        plain = "%.4f,%.4f" % (lat, lng)
        if not self.amap_key:
            return plain
        glat, glng = wgs_to_gcj(lat, lng)
        ck = "%.4f,%.4f" % (glat, glng)
        cache = self._read_json(self.geo_cache_file, {})
        if ck in cache:
            return cache[ck]
        try:
            q = urllib.parse.urlencode({"key": self.amap_key, "location": "%.6f,%.6f" % (glng, glat),
                                        "extensions": "all", "radius": 200, "roadlevel": 0})
            op = urllib.request.build_opener(urllib.request.ProxyHandler({}))   # 高德是国内服务，直连
            d = json.loads(op.open("https://restapi.amap.com/v3/geocode/regeo?" + q, timeout=8).read())
            rc = d.get("regeocode") or {}
            ac = rc.get("addressComponent") or {}
            pois = rc.get("pois") or []
            s = lambda v: v if isinstance(v, str) else ""
            area = s(ac.get("township")) or s(ac.get("district"))
            near = ""
            if pois and float(pois[0].get("distance") or 999) < 120:
                near = s(pois[0].get("name"))
            elif s((ac.get("streetNumber") or {}).get("street")):
                near = s(ac["streetNumber"]["street"])
            label = (area + " " + near + "附近").strip() if near else (area or s(rc.get("formatted_address")) or plain)
        except Exception:
            return plain   # 失败不缓存，下次再试
        cache[ck] = label
        self._write_json(self.geo_cache_file, cache)
        return label

    def label(self, lat, lng):
        n = self.name_for(lat, lng)
        return (n["name"], n["id"]) if n else (self.regeo(lat, lng), None)

    def set_name(self, lat, lng, name, pid=None):
        name = str(name or "").strip()[:40]
        with LOCK:
            names = self.names()
            if pid:
                for n in names:
                    if n["id"] == pid:
                        if not name:
                            names.remove(n)
                        else:
                            n["name"] = name
                            n["updated"] = datetime.now().isoformat(timespec="seconds")
                        self._write_json(self.names_file, names)
                        return n if name else {"deleted": pid}
                raise KeyError("没有这个地方")
            if not name:
                raise ValueError("名字不能空")
            n = {"id": uuid.uuid4().hex[:8], "lat": float(lat), "lng": float(lng), "name": name,
                 "radius": NAME_RADIUS_M, "created": datetime.now().isoformat(timespec="seconds")}
            names.append(n)
            self._write_json(self.names_file, names)
            return n


# ───────────────────────── 足迹 ─────────────────────────
def _hm(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M")


def _dur(sec):
    m = int(sec // 60)
    return "%d 小时 %d 分" % (m // 60, m % 60) if m >= 60 else "%d 分钟" % m


def _is_launcher(pkg: str) -> bool:
    p = (pkg or "").lower()
    return "launcher" in p or p in ("com.miui.home",)


def build_footprint(st: Store, day: str, now_ms=None) -> dict:
    now_ms = now_ms or time.time() * 1000
    who = st.who
    u = st.usage_day(day)
    apps = sorted(
        [{"label": v.get("label") or k, "total_sec": int(v.get("total_sec", 0))}
         for k, v in (u.get("apps") or {}).items() if isinstance(v, dict) and not _is_launcher(k)],
        key=lambda a: -a["total_sec"])
    apps = [a for a in apps if a["total_sec"] >= 60]
    sessions = [x for x in (u.get("sessions") or []) if not _is_launcher(x.get("pkg"))]

    pts = st.points(day)
    places = []
    for s in st.stays(pts):
        name, pid = st.label(s["lat"], s["lng"])
        places.append({"start": _hm(s["start"]), "end": _hm(s["end"]), "minutes": int((s["end"] - s["start"]) / 60000),
                       "name": name, "named": bool(pid), "place_id": pid, "lat": s["lat"], "lng": s["lng"]})
    now = None
    if pts:
        last = pts[-1]
        name, pid = st.label(last["lat"], last["lng"])
        since_ms = st.stay_since(last["lat"], last["lng"], last["t"])
        sd = datetime.fromtimestamp(since_ms / 1000)
        if sd.strftime("%Y-%m-%d") == day:
            since_txt = _hm(since_ms)
        elif (datetime.strptime(day, "%Y-%m-%d") - timedelta(days=1)).date() == sd.date():
            since_txt = "昨天 " + _hm(since_ms)
        else:
            since_txt = sd.strftime("%m/%d ") + _hm(since_ms)
        now = {"name": name, "named": bool(pid), "place_id": pid, "lat": last["lat"], "lng": last["lng"],
               "since": since_txt, "last_seen": _hm(last["t"]),
               "stale_min": int((now_ms - last["t"]) / 60000), "battery": last.get("batt"), "charging": last.get("charging")}

    lines = []
    is_today = day == datetime.now().strftime("%Y-%m-%d")
    dl = "今天" if is_today else day
    if now and is_today:
        stale = "（%d 分钟前的位置）" % now["stale_min"] if now["stale_min"] >= 30 else ""
        batt = ""
        if now.get("battery") is not None:
            batt = "，手机电量 %s%%" % now["battery"] + ("、在充电" if now.get("charging") else "")
        lines.append("%s现在在：%s，%s 到的%s%s。" % (who, now["name"], now["since"], stale, batt))
    elif is_today:
        lines.append("%s今天还没有位置记录。" % who)
    if places:
        lines.append(dl + "待过的地方：")
        for p in places:
            lines.append("· %s–%s %s（%s）" % (p["start"], p["end"], p["name"], _dur(p["minutes"] * 60)))
    if apps:
        lines.append(dl + "手机一共用了 %s，最久的：" % _dur(sum(a["total_sec"] for a in apps)))
        for a in apps[:8]:
            lines.append("· %s %s" % (a["label"], _dur(a["total_sec"])))
    else:
        lines.append(dl + "没有手机使用记录（壳还没补报，或没开「使用情况访问」）。")

    # 切换经过：不到 1 分钟的先去掉；同一个 App 前后两段间隔不到 3 分钟的接成一段
    raw = []
    for x in sorted(sessions, key=lambda x: x["start_ms"]):
        if x["end_ms"] - x["start_ms"] < 60000:
            continue
        lab = x.get("label") or x.get("pkg")
        if raw and raw[-1]["label"] == lab and x["start_ms"] - raw[-1]["end_ms"] <= 3 * 60000:
            raw[-1]["end_ms"] = max(raw[-1]["end_ms"], x["end_ms"])
        else:
            raw.append({"label": lab, "start_ms": x["start_ms"], "end_ms": x["end_ms"]})
    tl = [{"start": _hm(x["start_ms"]), "end": _hm(x["end_ms"]), "minutes": int((x["end_ms"] - x["start_ms"]) / 60000), "label": x["label"]} for x in raw]
    if tl:
        lines.append(dl + "手机上最近的经过：")
        for t in tl[-12:]:
            lines.append("· %s–%s %s（%s）" % (t["start"], t["end"], t["label"], _dur(t["minutes"] * 60)))
    return {"date": day, "now": now if is_today else None, "places": places, "apps": apps[:30],
            "timeline": tl[-400:], "text": "\n".join(lines)}


# ───────────────────────── HTTP ─────────────────────────
class Handler(BaseHTTPRequestHandler):
    store: Store = None  # type: ignore
    token: str = ""
    server_version = "aiphonepeek"

    def log_message(self, fmt, *args):   # 默认日志带 IP 和路径；这里只留一行简短的
        print("%s %s" % (datetime.now().strftime("%H:%M:%S"), fmt % args), flush=True)

    def _send(self, code: int, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _authed(self) -> bool:
        if not self.token:
            return True
        got = self.headers.get("Authorization", "")
        return got.startswith("Bearer ") and hmac.compare_digest(got[7:].strip().encode(), self.token.encode())

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > MAX_BODY:
            raise ValueError("body too large")
        d = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(d, dict):
            raise ValueError("body must be a JSON object")
        return d

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/health":
            return self._send(200, {"ok": True})
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        if u.path == "/footprint":
            day = (urllib.parse.parse_qs(u.query).get("date") or [""])[0] or datetime.now().strftime("%Y-%m-%d")
            try:
                datetime.strptime(day, "%Y-%m-%d")
            except ValueError:
                return self._send(400, {"error": "date 要 YYYY-MM-DD"})
            with LOCK:
                return self._send(200, build_footprint(self.store, day))
        if u.path == "/places":
            return self._send(200, self.store.names())
        self._send(404, {"error": "not found"})

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        if not self._authed():
            return self._send(401, {"error": "unauthorized"})
        try:
            body = self._body()
        except Exception as e:
            return self._send(400, {"error": "invalid json: %s" % e})
        try:
            if u.path == "/report":
                return self._send(200, self.store.save_report(body))
            if u.path == "/location":
                return self._send(200, self.store.save_location(body))
            if u.path == "/places/name":
                if body.get("place_id"):
                    r = self.store.set_name(None, None, body.get("name"), pid=str(body["place_id"]))
                else:
                    lat, lng = body.get("lat"), body.get("lng")
                    if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                        return self._send(400, {"error": "lat/lng 要数字"})
                    r = self.store.set_name(lat, lng, body.get("name"))
                return self._send(200, {"ok": True, "place": r})
        except (ValueError, KeyError) as e:
            return self._send(400, {"error": str(e)})
        self._send(404, {"error": "not found"})


def main():
    ap = argparse.ArgumentParser(description="aiphonepeek 收数据的小服务")
    ap.add_argument("--host", default=os.environ.get("PEEK_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("PEEK_PORT", "8787")))
    ap.add_argument("--data", default=os.environ.get("PEEK_DATA", "./data"))
    ap.add_argument("--token", default=os.environ.get("PEEK_TOKEN", ""), help="口令，必填（也可用环境变量 PEEK_TOKEN）")
    ap.add_argument("--no-token", action="store_true", help="不设口令，只在本机自己试用时用；只能配本机地址，前面挂了反代也不要用")
    ap.add_argument("--amap-key", default=os.environ.get("AMAP_KEY", ""), help="可选：高德 Web 服务 key，用于坐标反查地名")
    ap.add_argument("--who", default=os.environ.get("PEEK_WHO", "用户"), help="给 AI 读的 text 里怎么称呼机主，默认「用户」")
    a = ap.parse_args()
    loopback = a.host in ("127.0.0.1", "localhost", "::1")
    if not a.token and not a.no_token:
        sys.exit("没设口令，不启动。手机能连上的部署（局域网、反代、隧道）都必须设口令：--token 或环境变量 PEEK_TOKEN。只在本机自己试用可以加 --no-token。")
    if a.no_token and not a.token and not loopback:
        sys.exit("--no-token 只能配本机地址（127.0.0.1 / localhost / ::1）。")
    Handler.store = Store(Path(a.data), a.amap_key, a.who)
    Handler.token = a.token
    if not a.token:
        print("警告：没有口令，只适合本机试用。前面挂了反代或隧道的话，任何人都能读写你的位置数据。", flush=True)
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print("listening on http://%s:%d  data=%s  token=%s  amap=%s" % (a.host, a.port, a.data, "on" if a.token else "off", "on" if a.amap_key else "off"), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
