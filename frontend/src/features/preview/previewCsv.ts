export interface DelimitedTable {
  rows: string[][];
  truncated: boolean;
}

/**
 * Небольшой CSV/TSV-парсер без зависимостей: кавычки, удвоенные кавычки,
 * переводы строк внутри ячеек. Ограничение строк защищает вкладку от
 * многомегабайтных выгрузок.
 */
export function parseDelimited(text: string, delimiter: string, maxRows = 1000): DelimitedTable {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let truncated = false;

  const pushCell = () => {
    row.push(cell);
    cell = '';
  };
  const pushRow = () => {
    pushCell();
    rows.push(row);
    row = [];
  };

  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"') {
        if (text[index + 1] === '"') {
          cell += '"';
          index += 1;
        } else {
          quoted = false;
        }
      } else {
        cell += char;
      }
      continue;
    }
    if (char === '"' && cell === '') {
      quoted = true;
    } else if (char === delimiter) {
      pushCell();
    } else if (char === '\n') {
      pushRow();
      if (rows.length >= maxRows) {
        truncated = index < text.length - 1;
        break;
      }
    } else if (char === '\r') {
      // CRLF: перевод строки обработает \n.
    } else {
      cell += char;
    }
  }

  if (!truncated && (cell !== '' || row.length > 0 || rows.length === 0)) pushRow();
  return { rows: rows.filter((cells) => cells.length > 1 || cells[0] !== ''), truncated };
}
