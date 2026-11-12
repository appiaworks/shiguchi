// Loads the gateway's browser surfaces in headless Chromium and fails on what a user would see
// break: a page that never renders, a Content-Security-Policy violation, a console error.
// Usage: see scripts/ui-smoke/README.md. BASE_URL defaults to http://localhost:8080.
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:8080';

const pages = [
  { name: 'gRPC console', path: '/grpc-ui/', ready: '#root *' , text: 'catalog' },
  { name: 'GraphiQL', path: '/graphiql', ready: '.graphiql-query-editor' },
  { name: 'Swagger UI', path: '/swagger-ui/index.html', ready: '.opblock-summary' },
];

const browser = await chromium.launch();
let failed = false;
for (const p of pages) {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  const problems = [];
  page.on('console', (m) => {
    if (m.type() === 'error') problems.push(`console error: ${m.text()}`);
  });
  page.on('pageerror', (e) => problems.push(`page error: ${e.message}`));
  await page.addInitScript(() =>
    document.addEventListener('securitypolicyviolation', (e) =>
      console.error(`CSP violation: ${e.violatedDirective} blocked ${e.blockedURI}`)));
  try {
    const res = await page.goto(base + p.path, { waitUntil: 'load' });
    if (!res || !res.ok()) problems.push(`HTTP ${res?.status()}`);
    await page.locator(p.ready).first().waitFor({ timeout: 60000 });
    if (p.text) await page.getByText(p.text).first().waitFor({ timeout: 30000 });
    await page.waitForTimeout(1500); // let late CSP / network errors surface
  } catch (e) {
    problems.push(`did not render: ${e.message.split('\n')[0]}`);
  }
  console.log(`${problems.length ? 'FAIL' : 'ok  '} ${p.name} (${p.path})`);
  problems.forEach((x) => console.log(`       ${x}`));
  failed ||= problems.length > 0;
  await ctx.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
