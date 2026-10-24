"""Collect public anime ratings/reviews into a separate, resumable local SQLite store.

No downloader mutations. Missing permissions, failed requests and incomplete pagination
are explicit states, never represented as zero ratings or a complete empty review set.
Install tools/reviews-requirements.txt. See docs/opinion-collection.md.
"""
import argparse
import csv
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import unicodedata
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin, urlsplit, parse_qs
from urllib.request import Request, urlopen
from bs4 import BeautifulSoup

UA = 'ani-rss-simple/0.1 (https://github.com/kahzee/ani-rss)'
HOSTS = {'api.bgm.tv', 'bgm.tv', 'kitsu.io', 'kitsu.app', 'api.jikan.moe',
         'myanimelist.net', 'api.themoviedb.org', 'www.imdb.com', 'movie.douban.com',
         'api.bilibili.com', 'www.bilibili.com', 'www.anime-planet.com', 'www.anisearch.com'}


class Unavailable(Exception):
    def __init__(self, status, message):
        self.status = status
        super().__init__(message)


def normalized(s):
    return ''.join(c for c in unicodedata.normalize('NFKC', s).casefold() if c.isalnum())


def text(node):
    return node.get_text('\n', strip=True) if node else ''


def valid_id(value):
    if not re.fullmatch(r'[1-9][0-9]{0,10}', str(value)):
        raise ValueError('Invalid source ID')
    return str(value)


def parse_bgm_comments(html, subject, page):
    soup = BeautifulSoup(html, 'html.parser')
    box = soup.select_one('#comment_box')
    if box is None:
        raise Unavailable('layout_changed', 'Comment container missing; not treated as an empty page')
    rows = []
    for item in box.select('div.item[data-item-user]'):
        comment = item.select_one('p.comment')
        if not text(comment):
            continue
        user = item.select_one('a.l[href^="/user/"]')
        stars = item.select_one('.starlight')
        rating = re.search(r'\bstars(\d+)\b', ' '.join(stars.get('class', []))) if stars else None
        identity = item.select_one('[id^="likes_grid_"]')
        rid = identity['id'].removeprefix('likes_grid_') if identity else item['data-item-user']
        rows.append({'id': rid, 'kind': 'short', 'author': text(user),
                     'author_id': item['data-item-user'], 'content': text(comment),
                     'rating': int(rating[1]) if rating else None, 'scale': 10,
                     'published_text': ' '.join(text(n) for n in item.select('small.grey')),
                     'url': f'https://bgm.tv/subject/{subject}/comments?page={page}',
                     'spoiler': None, 'content_complete': True})
    return rows, next_bgm_page(soup, page)


def next_bgm_page(soup, page):
    pages = []
    for link in soup.select('.page_inner a[href], .page_number a[href], a.p[href]'):
        value = parse_qs(urlsplit(link['href']).query).get('page', [''])[0]
        if value.isdigit():
            pages.append(int(value))
    return page + 1 if any(p > page for p in pages) else None


def parse_bgm_reviews(html, subject, page):
    soup = BeautifulSoup(html, 'html.parser')
    box = soup.select_one('#entry_list')
    if box is None:
        raise Unavailable('layout_changed', 'Review list missing; not treated as an empty page')
    rows = []
    for item in box.select('.item'):
        link = item.select_one('h2 a[href^="/blog/"]')
        if not link:
            continue
        rid = re.fullmatch(r'/blog/(\d+)', link['href'])
        if not rid:
            continue
        user = item.select_one('.tools a[href^="/user/"]')
        rows.append({'id': rid[1], 'kind': 'long', 'title': text(link),
                     'author': text(user), 'author_id': item.get('data-item-user', ''),
                     'content': text(item.select_one('.content')), 'content_complete': False,
                     'published_text': text(item.select_one('.tools .time')),
                     'rating': None, 'url': 'https://bgm.tv' + link['href'], 'spoiler': None})
    return rows, next_bgm_page(soup, page)


def parse_bgm_blog(html):
    soup = BeautifulSoup(html, 'html.parser')
    node = soup.select_one('#entry_content')
    if node is None or not text(node):
        raise Unavailable('layout_changed', 'Full review body missing; list excerpt retained')
    for child in node.select('script,style'):
        child.decompose()
    return text(node)


def exact_kitsu(items, subject):
    """No fuzzy season guesses: exact original title plus matching year where known."""
    original = normalized(subject.get('name', ''))
    year = subject.get('date', '')[:4]
    choices = []
    for item in items:
        a = item.get('attributes', {})
        names = list(a.get('titles', {}).values()) + [a.get('canonicalTitle', '')] + a.get('abbreviatedTitles', [])
        if not original or original not in {normalized(n) for n in names if n}:
            continue
        start = a.get('startDate') or ''
        if year and start and start[:4] != year:
            continue
        choices.append(item)
    if len(choices) != 1:
        raise Unavailable('mapping_required', 'No unique exact original-title/year match; other seasons not substituted')
    return choices[0]


class Store:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(self.root / 'opinions.db', timeout=10)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
          CREATE TABLE IF NOT EXISTS ratings(subject TEXT,source TEXT,scope TEXT,body TEXT,checked REAL,
            PRIMARY KEY(subject,source,scope));
          CREATE TABLE IF NOT EXISTS reviews(subject TEXT,source TEXT,id TEXT,kind TEXT,body TEXT,checked REAL,
            PRIMARY KEY(subject,source,id,kind));
          CREATE TABLE IF NOT EXISTS tasks(subject TEXT,source TEXT,kind TEXT,cursor TEXT,state TEXT,message TEXT,
            retry REAL DEFAULT 0,checked REAL,PRIMARY KEY(subject,source,kind));
          CREATE TABLE IF NOT EXISTS mappings(subject TEXT,source TEXT,external_id TEXT,evidence TEXT,
            PRIMARY KEY(subject,source));
          CREATE TABLE IF NOT EXISTS cache(url TEXT PRIMARY KEY,body BLOB,checked REAL);
        ''')

    def status(self, sid, source, kind, state, message='', cursor=None, retry=0):
        self.db.execute('INSERT INTO tasks VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(subject,source,kind) DO UPDATE SET '
                        'cursor=excluded.cursor,state=excluded.state,message=excluded.message,retry=excluded.retry,checked=excluded.checked',
                        (sid, source, kind, json.dumps(cursor), state, message, retry, time.time()))
        self.db.commit()

    def task(self, sid, source, kind):
        row = self.db.execute('SELECT cursor,state,retry,checked FROM tasks WHERE subject=? AND source=? AND kind=?',
                              (sid, source, kind)).fetchone()
        return (json.loads(row[0]), row[1], row[2], row[3]) if row else (None, '', 0, 0)

    def rating(self, sid, source, scope, score, count, scale, url, **extra):
        body = dict(score=score, votes=count, scale=scale, url=url, scope=scope, **extra)
        self.db.execute('INSERT INTO ratings VALUES(?,?,?,?,?) ON CONFLICT(subject,source,scope) DO UPDATE SET body=excluded.body,checked=excluded.checked',
                        (sid, source, scope, json.dumps(body, ensure_ascii=False), time.time()))
        self.db.commit()

    def review(self, sid, source, row):
        old = self.db.execute('SELECT body FROM reviews WHERE subject=? AND source=? AND id=? AND kind=?',
                              (sid, source, row['id'], row['kind'])).fetchone()
        # Refreshing a list excerpt must not replace a previously collected full article.
        if old:
            previous = json.loads(old[0])
            if previous.get('content_complete') and not row.get('content_complete'):
                row = {**row, 'content': previous['content'], 'content_complete': True}
        self.db.execute('INSERT INTO reviews VALUES(?,?,?,?,?,?) ON CONFLICT(subject,source,id,kind) DO UPDATE SET body=excluded.body,checked=excluded.checked',
                        (sid, source, row['id'], row['kind'], json.dumps(row, ensure_ascii=False), time.time()))
        self.db.commit()

    def mapping(self, sid, source, external, evidence):
        old = self.db.execute('SELECT external_id FROM mappings WHERE subject=? AND source=?', (sid, source)).fetchone()
        if old and old[0] != str(external) and not evidence.startswith('explicit'):
            raise Unavailable('mapping_conflict', 'Provider mappings disagree; existing mapping preserved')
        self.db.execute('INSERT INTO mappings VALUES(?,?,?,?) ON CONFLICT(subject,source) DO UPDATE SET external_id=excluded.external_id,evidence=excluded.evidence',
                        (sid, source, str(external), evidence))
        self.db.commit()

    def mapped(self, sid, source):
        row = self.db.execute('SELECT external_id FROM mappings WHERE subject=? AND source=?', (sid, source)).fetchone()
        return row[0] if row else None

    def export(self):
        data = {'checkedAt': time.time(), 'ratings': [], 'reviews': [], 'tasks': [], 'mappings': []}
        for sid, source, scope, body, checked in self.db.execute('SELECT * FROM ratings'):
            data['ratings'].append(dict(subject=sid, source=source, checkedAt=checked, **json.loads(body)))
        for sid, source, rid, kind, body, checked in self.db.execute('SELECT * FROM reviews'):
            data['reviews'].append(dict(subject=sid, source=source, checkedAt=checked, **json.loads(body)))
        for sid, source, kind, cursor, state, message, retry, checked in self.db.execute('SELECT * FROM tasks'):
            data['tasks'].append(dict(subject=sid, source=source, kind=kind, cursor=json.loads(cursor), state=state,
                                      message=message, retryAt=retry, checkedAt=checked))
        for sid, source, external, evidence in self.db.execute('SELECT * FROM mappings'):
            data['mappings'].append(dict(subject=sid, source=source, externalId=external, evidence=evidence))
        target = self.root / 'opinions.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(target)
        def cell(value):
            value = '' if value is None else str(value)
            return "'"+value if value.startswith(('=','+','-','@')) else value
        titles = {r['subject']:r.get('name_cn') or r.get('name') or r['subject'] for r in data['ratings'] if r['source']=='bangumi'}
        with (self.root/'ratings.csv').open('w',encoding='utf-8-sig',newline='') as out:
            writer=csv.writer(out);writer.writerow(['Bangumi ID','番名','来源','范围','评分','满分','评分人数','原文链接'])
            for r in data['ratings']:
                writer.writerow([cell(v) for v in [r['subject'],titles.get(r['subject'],r['subject']),r['source'],r.get('scope'),r.get('score'),r.get('scale'),r.get('votes'),r.get('url')]])
        with (self.root/'source-status.csv').open('w',encoding='utf-8-sig',newline='') as out:
            writer=csv.writer(out);writer.writerow(['Bangumi ID','番名','来源','任务','状态','后续位置','原因'])
            for t in data['tasks']:
                writer.writerow([cell(v) for v in [t['subject'],titles.get(t['subject'],t['subject']),t['source'],t['kind'],t['state'],json.dumps(t['cursor'],ensure_ascii=False),t['message']]])
        with (self.root/'reviews.jsonl').open('w',encoding='utf-8') as out:
            for row in data['reviews']:
                out.write(json.dumps(row,ensure_ascii=False)+'\n')
        summary = {s: dict(ratings=sum(r['source'] == s for r in data['ratings']),
                           ratedItems=sum(r['source']==s and r.get('score') is not None for r in data['ratings']),
                           reviews=sum(r['source'] == s for r in data['reviews']),
                           completeReviewBodies=sum(r['source']==s and r.get('content_complete',False) for r in data['reviews']),
                           states=sorted({t['state'] for t in data['tasks'] if t['source'] == s}))
                   for s in sorted({t['source'] for t in data['tasks']})}
        (self.root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
        return summary


class Network:
    def __init__(self, store, budget=300):
        self.store, self.budget, self.calls = store, budget, 0
        self.last, self.blocked, self.failures = {}, {}, {}

    def get(self, url, headers=None, ttl=6*3600):
        parsed = urlsplit(url)
        if parsed.scheme != 'https' or parsed.hostname not in HOSTS:
            raise Unavailable('invalid_url', 'Provider URL not allowed')
        # Keys are never stored as URLs or included in error messages.
        key = hashlib.sha256(url.encode()).hexdigest()
        row = self.store.db.execute('SELECT body,checked FROM cache WHERE url=?', (key,)).fetchone()
        if row and time.time()-row[1] < ttl:
            return bytes(row[0]).decode('utf-8')
        host = parsed.hostname
        if host in self.blocked:
            raise self.blocked[host]
        if self.calls >= self.budget:
            raise Unavailable('pending', 'Request budget reached; resume next run')
        wait = self.last.get(host, 0) + 1.2 - time.monotonic()
        if wait > 0:
            time.sleep(wait)
        self.calls += 1
        self.last[host] = time.monotonic()
        try:
            defaults = {'User-Agent': UA}
            if host in ('kitsu.io', 'kitsu.app'):
                defaults['Accept'] = 'application/vnd.api+json'
            elif host.startswith('api.'):
                defaults['Accept'] = 'application/json'
            request = Request(url, headers={**defaults, **(headers or {})})
            with urlopen(request, timeout=15) as response:
                # Reject a cross-provider redirect before consuming its data.
                if urlsplit(response.url).hostname not in HOSTS:
                    raise Unavailable('blocked', 'Unexpected/verification redirect; no bypass attempted')
                raw = response.read(8_000_001)
                if len(raw) > 8_000_000:
                    raise Unavailable('too_large', 'Response exceeded safe size; not silently truncated')
                body = raw.decode('utf-8-sig')
        except HTTPError as e:
            state = 'auth_required' if e.code == 401 else 'blocked' if e.code == 403 else 'rate_limited' if e.code == 429 else 'unavailable'
            problem = Unavailable(state, f'HTTP {e.code}; no bypass attempted')
            if e.code in (401, 403, 429):
                self.blocked[host] = problem
            raise problem from None
        except (URLError, TimeoutError, UnicodeError):
            problem = Unavailable('network_error', 'Connection/TLS/decoding failed; previous data preserved')
            self.failures[host] = self.failures.get(host, 0) + 1
            if self.failures[host] >= 2:
                self.blocked[host] = problem
            raise problem from None
        self.store.db.execute('INSERT INTO cache VALUES(?,?,?) ON CONFLICT(url) DO UPDATE SET body=excluded.body,checked=excluded.checked',
                              (key, raw, time.time()))
        self.store.db.commit()
        return body

    def json(self, url, headers=None):
        value = json.loads(self.get(url, headers))
        if not isinstance(value, dict):
            raise Unavailable('layout_changed', 'Expected a JSON object')
        return value

    def imdb_dataset(self):
        target = self.store.root / 'title.ratings.tsv.gz'
        if target.exists() and time.time()-target.stat().st_mtime < 24*3600:
            return target
        if self.calls >= self.budget:
            raise Unavailable('pending', 'Request budget reached')
        self.calls += 1
        temporary = target.with_suffix('.downloading')
        try:
            req = Request('https://datasets.imdbws.com/title.ratings.tsv.gz', headers={'User-Agent': UA})
            with urlopen(req, timeout=20) as response, temporary.open('wb') as out:
                total = 0
                while block := response.read(1024*1024):
                    total += len(block)
                    if total > 64*1024*1024:
                        raise Unavailable('too_large', 'IMDb dataset exceeded expected size')
                    out.write(block)
            # Validate before replacing a previous usable cache.
            with gzip.open(temporary, 'rt', encoding='utf-8') as source:
                if source.readline().strip() != 'tconst\taverageRating\tnumVotes':
                    raise Unavailable('layout_changed', 'IMDb dataset schema changed')
                for _ in source:
                    pass
            temporary.replace(target)
            return target
        except (URLError, TimeoutError, EOFError, OSError):
            raise Unavailable('network_error', 'IMDb dataset unavailable; previous ratings retained') from None
        finally:
            temporary.unlink(missing_ok=True)


class Collector:
    def __init__(self, store, network, page_limit=100):
        self.s, self.n, self.limit = store, network, page_limit

    def execute(self, sid, source, kind, function):
        cursor, state, retry, checked = self.s.task(sid, source, kind)
        if time.time() < retry or state == 'complete' and time.time()-checked < 6*3600:
            return
        try:
            function(cursor if state != 'complete' else None)
        except Unavailable as e:
            # Preserve the last COMMITTED page, not the initial cursor of this invocation.
            latest = self.s.task(sid, source, kind)[0]
            self.s.status(sid, source, kind, e.status, str(e), latest,
                          time.time()+3600 if e.status not in ('pending', 'mapping_required', 'key_required') else 0)
        except Exception as e:
            self.s.status(sid, source, kind, 'error', type(e).__name__ + '; previous data preserved', self.s.task(sid, source, kind)[0], time.time()+3600)

    def bgm_rating(self, sid):
        info = self.n.json('https://api.bgm.tv/v0/subjects/' + sid)
        if str(info.get('id')) != sid or info.get('type') != 2:
            raise Unavailable('mapping_required', 'Subject is not the requested anime')
        r = info.get('rating', {})
        self.s.rating(sid, 'bangumi', 'season', r.get('score') if r.get('total', 0) else None,
                      r.get('total'), 10, 'https://bgm.tv/subject/' + sid, distribution=r.get('count'), rank=r.get('rank'),
                      name=info.get('name'), name_cn=info.get('name_cn'), date=info.get('date'), summary=info.get('summary'))
        self.s.status(sid, 'bangumi', 'rating', 'complete')
        return info

    def bgm_pages(self, sid, kind, cursor):
        page = int(cursor or 1)
        route = 'comments' if kind == 'short' else 'reviews'
        parse = parse_bgm_comments if kind == 'short' else parse_bgm_reviews
        for _ in range(self.limit):
            rows, following = parse(self.n.get(f'https://bgm.tv/subject/{sid}/{route}?page={page}'), sid, page)
            for row in rows:
                self.s.review(sid, 'bangumi', row)
            self.s.status(sid, 'bangumi', kind, 'pending' if following else 'complete', cursor=following)
            if not following:
                return
            page = following
        raise Unavailable('pending', 'More pages remain; resume from saved cursor')

    def bgm_full(self, sid):
        pending = self.s.db.execute("SELECT body FROM reviews WHERE subject=? AND source='bangumi' AND kind='long'", (sid,)).fetchall()
        pending = [json.loads(body) for (body,) in pending if not json.loads(body).get('content_complete')]
        for row in pending[:self.limit]:
            row['content'] = parse_bgm_blog(self.n.get('https://bgm.tv/blog/' + valid_id(row['id'])))
            row['content_complete'] = True
            self.s.review(sid, 'bangumi', row)
        list_state = self.s.task(sid, 'bangumi', 'long')[1]
        complete = list_state == 'complete' and len(pending) <= self.limit
        self.s.status(sid, 'bangumi', 'long_bodies', 'complete' if complete else 'pending',
                      '' if complete else 'More list pages or full article bodies remain')

    def kitsu(self, sid, info):
        kid = self.s.mapped(sid, 'kitsu')
        if kid:
            item = self.n.json('https://kitsu.io/api/edge/anime/' + valid_id(kid)).get('data')
            # Manual mappings are explicit; auto mappings were exact title/year matches.
            if not item or str(item.get('id')) != kid:
                raise Unavailable('mapping_required', 'Kitsu ID not found')
        else:
            query = urlencode({'filter[text]': info['name'], 'page[limit]': 20})
            result = self.n.json('https://kitsu.io/api/edge/anime?' + query)
            item = exact_kitsu(result.get('data', []), info)
            kid = valid_id(item['id'])
            self.s.mapping(sid, 'kitsu', kid, 'unique exact original title and compatible year')
        a = item['attributes']
        distribution = a.get('ratingFrequencies') or {}
        count = sum(int(v) for v in distribution.values()) if distribution else None
        score = float(a['averageRating']) if a.get('averageRating') is not None else None
        self.s.rating(sid, 'kitsu', 'season', score, count, 100, 'https://kitsu.app/anime/' + kid,
                      distribution=distribution, rank=a.get('ratingRank'), summary=a.get('synopsis'))
        self.s.status(sid, 'kitsu', 'rating', 'complete')
        known = {'myanimelist/anime': 'myanimelist', 'imdb': 'imdb', 'themoviedb/tv': 'tmdb', 'anilist/anime': 'anilist'}
        url = 'https://kitsu.io/api/edge/anime/' + kid + '/mappings'
        seen = set()
        while url:
            if url in seen or len(seen)>=50:
                raise Unavailable('layout_changed','Kitsu mapping pagination repeated or exceeded expected size')
            seen.add(url)
            mappings = self.n.json(url)
            for m in mappings.get('data', []):
                attr = m.get('attributes', {})
                if attr.get('externalSite') in known:
                    self.s.mapping(sid, known[attr['externalSite']], attr['externalId'], 'Kitsu provider mapping from ' + kid)
            url = mappings.get('links',{}).get('next')

    def kitsu_reviews(self, sid, cursor):
        kid = self.s.mapped(sid, 'kitsu')
        if not kid:
            raise Unavailable('mapping_required', 'Kitsu subject mapping not confirmed')
        url = cursor or f'https://kitsu.io/api/edge/anime/{valid_id(kid)}/reviews?page%5Blimit%5D=20'
        for _ in range(self.limit):
            value = self.n.json(url)
            if 'data' not in value or not isinstance(value['data'], list):
                raise Unavailable('layout_changed', 'Review response has no data array')
            for item in value['data']:
                a = item.get('attributes', {})
                self.s.review(sid, 'kitsu', dict(id=str(item['id']), kind='long', content=a.get('content', ''),
                    content_complete=True, rating=a.get('rating'), scale=None, published_text=a.get('createdAt'),
                    spoiler=a.get('spoiler'), url='https://kitsu.app/reviews/' + str(item['id']), author_id=item.get('relationships', {}).get('user', {}).get('data', {}).get('id')))
            following = value.get('links', {}).get('next')
            if following == url:
                raise Unavailable('layout_changed', 'Pagination repeated its own URL')
            self.s.status(sid, 'kitsu', 'reviews', 'pending' if following else 'complete', cursor=following)
            if not following:
                return
            url = following
        raise Unavailable('pending', 'More pages remain')

    def mal(self, sid):
        mid = self.s.mapped(sid, 'myanimelist')
        if not mid:
            raise Unavailable('mapping_required', 'MAL subject mapping not confirmed')
        try:
            r = self.n.json('https://api.jikan.moe/v4/anime/' + valid_id(mid))['data']
        except Unavailable as e:
            if e.status != 'network_error':
                raise
            # An explicitly recorded alternative transport, not an unverified mirror.
            self.structured_page(sid, 'myanimelist', 'https://myanimelist.net/anime/' + mid)
            self.s.status(sid, 'myanimelist', 'rating', 'complete', 'Public structured page; Jikan connection unavailable')
            return
        self.s.rating(sid, 'myanimelist', 'season', r.get('score'), r.get('scored_by'), 10,
                      'https://myanimelist.net/anime/' + mid, rank=r.get('rank'), transport='Jikan unofficial API')
        self.s.status(sid, 'myanimelist', 'rating', 'complete')

    def bili_rating(self, sid):
        mid = self.s.mapped(sid, 'bilibili')
        if not mid:
            raise Unavailable('mapping_required', 'Verified main-series Bilibili media ID required')
        j = self.n.json('https://api.bilibili.com/pgc/review/user?media_id=' + valid_id(mid))
        if j.get('code') != 0:
            raise Unavailable('blocked' if j.get('code') in (-412,-352) else 'unavailable', 'Bilibili returned code ' + str(j.get('code')))
        media = j.get('result', {}).get('media', {})
        if str(media.get('media_id')) != mid:
            raise Unavailable('mapping_required', 'Bilibili media ID mismatch')
        r = media.get('rating') or {}
        self.s.rating(sid, 'bilibili', 'provider_item', r.get('score'), r.get('count'), 10,
                      'https://www.bilibili.com/bangumi/media/md' + mid, name=media.get('title'), scope_note='Media edition; not automatically combined with other platforms')
        self.s.status(sid, 'bilibili', 'rating', 'complete')

    def tmdb_target(self, sid):
        token = os.environ.get('TMDB_READ_TOKEN', '')
        if not token:
            raise Unavailable('key_required', 'TMDB_READ_TOKEN is not configured; no account or token created automatically')
        target = self.s.mapped(sid, 'tmdb') or ''
        if not re.fullmatch(r'(tv|movie)/[1-9]\d*', target):
            raise Unavailable('mapping_required', 'Explicit tv/ID or movie/ID mapping required')
        return target, {'Authorization':'Bearer ' + token}

    def tmdb_rating(self, sid):
        target, headers = self.tmdb_target(sid)
        j = self.n.json('https://api.themoviedb.org/3/' + target, headers)
        self.s.rating(sid, 'tmdb', 'series' if target.startswith('tv/') else 'movie',
                      j.get('vote_average') if j.get('vote_count') else None, j.get('vote_count'), 10,
                      'https://www.themoviedb.org/' + target, name=j.get('name',j.get('title')),
                      scope_note='Series-level score is not the current season score')
        self.s.status(sid, 'tmdb', 'rating', 'complete')

    def tmdb_reviews(self, sid, cursor):
        target, headers = self.tmdb_target(sid)
        languages = ['zh-CN','zh-TW','en-US','ja-JP']
        language_index = (cursor or {}).get('language', 0)
        page = (cursor or {}).get('page', 1)
        for _ in range(self.limit):
            language = languages[language_index]
            j = self.n.json('https://api.themoviedb.org/3/' + target + '/reviews?' + urlencode(dict(language=language,page=page)), headers)
            if not isinstance(j.get('results'),list):
                raise Unavailable('layout_changed','TMDB review results missing')
            for row in j['results']:
                self.s.review(sid,'tmdb',dict(id=str(row['id']),kind='long',content=row.get('content',''),content_complete=True,
                    author=row.get('author'),rating=row.get('author_details',{}).get('rating'),scale=10,
                    published_text=row.get('created_at'),url=row.get('url'),spoiler=None,requested_language=language,scope='series' if target.startswith('tv/') else 'movie'))
            if page < j.get('total_pages',0):
                page += 1
            else:
                language_index += 1
                page = 1
            if language_index == len(languages):
                self.s.status(sid,'tmdb','reviews','partial','All pages of zh-CN, zh-TW, en-US, ja-JP collected; not all world languages',{'languages':languages})
                return
            self.s.status(sid,'tmdb','reviews','pending',cursor={'language':language_index,'page':page})
        raise Unavailable('pending','More language/page combinations remain')

    def bili_reviews(self, sid, kind, cursor):
        mid = self.s.mapped(sid, 'bilibili')
        if not mid:
            raise Unavailable('mapping_required', 'Verified Bilibili media ID required')
        for _ in range(self.limit):
            query = dict(media_id=valid_id(mid), ps=20, sort=0)
            if cursor is not None:
                query['cursor'] = cursor
            j = self.n.json(f'https://api.bilibili.com/pgc/review/{kind}/list?' + urlencode(query))
            if j.get('code') != 0:
                raise Unavailable('blocked' if j.get('code') in (-412,-352) else 'unavailable', 'Bilibili returned code ' + str(j.get('code')))
            d = j.get('data', {})
            if not isinstance(d.get('list'), list):
                raise Unavailable('layout_changed', 'Bilibili review list schema changed')
            rows = d['list']
            for r in rows:
                if str(r.get('media_id', mid)) != mid:
                    raise Unavailable('mapping_required', 'Review belongs to a different media item')
                rid = str(r['review_id'])
                self.s.review(sid, 'bilibili', dict(id=rid, kind=kind, title=r.get('title'), content=r.get('content', r.get('summary', '')),
                    content_complete=kind=='short', author=r.get('author', {}).get('uname'), author_id=str(r.get('mid', '')),
                    rating=r.get('score'), scale=10, published_text=str(r.get('ctime', '')), spoiler=None,
                    url=r.get('url') or ('https://www.bilibili.com/bangumi/media/md' + mid), likes=r.get('stat', {}).get('liked')))
            following = d.get('next')
            # Unknown cursor structures must not be mistaken for the last page.
            if rows and following is None:
                self.s.status(sid, 'bilibili', kind, 'partial', 'No next cursor supplied; completeness unverified', cursor)
                return
            if isinstance(following, dict):
                following = following.get('cursor')
                if following is None:
                    raise Unavailable('layout_changed', 'Bilibili cursor schema changed')
            if rows and following == cursor:
                raise Unavailable('layout_changed', 'Bilibili cursor repeated')
            more = bool(rows) and following not in (None, 0, '0', '')
            self.s.status(sid, 'bilibili', kind, 'pending' if more else 'complete', cursor=following if more else None)
            if not more:
                return
            cursor = following
        raise Unavailable('pending', 'More pages remain')

    def bili_full(self, sid):
        if not self.s.mapped(sid,'bilibili'):
            raise Unavailable('mapping_required','No verified Bilibili media mapping')
        rows = self.s.db.execute("SELECT body FROM reviews WHERE subject=? AND source='bilibili' AND kind='long'",(sid,)).fetchall()
        rows = [json.loads(body) for (body,) in rows if not json.loads(body).get('content_complete')]
        for row in rows[:self.limit]:
            url = row.get('url','')
            if not re.fullmatch(r'https://www\.bilibili\.com/read/cv\d+/?',url):
                raise Unavailable('mapping_required','No canonical public article URL; excerpt retained')
            soup = BeautifulSoup(self.n.get(url),'html.parser')
            node = soup.select_one('.article-holder')
            if not node or not text(node):
                raise Unavailable('blocked','Public article body unavailable; list excerpt retained, no challenge bypass')
            for child in node.select('script,style'):
                child.decompose()
            row['content'] = text(node)
            row['content_complete'] = True
            self.s.review(sid,'bilibili',row)
        complete = len(rows)<=self.limit and self.s.task(sid,'bilibili','long')[1]=='complete'
        self.s.status(sid,'bilibili','long_bodies','complete' if complete else 'pending',
                      '' if complete else 'List pages or article bodies remain')

    def imdb_ratings(self):
        targets = {}
        for sid, external in self.s.db.execute("SELECT subject,external_id FROM mappings WHERE source='imdb'"):
            if re.fullmatch(r'tt\d+', external):
                targets.setdefault(external, []).append(sid)
        if not targets:
            return
        try:
            dataset = self.n.imdb_dataset()
            with gzip.open(dataset, 'rt', encoding='utf-8', newline='') as f:
                for row in csv.DictReader(f, delimiter='\t'):
                    if row['tconst'] in targets:
                        for sid in targets.pop(row['tconst']):
                            self.s.rating(sid, 'imdb', 'provider_item', float(row['averageRating']), int(row['numVotes']), 10,
                                          'https://www.imdb.com/title/' + row['tconst'] + '/', transport='IMDb official non-commercial dataset')
                            self.s.status(sid, 'imdb', 'rating', 'complete')
            for subjects in targets.values():
                for sid in subjects:
                    self.s.status(sid, 'imdb', 'rating', 'not_found', 'ID has no rating in the downloaded dataset')
        except Unavailable as e:
            for subjects in targets.values():
                for sid in subjects:
                    self.s.status(sid, 'imdb', 'rating', e.status, str(e))

    def mal_reviews(self, sid, cursor):
        mid = self.s.mapped(sid, 'myanimelist')
        if not mid:
            raise Unavailable('mapping_required', 'MAL subject mapping not confirmed')
        page = int(cursor or 1)
        for _ in range(self.limit):
            j = self.n.json(f'https://api.jikan.moe/v4/anime/{valid_id(mid)}/reviews?page={page}')
            for row in j['data']:
                self.s.review(sid, 'myanimelist', dict(id=str(row['mal_id']), kind='long', content=row.get('review', ''),
                    content_complete=True, rating=row.get('score'), scale=10, author=row.get('user', {}).get('username'),
                    published_text=row.get('date'), spoiler=row.get('is_spoiler'), url=row.get('url'), transport='Jikan unofficial API'))
            more = j.get('pagination', {}).get('has_next_page', False)
            self.s.status(sid, 'myanimelist', 'reviews', 'pending' if more else 'complete', cursor=page+1 if more else None)
            if not more:
                return
            page += 1
        raise Unavailable('pending', 'More pages remain')

    def structured_page(self, sid, source, url):
        soup = BeautifulSoup(self.n.get(url), 'html.parser')
        objects = []
        for script in soup.select('script[type="application/ld+json"]'):
            try:
                value = json.loads(script.string or script.get_text())
                objects += value if isinstance(value, list) else value.get('@graph', [value])
            except (ValueError, AttributeError):
                continue
        rated = [o for o in objects if isinstance(o, dict) and isinstance(o.get('aggregateRating'), dict)]
        if len(rated) != 1:
            raise Unavailable('layout_changed', 'No unique structured rating; page is not a zero score')
        obj = rated[0]
        r = obj['aggregateRating']
        self.s.rating(sid, source, 'provider_item', r.get('ratingValue'), r.get('ratingCount', r.get('reviewCount')),
                      r.get('bestRating'), url, name=obj.get('name'), identity='explicit mapping; scope requires verification')
        reviews = obj.get('review', [])
        if isinstance(reviews, dict):
            reviews = [reviews]
        for row in reviews:
            content = row.get('reviewBody', '')
            if not content:
                continue
            author = row.get('author', {})
            author = author.get('name') if isinstance(author, dict) else str(author)
            rid = row.get('url') or hashlib.sha256((str(author)+content).encode()).hexdigest()
            self.s.review(sid, source, dict(id=rid, kind='embedded', author=author, content=content,
                content_complete=True, rating=row.get('reviewRating', {}).get('ratingValue'),
                published_text=row.get('datePublished'), url=row.get('url', url), spoiler=None))
        self.s.status(sid, source, 'structured_page', 'partial', 'Only page-embedded reviews collected; not all reviews')

    def run_ratings(self, sid):
        info = None
        def bgm(_):
            nonlocal info
            info = self.bgm_rating(sid)
        self.execute(sid, 'bangumi', 'rating', bgm)
        if info is None:
            try:
                info = self.n.json('https://api.bgm.tv/v0/subjects/' + sid)
            except Exception:
                pass
        if info:
            self.execute(sid, 'kitsu', 'rating', lambda _: self.kitsu(sid, info))
        self.execute(sid, 'myanimelist', 'rating', lambda _: self.mal(sid))
        # Do not silently sign up for services or infer an unrelated movie/season ID.
        for source, template in [('douban', 'https://movie.douban.com/subject/{}/'), ('imdb', 'https://www.imdb.com/title/{}/'),
                                 ('animeplanet', 'https://www.anime-planet.com/anime/{}'), ('anisearch', 'https://www.anisearch.com/anime/{}')]:
            external = self.s.mapped(sid, source)
            if external and re.fullmatch(r'[A-Za-z0-9_-]+', external):
                self.execute(sid, source, 'structured_page', lambda _, src=source, u=template.format(external): self.structured_page(sid, src, u))
            else:
                self.s.status(sid, source, 'structured_page', 'mapping_required', 'No verified provider item ID; no fuzzy substitution')
        self.execute(sid, 'tmdb', 'rating', lambda _: self.tmdb_rating(sid))
        self.execute(sid, 'bilibili', 'rating', lambda _: self.bili_rating(sid))
        self.s.status(sid, 'anilist', 'rating_reviews', 'permission_required', 'Tracker use restrictions must be resolved before enabling API acquisition')

    def run_reviews(self, sid):
        for kind in ('short', 'long'):
            self.execute(sid, 'bangumi', kind, lambda cursor, k=kind: self.bgm_pages(sid, k, cursor))
        # Newly discovered excerpts invalidate an earlier empty/complete article task.
        bodies = self.s.db.execute("SELECT body FROM reviews WHERE subject=? AND source='bangumi' AND kind='long'", (sid,)).fetchall()
        if any(not json.loads(body).get('content_complete') for (body,) in bodies) and self.s.task(sid,'bangumi','long_bodies')[1]=='complete':
            self.s.status(sid,'bangumi','long_bodies','pending','New full articles await acquisition')
        self.execute(sid, 'bangumi', 'long_bodies', lambda _: self.bgm_full(sid))
        self.execute(sid, 'kitsu', 'reviews', lambda cursor: self.kitsu_reviews(sid, cursor))
        self.execute(sid, 'myanimelist', 'reviews', lambda cursor: self.mal_reviews(sid, cursor))
        for kind in ('short','long'):
            self.execute(sid, 'bilibili', kind, lambda cursor, k=kind: self.bili_reviews(sid, k, cursor))
        rows = self.s.db.execute("SELECT body FROM reviews WHERE subject=? AND source='bilibili' AND kind='long'",(sid,)).fetchall()
        if any(not json.loads(body).get('content_complete') for (body,) in rows) and self.s.task(sid,'bilibili','long_bodies')[1]=='complete':
            self.s.status(sid,'bilibili','long_bodies','pending')
        self.execute(sid,'bilibili','long_bodies',lambda _:self.bili_full(sid))
        self.execute(sid,'tmdb','reviews',lambda cursor:self.tmdb_reviews(sid,cursor))


def import_site_mappings(store, file, subjects):
    """Bangumi-data is an explicit cross-site mapping, not a title-search guess."""
    if not file.exists():
        return
    data = json.loads(file.read_text(encoding='utf-8-sig'))
    allowed = {'mal':'myanimelist', 'tmdb':'tmdb', 'anidb':'anidb', 'aniList':'anilist', 'bilibili':'bilibili'}
    for item in data['items']:
        bgm = [str(v['id']) for v in item['sites'] if v['site']=='bangumi' and str(v['id']) in subjects]
        for sid in bgm:
            for site in item['sites']:
                source = allowed.get(site['site'])
                if not source:
                    continue
                # Some series entries link only an SP edition; its score is NOT the main series score.
                if source == 'bilibili' and site.get('comment'):
                    store.status(sid,source,'mapping','scope_mismatch','Edition annotation requires review; main-series mapping not assumed')
                    continue
                try:
                    store.mapping(sid,source,site['id'],'bangumi-data explicit cross-reference')
                except Unavailable as e:
                    store.status(sid,source,'mapping',e.status,str(e))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=Path('config/simple-tracking.json'))
    parser.add_argument('--output', type=Path, default=Path('config/opinions'))
    parser.add_argument('--subject', action='append', default=[], help='Additional Bangumi ID; does not follow/download it')
    parser.add_argument('--mappings', type=Path, help='JSON: {"BangumiID":{"kitsu":"ID","myanimelist":"ID",...}}; explicit verified IDs only')
    parser.add_argument('--site-map', type=Path, help='Optional bangumi-data dist/data.json, obtained from its documented distribution')
    parser.add_argument('--retry-network-errors', action='store_true', help='One explicit retry pass for transient failures; never clears auth/403/429 backoff')
    parser.add_argument('--pages-per-source', type=int, default=5)
    parser.add_argument('--rounds', type=int, default=1, help='Fair pagination rounds across subjects/providers')
    parser.add_argument('--request-budget', type=int, default=300)
    args = parser.parse_args()
    if not 1 <= args.pages_per_source <= 1000 or not 1 <= args.request_budget <= 10000 or not 1 <= args.rounds <= 1000:
        parser.error('Pagination and network budgets must be positive and bounded')
    state = json.loads(args.state.read_text(encoding='utf-8-sig'))
    ids = list(dict.fromkeys([valid_id(s['id']) for s in state['shows']] + [valid_id(v) for v in args.subject]))
    store = Store(args.output)
    store.db.execute("DELETE FROM tasks WHERE source IN ('tmdb','bilibili') AND kind='rating_reviews'")
    store.db.commit()
    if args.retry_network_errors:
        store.db.execute("UPDATE tasks SET retry=0 WHERE state='network_error'")
        store.db.commit()
    if args.site_map:
        import_site_mappings(store,args.site_map,set(ids))
    if args.mappings:
        for sid, sources in json.loads(args.mappings.read_text(encoding='utf-8-sig')).items():
            for source, external in sources.items():
                if source not in {'kitsu','myanimelist','imdb','douban','tmdb','animeplanet','anisearch','bilibili'}:
                    raise ValueError('Unsupported mapping source')
                store.mapping(valid_id(sid), source, external, 'explicit mapping file')
    network = Network(store, args.request_budget)
    collector = Collector(store, network, args.pages_per_source)
    for sid in ids:
        print(json.dumps({'phase':'ratings','subject': sid, 'requests': network.calls}), flush=True)
        collector.run_ratings(sid)
        store.export()
    collector.imdb_ratings()
    for round_index in range(args.rounds):
        before = list(store.db.execute("SELECT subject,source,kind,cursor FROM tasks WHERE state='pending' ORDER BY subject,source,kind"))
        for sid in ids:
            print(json.dumps({'phase':'reviews','round':round_index+1,'subject':sid,'requests':network.calls}),flush=True)
            collector.run_reviews(sid)
            store.export()
        after = list(store.db.execute("SELECT subject,source,kind,cursor FROM tasks WHERE state='pending' ORDER BY subject,source,kind"))
        if network.calls >= network.budget or not after or before == after:
            break
    print(json.dumps({'subjects': len(ids), 'networkRequests': network.calls, 'sources': store.export()}), flush=True)


if __name__ == '__main__':
    main()
