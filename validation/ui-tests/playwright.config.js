// Real-browser checks against a cluster behind the round-robin load balancer (ET21). BASE_URL and AUTH (user:token)
// come from the experiment script.
const { defineConfig } = require('@playwright/test');
const [username, password] = (process.env.AUTH || 'admin:admin').split(':');
module.exports = defineConfig({
  testDir: '.',
  timeout: 180000,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/results.json' }]],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8090',
    // preemptively: Jenkins answers an anonymous request for an admin page with a login page, not a 401 challenge
    extraHTTPHeaders: { Authorization: 'Basic ' + Buffer.from(`${username}:${password}`).toString('base64') },
    headless: true,
    trace: 'retain-on-failure',
  },
});
