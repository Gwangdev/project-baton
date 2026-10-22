import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, openSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

/**
 * 브라우저 테스트용 실제 스택을 띄운다. 일회용 pgvector/pgvector:pg17 컨테이너(백엔드 통합 테스트와 같은 이미지)와
 * `bootRunE2e`로 실행한 Spring 애플리케이션이다. 애플리케이션은 저장소의 Flyway 마이그레이션 전부를 적용하고, `e2e` 프로필에서
 * stub AI 프로필로 합성 fixture를 넣는다.
 *
 * 비밀값은 코드에 두지 않는다. DB 비밀번호는 실행마다 무작위로 만들고, seed 비밀번호는 BATON_SEED_PASSWORD에서
 * 받거나 여기서 만들어 내보내 테스트가 같은 값을 읽게 한다. 두 값 모두 환경변수로만 백엔드에 전달한다.
 * 돌려주는 함수는 백엔드를 멈추고 컨테이너를 지운다.
 */

const repoRoot = fileURLToPath(new URL('../../../../', import.meta.url))
const apiPort = process.env.BATON_E2E_API_PORT ?? '18080'
const container = `baton-e2e-db-${process.pid}`
// 백엔드가 e2e 실행에서만 자기 PID를 쓰는 파일 (backend/src/e2e/resources/META-INF/spring.factories).
const pidFile = `${tmpdir()}/baton-e2e-${process.pid}.pid`

async function waitFor(what: string, check: () => Promise<boolean> | boolean, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await check()) return
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error(`Timed out waiting for ${what}`)
}

function databasePort(): string {
  const mapping = execFileSync('docker', ['port', container, '5432'], { encoding: 'utf8' }).trim().split('\n')[0]
  return mapping.slice(mapping.lastIndexOf(':') + 1)
}

export default async function globalSetup(): Promise<() => Promise<void>> {
  const databasePassword = randomBytes(16).toString('hex')
  process.env.BATON_SEED_PASSWORD ||= randomBytes(12).toString('hex')

  execFileSync('docker', [
    'run', '-d', '--rm', '--name', container, '-e', `POSTGRES_PASSWORD=${databasePassword}`, '-e', 'POSTGRES_DB=baton',
    '-p', '127.0.0.1::5432', 'pgvector/pgvector:pg17',
  ], { stdio: 'ignore' })

  let backend: ChildProcess | null = null
  const stop = (pid: number) => {
    try {
      process.kill(pid, 'SIGTERM')
    } catch {
      // 이미 멈췄다.
    }
  }
  const teardown = async () => {
    // Gradle의 JavaExec는 애플리케이션 JVM을 Gradle 프로세스 그룹 밖에서 띄운다. 그래서 JVM은 스스로 쓴 PID
    // 파일로, Gradle은 자기 그룹으로 멈춘다. 포트로 PID를 찾는 방식은 lsof가 없는 환경에서 실패한다.
    if (existsSync(pidFile)) {
      const pid = Number(readFileSync(pidFile, 'utf8').trim())
      if (Number.isSafeInteger(pid) && pid > 0) stop(pid)
      rmSync(pidFile, { force: true })
    }
    if (backend?.pid) stop(-backend.pid)
    // --rm은 컨테이너가 스스로 멈출 때만 익명 volume(PostgreSQL 데이터)을 함께 지운다. 여기서 먼저 지우면 그 정리가
    // 일어나지 않아 실행마다 volume 하나가 남았으므로 -v로 함께 지운다.
    execFileSync('docker', ['rm', '-fv', container], { stdio: 'ignore' })
  }

  try {
    await waitFor('PostgreSQL', () => {
      try {
        execFileSync('docker', ['exec', container, 'pg_isready', '-U', 'postgres', '-d', 'baton'], { stdio: 'ignore' })
        return true
      } catch {
        return false
      }
    }, 60_000)

    // Playwright가 시작할 때 비우는 test-results 밖에 둔다. 이 패키지에서 *.log는 git이 무시한다.
    const log = openSync(`${repoRoot}frontend/e2e-backend.log`, 'w')
    backend = spawn('./backend/gradlew', ['-p', 'backend', 'bootRunE2e', '--no-daemon', '--quiet'], {
      cwd: repoRoot,
      detached: true,
      stdio: ['ignore', log, log],
      env: {
        ...process.env,
        BATON_DB_URL: `jdbc:postgresql://127.0.0.1:${databasePort()}/baton`,
        BATON_DB_USERNAME: 'postgres',
        BATON_DB_PASSWORD: databasePassword,
        BATON_SEED_PASSWORD: process.env.BATON_SEED_PASSWORD,
        SERVER_PORT: apiPort,
        SPRING_PID_FILE: pidFile,
      },
    })

    await waitFor('the backend', async () => {
      if (backend?.exitCode !== null) throw new Error('Backend exited during start-up; see frontend/e2e-backend.log')
      try {
        return (await fetch(`http://127.0.0.1:${apiPort}/api/v1/auth/csrf`)).ok
      } catch {
        return false
      }
    }, 240_000)
  } catch (error) {
    await teardown()
    throw error
  }
  return teardown
}
