#!/usr/bin/env python3
"""HTML 문서의 특정 요소를 PNG로 굽는 공용 렌더러.

ERD 목록과 OAS 맵은 Mermaid 로 그릴 수 없는 표·카드 배치라 HTML/CSS 로 짜고
요소를 잘라 쓴다. 이 캡처도 예전에는 임시 스크립트로 돌리고 지웠다.
mermaid_png.py 와 같은 자리에 영구 도구로 둔다.
"""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLAYWRIGHT = ROOT / "frontend" / "node_modules" / "playwright" / "index.mjs"

CAPTURE = """
import { chromium } from '__PLAYWRIGHT__'

const jobs = __JOBS__
const browser = await chromium.launch()
for (const job of jobs) {
  const page = await browser.newPage({ deviceScaleFactor: 2 })
  await page.goto('file://' + job.html, { waitUntil: 'networkidle' })
  if (job.wait) await page.waitForSelector(job.wait, { timeout: 60000 })
  const el = await page.$(job.selector)
  if (!el) { console.error(job.html + ': ' + job.selector + ' 없음'); process.exit(1) }
  await el.screenshot({ path: job.out })
  console.log(job.out.split('/').pop())
  await page.close()
}
await browser.close()
"""


def render(jobs):
    """jobs 는 (html 경로, CSS 선택자, 출력 png 경로[, 기다릴 선택자]) 의 목록이다.

    네 번째 값은 불러온 뒤에 그려지는 페이지(Mermaid)가 다 그려졌다는 표지다.
    networkidle 은 스크립트를 받은 시점이라 그림이 아직 없을 수 있다.
    """
    if not PLAYWRIGHT.exists():
        print("playwright 없음. frontend에서 npm install 먼저 실행할 것", file=sys.stderr)
        return 1
    payload = [{"html": str(pathlib.Path(job[0]).resolve()), "selector": job[1], "out": str(job[2]),
                "wait": job[3] if len(job) > 3 else None} for job in jobs]
    script = ROOT / "tools" / "_capture_html.mjs"
    script.write_text(
        CAPTURE.replace("__PLAYWRIGHT__", PLAYWRIGHT.as_uri())
               .replace("__JOBS__", json.dumps(payload, ensure_ascii=False)),
        encoding="utf-8")
    try:
        return subprocess.run(["node", str(script)], cwd=ROOT).returncode
    finally:
        script.unlink(missing_ok=True)
