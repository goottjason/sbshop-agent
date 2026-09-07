import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('collect-marketplus-transmissions.py'))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class CollectorTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = dict(kind='transmissionHistory', ready=True, busy=False, visibleRowCount=1,
                             capturedAt='2026-09-06T07:03:00Z', selectedTab='전송실패',
                             url='https://mp.cafe24.com/mp/queue/productList?page=1&queue_status=F', rows=[
                                 dict(cells=['5847', 'seller-g', 'P0000UVU', '3490363781', '상품명', '상품수정',
                                             '[실패] 상세 확인 필요', '2026-09-06 16:02', '2026-09-06 16:02'],
                                      marketIcons=['Gmarket_svg_1'], products=[dict(productCode='P0000UVU', productNo='14086', shopNo='1')])])

    def test_korean_time_and_only_allowlisted_fields(self):
        value = collector.normalize(self.snapshot, 'testmall')
        self.assertEqual('CURRENT_PAGE', value['coverage'])
        self.assertEqual('2026-09-06T07:02:00+00:00', value['rows'][0]['completedAt'])
        self.assertNotIn('5847', str(value))  # UI ordinal is not a job ID.
        self.assertNotIn('상품명', str(value))

    def test_loading_wrong_tab_empty_and_truncated_pages_rejected(self):
        for overrides in [dict(ready=False), dict(busy=True), dict(rows=[]), dict(visibleRowCount=101),
                          dict(selectedTab='전송완료'), dict(url='https://other.example/mp/queue/productList?queue_status=F')]:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                collector.normalize({**self.snapshot, **overrides}, 'testmall')

    def test_ambiguous_identity_or_market_rejected(self):
        for key, value in [('products', []), ('marketIcons', ['Gmarket_svg_1', 'Auction_svg_1'])]:
            changed = copy.deepcopy(self.snapshot)
            changed['rows'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                collector.normalize(changed, 'testmall')

    def test_default_completed_url_requires_selected_tab_and_success_rows(self):
        value = copy.deepcopy(self.snapshot)
        value.update(selectedTab='전송완료', url='https://mp.cafe24.com/mp/queue/productList')
        value['rows'][0]['cells'][6] = '[성공] 전송이 완료되었습니다.'
        self.assertEqual('SUCCESS', collector.normalize(value, 'testmall')['rows'][0]['outcome'])
        for query in ('?queue_status=F', '?queue_status=', '?queue_status=S&queue_status=F', '?queue_status=S&queue_status=S'):
            with self.subTest(query=query), self.assertRaises(ValueError):
                collector.normalize({**value, 'url': value['url'] + query}, 'testmall')
        with self.assertRaises(ValueError):
            collector.normalize({**self.snapshot, 'url': value['url']}, 'testmall')

    def test_generated_market_icon_suffixes_do_not_change_identity(self):
        for prefix, market in [('Gmarket', 'GMARKET'), ('Auction', 'AUCTION')]:
            for suffix in ('', '_1', '_2', '_20'):
                with self.subTest(prefix=prefix, suffix=suffix):
                    self.snapshot['rows'][0]['marketIcons'] = [prefix + '_svg' + suffix]
                    self.assertEqual(market, collector.normalize(self.snapshot, 'testmall')['rows'][0]['market'])
        for icons in (['OtherAuction_svg'], ['Auction_svg_extra'], ['Auction_svg_2', 'Gmarket_svg']):
            self.snapshot['rows'][0]['marketIcons'] = icons
            with self.subTest(icons=icons), self.assertRaises(ValueError):
                collector.normalize(self.snapshot, 'testmall')

    def test_single_bad_row_aborts_export_instead_of_silently_dropping_it(self):
        changed = copy.deepcopy(self.snapshot)
        changed['rows'].append(dict(cells=['loading'], products=[], marketIcons=[]))
        changed['visibleRowCount'] = 2
        with self.assertRaises(ValueError):
            collector.normalize(changed, 'testmall')

    def test_impossible_clock_order_rejected(self):
        self.snapshot['rows'][0]['cells'][8] = '2026-09-06 16:04'
        with self.assertRaises(ValueError):
            collector.normalize(self.snapshot, 'testmall')

    def page(self, page, ordinal, has_next=True):
        value = copy.deepcopy(self.snapshot)
        value['pagination'] = dict(page=page, hasNext=has_next)
        value['url'] = f'https://mp.cafe24.com/mp/queue/productList?page={page}&queue_status=F'
        value['rows'][0]['cells'][0] = str(ordinal)
        value['rows'][0]['cells'][3] = f'34903637{page:02d}'
        return value

    def test_multiple_pages_preserve_explicit_partial_limit(self):
        pages = iter([self.page(1, 3), self.page(2, 2)])
        moves, saved = [], []
        run = collector.collect('testmall', 2, read=lambda: next(pages), next_page=lambda: moves.append(1), persist=lambda r: saved.append(copy.deepcopy(r)))
        self.assertEqual('PARTIAL', run['status'])
        self.assertEqual('PAGE_LIMIT', run['stopReason'])
        self.assertEqual([1, 2], [p['page'] for p in run['pages']])
        self.assertEqual(1, len(moves))
        self.assertEqual(2, len(saved[-1]['pages']))

    def test_resume_validates_last_page_then_moves_forward(self):
        run = collector.collect('testmall', 1, read=lambda: self.page(1, 2), next_page=lambda: None)
        pages = iter([self.page(1, 2), self.page(2, 1, False)])
        resumed = collector.collect('testmall', 2, read=lambda: next(pages), next_page=lambda: None, resume=run)
        self.assertEqual('REACHED_LAST_PAGE', resumed['status'])
        self.assertEqual(2, len(resumed['pages']))

    def test_changed_search_or_shifted_rows_stop_before_storing_bad_page(self):
        for change in ('search', 'shift', 'skip'):
            bad = self.page(2, 2)
            if change == 'search': bad['filters'] = [dict(placeholder='', value='changed')]
            if change == 'shift': bad['rows'][0]['cells'][0] = '3'
            if change == 'skip': bad = self.page(3, 2)
            pages = iter([self.page(1, 3), bad])
            run = collector.collect('testmall', 3, read=lambda: next(pages), next_page=lambda: None)
            self.assertEqual('PARTIAL', run['status'])
            self.assertEqual(1, len(run['pages']))
            self.assertNotEqual('PAGE_LIMIT', run['stopReason'])

    def test_read_failure_preserves_completed_pages_for_retry(self):
        calls = []
        def read():
            calls.append(1)
            if len(calls) > 1: raise ValueError('LOGIN_REQUIRED')
            return self.page(1, 3)
        run = collector.collect('testmall', 3, read=read, next_page=lambda: None)
        self.assertEqual('PARTIAL', run['status'])
        self.assertEqual('LOGIN_REQUIRED', run['stopReason'])
        self.assertEqual(1, len(run['pages']))

    def test_last_page_indicator_without_ordinal_one_is_not_completed(self):
        run = collector.collect('testmall', 1, read=lambda: self.page(1, 9, False), next_page=lambda: None)
        self.assertEqual('PARTIAL', run['status'])

    def test_unstable_loading_requires_two_matching_reads(self):
        loading = self.page(1, 2); loading['ready'] = False
        final = self.page(2, 1, False)
        reads = iter([loading, final, final])
        self.assertEqual(2, collector.stable_snapshot(read=lambda: next(reads), sleep=lambda _: None)['pagination']['page'])

    def test_interrupt_preserves_pages_and_marks_partial(self):
        calls = []
        def read():
            calls.append(1)
            if len(calls) > 1: raise KeyboardInterrupt()
            return self.page(1, 3)
        run = collector.collect('testmall', 3, read=read, next_page=lambda: None)
        self.assertEqual('PARTIAL', run['status'])
        self.assertEqual('INTERRUPTED', run['stopReason'])
        self.assertEqual(1, len(run['pages']))


if __name__ == '__main__':
    unittest.main()
