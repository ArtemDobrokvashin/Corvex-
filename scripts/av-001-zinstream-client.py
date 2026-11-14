#!/usr/bin/env python3
"""
Клиент zINSTREAM для проверки ClamAV с настоящим clamd.
Используется только со синтетическими данными (чистый поток, EICAR).
Не отправляет реальных писем, не меняет конфигурацию ClamAV.

Выходные коды:
  0 — результат соответствует ожидаемому (PASS)
  1 — результат не соответствует ожидаемому (FAIL)
  2 — ошибка подключения/протокола (ERROR)
"""

import socket
import struct
import sys
import os

# EICAR test string (синтетический тестовый вирус, безвредный)
EICAR = b"X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
CLEAN_PAYLOAD = b"synthetic clean stream content for clamav av-001 check"


def send_zinstream(host, port, payload, timeout=10):
    """
    Отправляет payload через zINSTREAM протокол clamd.
    Возвращает (reply_text, error_message).
    """
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect((host, port))

        # Команда zINSTREAM с NUL-терминатором
        sock.sendall(b"zINSTREAM\0")

        # Отправляем данные чанками по 4-байтовой длине (big-endian)
        chunk_size = 4096
        offset = 0
        while offset < len(payload):
            chunk = payload[offset:offset + chunk_size]
            sock.sendall(struct.pack(">I", len(chunk)))
            sock.sendall(chunk)
            offset += len(chunk)

        # Терминирующий нулевой чанк
        sock.sendall(struct.pack(">I", 0))
        sock.shutdown(socket.SHUT_WR)

        # Читаем ответ до NUL или закрытия соединения
        reply = bytearray()
        while True:
            data = sock.recv(4096)
            if not data:
                break
            reply.extend(data)
            if b"\0" in reply:
                break

        sock.close()

        reply_text = reply.decode("utf-8", errors="replace").rstrip("\0").strip()
        return reply_text, None

    except socket.timeout:
        return None, "таймаут подключения/чтения"
    except ConnectionRefusedError:
        return None, "соединение отклонено (сканер недоступен)"
    except OSError as e:
        return None, f"ошибка сети: {e}"


def check_result(reply, error, expected_type, label):
    """
    Проверяет ответ clamd против ожидаемого типа.
    Возвращает (passed: bool, message: str).
    """
    if error is not None:
        if expected_type == "error":
            return True, f"PASS [{label}] — ожидаемая ошибка: {error}"
        return False, f"FAIL [{label}] — ошибка вместо результата: {error}"

    if reply is None:
        if expected_type == "error":
            return True, f"PASS [{label}] — пустой ответ (сканер недоступен)"
        return False, f"FAIL [{label}] — пустой ответ от сканера"

    if expected_type == "clean":
        if reply == "stream: OK":
            return True, f"PASS [{label}] — 'stream: OK'"
        return False, f"FAIL [{label}] — ожидался 'stream: OK', получено: {reply!r}"

    if expected_type == "infected":
        if "FOUND" in reply:
            # Извлекаем сигнатуру
            sig = reply.replace("stream: ", "").replace(" FOUND", "").strip()
            return True, f"PASS [{label}] — '{reply}' (сигнатура: {sig})"
        return False, f"FAIL [{label}] — ожидался FOUND, получено: {reply!r}"

    if expected_type == "error":
        return False, f"FAIL [{label}] — ожидалась ошибка, получено: {reply!r}"

    return False, f"FAIL [{label}] — неизвестный тип ожидания: {expected_type}"


def main():
    if len(sys.argv) < 3:
        print("Использование: av-001-zinstream-client.py <host> <port> <test_type>")
        print("  test_type: clean | infected | error")
        sys.exit(2)

    host = sys.argv[1]
    port = int(sys.argv[2])
    test_type = sys.argv[3]

    if test_type == "clean":
        payload = CLEAN_PAYLOAD
    elif test_type == "infected":
        payload = EICAR
    elif test_type == "error":
        # Для проверки недоступности не отправляем данные,
        # просто пытаемся подключиться к закрытому порту
        reply, error = send_zinstream(host, port, b"", timeout=5)
        passed, msg = check_result(reply, error, "error", "unavailable-scanner")
        print(msg)
        sys.exit(0 if passed else 1)
    else:
        print(f"FAIL — неизвестный тип теста: {test_type}")
        sys.exit(2)

    reply, error = send_zinstream(host, port, payload, timeout=15)
    passed, msg = check_result(reply, error, test_type, test_type)
    print(msg)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
