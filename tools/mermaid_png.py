#!/usr/bin/env python3
"""Mermaid 원본을 PNG로 굽는 공용 렌더러.

UI 흐름도와 API 트리가 같은 파이프라인을 쓴다. 두 벌로 두면 한쪽만 고쳐져
그림의 색과 간격이 갈라진다.

Mermaid 기본 레이아웃(dagre)은 간선을 곡선 스플라인으로 그린다. 화면과 분기가
많아지면 선이 서로 교차하고 간선 라벨이 붙어 한 문장처럼 읽혔다. ELK 는 간선을
직각으로 라우팅하고 라벨 자리를 따로 잡으므로 같은 원본에서 훨씬 정돈된 그림이
나온다. 그래서 렌더 엔진만 ELK 로 바꾸고 원본 문법은 Mermaid 그대로 둔다.

이전에는 캡처 스크립트를 frontend/ 에 임시로 만들어 돌리고 지웠다. 그림이 바뀔
때마다 스크립트를 다시 쓰게 되어 재현이 되지 않았다. 여기에 영구 도구로 남긴다.
"""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLAYWRIGHT = ROOT / "frontend" / "node_modules" / "playwright" / "index.mjs"

CAPTURE = """
import { chromium } from '__PLAYWRIGHT__'
import fs from 'node:fs'

const jobs = __JOBS__
const HTML = `<!doctype html><meta charset="utf-8">
<style>
  body { margin: 0; padding: 28px; background: #ffffff;
         font-family: Pretendard, -apple-system, BlinkMacSystemFont, sans-serif; }
  #mmd svg { max-width: none !important; }
</style>
<pre class="mermaid" id="mmd"></pre>
<script type="module">
import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs'
import elk from 'https://cdn.jsdelivr.net/npm/@mermaid-js/layout-elk@0.1.7/dist/mermaid-layout-elk.esm.min.mjs'
mermaid.registerLayoutLoaders(elk)
mermaid.initialize({
  startOnLoad: false,
  theme: 'base',
  layout: 'elk',
  fontFamily: 'Pretendard, -apple-system, sans-serif',
  themeVariables: {
    primaryColor: '#ffffff', primaryTextColor: '#0b1b33', primaryBorderColor: '#0b1b33',
    lineColor: '#5b6b80', tertiaryColor: '#f4f6f9', fontSize: '14px',
  },
  elk: {
    // ORTHOGONAL 은 간선을 직각으로 꺾어 그린다. 선이 겹쳐도 어느 상자에서
    // 나와 어디로 가는지 눈으로 따라갈 수 있다.
    edgeRouting: 'ORTHOGONAL',
    mergeEdges: false,
    nodePlacementStrategy: 'BRANDES_KOEPF',
    // 기본 GREEDY 는 순환을 끊을 때 주 흐름 간선을 뒤집어 S10과 S11의 위아래가
    // 뒤바뀌었다. DEPTH_FIRST 는 깊이우선 탐색이 찾은 진짜 되돌이 간선을 고른다.
    cycleBreakingStrategy: 'DEPTH_FIRST',
  },
  flowchart: { padding: 16, useMaxWidth: false },
})
document.getElementById('mmd').textContent = __SRC__
try {
  await mermaid.run({ nodes: [document.getElementById('mmd')] })
} catch (e) {
  window.__error = e.message
}
window.__done = true
</script>`

const browser = await chromium.launch()

for (const job of jobs) {
  const page = await browser.newPage({ deviceScaleFactor: 2 })
  // 원본은 HTML 문자열에 직접 박아 넣는다. setContent 는 문서를 갈아엎으므로
  // 미리 심어 둔 전역은 페이지 스크립트가 읽기 전에 사라진다.
  const src = JSON.stringify(fs.readFileSync(job.src, 'utf8'))
  await page.setContent(HTML.replace('__SRC__', src), { waitUntil: 'networkidle' })
  await page.waitForFunction(() => window.__done, null, { timeout: 30000 })
  const err = await page.evaluate(() => window.__error)
  if (err) { console.error(job.src + ': ' + err); process.exit(1) }
  await (await page.$('#mmd svg')).screenshot({ path: job.out })
  console.log(job.out.split('/').pop())
  await page.close()
}
await browser.close()

"""


def render(jobs):
    """jobs 는 (원본 .mmd 경로, 출력 .png 경로) 쌍의 목록이다."""
    if not PLAYWRIGHT.exists():
        print("playwright 없음. frontend에서 npm install 먼저 실행할 것", file=sys.stderr)
        return 1

    payload = []
    for src, out in jobs:
        src, out = pathlib.Path(src), pathlib.Path(out)
        if not src.exists():
            print(f"원본 없음: {src}", file=sys.stderr)
            return 1
        payload.append({"src": str(src), "out": str(out)})

    # 캡처 스크립트는 매번 새로 쓴다. 원본이 바뀌었는데 스크립트만 남아 옛 그림을
    # 다시 뽑는 상황을 막는다.
    script = ROOT / "tools" / "_capture.mjs"
    body = (CAPTURE
            .replace("__PLAYWRIGHT__", PLAYWRIGHT.as_uri())
            .replace("__JOBS__", json.dumps(payload, ensure_ascii=False)))
    script.write_text(body, encoding="utf-8")
    try:
        return subprocess.run(["node", str(script)], cwd=ROOT).returncode
    finally:
        script.unlink(missing_ok=True)
