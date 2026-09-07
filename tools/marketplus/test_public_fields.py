from datetime import datetime, timezone
import unittest
import public_fields


class PublicFieldsTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 9, 7, 10, 0, tzinfo=timezone.utc)
        self.snapshot = dict(schemaVersion=1, source='LIVE_CHROME_PUBLIC_MARKET', market='AUCTION', externalId='D888859044',
            url='https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888859044', productIds=['D888859044'],
            sellerAccounts=['seller-fixture'], prices=['95500'], quantities=['500'], errorPage=False,
            quantityBasis='PUBLIC_NO_OPTION_REMAINING', capturedAt=self.now.isoformat())

    def parse(self, snapshot=None):
        return public_fields.parse(snapshot or self.snapshot, 'AUCTION', 'D888859044', 'seller-fixture', self.now)

    def test_typed_price_and_no_option_remaining_quantity_do_not_imply_listing_state(self):
        value = self.parse()
        self.assertEqual(value['values'], {'salePrice': '95500', 'salesQuantity': '500'})
        self.assertEqual(value['listingState'], 'UNVERIFIED')

    def test_wrong_or_ambiguous_product_and_seller_are_rejected(self):
        for patch in [dict(productIds=['other']), dict(productIds=['D888859044', 'other']), dict(sellerAccounts=[]),
                      dict(sellerAccounts=['other']), dict(sellerAccounts=['seller-fixture', 'other'])]:
            with self.subTest(patch=patch), self.assertRaises(public_fields.PublicFieldError): self.parse(self.snapshot | patch)

    def test_error_or_empty_page_never_becomes_deletion_or_success(self):
        for patch in [dict(errorPage=True), dict(errorPage=None), dict(prices=[]), dict(productIds=[])]:
            with self.subTest(patch=patch), self.assertRaises(public_fields.PublicFieldError): self.parse(self.snapshot | patch)

    def test_coupon_price_duplicates_and_invalid_numbers_are_not_guessed(self):
        for prices in [['95500', '95500'], ['95500', '85000'], ['95,500'], ['-1'], ['1e5']]:
            with self.subTest(prices=prices), self.assertRaises(public_fields.PublicFieldError): self.parse(self.snapshot | dict(prices=prices))

    def test_unverified_option_quantity_is_omitted(self):
        value = self.parse(self.snapshot | dict(quantityBasis='NOT_VERIFIED', quantities=['500']))
        self.assertEqual(value['values'], {'salePrice': '95500'})

    def test_wrong_url_account_target_and_stale_capture_are_rejected(self):
        for patch in [dict(url='https://example.com/DetailView.aspx?ItemNo=D888859044'),
                      dict(url=self.snapshot['url'] + '&ItemNo=other'), dict(capturedAt='2026-09-07T09:00:00Z'),
                      dict(capturedAt='2026-09-07T11:00:00Z'), dict(capturedAt='2026-09-07T10:00:00')]:
            with self.subTest(patch=patch), self.assertRaises(public_fields.PublicFieldError): self.parse(self.snapshot | patch)


if __name__ == '__main__': unittest.main()
