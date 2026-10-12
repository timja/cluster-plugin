// Target 15: what a person sees through a load balancer that does not pin browsers to a controller.
const { test, expect } = require('@playwright/test');

test('a live console follows a build running on one controller, whichever controller each request reaches', async ({ page }) => {
  await page.goto(`/job/ui-ticker/1/console`);
  // the page's own JavaScript fetches the rest of the log as it grows (progressive text), through the load balancer
  await expect(page.locator('pre.console-output, #out')).toContainText('tick 20', { timeout: 120000 });
  await expect(page.locator('pre.console-output, #out')).toContainText('Finished: SUCCESS', { timeout: 120000 });
  const text = await page.locator('pre.console-output, #out').innerText();
  for (let i = 1; i <= 20; i++) {
    expect(text.split(`tick ${i}\n`).length - 1, `tick ${i} once`).toBe(1);
  }
});

test('a job configured in the browser is saved for every controller', async ({ page, request }) => {
  await page.goto(`/job/ui-config/configure`);
  await page.locator('textarea[name="description"]').fill('described in a browser');
  await page.getByRole('button', { name: 'Save' }).click();
  await expect(page).toHaveURL(/\/job\/ui-config\/$/);
  // the controller that did not serve the save has the change once it has applied it (milliseconds to a second or two)
  for (const port of [8081, 8082]) {
    await expect.poll(async () => {
      const r = await request.get(`http://localhost:${port}/job/ui-config/api/json?tree=description`);
      return (await r.json()).description;
    }, { timeout: 10000 }).toBe('described in a browser');
  }
});

test('the cluster page shows both controllers with their executors', async ({ page }) => {
  await page.goto(`/manage/cluster/`);
  await expect(page.getByRole('cell', { name: 'jenkins-a', exact: true })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'jenkins-b', exact: true })).toBeVisible();
  await expect(page.getByText(/of 4 busy/).first()).toBeVisible();
});

test('the plugin manager says changes apply to every controller, and shows their status', async ({ page }) => {
  await page.goto(`/manage/pluginManager/`);
  await expect(page.locator('.cnj-cluster-plugins')).toContainText('apply to all of them');
  await expect(page.locator('.cnj-cluster-plugins__status')).toContainText('jenkins-b');
});
