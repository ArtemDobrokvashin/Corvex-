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

import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'scripts' / 'verify-dlq-resilience.ps1'
SCRIPT_SH = REPO / 'scripts' / 'verify-dlq-resilience.sh'


class VerifyDlqResilienceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = SCRIPT.read_text(encoding='utf-8')
        self.shell = SCRIPT_SH.read_text(encoding='utf-8')

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
        commands = re.findall(r'rabbitmqctl\s+(?:-\w+\s+)*([a-z_]+)', self.text)
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
        # Ждём healthy после перезапуска, а не до него.
        wait_at = self.text.index('$healthy = $false')
        self.assertLess(restart_at, wait_at)


if __name__ == '__main__':
    unittest.main()


FAKE_DOCKER = """#!/usr/bin/env bash
# Подставной docker: отдаёт заранее заданные ответы и пишет журнал вызовов.
state="$FAKE_STATE"
echo "$*" >> "$state/log"
case "$*" in
  "compose exec"*)
    calls_file="$state/calls"
    n=$(cat "$calls_file" 2>/dev/null || echo 0)
    n=$((n + 1))
    echo "$n" > "$calls_file"
    busy_limit="${FAKE_BUSY_CALLS:-${FAKE_BEFORE_CALLS:-1}}"
    if [ "$n" -le "${FAKE_BEFORE_CALLS:-1}" ]; then cat "$state/before.txt"
    elif [ -f "$state/drained.txt" ] && [ "$n" -gt "$busy_limit" ]; then cat "$state/drained.txt"
    else cat "$state/after.txt"; fi
    ;;
  "compose restart"*) exit 0 ;;
  "compose ps"*) cat "$state/ps.txt" ;;
  *) exit 0 ;;
esac
"""

BEFORE = "mail.spam.inbound\t0\nmail.spam.inbound.dlq\t45\nmail.av.spam-checked\t0\nmail.av.spam-checked.dlq\t0\nmail.storage.av-checked\t0\nmail.storage.av-checked.dlq\t0\n"
AFTER_SAME = BEFORE
AFTER_GROWN = "mail.spam.inbound\t0\nmail.spam.inbound.dlq\t47\nmail.av.spam-checked\t0\nmail.av.spam-checked.dlq\t0\nmail.storage.av-checked\t0\nmail.storage.av-checked.dlq\t0\n"


class VerifyDlqResilienceFunctionalTest(unittest.TestCase):
    """Прогон настоящего bash-скрипта с подставным docker."""

    def setUp(self) -> None:
        self.root = Path(tempfile.mkdtemp(prefix='corvex-dlq-'))
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / 'scripts').mkdir()
        shutil.copy2(SCRIPT_SH, self.root / 'scripts' / 'verify-dlq-resilience.sh')
        os.chmod(self.root / 'scripts' / 'verify-dlq-resilience.sh', 0o755)

        self.state = self.root / 'state'
        self.state.mkdir()
        bindir = self.root / 'bin'
        bindir.mkdir()
        docker = bindir / 'docker'
        docker.write_text(FAKE_DOCKER, encoding='utf-8')
        os.chmod(docker, 0o755)
        self.env = dict(os.environ, PATH=f'{bindir}:{os.environ["PATH"]}',
                        FAKE_STATE=str(self.state))

    def scenario(self, *, before: str = BEFORE, after: str = AFTER_SAME,
                 healthy: bool = True, before_calls: int = 1) -> None:
        (self.state / 'before.txt').write_text(before, encoding='utf-8')
        (self.state / 'after.txt').write_text(after, encoding='utf-8')
        (self.state / 'ps.txt').write_text(
            'mail-storage-service healthy\nrabbitmq healthy\n' if healthy
            else 'mail-storage-service starting\n', encoding='utf-8')
        self.env['FAKE_BEFORE_CALLS'] = str(before_calls)

    def run_script(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ['bash', str(self.root / 'scripts' / 'verify-dlq-resilience.sh'), *args],
            cwd=self.root, env=self.env, capture_output=True, text=True, timeout=120,
        )

    def calls(self) -> list[str]:
        log = self.state / 'log'
        return log.read_text(encoding='utf-8').splitlines() if log.exists() else []

    def test_pass_when_dlq_does_not_grow(self) -> None:
        self.scenario()
        result = self.run_script('--wait', '5')

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('[PASS]', result.stdout)
        self.assertIn('рост: 0', result.stdout)
        self.assertTrue(any('compose restart' in line for line in self.calls()))

    def test_fails_when_dlq_grows(self) -> None:
        self.scenario(after=AFTER_GROWN)
        result = self.run_script('--wait', '5')

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('[FAIL]', result.stdout)
        self.assertIn('рост: 2', result.stdout)

    def test_fails_when_service_never_becomes_healthy(self) -> None:
        self.scenario(healthy=False)
        result = self.run_script('--wait', '3')

        self.assertEqual(2, result.returncode, result.stdout + result.stderr)
        self.assertIn('не стал healthy', result.stdout)

    def test_baseline_does_not_restart(self) -> None:
        self.scenario()
        result = self.run_script('--baseline')

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('Перезапуск не выполнялся', result.stdout)
        self.assertFalse(any('compose restart' in line for line in self.calls()))
        self.assertIn('45', result.stdout)

    def test_waits_until_working_queues_drain(self) -> None:
        busy = BEFORE.replace('mail.spam.inbound\t0', 'mail.spam.inbound\t12')
        self.scenario(after=busy, before_calls=1)
        (self.state / 'drained.txt').write_text(BEFORE, encoding='utf-8')
        self.env['FAKE_BUSY_CALLS'] = '2'
        result = self.run_script('--wait', '20')

        # Первый замер — 12 писем в работе, скрипт обязан опросить повторно и дождаться нуля.
        self.assertIn('[PASS]', result.stdout, result.stdout + result.stderr)
        exec_calls = [line for line in self.calls() if line.startswith('compose exec')]
        self.assertGreaterEqual(len(exec_calls), 3)
        # Последний замер показывает пустую рабочую очередь: скрипт дождался дренажа.
        tail = result.stdout.rsplit('=== Очереди после перезапуска ===', 1)[1]
        self.assertRegex(tail, r'mail\.spam\.inbound\s+0\n')

    def test_never_purges_queues(self) -> None:
        self.scenario()
        self.run_script('--wait', '5')
        for line in self.calls():
            self.assertNotIn('purge', line)
            self.assertNotIn('delete', line)


class ScriptParityTest(unittest.TestCase):
    """Обе версии обязаны проверять одни и те же очереди и не чистить их."""

    def test_bash_twin_exists_and_parses(self) -> None:
        self.assertTrue(SCRIPT_SH.is_file())
        result = subprocess.run(['bash', '-n', str(SCRIPT_SH)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_both_versions_watch_the_same_queues(self) -> None:
        ps1 = SCRIPT.read_text(encoding='utf-8')
        sh = SCRIPT_SH.read_text(encoding='utf-8')
        queues = ('mail.spam.inbound', 'mail.spam.inbound.dlq', 'mail.av.spam-checked',
                  'mail.av.spam-checked.dlq', 'mail.storage.av-checked',
                  'mail.storage.av-checked.dlq')
        for queue in queues:
            with self.subTest(queue=queue):
                self.assertIn(queue, ps1)
                self.assertIn(queue, sh)

    def test_neither_version_purges(self) -> None:
        for path in (SCRIPT, SCRIPT_SH):
            text = path.read_text(encoding='utf-8')
            for word in ('queue_purge', 'purge_queue', 'rabbitmqadmin', 'shovel'):
                with self.subTest(file=path.name, word=word):
                    self.assertNotIn(word, text)
