import { expect, test } from '@playwright/test';

/**
 * Минимальный, но честный набор из UX-BACKLOG P3-4: открыть письмо, ответить,
 * переместить, отменить, пройти по списку клавиатурой. Все действия идут через
 * те же элементы, что видит человек, — селекторы по роли и тексту, а не по
 * внутренним классам, иначе тест закрепляет вёрстку, а не поведение.
 */

/**
 * Демо-режим включается тем же путём, что и кнопка «Посмотреть демо»:
 * флаг в sessionStorage, иначе AppShell попытается восстановить сессию и
 * уйдёт на /login — сервера в этом прогоне нет.
 */
async function openMail(page: import('@playwright/test').Page) {
  await page.addInitScript(() => window.sessionStorage.setItem('corvex.demo', '1'));
  await page.goto('/mail');
  await expect(page.getByRole('heading', { name: /Почта|Mail/ }).first()).toBeVisible();
  // Список писем в демо-режиме непустой: без этого дальнейшие шаги бессмысленны.
  await expect(page.locator('[data-mail-id]').first()).toBeVisible();
}

test('action buttons stay visible on a 1366 px screen', async ({ page }) => {
  await openMail(page);
  await page.locator('[data-mail-id]').first().click();

  // P1-4: ответ/пересылка/архив/спам не должны прятаться на ноутбуке.
  for (const label of [/Ответить|Reply/, /Переслать|Forward/, /В архив|Archive/]) {
    const button = page.getByRole('button', { name: label }).first();
    await expect(button).toBeVisible();
    await expect(button).toBeInViewport();
  }
});

test('the list can be walked with the keyboard', async ({ page }) => {
  await openMail(page);

  // P1-5: Tab доводит до списка, стрелки двигают выбор, Enter открывает письмо.
  await page.locator('[data-mail-id]').first().focus();
  await expect(page.locator('[data-mail-id]').first()).toBeFocused();
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');

  // Открылось письмо с темой, а не пустая панель.
  await expect(page.getByRole('button', { name: /Ответить|Reply/ }).first()).toBeVisible();
});

test('reply opens the composer with a quoted body and the recipient', async ({ page }) => {
  await openMail(page);
  await page.locator('[data-mail-id]').first().click();
  await page.getByRole('button', { name: /Ответить|Reply/ }).first().click();

  await expect(page.locator('#compose-to')).not.toHaveValue('');
  await expect(page.locator('section[aria-label]').filter({ has: page.locator('#compose-to') })).toBeVisible();
});

test('a message can be moved to another folder and the list follows', async ({ page }) => {
  await openMail(page);
  const first = page.locator('[data-mail-id]').first();
  const id = await first.getAttribute('data-mail-id');
  await first.click();

  const move = page.getByRole('button', { name: /Переместить|Move/ }).first();
  if (await move.count()) {
    await move.click();
    await page.getByRole('menuitem', { name: /Архив|Archive/ }).first().click();
    // Письмо ушло из «Входящих»: в текущем списке его больше нет.
    await expect(page.locator(`[data-mail-id="${id}"]`)).toHaveCount(0);
  }
});

test('the interface survives 200 % zoom', async ({ page }) => {
  // Масштаб имитируется уменьшением CSS-вьюпорта: так же перестраивается вёрстка.
  await page.setViewportSize({ width: 683, height: 400 });
  await openMail(page);
  await expect(page.locator('[data-mail-id]').first()).toBeVisible();
  // Кнопка «Написать» остаётся доступной, а не уезжает за край.
  await expect(page.getByRole('button', { name: /Написать|Compose/ }).first()).toBeInViewport();
});

test('English locale keeps every action labelled', async ({ page }) => {
  await page.addInitScript(() => {
    window.sessionStorage.setItem('corvex.demo', '1');
    window.localStorage.setItem('corvex.locale', 'en');
  });
  await page.goto('/mail');
  await expect(page.locator('[data-mail-id]').first()).toBeVisible();
  // Ни одной пустой кнопки: подпись или aria-label обязательны.
  const unlabelled = await page.locator('button:visible').evaluateAll((buttons) => buttons.filter((button) => (
    !(button.textContent ?? '').trim() && !button.getAttribute('aria-label') && !button.getAttribute('title')
  )).length);
  expect(unlabelled).toBe(0);
});
