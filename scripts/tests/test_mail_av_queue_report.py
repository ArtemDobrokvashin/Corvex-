import contextlib
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import mail_av_queue_report as report


def row(name, **overrides):
    return {'name': name, 'messages_ready': 1, 'messages_unacknowledged': 0,
            'consumers': 2, 'type': 'classic', **overrides}


class QueueReportTests(unittest.TestCase):
    def test_missing_queue_is_unknown_and_unrelated_names_are_not_returned(self):
        rows = [row(report.QUEUES[0]), row('unrelated-private-queue')]
        result = report.parse_report(json.dumps(rows).encode())
        self.assertEqual(result[report.QUEUES[0]]['messages_ready'], 1)
        self.assertIsNone(result[report.QUEUES[1]])
        self.assertNotIn('unrelated-private-queue', result)

    def test_invalid_or_duplicated_relevant_rows_fail(self):
        invalid = [b'', b'{}', b'\xff', b'null', json.dumps([{}]).encode()]
        for value in (-1, True, None, '1'):
            invalid.append(json.dumps([row(report.QUEUES[0], messages_ready=value)]).encode())
        invalid += [json.dumps([row(report.QUEUES[0], type='offline')]).encode(),
                    json.dumps([row(report.QUEUES[0]), row(report.QUEUES[0])]).encode()]
        for raw in invalid:
            with self.subTest(raw=raw), self.assertRaises(report.ReportError):
                report.parse_report(raw)

    def test_collection_is_read_only_and_never_gets_messages(self):
        response = subprocess.CompletedProcess([], 0, b'[]', b'')
        with patch.object(report.subprocess, 'run', return_value=response) as run:
            result = report.collect(Path('/example'), 'project')
        self.assertTrue(all(value is None for value in result.values()))
        args = run.call_args.args[0]
        self.assertIn('list_queues', args)
        self.assertNotIn('get', args)
        self.assertNotIn('purge_queue', args)
        self.assertNotIn('delete_queue', args)
        self.assertEqual(run.call_args.kwargs['cwd'], Path('/example'))

    def test_execution_failure_and_timeout_never_return_success(self):
        with patch.object(report.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'[]', b'secret')):
            with self.assertRaises(report.ReportError) as error:
                report.collect(Path('.'), None)
            self.assertNotIn('secret', str(error.exception))
        with patch.object(report.subprocess, 'run', side_effect=subprocess.TimeoutExpired('docker', 75)):
            with self.assertRaises(report.ReportError):
                report.collect(Path('.'), None)

    def test_output_warns_snapshot_is_not_a_safety_guarantee(self):
        output = io.StringIO()
        with patch.object(report, 'collect', return_value=dict.fromkeys(report.QUEUES)), contextlib.redirect_stdout(output):
            self.assertEqual(report.main([]), 0)
        self.assertIn('не подтверждение сохранности', output.getvalue())
        self.assertIn('отсутствует или не видна', output.getvalue())
        self.assertNotIn('PASS', output.getvalue())


if __name__ == '__main__':
    unittest.main()
