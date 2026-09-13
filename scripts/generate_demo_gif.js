const http = require('http');
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');
const puppeteer = require(path.resolve(__dirname, 'node_modules/puppeteer'));

const PORT = 8098;
const HTML_FILE = path.resolve(__dirname, 'idemgate/src/main/resources/static/dashboard.html');
const IMAGES_DIR = path.resolve(__dirname, 'idemgate/docs/images');
const FRAMES_DIR = path.resolve(__dirname, '.frames_idemgate');
const OUTPUT_GIF = path.join(IMAGES_DIR, 'idemgate_demo.gif');

let state = {
  stats: {
    totalRequests: 142,
    cacheHitRatioPercent: 38.2,
    cacheHits: 54,
    cacheMisses: 88,
    raceConditionsSerialized: 19,
    activeCachedKeys: 12,
    rateLimitedRequests: 3
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
      status: 'IN-FLIGHT',
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

    // CORS & Headers
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', '*');

    if (pathname === '/' || pathname === '/idemgate/dashboard' || pathname === '/dashboard.html') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(htmlContent);
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
        statusCode: 200,
        fingerprint: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
      };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        idempotencyKey: found.key,
        status: found.status,
        statusCode: found.statusCode || 200,
        fingerprint: found.fingerprint || 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        tenantId: 'tenant-acme-corp',
        ttlRemainingSeconds: 86340,
        cachedResponse: {
          orderId: '101',
          status: 'CONFIRMED',
          transactionId: 'txn_89412a8'
        },
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736'
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

        const statusCode = isReplay ? 200 : 201;
        const latency = isReplay ? 2 : 14;

        res.writeHead(statusCode, {
          'Content-Type': 'application/json',
          'Idempotent-Replayed': isReplay ? 'true' : 'false',
          'X-IdemGate-Request-Id': isReplay ? 'idm_req_108bb4e' : 'idm_req_992f01a',
          'X-IdemGate-Latency-Ms': String(latency)
        });

        res.end(JSON.stringify({
          orderId: '101',
          status: 'CONFIRMED',
          transactionId: 'txn_89412a8',
          note: isReplay ? 'Served verbatim from distributed cache' : 'Processed by upstream billing engine'
        }));
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

  const server = createServer();
  await new Promise(resolve => server.listen(PORT, resolve));
  console.log(`📡 Mock IdemGate server listening on http://127.0.0.1:${PORT}`);

  console.log('🚀 Launching Puppeteer...');
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
  });

  const page = await browser.newPage();
  await page.setViewport({ width: 1200, height: 750, deviceScaleFactor: 1 });
  await page.goto(`http://127.0.0.1:${PORT}/dashboard.html`, { waitUntil: 'networkidle0' });
  await sleep(1000);

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
      await sleep(100); // 10 FPS
    }
  };

  const recordingPromise = captureLoop();

  // 1. Initial view: show live stats and key register
  console.log('Action 1: Showing dashboard overview...');
  await sleep(1800);

  // 2. Click Send Request in simulator (First call: Miss -> Upstream processed)
  console.log('Action 2: Submitting initial order request (Cache Miss)...');
  const submitBtn = await page.$('button.btn-primary');
  if (submitBtn) {
    await submitBtn.click();
  }
  await sleep(2800);

  // 3. Click Send Request AGAIN with the exact same key (Second call: Hit -> Instant Replayed)
  console.log('Action 3: Submitting retry with same key (Cache Hit & Replay)...');
  if (submitBtn) {
    await submitBtn.click();
  }
  await sleep(2800);

  // 4. Click Inspect on the newly added demo key in the table
  console.log('Action 4: Inspecting key details in modal...');
  const inspectBtns = await page.$$('table button.btn');
  if (inspectBtns.length > 0) {
    await inspectBtns[0].click();
  }
  await sleep(3000);

  // 5. Close the inspect modal
  console.log('Action 5: Closing modal...');
  const closeBtn = await page.$('.modal-close');
  if (closeBtn) {
    await closeBtn.click();
  }
  await sleep(1800);

  // Stop recording
  recording = false;
  await recordingPromise;
  await browser.close();
  server.close();

  console.log(`🎬 Captured ${frameCount} frames. Encoding optimized GIF with FFmpeg...`);
  if (!fs.existsSync(IMAGES_DIR)) {
    fs.mkdirSync(IMAGES_DIR, { recursive: true });
  }

  const ffmpegCmd = `ffmpeg -y -framerate 8 -i "${path.join(FRAMES_DIR, 'frame_%05d.png')}" -vf "scale=880:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128:reserve_transparent=0[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" -loop 0 "${OUTPUT_GIF}"`;

  execSync(ffmpegCmd, { stdio: 'inherit' });

  const stat = fs.statSync(OUTPUT_GIF);
  console.log(`✅ Success! Generated ${OUTPUT_GIF} (${(stat.size / 1024).toFixed(1)} KB)`);

  // Clean frames
  fs.rmSync(FRAMES_DIR, { recursive: true, force: true });
}

record().catch(err => {
  console.error('Fatal error:', err);
  process.exit(1);
});
