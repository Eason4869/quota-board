// Execute the production JS template: WebView's callback does not await Promises.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname, '../main/java/com/yusheng/quota/net/PageFetch.kt'), 'utf8');
const template = source.match(/return """([\s\S]*?)"""\.trimIndent\(\)/)[1];
const script = key => template.replace(/\$list/g, '"https://example.test/quota"')
  .replace(/\$acceptLiteral/g, '"application/json"').replace(/\$keyLiteral/g, JSON.stringify(key));
(async () => {
  const pending = [];
  const window = {};
  const context = vm.createContext({ window, AbortController, fetch: (url, options) =>
    new Promise(resolve => pending.push({ url, options, resolve })) });
  assert.equal(vm.runInContext(script('first'), context), true);
  assert.equal(window.first.result, null);
  vm.runInContext(script('second'), context);
  pending[1].resolve({ status: 200, text: async () => '{"balance":2}' });
  await new Promise(setImmediate);
  assert.equal(JSON.parse(window.second.result)[0].body, '{"balance":2}');
  assert.equal(window.first.result, null);
  const first = window.first;
  delete window.first;
  first.controller.abort();
  assert.equal(pending[0].options.signal.aborted, true);
  pending[0].resolve({ status: 200, text: async () => '{"balance":1}' });
  await new Promise(setImmediate);
  assert.equal(window.first, undefined, 'late completion must not restore cancelled state');
  assert.equal(pending[1].options.credentials, 'include');
  const failures = vm.createContext({ window: {}, AbortController, fetch: async () => { throw Error('offline'); } });
  vm.runInContext(script('failed'), failures);
  await new Promise(setImmediate);
  assert.equal(JSON.parse(failures.window.failed.result)[0].status, 0);
  console.log('PASS: asynchronous results, request isolation, cancellation, and fetch errors');
})().catch(error => { console.error(error); process.exitCode = 1; });
