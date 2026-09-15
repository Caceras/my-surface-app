#!/usr/bin/env python3
"""Exercise publication decisions without network access or release writes."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import publish_preview as publisher


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = Path.cwd()
        os.chdir(self.tmp.name)
        folder = Path('dist/evidence'); folder.mkdir(parents=True)
        for name in ('aegentica-ai-core.apk', 'aegentica-ai-nano.apk',
                     'pixel-surface-lab-core.apk', 'pixel-surface-lab-nano.apk',
                     'release-evidence.json', 'SHA256SUMS'):
            (folder / name).write_text(name)
        self.meta = dict(source_sha='a' * 40, repository='owner/repo', run_id='123', build=12, attempt=1, tests=dict(tests=140))
        self.env = patch.dict(os.environ, GITHUB_REPOSITORY='owner/repo', GITHUB_SHA='a' * 40,
                              GITHUB_REF_NAME='feature', GITHUB_RUN_ID='123', GITHUB_RUN_NUMBER='12',
                              GITHUB_RUN_ATTEMPT='1', GITHUB_STEP_SUMMARY='summary.md')
        self.env.start()
        self.verify = patch.object(publisher, 'verify', return_value=self.meta).start()
        self.gh = patch.object(publisher, 'gh', return_value='').start()
        self.head = patch.object(publisher, 'current_head', return_value=True).start()
        self.release = patch.object(publisher, 'release', return_value=None).start()
        self.download = patch.object(publisher, 'verify_download').start()
        patch('builtins.print').start()

    def tearDown(self):
        patch.stopall(); self.env.stop()
        os.chdir(self.previous); self.tmp.cleanup()

    def commands(self):
        return [call.args for call in self.gh.call_args_list]

    def test_stale_source_never_publishes(self):
        self.head.return_value = False
        publisher.main()
        self.gh.assert_not_called(); self.release.assert_not_called()

    def test_wrong_provenance_blocks_before_network(self):
        self.meta['run_id'] = 'different'
        with self.assertRaisesRegex(ValueError, 'provenance'): publisher.main()
        self.head.assert_not_called(); self.gh.assert_not_called()

    def test_branch_advancing_during_upload_keeps_only_unique_download(self):
        self.head.side_effect = [True, False]
        publisher.main()
        self.assertEqual(self.release.call_count, 1)
        self.assertEqual(self.download.call_count, 1)
        self.assertIn('rolling preview left unchanged', Path('summary.md').read_text())

    def test_all_assets_verified_for_both_links(self):
        publisher.main()
        self.assertEqual(self.download.call_count, 2)
        for call in self.download.call_args_list:
            self.assertEqual(len(call.args[2]), 7)
        self.assertEqual(self.commands()[0][:3], ('release', 'create', 'preview-feature-build-12'))
        self.assertIn('--draft', self.commands()[0])

    def test_failed_download_never_publishes_draft_or_alias(self):
        self.download.side_effect = ValueError('mismatch')
        with self.assertRaisesRegex(ValueError, 'mismatch'): publisher.main()
        self.assertEqual(len(self.commands()), 1)
        self.assertEqual(self.release.call_count, 1)

    def test_public_build_is_not_overwritten(self):
        self.release.side_effect = [dict(isDraft=False, targetCommitish='a' * 40), None]
        publisher.main()
        self.assertFalse(any(c[:3] == ('release', 'upload', 'preview-feature-build-12') for c in self.commands()))
        self.assertFalse(any(c[:3] == ('release', 'create', 'preview-feature-build-12') for c in self.commands()))

    def test_draft_repair_and_rolling_update_verify_all_assets(self):
        self.release.return_value = dict(isDraft=True, targetCommitish='a' * 40)
        publisher.main()
        self.assertEqual(sum(c[:2] == ('release', 'upload') for c in self.commands()), 2)
        self.assertEqual(self.download.call_count, 2)

    def test_retry_has_distinct_download(self):
        self.meta['attempt'] = 2
        os.environ['GITHUB_RUN_ATTEMPT'] = '2'
        publisher.main()
        self.assertEqual(self.commands()[0][2], 'preview-feature-build-12-r2')

    def test_different_release_target_is_never_repaired(self):
        self.release.return_value = dict(isDraft=True, targetCommitish='b' * 40)
        with self.assertRaisesRegex(ValueError, 'different commit'): publisher.main()
        self.gh.assert_not_called()


if __name__ == '__main__':
    unittest.main()
