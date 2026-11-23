// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import {
  attachmentPrintDocument,
  mailPrintDocument,
  tablePrintHtml,
  textPrintHtml,
} from './printDocument';

const labels = { from: 'От', to: 'Кому', cc: 'Копия', date: 'Дата', attachments: 'Вложения' };

const baseMail = {
  subject: 'Отчёт за квартал',
  from: 'ivan@example.test',
  to: 'petr@example.test',
  date: '23.11.2026, 10:00',
  labels,
};

describe('mailPrintDocument', () => {
  it('печатает шапку письма: от, кому, дата и тема', () => {
    const html = mailPrintDocument({ ...baseMail, text: 'Привет' });
    expect(html).toContain('<title>Отчёт за квартал</title>');
    expect(html).toContain('<h1>Отчёт за квартал</h1>');
    expect(html).toContain('<dt>От</dt><dd>ivan@example.test</dd>');
    expect(html).toContain('<dt>Кому</dt><dd>petr@example.test</dd>');
    expect(html).toContain('<dt>Дата</dt><dd>23.11.2026, 10:00</dd>');
    expect(html).toContain('<p>Привет</p>');
  });

  it('печатает копию только когда она есть', () => {
    const withoutCc = mailPrintDocument({ ...baseMail, text: 'x' });
    expect(withoutCc).not.toContain('<dt>Копия</dt>');
    const withCc = mailPrintDocument({ ...baseMail, cc: 'boss@example.test', text: 'x' });
    expect(withCc).toContain('<dt>Копия</dt><dd>boss@example.test</dd>');
  });

  it('экранирует поля шапки: скрипт из темы не становится тегом', () => {
    const html = mailPrintDocument({
      ...baseMail,
      subject: '<script>alert(1)</script>',
      from: 'a"b@example.test',
      text: 'тело',
    });
    expect(html).not.toContain('<script>alert(1)</script>');
    expect(html).toContain('&lt;script&gt;alert(1)&lt;/script&gt;');
    expect(html).toContain('a&quot;b@example.test');
  });

  it('не обходит антитрекинг: внешняя картинка блокируется, пока не разрешена', () => {
    const html = mailPrintDocument({
      ...baseMail,
      html: '<p>Текст</p><img src="https://tracker.invalid/pixel.gif" alt="Логотип">',
    });
    expect(html).not.toContain('tracker.invalid');
    expect(html).toContain('class="corvex-blocked-image"');
    expect(html).toContain('Логотип');
  });

  it('печатает внешние картинки без referrer, если пользователь их разрешил', () => {
    const html = mailPrintDocument({
      ...baseMail,
      html: '<img src="https://cdn.example.test/logo.png" alt="Логотип">',
      allowRemoteImages: true,
    });
    expect(html).toContain('src="https://cdn.example.test/logo.png"');
    expect(html).toContain('referrerpolicy="no-referrer"');
  });

  it('вырезает активное содержимое тела письма', () => {
    const html = mailPrintDocument({
      ...baseMail,
      html: '<script>alert(1)</script><a href="https://evil.test" onclick="alert(2)">ссылка</a>',
    });
    expect(html).not.toContain('<script>alert(1)');
    expect(html).not.toContain('onclick');
    expect(html).not.toContain('href=');
    expect(html).toContain('ссылка');
  });

  it('перечисляет вложения именами, не подставляя их содержимое', () => {
    const html = mailPrintDocument({
      ...baseMail,
      text: 'тело',
      attachments: ['смета.xlsx', '<b>злой</b>.pdf'],
    });
    expect(html).toContain('<div>Вложения</div>');
    expect(html).toContain('<li>смета.xlsx</li>');
    expect(html).toContain('<li>&lt;b&gt;злой&lt;/b&gt;.pdf</li>');
    expect(html).not.toContain('<b>злой</b>');
  });

  it('не рисует блок вложений для пустого списка', () => {
    const html = mailPrintDocument({ ...baseMail, text: 'тело', attachments: [] });
    expect(html).not.toContain('class="attachments"');
  });

  it('печатает текстовое тело, если HTML отсутствует или пуст', () => {
    const html = mailPrintDocument({ ...baseMail, html: '   ', text: 'Строка <1>\n\nВторая' });
    expect(html).toContain('<p>Строка &lt;1&gt;</p>');
    expect(html).toContain('<p>Вторая</p>');
  });
});

describe('attachmentPrintDocument', () => {
  it('не печатает имя файла в теле листа — ни шапкой, ни подписью', () => {
    // Дефект: имя печаталось дважды (заголовок сверху и подпись снизу) и
    // выглядело как ошибка вёрстки. В теле листа его быть не должно.
    const fileName = 'st_small_507x507-pad_600x600_f8f8f8-u2.jpg';
    const html = attachmentPrintDocument({
      fileName,
      imageDataUrl: 'data:image/jpeg;base64,YWJj',
      footer: '31 КБ',
    });
    const body = html.slice(html.indexOf('<body>'));
    // В видимом содержимом листа имени нет; `alt` картинки не печатается, а нужен
    // там, где документ откроется в табе и картинка не загрузится.
    const visible = body.replace(/alt="[^"]*"/g, '');
    expect(visible).not.toContain(fileName);
    expect(body).not.toContain('<h1');
    expect(html).toContain('<title>');
    expect(body).toContain('31 КБ');
  });

  it('экранирует имя файла в alt картинки и подписи', () => {
    const html = attachmentPrintDocument({
      fileName: '<img src=x onerror=alert(1)>.txt',
      imageDataUrl: 'data:image/png;base64,YWJj',
      footer: 'a & b',
    });
    expect(html).not.toContain('<img src=x');
    expect(html).toContain('alt="&lt;img src=x onerror=alert(1)&gt;.txt"');
    expect(html).toContain('a &amp; b');
  });

  it('вставляет картинку как data URL, а не ссылку на сеть', () => {
    const html = attachmentPrintDocument({
      fileName: 'схема.png',
      imageDataUrl: 'data:image/png;base64,YWJj',
    });
    expect(html).toContain('src="data:image/png;base64,YWJj"');
    expect(html).not.toContain('http');
  });
});

describe('textPrintHtml', () => {
  it('сохраняет переносы и экранирует разметку', () => {
    const html = textPrintHtml('первая\n<script>x</script>');
    expect(html).toContain('white-space:pre-wrap');
    expect(html).toContain('&lt;script&gt;x&lt;/script&gt;');
    expect(html).not.toContain('<script>x');
  });
});

describe('tablePrintHtml', () => {
  it('делает шапку th, остальные строки td и экранирует ячейки', () => {
    const html = tablePrintHtml([['Имя', '<b>Сумма</b>'], ['Иван', '100']], false);
    expect(html).toContain('<thead><tr><th');
    expect(html).toContain('Имя');
    expect(html).toContain('&lt;b&gt;Сумма&lt;/b&gt;');
    expect(html).toContain('<td');
    expect(html).toContain('Иван');
    expect(html).not.toContain('Показаны не все строки');
  });

  it('предупреждает, что строки обрезаны', () => {
    const html = tablePrintHtml([['A'], ['1']], true);
    expect(html).toContain('Показаны не все строки.');
  });
});
