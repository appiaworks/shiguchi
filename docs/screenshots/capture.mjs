// Captures the doc screenshots from samples/sample-jpa-backend (sample content only).
// Usage: see docs/screenshots/README.md
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:8181';
const out = process.env.OUT_DIR ?? 'docs/images/'; // run from the repo root
const size = { width: 1280, height: 800 };

const browser = await chromium.launch();
const ctx = await browser.newContext({ permissions: ['clipboard-read', 'clipboard-write'], viewport: size, colorScheme: 'light', deviceScaleFactor: 1 });
const page = await ctx.newPage();

// GraphiQL: a connection query with a field selection, run and shown with its result.
const query = `{
  books(first: 3, sort: [{ field: "title", sortOrder: SORT_ORDER_ASC }]) {
    edges { node { id title } }
    pageInfo { hasNextPage endCursor }
  }
}`;
await page.goto(`${base}/graphiql`);
const editor = page.locator('.graphiql-query-editor').first();
await editor.waitFor({ timeout: 60000 });
await page.waitForTimeout(3000);
// Paste rather than type: the editor auto-closes braces and auto-indents typed text.
await page.evaluate((q) => navigator.clipboard.writeText(q), query);
await editor.click();
await page.keyboard.press('Control+A');
await page.keyboard.press('Control+V');
await page.locator('.graphiql-execute-button').click();
await page.waitForTimeout(4000);
if (process.env.DEBUG_SHOT) await page.screenshot({ path: process.env.DEBUG_SHOT });
await page.locator('.graphiql-response').getByText('Dune').first().waitFor({ timeout: 30000 });
await page.screenshot({ path: `${out}graphiql.png` });

// Swagger UI: the operation list with the first operation expanded.
await page.goto(`${base}/swagger-ui/index.html`);
await page.locator('.opblock-summary').first().waitFor({ timeout: 60000 });
await page.locator('.opblock-summary').first().click();
await page.locator('.opblock-body .parameters-container, .opblock-body .responses-wrapper').first().waitFor({ timeout: 30000 });
await page.waitForTimeout(500);
await page.screenshot({ path: `${out}swagger-ui.png` });

await browser.close();
