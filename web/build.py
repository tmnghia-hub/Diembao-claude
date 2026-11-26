#!/usr/bin/env python3
"""Builds the static news reader: RSS feeds -> translate titles and summaries -> site/index.html.

Runs in GitHub Actions. Only the Python standard library is needed, plus `cryptography`
when SITE_PASSWORD is set. Only titles and short summaries are published, never full articles.

Settings (environment variables, all optional):
  SITE_TITLE            name shown in the masthead
  TARGET_LANGUAGE       language to translate into, default Vietnamese
  TRANSLATION_STYLE     how the AI should write the translation
  GEMINI_API_KEY        secret; without it nothing is translated
  GEMINI_MODEL          default gemini-flash-latest
  GEMINI_BASE_URL       OpenAI compatible endpoint of Gemini
  SITE_PASSWORD         secret; when set, the page is encrypted and asks for it (at least 10 characters)
  REQUIRE_PASSWORD      "true" = refuse to publish a readable page when no password is set
  MAX_AGE_DAYS, MAX_PER_FEED, MAX_TOTAL, MAX_TRANSLATE_PER_RUN, BATCH_SIZE
"""

from __future__ import annotations

import base64
import concurrent.futures
import gzip
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
USER_AGENT = "Mozilla/5.0 (compatible; DiemBaoReader/1.0; +https://github.com)"
PBKDF2_ITERATIONS = 600_000
DEFAULT_STYLE = (
    "Write like a science magazine: precise, neutral and formal, using the established scientific "
    "terminology of the target language. Restructure sentences so they read naturally instead of "
    "translating word by word. Keep names, numbers, units, abbreviations and species names exact."
)


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name, "").strip()
    return value if value else default


def env_int(name: str, default: int) -> int:
    try:
        return int(env(name, str(default)))
    except ValueError:
        return default


SITE_TITLE = env("SITE_TITLE", "Điểm Báo")
TARGET_LANGUAGE = env("TARGET_LANGUAGE", "Vietnamese")
STYLE = env("TRANSLATION_STYLE", DEFAULT_STYLE)
GEMINI_API_KEY = env("GEMINI_API_KEY")
GEMINI_MODEL = env("GEMINI_MODEL", "gemini-flash-latest")
GEMINI_BASE_URL = env("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai/")
SITE_PASSWORD = os.environ.get("SITE_PASSWORD", "")
REQUIRE_PASSWORD = env("REQUIRE_PASSWORD", "false").lower() == "true"
MIN_PASSWORD_LENGTH = 10
MAX_AGE_DAYS = env_int("MAX_AGE_DAYS", 10)
MAX_PER_FEED = env_int("MAX_PER_FEED", 12)
MAX_TOTAL = env_int("MAX_TOTAL", 400)
MAX_TRANSLATE_PER_RUN = env_int("MAX_TRANSLATE_PER_RUN", 150)
BATCH_SIZE = env_int("BATCH_SIZE", 10)
OPML_PATH = Path(env("OPML_PATH", str(ROOT / "feeds.opml")))
CACHE_PATH = Path(env("CACHE_PATH", str(ROOT / ".cache" / "translations.json")))
OUTPUT_DIR = Path(env("OUTPUT_DIR", str(ROOT.parent / "site")))
TEMPLATE_PATH = ROOT / "template.html"


def log(message: str) -> None:
    print(message, flush=True)


# ---------------------------------------------------------------- OPML

def read_opml(path: Path) -> list[dict]:
    """Returns [{name, url, category}] from an OPML file, one level of folders."""
    tree = ET.parse(path)
    feeds: list[dict] = []

    def walk(node: ET.Element, category: str) -> None:
        for child in node.findall("outline"):
            url = child.get("xmlUrl")
            name = child.get("title") or child.get("text") or url or ""
            if url:
                feeds.append({"name": name, "url": url, "category": category})
            else:
                walk(child, name or category)

    body = tree.getroot().find("body")
    if body is not None:
        walk(body, "")
    return feeds


# ---------------------------------------------------------------- Fetching and parsing

def fetch(url: str, timeout: int = 25) -> bytes:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/rss+xml, application/atom+xml, application/xml, text/xml, */*",
            "Accept-Encoding": "gzip",
        },
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        data = response.read(8_000_000)
        if response.headers.get("Content-Encoding", "").lower() == "gzip":
            data = gzip.decompress(data)
        return data


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1] if isinstance(tag, str) else ""


def child_text(node: ET.Element, *names: str) -> str:
    for child in node:
        if local(child.tag) in names and (child.text or "").strip():
            return child.text.strip()
    return ""


def parse_xml(data: bytes) -> ET.Element:
    try:
        return ET.fromstring(data)
    except ET.ParseError:
        # Feeds sometimes contain bare ampersands or HTML entities
        text = data.decode("utf-8", errors="replace")
        text = re.sub(r"&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)", "&amp;", text)
        text = re.sub(r"^<\?xml[^>]*\?>", "", text.lstrip())
        return ET.fromstring(text)


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.first_image = ""
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self._skip += 1
        elif tag == "img" and not self.first_image:
            self.first_image = dict(attrs).get("src") or ""
        elif tag in ("p", "br", "div", "li"):
            self.parts.append(" ")

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self._skip:
            self._skip -= 1

    def handle_data(self, data):
        if not self._skip:
            self.parts.append(data)


def html_to_text(markup: str) -> tuple[str, str]:
    """Returns (plain text, first image src)."""
    extractor = _TextExtractor()
    try:
        extractor.feed(markup)
        extractor.close()
    except Exception:  # noqa: BLE001 - broken markup must never stop the build
        return html.unescape(re.sub(r"<[^>]+>", " ", markup)), ""
    text = re.sub(r"\s+", " ", "".join(extractor.parts)).strip()
    return text, extractor.first_image


def make_summary(text: str, limit: int = 420) -> str:
    text = re.sub(r"\s*The post .{0,200}? appeared first on .{0,100}$", "", text).strip()
    text = re.sub(r"\s*\[?(?:Read more|Continue reading)[^\]]*\]?\s*$", "", text, flags=re.I).strip()
    if len(text) <= limit:
        return text
    cut = text[:limit]
    sentence_end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
    if sentence_end > limit * 0.5:
        return cut[: sentence_end + 1]
    return cut[: cut.rfind(" ")].rstrip(",;:") + "…"


def parse_date(value: str) -> datetime | None:
    if not value:
        return None
    value = value.strip()
    try:
        parsed = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        parsed = None
    if parsed is None:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def clean_image(url: str) -> str:
    url = (url or "").strip()
    if url.startswith("//"):
        url = "https:" + url
    if url.startswith("http://"):
        url = "https://" + url[len("http://"):]
    return url if url.startswith("https://") else ""


def entry_image(node: ET.Element, fallback_html_image: str) -> str:
    for child in node.iter():
        name = local(child.tag)
        if name == "thumbnail" and child.get("url"):
            return clean_image(child.get("url", ""))
        if name == "content" and child.get("url"):
            kind = (child.get("medium") or child.get("type") or "").lower()
            if "image" in kind:
                return clean_image(child.get("url", ""))
        if name == "enclosure" and child.get("url") and "image" in (child.get("type") or "").lower():
            return clean_image(child.get("url", ""))
    return clean_image(fallback_html_image)


def parse_feed(data: bytes, feed: dict) -> list[dict]:
    root = parse_xml(data)
    items: list[dict] = []
    nodes = [n for n in root.iter() if local(n.tag) in ("item", "entry")]
    for node in nodes:
        title = html_to_text(child_text(node, "title"))[0]
        link = child_text(node, "link")
        if not link:
            for child in node:
                if local(child.tag) == "link" and child.get("href") and child.get("rel", "alternate") == "alternate":
                    link = child.get("href", "")
                    break
        link = link.strip()
        if not title or not link.startswith(("http://", "https://")):
            continue

        raw_summary = child_text(node, "description", "summary") or child_text(node, "encoded", "content")
        text, html_image = html_to_text(raw_summary)
        if not html_image:
            html_image = html_to_text(child_text(node, "encoded", "content"))[1]
        published = parse_date(child_text(node, "pubDate", "published", "updated", "date"))
        guid = child_text(node, "guid", "id") or link
        items.append(
            {
                "id": hashlib.sha1(guid.encode("utf-8")).hexdigest()[:16],
                "src": feed["name"],
                "cat": feed["category"],
                "title": title,
                "summary": make_summary(text),
                "link": link,
                "date": (published or datetime.now(timezone.utc)).isoformat(),
                "img": entry_image(node, html_image),
            }
        )
    return items


def load_all_feeds(feeds: list[dict]) -> list[dict]:
    def load(feed: dict) -> list[dict]:
        try:
            items = parse_feed(fetch(feed["url"]), feed)
            log(f"  ok   {feed['name']}: {len(items)} bài")
            return items
        except Exception as error:  # noqa: BLE001 - one broken feed must not break the site
            log(f"  FAIL {feed['name']}: {type(error).__name__}: {str(error)[:120]}")
            return []

    cutoff = datetime.now(timezone.utc) - timedelta(days=MAX_AGE_DAYS)
    collected: dict[str, dict] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for feed, items in zip(feeds, pool.map(load, feeds)):
            items = [i for i in items if datetime.fromisoformat(i["date"]) >= cutoff]
            items.sort(key=lambda i: i["date"], reverse=True)
            for item in items[:MAX_PER_FEED]:
                collected.setdefault(item["id"], item)

    result = sorted(collected.values(), key=lambda i: i["date"], reverse=True)
    return result[:MAX_TOTAL]


# ---------------------------------------------------------------- Translation

class RateLimited(Exception):
    pass


def config_hash() -> str:
    return hashlib.sha1(f"{TARGET_LANGUAGE}|{STYLE}".encode("utf-8")).hexdigest()[:10]


def load_cache() -> dict:
    try:
        return json.loads(CACHE_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def save_cache(cache: dict) -> None:
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    CACHE_PATH.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")


def call_model(payload: dict) -> str:
    url = GEMINI_BASE_URL.rstrip("/") + "/chat/completions"
    body = json.dumps(payload).encode("utf-8")
    delays = [3, 10, 30]
    for attempt in range(len(delays) + 1):
        request = urllib.request.Request(
            url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {GEMINI_API_KEY}",
                "User-Agent": USER_AGENT,
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                data = json.loads(response.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:200]
            if error.code in (429, 500, 502, 503, 504) and attempt < len(delays):
                time.sleep(delays[attempt])
                continue
            if error.code == 429:
                raise RateLimited(detail) from error
            raise RuntimeError(f"HTTP {error.code}: {detail}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt < len(delays):
                time.sleep(delays[attempt])
                continue
            raise RuntimeError(f"network error: {error}") from error
    raise RuntimeError("unreachable")


def parse_model_json(text: str):
    text = text.strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    data = json.loads(text)
    if isinstance(data, dict):
        data = data.get("items", data.get("translations", []))
    return data


def translate_batch(batch: list[dict]) -> dict[str, dict]:
    """Translates title and summary of the items. Returns {id: {"t":..., "s":...}}."""
    system = (
        "You are a professional translator working for a newspaper. "
        f"Translate the title and summary of each news item into {TARGET_LANGUAGE}. "
        f"Style requested by the reader: {STYLE} "
        "If a text is already in the target language, return it unchanged. "
        "Never add comments, never merge or drop items. "
        'Answer with ONLY a JSON object of the form {"items":[{"id":"...","title":"...","summary":"..."}]} '
        "that contains every id of the input exactly once."
    )
    user = json.dumps(
        [{"id": i["id"], "title": i["title"], "summary": i["summary"]} for i in batch],
        ensure_ascii=False,
    )
    payload = {
        "model": GEMINI_MODEL,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "response_format": {"type": "json_object"},
    }
    try:
        rows = parse_model_json(call_model(payload))
        wanted = {i["id"] for i in batch}
        result = {}
        for row in rows:
            if isinstance(row, dict) and row.get("id") in wanted and str(row.get("title", "")).strip():
                result[row["id"]] = {
                    "t": str(row["title"]).strip(),
                    "s": str(row.get("summary", "")).strip(),
                }
        missing = wanted - set(result)
        if missing and len(batch) > 1:
            # Try again for the ones the model skipped
            result.update(translate_batch([i for i in batch if i["id"] in missing]))
        return result
    except (ValueError, KeyError, TypeError):
        if len(batch) > 1:
            middle = len(batch) // 2
            return {**translate_batch(batch[:middle]), **translate_batch(batch[middle:])}
        return {}


def translate_items(items: list[dict]) -> None:
    cache = load_cache()
    suffix = config_hash()
    todo: list[dict] = []
    for item in items:
        hit = cache.get(f"{item['id']}:{suffix}")
        if hit:
            item["t"], item["s"] = hit["t"], hit["s"]
        else:
            todo.append(item)

    log(f"Đã có bản dịch: {len(items) - len(todo)}, cần dịch: {len(todo)}")
    if todo and not GEMINI_API_KEY:
        log("Không có GEMINI_API_KEY nên các bài mới giữ nguyên bản gốc.")
        todo = []

    todo = todo[:MAX_TRANSLATE_PER_RUN]
    try:
        for start in range(0, len(todo), BATCH_SIZE):
            batch = todo[start:start + BATCH_SIZE]
            translated = translate_batch(batch)
            for item in batch:
                row = translated.get(item["id"])
                if row:
                    item["t"], item["s"] = row["t"], row["s"]
                    cache[f"{item['id']}:{suffix}"] = row
            log(f"  dịch {min(start + BATCH_SIZE, len(todo))}/{len(todo)}")
    except RateLimited as error:
        log(f"Hết hạn mức của Gemini, các bài còn lại sẽ dịch ở lần chạy sau: {error}")
    except RuntimeError as error:
        log(f"Lỗi khi gọi Gemini, các bài còn lại giữ bản gốc: {error}")

    # Keep the cache small: only what is on the page now
    alive = {f"{i['id']}:{suffix}" for i in items}
    save_cache({k: v for k, v in cache.items() if k in alive})


# ---------------------------------------------------------------- Output

def encrypt(plain: bytes, password: str) -> str:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

    salt = os.urandom(16)
    iv = os.urandom(12)
    key = PBKDF2HMAC(
        algorithm=hashes.SHA256(), length=32, salt=salt, iterations=PBKDF2_ITERATIONS
    ).derive(password.encode("utf-8"))
    ciphertext = AESGCM(key).encrypt(iv, plain, None)
    return base64.b64encode(salt + iv + ciphertext).decode("ascii")


def script_safe_json(value) -> str:
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return (
        text.replace("</", "<\\/")
        .replace("<!--", "<\\!--")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029")
    )


def build_site(items: list[dict]) -> Path:
    data = {
        "title": SITE_TITLE,
        "generated": datetime.now(timezone.utc).isoformat(),
        "language": TARGET_LANGUAGE,
        "items": items,
    }
    if SITE_PASSWORD:
        plain = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        payload = {"mode": "enc", "title": SITE_TITLE, "iter": PBKDF2_ITERATIONS, "b64": encrypt(plain, SITE_PASSWORD)}
    else:
        payload = {"mode": "plain", "title": SITE_TITLE, "data": data}

    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    page = template.replace("/*__PAYLOAD__*/null", script_safe_json(payload)).replace(
        "__SITE_TITLE__", html.escape(SITE_TITLE)
    )
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUTPUT_DIR / "index.html"
    out.write_text(page, encoding="utf-8")
    (OUTPUT_DIR / ".nojekyll").write_text("", encoding="utf-8")
    return out


def main() -> int:
    if REQUIRE_PASSWORD and not SITE_PASSWORD:
        log("LỖI: REQUIRE_PASSWORD=true nhưng chưa có secret SITE_PASSWORD. Không đăng trang để tránh lộ nội dung.")
        return 2
    if SITE_PASSWORD and len(SITE_PASSWORD) < MIN_PASSWORD_LENGTH:
        log(f"LỖI: SITE_PASSWORD phải dài ít nhất {MIN_PASSWORD_LENGTH} ký tự, nếu không rất dễ bị dò ra.")
        return 2
    feeds = read_opml(OPML_PATH)
    log(f"Đọc {len(feeds)} nguồn tin từ {OPML_PATH.name}")
    items = load_all_feeds(feeds)
    log(f"Tổng cộng {len(items)} bài sau khi lọc")
    if not items:
        log("Không tải được bài nào, dừng để không ghi đè trang cũ bằng trang trống.")
        return 1
    translate_items(items)
    out = build_site(items)
    mode = "mã hóa bằng mật khẩu" if SITE_PASSWORD else "công khai"
    log(f"Đã tạo {out} ({out.stat().st_size // 1024} KB, {mode})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
