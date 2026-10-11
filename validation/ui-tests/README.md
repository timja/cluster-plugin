# Real-browser checks (ET21)

Playwright drives Chromium against a cluster behind the round-robin load balancer (`rr`, no stickiness), as a person
would use it: a live console following a build running on one controller, a job configured in a form, the cluster
page, the plugin manager's notice.

```sh
(cd validation/ui-tests && npm ci)   # @playwright/test 1.63.0, which uses the Chromium build cached locally (1243)
validation/experiments/ET21-browser.sh
```

The experiment starts the cluster, creates the jobs the tests use, runs `npx playwright test` with `BASE_URL` and
`AUTH` (an API token, so no login form), and reports each test as a check. Traces of failed tests are kept in
`test-results/`.
