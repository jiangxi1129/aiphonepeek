#!/usr/bin/env python3
"""把 aiphonepeek 服务的 /footprint 包成 MCP 工具（只读）。

环境变量：
  PEEK_URL    服务地址，默认 http://127.0.0.1:8787
  PEEK_TOKEN  口令（服务端设了才需要）
需要 Python 3.10+ 和官方 mcp 库：pip install -r requirements.txt
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request

from mcp.server.fastmcp import FastMCP

BASE = os.environ.get("PEEK_URL", "http://127.0.0.1:8787").rstrip("/")
TOKEN = os.environ.get("PEEK_TOKEN", "")

mcp = FastMCP("aiphonepeek")


def _get_footprint(date: str) -> dict:
    url = BASE + "/footprint" + ("?" + urllib.parse.urlencode({"date": date}) if date else "")
    req = urllib.request.Request(url)
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


@mcp.tool()
def phone_footprint(date: str = "") -> str:
    """查看机主的手机足迹：现在在哪、当天待过哪些地方、手机上用了什么 App（时长）、最近的 App 切换经过。

    date 留空表示今天，也可以填 YYYY-MM-DD 查某一天（最近 30 天有位置，90 天有使用记录）。
    返回一段可直接阅读的文字。数据来自机主自己安卓壳的定时补报，可能有延迟，
    文字里的「N 分钟前的位置」就是在提醒数据多旧。
    """
    try:
        return _get_footprint(date)["text"]
    except urllib.error.HTTPError as e:
        if e.code == 401:
            return "服务拒绝了请求（401）：PEEK_TOKEN 不对或没设。"
        return "查询失败：HTTP %d" % e.code
    except Exception as e:  # 服务没开、网络不通
        return "连不上足迹服务（%s）：%s" % (BASE, e)


if __name__ == "__main__":
    mcp.run()  # 默认 stdio，Claude Desktop 等直接拉起
