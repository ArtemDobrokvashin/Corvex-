"""Структурные проверки scripts/verify-dlq-resilience.ps1.

Сам сценарий выполняется только на стенде с Docker. Здесь фиксируется то, что можно
проверить без него и что легко потерять при правках:

- скрипт читает именно те очереди, через которые шло письмо: пропуск любой из них
  делает тест слепым к части потерь;
- скрипт не чистит и не переотправляет очереди: автоочистка DLQ запрещена
  docs/OPERATIONS.md, потому что сообщение может ссылаться на удалённый spool;
- вердикт считается по росту DLQ, а не по отсутствию ошибок в выводе;
- ненулевой код возврата при росте DLQ и при недоступном rabbitmqctl.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'scripts' / 'verify-dlq-resilience.ps1'


class VerifyDlqResilienceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = SCRIPT.read_text(encoding='utf-8')

    def test_script_exists(self) -> None:
        self.assertTrue(SCRIPT.is_file())

    def test_watches_every_pipeline_queue(self) -> None:
        for queue in ('mail.spam.inbound', 'mail.spam.inbound.dlq',
                      'mail.av.spam-checked', 'mail.av.spam-checked.dlq',
                      'mail.storage.av-checked', 'mail.storage.av-checked.dlq'):
            with self.subTest(queue=queue):
                self.assertIn(f'"{queue}"', self.text)

    def test_never_purges_or_republishes(self) -> None:
        # Автоматический replay DLQ запрещён: сообщение может ссылаться на spool,
        # который уже удалён, и повтор создаст дубль или 404.
        forbidden = ('queue_purge', 'purge_queue', 'queue_delete',
                     'rabbitmqadmin', 'shovel')
        for word in forbidden:
            with self.subTest(word=word):
                self.assertNotIn(word, self.text)

    def test_only_reads_counters(self) -> None:
        commands = re.findall(r'rabbitmqctl\s+([a-z_]+)', self.text)
        self.assertEqual({'list_queues'}, set(commands))

    def test_verdict_is_a_dlq_delta(self) -> None:
        self.assertIn('$dlqAfter - $dlqBefore', self.text)
        self.assertIn('$growth -gt 0', self.text)

    def test_fails_with_nonzero_exit_code(self) -> None:
        self.assertRegex(self.text, r'(?m)^\s*exit 1\s*$')
        self.assertRegex(self.text, r'(?m)^\s*exit 2\s*$')

    def test_baseline_mode_does_not_restart(self) -> None:
        baseline_branch = self.text.split('if ($BaselineOnly)')[1].split('exit 0')[0]
        self.assertNotIn('docker compose restart', baseline_branch)

    def test_waits_for_healthy_before_measuring(self) -> None:
        restart_at = self.text.index('docker compose restart')
        # Ищем вызов, а не объявление функции: объявление стоит выше по файлу.
        call_at = self.text.index('Wait-StorageHealthy $WaitSeconds')
        self.assertLess(restart_at, call_at)


if __name__ == '__main__':
    unittest.main()
