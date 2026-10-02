const http = require('http');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

let puppeteer;
try {
  puppeteer = require('puppeteer');
} catch (e) {
  puppeteer = require(path.resolve(__dirname, 'node_modules/puppeteer'));
}

const PORT = 8098;
const BASE_DIR = path.resolve(__dirname, '..');
const HTML_FILE = path.join(BASE_DIR, 'src/main/resources/static/dashboard.html');
const IMAGES_DIR = path.join(BASE_DIR, 'docs/images');
const FRAMES_DIR = path.join(BASE_DIR, '.frames_idemgate');
const OUTPUT_GIF = path.join(IMAGES_DIR, 'idemgate_demo.gif');
const OUTPUT_OVERVIEW = path.join(IMAGES_DIR, 'dashboard-live-overview.png');
const OUTPUT_MODAL = path.join(IMAGES_DIR, 'dashboard-inspect-modal.png');

const MTLG_SITE_IMAGES = 'B:/workgit/mtlg-site/images';

let state = {
  stats: {
    storageType: 'memory',
    totalRequests: 184,
    cacheHitRatioPercent: 42.6,
    cacheHits: 78,
    cacheMisses: 106,
    raceConditionsSerialized: 24,
    activeCachedKeys: 16,
    rateLimitedRequests: 5,
    meanProxyLatencyMs: 4.82,
    maxProxyLatencyMs: 28.4
  },
  config: {
    storageType: 'memory',
    upstreamUrl: 'http://localhost:8080/upstream-mock',
    idempotencyHeader: 'Idempotency-Key',
    lockTtlSeconds: 30,
    recordTtlSeconds: 86400,
    maxBodySizeBytes: 10485760,
    rateLimitEnabled: true,
    defaultCapacity: 100,
    defaultRefillRate: 20
  },
  keys: [
    {
      key: 'pay_9f82a1c0',
      status: 'RESOLVED',
      fingerprint: 'f8a3c90e1b42a7c8812e',
      statusCode: 201,
      createdAt: new Date(Date.now() - 320000).toISOString()
    },
    {
      key: 'order_checkout_4821',
      status: 'RESOLVED',
      fingerprint: '4a2b91d4e08cff1082a9',
      statusCode: 200,
      createdAt: new Date(Date.now() - 210000).toISOString()
    },
    {
      key: 'charge_cust_8832',
      status: 'IN_FLIGHT',
      fingerprint: '9e120da4b8716b23ce81',
      statusCode: null,
      createdAt: new Date(Date.now() - 15000).toISOString()
    },
    {
      key: 'sub_renew_7719',
      status: 'RESOLVED',
      fingerprint: '1c02bf89e47298aa124b',
      statusCode: 200,
      createdAt: new Date(Date.now() - 65000).toISOString()
    }
  ]
};

let simCallCount = 0;

function createServer() {
  const htmlContent = fs.readFileSync(HTML_FILE, 'utf8');

  const server = http.createServer((req, res) => {
    const parsedUrl = new URL(req.url, `http://localhost:${PORT}`);
    const pathname = parsedUrl.pathname;

    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', '*');

    if (pathname === '/' || pathname === '/idemgate/dashboard' || pathname === '/dashboard.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(htmlContent);
      return;
    }

    if (pathname === '/idemgate/api/v1/config') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(state.config));
      return;
    }

    if (pathname === '/idemgate/api/v1/stats') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(state.stats));
      return;
    }

    if (pathname === '/idemgate/api/v1/keys') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ keys: state.keys }));
      return;
    }

    if (pathname.startsWith('/idemgate/api/v1/inspect/')) {
      const key = decodeURIComponent(pathname.replace('/idemgate/api/v1/inspect/', ''));
      const found = state.keys.find(k => k.key === key) || {
        key,
        status: 'RESOLVED',
        statusCode: 201,
        fingerprint: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        key: found.key,
        status: found.status,
        statusCode: found.statusCode || 201,
        fingerprint: found.fingerprint || 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        tenantId: 'tenant-acme-corp',
        hasCachedResponse: true,
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 86400000).toISOString(),
        cachedResponse: {
          orderId: '101',
          amount: 199.99,
          currency: 'USD',
          status: 'SUCCESS',
          transactionId: 'txn_9921fa44'
        }
      }, null, 2));
      return;
    }

    if (pathname === '/api/v1/orders' && req.method === 'POST') {
      let body = '';
      req.on('data', chunk => body += chunk);
      req.on('end', () => {
        simCallCount++;
        const isReplay = simCallCount > 1;

        if (!isReplay) {
          state.stats.totalRequests++;
          state.stats.cacheMisses++;
          state.stats.activeCachedKeys++;
          state.keys.unshift({
            key: 'demo-order-key-101',
            status: 'RESOLVED',
            fingerprint: 'e3b0c44298fc1c149afb',
            statusCode: 201,
            createdAt: new Date().toISOString()
          });
        } else {
          state.stats.totalRequests++;
          state.stats.cacheHits++;
          state.stats.raceConditionsSerialized++;
          state.stats.cacheHitRatioPercent = +(
            (state.stats.cacheHits / (state.stats.cacheHits + state.stats.cacheMisses)) * 100
          ).toFixed(1);
        }

        const statusCode = 201;
        const latency = isReplay ? 1 : 12;

        res.writeHead(statusCode, {
          'Content-Type': 'application/json',
          'Idempotent-Replayed': isReplay ? 'true' : 'false',
          'X-IdemGate-Request-Id': isReplay ? 'idm_req_108bb4e' : 'idm_req_992f01a',
          'X-IdemGate-Latency-Ms': String(latency),
          'X-RateLimit-Remaining': '98'
        });

        res.end(JSON.stringify({
          orderId: '101',
          amount: 199.99,
          currency: 'USD',
          status: 'SUCCESS',
          transactionId: 'txn_9921fa44',
          note: isReplay ? 'Served verbatim from in-memory cache' : 'Processed by upstream billing engine'
        }, null, 2));
      });
      return;
    }

    res.writeHead(404);
    res.end('Not Found');
  });

  return server;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function record() {
  if (fs.existsSync(FRAMES_DIR)) {
    fs.rmSync(FRAMES_DIR, { recursive: true, force: true });
  }
  fs.mkdirSync(FRAMES_DIR, { recursive: true });
  fs.mkdirSync(IMAGES_DIR, { recursive: true });

  const server = createServer();
  await new Promise(resolve => server.listen(PORT, resolve));
  console.log(`📡 Mock IdemGate server listening on http://127.0.0.1:${PORT}`);

  console.log('🚀 Launching Puppeteer...');
  const chromePath = fs.existsSync('C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe')
    ? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
    : undefined;

  const browser = await puppeteer.launch({
    headless: 'new',
    executablePath: chromePath,
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 800, deviceScaleFactor: 2 });
  await page.goto(`http://127.0.0.1:${PORT}/dashboard.html`, { waitUntil: 'networkidle0' });
  await sleep(1000);

  // Take High-Res Overview Screenshot (dashboard-live-overview.png)
  console.log('📸 Capturing high-res dashboard-live-overview.png...');
  await page.screenshot({ path: OUTPUT_OVERVIEW, fullPage: false });

  let frameCount = 0;
  let recording = true;

  const captureLoop = async () => {
    while (recording) {
      const frameNum = String(frameCount++).padStart(5, '0');
      try {
        await page.screenshot({
          path: path.join(FRAMES_DIR, `frame_${frameNum}.png`),
          fullPage: false
        });
      } catch (err) {}
      await sleep(100);
    }
  };

  const recordingPromise = captureLoop();

  // 1. Initial view: show live stats and key register
  console.log('Action 1: Showing dashboard overview...');
  await sleep(1500);

  // 2. Click Send Request in simulator (First call: Miss -> Upstream processed)
  console.log('Action 2: Submitting initial order request (Cache Miss)...');
  const submitBtn = await page.$('button.btn-primary');
  if (submitBtn) {
    await submitBtn.click();
  }
  await sleep(2500);

  // 3. Click Send Request AGAIN with the exact same key (Second call: Hit -> Instant Replayed)
  console.log('Action 3: Submitting retry with same key (Cache Hit & Replay)...');
  if (submitBtn) {
    await submitBtn.click();
  }
  await sleep(2500);

  // 4. Click Inspect on the newly added demo key in the table
  console.log('Action 4: Inspecting key details in modal...');
  const inspectBtns = await page.$$('table button.btn');
  if (inspectBtns.length > 0) {
    await inspectBtns[0].click();
  }
  await sleep(1000);

  // Capture High-Res Modal Screenshot (dashboard-inspect-modal.png)
  console.log('📸 Capturing high-res dashboard-inspect-modal.png...');
  await page.screenshot({ path: OUTPUT_MODAL, fullPage: false });
  await sleep(2000);

  // 5. Close the inspect modal
  console.log('Action 5: Closing modal...');
  const closeBtn = await page.$('.modal-close');
  if (closeBtn) {
    await closeBtn.click();
  }
  await sleep(1500);

  // Stop recording
  recording = false;
  await recordingPromise;
  await browser.close();
  server.close();

  console.log(`🎬 Captured ${frameCount} frames. Encoding optimized GIF with FFmpeg...`);

  const ffmpegCmd = `ffmpeg -y -framerate 8 -i "${path.join(FRAMES_DIR, 'frame_%05d.png')}" -vf "scale=920:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128:reserve_transparent=0[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" -loop 0 "${OUTPUT_GIF}"`;

  execSync(ffmpegCmd, { stdio: 'inherit' });

  const stat = fs.statSync(OUTPUT_GIF);
  console.log(`✅ Success! Generated ${OUTPUT_GIF} (${(stat.size / 1024).toFixed(1)} KB)`);

  // Clean frames
  fs.rmSync(FRAMES_DIR, { recursive: true, force: true });

  // Sync to MTLG Site if exists
  if (fs.existsSync(MTLG_SITE_IMAGES)) {
    console.log('🔄 Syncing updated assets to mtlg-site/images...');
    fs.copyFileSync(OUTPUT_OVERVIEW, path.join(MTLG_SITE_IMAGES, 'idemgate-1.png'));
    fs.copyFileSync(OUTPUT_MODAL, path.join(MTLG_SITE_IMAGES, 'idemgate-2.png'));
    fs.copyFileSync(OUTPUT_GIF, path.join(MTLG_SITE_IMAGES, 'idemgate-3.gif'));
    console.log('✅ Synchronized to mtlg-site successfully!');
  }
}

record().catch(err => {
  console.error('Fatal error:', err);
  process.exit(1);
});
