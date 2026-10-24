import json
from pathlib import Path
import tempfile
import gzip
import csv
import unittest
from unittest.mock import patch
import collect_opinions as c


class OpinionCollectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = c.Store(Path(self.temp.name))

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def test_chinese_comment_rating_and_page_are_preserved(self):
        html = '''<div id="comment_box"><div class="item" data-item-user="u">
          <a class="l" href="/user/u">用户</a><span class="starlight stars8"></span>
          <small class="grey">@ 昨天</small><p class="comment">中文<br>第二行</p>
          <div id="likes_grid_123"></div></div></div>
          <div class="page_inner"><a href="?page=2">2</a></div>'''
        rows, page = c.parse_bgm_comments(html, '253', 1)
        self.assertEqual(2, page)
        self.assertEqual('123', rows[0]['id'])
        self.assertEqual('中文\n第二行', rows[0]['content'])
        self.assertEqual(8, rows[0]['rating'])
        self.assertIsNone(rows[0]['spoiler'])

    def test_layout_failure_is_not_an_empty_complete_comment_set(self):
        with self.assertRaises(c.Unavailable):
            c.parse_bgm_comments('<html>Verify you are human</html>', '1', 1)
        self.assertEqual(([], None), c.parse_bgm_comments('<div id="comment_box"></div>', '1', 1))

    def test_long_review_excerpts_never_pretend_to_be_full_articles(self):
        rows, _ = c.parse_bgm_reviews('''<div id="entry_list"><div class="item" data-item-user="u">
          <h2><a href="/blog/17">标题</a></h2><div class="content">摘要…</div>
          <div class="tools"><div class="time"><a href="/user/u">作者</a>2026</div></div></div></div>''', '1', 1)
        self.assertFalse(rows[0]['content_complete'])
        self.assertEqual('17', rows[0]['id'])
        self.assertEqual('正文', c.parse_bgm_blog('<div id="entry_content">正文<script>bad()</script></div>'))
        with self.assertRaises(c.Unavailable): c.parse_bgm_blog('<p>Login required</p>')

    def test_exact_mapping_rejects_wrong_year_and_ambiguous_same_title(self):
        def item(i, year):return dict(id=i, attributes=dict(titles={'ja_jp':'テスト 第二期'},startDate=year+'-01-01'))
        subject = dict(name='テスト 第二期', date='2026-01-01')
        self.assertEqual('2', c.exact_kitsu([item('1','2025'),item('2','2026')], subject)['id'])
        with self.assertRaises(c.Unavailable):c.exact_kitsu([item('1','2025')],subject)
        with self.assertRaises(c.Unavailable):c.exact_kitsu([item('1','2026'),item('2','2026')],subject)

    def test_resume_keeps_last_committed_page_after_network_failure(self):
        class Net:
            def get(self, url):
                if 'page=2' in url:raise c.Unavailable('network_error','fixture')
                return '<div id="comment_box"></div><div class="page_inner"><a href="?page=2">2</a></div>'
        collector=c.Collector(self.store,Net())
        collector.execute('1','bangumi','short',lambda cursor:collector.bgm_pages('1','short',cursor))
        cursor,state,_,_=self.store.task('1','bangumi','short')
        self.assertEqual(2,cursor)
        self.assertEqual('network_error',state)

    def test_source_deduplication_preserves_full_text_over_new_excerpt(self):
        full=dict(id='1',kind='long',content='完整原文',content_complete=True)
        self.store.review('1','bangumi',full)
        self.store.review('1','bangumi',{**full,'content':'摘要','content_complete':False})
        self.assertEqual(1,self.store.db.execute('SELECT count(*) FROM reviews').fetchone()[0])
        row=json.loads(self.store.db.execute('SELECT body FROM reviews').fetchone()[0])
        self.assertEqual('完整原文',row['content'])
        self.store.review('1','myanimelist',full)
        self.assertEqual(2,self.store.db.execute('SELECT count(*) FROM reviews').fetchone()[0])

    def test_budget_failure_has_no_fake_zero_rating_and_exports_status(self):
        collector=c.Collector(self.store,c.Network(self.store,budget=0))
        collector.execute('1','bangumi','rating',lambda _:collector.bgm_rating('1'))
        self.assertEqual(0,self.store.db.execute('SELECT count(*) FROM ratings').fetchone()[0])
        summary=self.store.export()
        self.assertEqual(['pending'],summary['bangumi']['states'])

    def test_only_https_allowlisted_provider_urls_are_requested(self):
        network=c.Network(self.store)
        for url in ['http://api.bgm.tv/v0/subjects/1','https://127.0.0.1/private','https://evil.test/']:
            with self.assertRaises(c.Unavailable):network.get(url)
        self.assertEqual(0,network.calls)

    def test_mal_review_pagination_collects_all_pages_and_deduplicates(self):
        self.store.mapping('1','myanimelist','1','fixture')
        class Net:
            def json(self,url):
                page=2 if 'page=2' in url else 1
                return {'data':[{'mal_id':page,'review':'text','score':8,'user':{'username':'name'}}],
                        'pagination':{'has_next_page':page==1}}
        c.Collector(self.store,Net()).mal_reviews('1',None)
        self.assertEqual(2,self.store.db.execute('SELECT count(*) FROM reviews').fetchone()[0])
        self.assertEqual('complete',self.store.task('1','myanimelist','reviews')[1])

    def test_bilibili_preserves_score_and_follows_next_cursor(self):
        self.store.mapping('1','bilibili','3375','fixture')
        calls=[]
        class Net:
            def json(self,url):
                calls.append(url)
                if 'review/user' in url:
                    return dict(code=0,result=dict(media=dict(media_id=3375,rating=dict(score=9.7,count=8000))))
                if 'cursor=90' in url:return dict(code=0,data=dict(list=[],next=0,total=1))
                return dict(code=0,data=dict(list=[dict(review_id=7,media_id=3375,content='中文',score=10)],next=90,total=1))
        collector=c.Collector(self.store,Net());collector.bili_rating('1');collector.bili_reviews('1','short',None)
        row=json.loads(self.store.db.execute("SELECT body FROM ratings WHERE source='bilibili'").fetchone()[0])
        self.assertEqual(9.7,row['score']);self.assertEqual(8000,row['votes'])
        self.assertTrue(any('cursor=90' in url for url in calls))
        self.assertEqual('complete',self.store.task('1','bilibili','short')[1])

    def test_bilibili_risk_response_is_not_saved_as_empty_reviews(self):
        self.store.mapping('1','bilibili','1','fixture')
        class Net:
            def json(self,url):return dict(code=-412)
        collector=c.Collector(self.store,Net())
        collector.execute('1','bilibili','short',lambda cursor:collector.bili_reviews('1','short',cursor))
        self.assertEqual('blocked',self.store.task('1','bilibili','short')[1])

    def test_sp_cross_reference_is_not_a_main_series_mapping(self):
        file=Path(self.temp.name)/'sites.json'
        file.write_text(json.dumps({'items':[{'sites':[{'site':'bangumi','id':'253'},
             {'site':'bilibili','id':'5383','comment':'SP'},{'site':'mal','id':'1'}]}]}),encoding='utf-8')
        c.import_site_mappings(self.store,file,{'253'})
        self.assertIsNone(self.store.mapped('253','bilibili'))
        self.assertEqual('1',self.store.mapped('253','myanimelist'))
        self.assertEqual('scope_mismatch',self.store.task('253','bilibili','mapping')[1])

    def test_conflicting_automatic_mapping_does_not_replace_explicit_one(self):
        self.store.mapping('1','kitsu','10','explicit mapping file')
        with self.assertRaises(c.Unavailable):self.store.mapping('1','kitsu','11','automatic fixture')
        self.assertEqual('10',self.store.mapped('1','kitsu'))

    def test_imdb_dataset_keeps_multiple_subjects_mapping_to_same_series_separate(self):
        file=Path(self.temp.name)/'fixture.gz'
        with gzip.open(file,'wt',encoding='utf-8') as out:out.write('tconst\taverageRating\tnumVotes\ntt1\t8.9\t12000\n')
        self.store.mapping('1','imdb','tt1','fixture');self.store.mapping('2','imdb','tt1','fixture')
        class Net:
            def imdb_dataset(self):return file
        c.Collector(self.store,Net()).imdb_ratings()
        self.assertEqual(2,self.store.db.execute("SELECT count(*) FROM ratings WHERE source='imdb'").fetchone()[0])
        self.assertEqual('provider_item',self.store.db.execute('SELECT scope FROM ratings LIMIT 1').fetchone()[0])

    def test_provider_content_negotiation_and_utf8_cache(self):
        class Response:
            url='https://kitsu.io/api/edge/anime/1'
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def read(self,*args):return '{"data":"中文"}'.encode('utf-8')
        network=c.Network(self.store)
        with patch('collect_opinions.urlopen',return_value=Response()) as remote:
            self.assertEqual('中文',network.json(Response.url)['data'])
            self.assertEqual('application/vnd.api+json',remote.call_args[0][0].get_header('Accept'))
            self.assertEqual('中文',network.json(Response.url)['data'])
            self.assertEqual(1,remote.call_count)

    def test_unrated_subject_is_not_a_zero_out_of_ten_rating(self):
        class Net:
            def json(self,url):return dict(id=1,type=2,rating=dict(score=0,total=0))
        c.Collector(self.store,Net()).bgm_rating('1')
        row=json.loads(self.store.db.execute('SELECT body FROM ratings').fetchone()[0])
        self.assertIsNone(row['score']);self.assertEqual(0,row['votes'])

    def test_new_excerpt_invalidates_old_empty_complete_body_task(self):
        self.store.review('1','bangumi',dict(id='17',kind='long',content='摘要',content_complete=False))
        for source,kind in [('bangumi','short'),('bangumi','long'),('bangumi','long_bodies'),('kitsu','reviews'),
                            ('myanimelist','reviews'),('bilibili','short'),('bilibili','long'),('bilibili','long_bodies'),('tmdb','reviews')]:
            self.store.status('1',source,kind,'complete')
        class Net:
            def get(self,url):return '<div id="entry_content">完整正文</div>'
        c.Collector(self.store,Net()).run_reviews('1')
        body=json.loads(self.store.db.execute('SELECT body FROM reviews').fetchone()[0])
        self.assertTrue(body['content_complete']);self.assertEqual('完整正文',body['content'])

    def test_tmdb_without_token_is_explicit_and_makes_no_request(self):
        with patch.dict('os.environ',{},clear=True):
            with self.assertRaises(c.Unavailable) as error:c.Collector(self.store,None).tmdb_target('1')
        self.assertEqual('key_required',error.exception.status)

    def test_export_keeps_sources_separate_and_neutralizes_csv_formulas(self):
        self.store.rating('1','bangumi','season',8.5,100,10,'https://bgm.tv/subject/1',name_cn='=untrusted')
        self.store.status('1','bangumi','rating','complete')
        summary=self.store.export()
        self.assertEqual(1,summary['bangumi']['ratedItems'])
        with (Path(self.temp.name)/'ratings.csv').open(encoding='utf-8-sig',newline='') as f:rows=list(csv.reader(f))
        self.assertEqual("'=untrusted",rows[1][1])
        self.assertTrue((Path(self.temp.name)/'source-status.csv').exists())
        self.assertTrue((Path(self.temp.name)/'reviews.jsonl').exists())


if __name__ == '__main__':unittest.main()
