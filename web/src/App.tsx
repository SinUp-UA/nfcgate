import { useEffect, useMemo, useState } from 'react'

type ExportFormat = 'jsonl' | 'csv'

type AuthMode = 'checking' | 'login' | 'bootstrap' | 'authed'

function toIsoLocalInputValue(d: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    d.getFullYear() +
    '-' +
    pad(d.getMonth() + 1) +
    '-' +
    pad(d.getDate()) +
    'T' +
    pad(d.getHours()) +
    ':' +
    pad(d.getMinutes())
  )
}


function parseContentDispositionFilename(value: string | null): string | null {
  if (!value) return null
  const m = /filename="?([^";]+)"?/i.exec(value)
  return m?.[1] ?? null
}

function formatUnixSeconds(ts: number | null | undefined): string {
  if (!ts) return '—'
  try {
    return new Date(ts * 1000).toISOString()
  } catch {
    return String(ts)
  }
}

function formatBytes(n: number | null | undefined): string {
  if (n == null) return '—'
  if (!Number.isFinite(n)) return String(n)
  if (n < 1024) return `${n} B`
  const kb = n / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KiB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(1)} MiB`
  const gb = mb / 1024
  return `${gb.toFixed(1)} GiB`
}

function App() {
  const now = useMemo(() => new Date(), [])
  const oneHourAgo = useMemo(() => new Date(Date.now() - 60 * 60 * 1000), [])

  const [authMode, setAuthMode] = useState<AuthMode>('checking')
  const [authToken, setAuthToken] = useState<string>(() => localStorage.getItem('nfcgate_token') ?? '')
  const [authUser, setAuthUser] = useState<string>(() => localStorage.getItem('nfcgate_user') ?? '')
  const [authError, setAuthError] = useState<string>('')
  const [authUsername, setAuthUsername] = useState<string>('')
  const [authPassword, setAuthPassword] = useState<string>('')

  const [startLocal, setStartLocal] = useState(toIsoLocalInputValue(oneHourAgo))
  const [endLocal, setEndLocal] = useState(toIsoLocalInputValue(now))
  const [format, setFormat] = useState<ExportFormat>('jsonl')
  const [tag, setTag] = useState<string>('')
  const [origin, setOrigin] = useState<string>('')
  const [session, setSession] = useState<string>('')
  const [status, setStatus] = useState<string>('')
  const [error, setError] = useState<string>('')

  const [statsStatus, setStatsStatus] = useState<string>('')
  const [statsError, setStatsError] = useState<string>('')
  const [stats, setStats] = useState<
    | null
    | {
        highlight: { '80CA'?: number }
        commands_reader: { cla_ins: string; count: number }[]
        commands_reader_header4?: { header4: string; count: number }[]
        responses_card_sw?: { sw: string; count: number }[]
        parsed_apdu: number
        parse_errors: number
        total_log_rows_scanned: number
      }
  >(null)

  const [healthStatus, setHealthStatus] = useState<string>('')
  const [healthError, setHealthError] = useState<string>('')
  const [health, setHealth] = useState<
    | null
    | {
        status: string
        server: string
        db_configured: boolean
        protobuf_indexing: boolean
        started_unix: number | null
        uptime_seconds?: number | null
        log_bytes_mode?: 'full' | 'redact' | 'none' | string
        db_file_bytes?: number | null
        counts?: {
          logs: number
          apdu_events: number
          payloads: number | null
        } | null
        latest?: {
          log_ts_unix: number | null
          apdu_ts_unix: number | null
        } | null
        retention?: {
          db_days?: number
          jsonl_days?: number
          sweep_seconds?: number
        }
      }
  >(null)

  const [tailStatus, setTailStatus] = useState<string>('')
  const [tailError, setTailError] = useState<string>('')
  const [tailRows, setTailRows] = useState<
    { ts: string; tag: string; origin: string; session: number | null; args: unknown[] }[]
  >([])

  const [adminStatus, setAdminStatus] = useState<string>('')
  const [adminError, setAdminError] = useState<string>('')
  const [adminUsers, setAdminUsers] = useState<
    { id: number; username: string; created_unix: number | null; disabled: boolean }[]
  >([])
  const [newAdminUsername, setNewAdminUsername] = useState<string>('')
  const [newAdminPassword, setNewAdminPassword] = useState<string>('')

  function persistAuth(nextToken: string, nextUser: string) {
    setAuthToken(nextToken)
    setAuthUser(nextUser)
    if (nextToken) localStorage.setItem('nfcgate_token', nextToken)
    else localStorage.removeItem('nfcgate_token')
    if (nextUser) localStorage.setItem('nfcgate_user', nextUser)
    else localStorage.removeItem('nfcgate_user')
  }

  function logout() {
    persistAuth('', '')
    setAuthUsername('')
    setAuthPassword('')
    setAuthError('')
    setAuthMode('login')
  }

  async function apiFetch(input: RequestInfo | URL, init?: RequestInit) {
    const headers = new Headers(init?.headers)
    if (authToken) headers.set('X-NFCGate-Token', authToken)
    return fetch(input, { ...init, headers })
  }

  useEffect(() => {
    let cancelled = false

    async function check() {
      setAuthError('')
      setAuthMode(authToken ? 'authed' : 'checking')
      try {
        const resp = await fetch('/api/auth/status', { cache: 'no-store' })
        const data = (await resp.json()) as { has_admins?: boolean }
        if (cancelled) return
        if (authToken) {
          setAuthMode('authed')
        } else {
          setAuthMode(data.has_admins ? 'login' : 'bootstrap')
        }
      } catch {
        if (cancelled) return
        setAuthMode(authToken ? 'authed' : 'login')
      }
    }

    void check()
    return () => {
      cancelled = true
    }
  }, [authToken])

  useEffect(() => {
    if (authMode === 'authed') {
      void loadAdmins()
    }
  }, [authMode])

  async function submitAuth(endpoint: '/api/auth/login' | '/api/auth/bootstrap') {
    setAuthError('')
    if (!authUsername.trim() || !authPassword) {
      setAuthError('Введите логин и пароль')
      return
    }

    try {
      const resp = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: authUsername.trim(), password: authPassword }),
      })
      if (!resp.ok) {
        const payload = (await resp.json().catch(() => ({}))) as { error?: string }
        if (payload.error === 'no_admins') {
          setAuthMode('bootstrap')
          setAuthError('Нет администраторов. Создайте первого администратора.')
          return
        }
        setAuthError(`Ошибка авторизации: ${payload.error ?? resp.status}`)
        return
      }
      const data = (await resp.json()) as { token: string; user?: { username?: string } }
      persistAuth(data.token, data.user?.username ?? authUsername.trim())
      setAuthMode('authed')
    } catch {
      setAuthError('Ошибка сети при авторизации')
    }
  }

  async function download() {
    setError('')
    setStatus('Загрузка...')

    const start = new Date(startLocal)
    const end = new Date(endLocal)
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
      setStatus('')
      setError('Некорректная дата/время')
      return
    }
    if (end < start) {
      setStatus('')
      setError('Конец диапазона должен быть после начала')
      return
    }

    const from = start.toISOString()
    const to = end.toISOString()
    let url = `/api/logs/export?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&format=${encodeURIComponent(format)}`
    if (tag.trim()) url += `&tag=${encodeURIComponent(tag.trim())}`
    if (origin.trim()) url += `&origin=${encodeURIComponent(origin.trim())}`
    if (session.trim()) url += `&session=${encodeURIComponent(session.trim())}`

    setStatus('Запрашиваю экспорт...')
    const resp = await apiFetch(url, { cache: 'no-store' })
    if (!resp.ok) {
      setStatus('')
      setError(`Ошибка экспорта: ${resp.status}`)
      return
    }

    const blob = await resp.blob()
    const suggestedName = parseContentDispositionFilename(resp.headers.get('content-disposition'))
    const filename = suggestedName ?? `logs_${from}_${to}.${format}`.replaceAll(':', '-')

    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setStatus('Готово')
  }

  async function loadStats() {
    setStatsError('')
    setStatsStatus('Загрузка аналитики...')
    setStats(null)

    const start = new Date(startLocal)
    const end = new Date(endLocal)
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
      setStatsStatus('')
      setStatsError('Некорректная дата/время')
      return
    }
    if (end < start) {
      setStatsStatus('')
      setStatsError('Конец диапазона должен быть после начала')
      return
    }

    const from = start.toISOString()
    const to = end.toISOString()
    let url = `/api/apdu/stats?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&top=20`
    if (tag.trim()) url += `&tag=${encodeURIComponent(tag.trim())}`
    if (origin.trim()) url += `&origin=${encodeURIComponent(origin.trim())}`
    if (session.trim()) url += `&session=${encodeURIComponent(session.trim())}`

    try {
      const resp = await apiFetch(url, { cache: 'no-store' })
      if (!resp.ok) {
        setStatsStatus('')
        setStatsError(`Ошибка аналитики: ${resp.status}`)
        return
      }
      const data = (await resp.json()) as NonNullable<typeof stats>
      setStats(data)
      setStatsStatus('Готово')
    } catch {
      setStatsStatus('')
      setStatsError('Ошибка сети при загрузке аналитики')
    }
  }

  async function loadHealth() {
    setHealthError('')
    setHealthStatus('Проверка...')
    setHealth(null)
    try {
      const resp = await fetch('/api/health', { cache: 'no-store' })
      if (!resp.ok) {
        setHealthStatus('')
        setHealthError(`Ошибка health: ${resp.status}`)
        return
      }
      const data = (await resp.json()) as NonNullable<typeof health>
      setHealth(data)
      setHealthStatus('Готово')
    } catch {
      setHealthStatus('')
      setHealthError('Ошибка сети при проверке')
    }
  }

  async function loadTail() {
    setTailError('')
    setTailStatus('Загрузка...')
    setTailRows([])

    let url = `/api/logs/tail?limit=200`
    if (tag.trim()) url += `&tag=${encodeURIComponent(tag.trim())}`
    if (origin.trim()) url += `&origin=${encodeURIComponent(origin.trim())}`
    if (session.trim()) url += `&session=${encodeURIComponent(session.trim())}`

    try {
      const resp = await apiFetch(url, { cache: 'no-store' })
      if (!resp.ok) {
        setTailStatus('')
        setTailError(`Ошибка tail: ${resp.status}`)
        return
      }
      const data = (await resp.json()) as {
        rows?: { ts: string; tag: string; origin: string; session: number | null; args: unknown[] }[]
      }
      setTailRows(data.rows ?? [])
      setTailStatus('Готово')
    } catch {
      setTailStatus('')
      setTailError('Ошибка сети при загрузке')
    }
  }

  async function loadAdmins() {
    setAdminError('')
    setAdminStatus('Загрузка администраторов...')
    setAdminUsers([])
    try {
      const resp = await apiFetch('/api/admin/users', { cache: 'no-store' })
      if (resp.status === 401) {
        logout()
        return
      }
      if (!resp.ok) {
        setAdminStatus('')
        setAdminError(`Ошибка загрузки администраторов: ${resp.status}`)
        return
      }
      const data = (await resp.json()) as {
        users?: { id: number; username: string; created_unix: number | null; disabled: boolean }[]
      }
      setAdminUsers(data.users ?? [])
      setAdminStatus('Готово')
    } catch {
      setAdminStatus('')
      setAdminError('Ошибка сети при загрузке администраторов')
    }
  }

  async function createAdmin() {
    setAdminError('')
    setAdminStatus('Создание администратора...')
    if (!newAdminUsername.trim() || !newAdminPassword) {
      setAdminStatus('')
      setAdminError('Введите логин и пароль для нового администратора')
      return
    }
    try {
      const resp = await apiFetch('/api/admin/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: newAdminUsername.trim(), password: newAdminPassword }),
      })
      if (resp.status === 401) {
        logout()
        return
      }
      if (!resp.ok) {
        const payload = (await resp.json().catch(() => ({}))) as { error?: string }
        setAdminStatus('')
        setAdminError(`Ошибка создания: ${payload.error ?? resp.status}`)
        return
      }
      setNewAdminPassword('')
      setAdminStatus('Готово')
      await loadAdmins()
    } catch {
      setAdminStatus('')
      setAdminError('Ошибка сети при создании администратора')
    }
  }

  return (
    <div className="min-h-screen bg-white text-slate-900">
      <div className="mx-auto max-w-3xl px-6 py-10">
        <h1 className="text-3xl font-semibold tracking-tight">NFC Gate</h1>
        <div className="mt-2 flex items-center justify-between gap-3 text-sm">
          <div className="text-slate-600">
            {authMode === 'authed' ? (
              <span>
                Вы вошли как <span className="font-medium text-slate-900">{authUser || 'admin'}</span>
              </span>
            ) : (
              <span className="text-slate-600">Требуется вход в панель</span>
            )}
          </div>
          {authMode === 'authed' ? (
            <button
              className="rounded border border-slate-300 px-3 py-2"
              onClick={() => logout()}
            >
              Выйти
            </button>
          ) : null}
        </div>

        {authMode !== 'authed' ? (
          <div className="mt-6 rounded-lg border border-slate-200 p-4">
            <h2 className="text-lg font-medium">
              {authMode === 'bootstrap' ? 'Создать первого администратора' : 'Вход в панель'}
            </h2>
            <p className="mt-2 text-sm text-slate-600">
              {authMode === 'bootstrap'
                ? 'Панель ещё не инициализирована — создайте первого администратора.'
                : 'Введите логин и пароль администратора.'}
            </p>

            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <label className="text-sm">
                <div className="mb-1 text-slate-700">Логин</div>
                <input
                  className="w-full rounded border border-slate-300 px-3 py-2"
                  value={authUsername}
                  onChange={(e) => setAuthUsername(e.target.value)}
                  autoComplete="username"
                />
              </label>
              <label className="text-sm">
                <div className="mb-1 text-slate-700">Пароль</div>
                <input
                  className="w-full rounded border border-slate-300 px-3 py-2"
                  type="password"
                  value={authPassword}
                  onChange={(e) => setAuthPassword(e.target.value)}
                  autoComplete={authMode === 'bootstrap' ? 'new-password' : 'current-password'}
                />
              </label>

              <div className="flex items-end">
                <button
                  className="w-full rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white"
                  onClick={() => void submitAuth(authMode === 'bootstrap' ? '/api/auth/bootstrap' : '/api/auth/login')}
                  disabled={authMode === 'checking'}
                >
                  {authMode === 'bootstrap' ? 'Создать и войти' : 'Войти'}
                </button>
              </div>
            </div>

            {authMode === 'checking' ? (
              <p className="mt-3 text-sm text-slate-600">Проверка статуса...</p>
            ) : null}
            {authError ? <p className="mt-3 text-sm text-red-600">{authError}</p> : null}
          </div>
        ) : (
          <>
            <p className="mt-2 text-slate-600">Выгрузка логов и аналитика по диапазону времени</p>

            <div className="mt-6 rounded-lg border border-slate-200 p-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <label className="text-sm">
                  <div className="mb-1 text-slate-700">Начало</div>
                  <input
                    className="w-full rounded border border-slate-300 px-3 py-2"
                    type="datetime-local"
                    value={startLocal}
                    onChange={(e) => setStartLocal(e.target.value)}
                  />
                </label>

            <label className="text-sm">
              <div className="mb-1 text-slate-700">Конец</div>
              <input
                className="w-full rounded border border-slate-300 px-3 py-2"
                type="datetime-local"
                value={endLocal}
                onChange={(e) => setEndLocal(e.target.value)}
              />
            </label>

            <label className="text-sm">
              <div className="mb-1 text-slate-700">Формат</div>
              <select
                className="w-full rounded border border-slate-300 px-3 py-2"
                value={format}
                onChange={(e) => setFormat(e.target.value as ExportFormat)}
              >
                <option value="jsonl">JSONL</option>
                <option value="csv">CSV</option>
              </select>
            </label>

            <label className="text-sm">
              <div className="mb-1 text-slate-700">Tag (опционально)</div>
              <input
                className="w-full rounded border border-slate-300 px-3 py-2"
                placeholder="например: server или log"
                value={tag}
                onChange={(e) => setTag(e.target.value)}
              />
            </label>

                <label className="text-sm">
                  <div className="mb-1 text-slate-700">Origin (опционально)</div>
                  <input
                    className="w-full rounded border border-slate-300 px-3 py-2"
                    placeholder="например: 10.0.0.5:12345"
                    value={origin}
                    onChange={(e) => setOrigin(e.target.value)}
                  />
                </label>

                <label className="text-sm">
                  <div className="mb-1 text-slate-700">Session (опционально)</div>
                  <input
                    className="w-full rounded border border-slate-300 px-3 py-2"
                    placeholder="например: 1"
                    value={session}
                    onChange={(e) => setSession(e.target.value)}
                  />
                </label>

                <div className="flex items-end">
                  <button
                    className="w-full rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white"
                    onClick={() => void download()}
                  >
                    Скачать
                  </button>
                </div>
              </div>

              {status ? <p className="mt-3 text-sm text-slate-600">{status}</p> : null}
              {error ? <p className="mt-3 text-sm text-red-600">{error}</p> : null}
            </div>

        <div className="mt-6 rounded-lg border border-slate-200 p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-lg font-medium">Аналитика APDU</h2>
            <button
              className="rounded border border-slate-300 px-3 py-2 text-sm"
              onClick={() => void loadStats()}
            >
              Показать
            </button>
          </div>

          {statsStatus ? <p className="mt-3 text-sm text-slate-600">{statsStatus}</p> : null}
          {statsError ? <p className="mt-3 text-sm text-red-600">{statsError}</p> : null}

          {stats ? (
            <div className="mt-4 space-y-4">
              <div className="text-sm text-slate-700">
                <div>Разобрано APDU: {stats.parsed_apdu}</div>
                <div>Ошибок парсинга: {stats.parse_errors}</div>
                <div>Строк логов просмотрено: {stats.total_log_rows_scanned}</div>
                <div>
                  80CA (CLA+INS) встречается: {stats.highlight?.['80CA'] ?? 0}
                </div>
              </div>

              <div>
                <div className="text-sm font-medium text-slate-900">ТОП команд (Reader → Card) по CLA+INS</div>
                <div className="mt-2 overflow-hidden rounded border border-slate-200">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-left">
                      <tr>
                        <th className="px-3 py-2">CLA+INS</th>
                        <th className="px-3 py-2">Кол-во</th>
                      </tr>
                    </thead>
                    <tbody>
                      {stats.commands_reader.length ? (
                        stats.commands_reader.map((r) => (
                          <tr key={r.cla_ins} className="border-t border-slate-200">
                            <td className="px-3 py-2 font-mono">{r.cla_ins}</td>
                            <td className="px-3 py-2">{r.count}</td>
                          </tr>
                        ))
                      ) : (
                        <tr className="border-t border-slate-200">
                          <td className="px-3 py-2" colSpan={2}>
                            Нет данных
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              <div>
                <div className="text-sm font-medium text-slate-900">ТОП команд (Reader → Card) по заголовку CLA+INS+P1+P2</div>
                <div className="mt-2 overflow-hidden rounded border border-slate-200">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-left">
                      <tr>
                        <th className="px-3 py-2">CLA+INS+P1+P2</th>
                        <th className="px-3 py-2">Кол-во</th>
                      </tr>
                    </thead>
                    <tbody>
                      {stats.commands_reader_header4?.length ? (
                        stats.commands_reader_header4.map((r) => (
                          <tr key={r.header4} className="border-t border-slate-200">
                            <td className="px-3 py-2 font-mono">{r.header4}</td>
                            <td className="px-3 py-2">{r.count}</td>
                          </tr>
                        ))
                      ) : (
                        <tr className="border-t border-slate-200">
                          <td className="px-3 py-2" colSpan={2}>
                            Нет данных
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              <div>
                <div className="text-sm font-medium text-slate-900">ТОП ответов карты (Card → Reader) по SW1SW2</div>
                <div className="mt-2 overflow-hidden rounded border border-slate-200">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-left">
                      <tr>
                        <th className="px-3 py-2">SW1SW2</th>
                        <th className="px-3 py-2">Кол-во</th>
                      </tr>
                    </thead>
                    <tbody>
                      {stats.responses_card_sw?.length ? (
                        stats.responses_card_sw.map((r) => (
                          <tr key={r.sw} className="border-t border-slate-200">
                            <td className="px-3 py-2 font-mono">{r.sw}</td>
                            <td className="px-3 py-2">{r.count}</td>
                          </tr>
                        ))
                      ) : (
                        <tr className="border-t border-slate-200">
                          <td className="px-3 py-2" colSpan={2}>
                            Нет данных
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          ) : null}
        </div>

        <div className="mt-6 rounded-lg border border-slate-200 p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-lg font-medium">Статус</h2>
            <button
              className="rounded border border-slate-300 px-3 py-2 text-sm"
              onClick={() => void loadHealth()}
            >
              Проверить
            </button>
          </div>

          {healthStatus ? <p className="mt-3 text-sm text-slate-600">{healthStatus}</p> : null}
          {healthError ? <p className="mt-3 text-sm text-red-600">{healthError}</p> : null}

          {health ? (
            <div className="mt-3 text-sm text-slate-700">
              <div>DB: {health.db_configured ? 'OK' : 'нет'}</div>
              <div>APDU indexing: {health.protobuf_indexing ? 'OK' : 'нет'}</div>
              <div>Log bytes: {health.log_bytes_mode ?? 'unknown'}</div>
              <div>Uptime: {health.uptime_seconds ?? '—'} sec</div>
              <div>DB size: {formatBytes(health.db_file_bytes)}</div>
              <div>
                Rows: logs={health.counts?.logs ?? '—'}, apdu_events={health.counts?.apdu_events ?? '—'}, payloads={health.counts?.payloads ?? '—'}
              </div>
              <div>
                Latest: logs={formatUnixSeconds(health.latest?.log_ts_unix)}, apdu={formatUnixSeconds(health.latest?.apdu_ts_unix)}
              </div>
              <div>
                Retention: db_days={health.retention?.db_days ?? 0}, jsonl_days={health.retention?.jsonl_days ?? 0},
                sweep_seconds={health.retention?.sweep_seconds ?? 0}
              </div>
              <div>Started: {formatUnixSeconds(health.started_unix)}</div>
              <div>Server: {health.server}</div>
            </div>
          ) : null}
        </div>

        <div className="mt-6 rounded-lg border border-slate-200 p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-lg font-medium">Администраторы</h2>
            <button
              className="rounded border border-slate-300 px-3 py-2 text-sm"
              onClick={() => void loadAdmins()}
            >
              Обновить
            </button>
          </div>

          {adminStatus ? <p className="mt-3 text-sm text-slate-600">{adminStatus}</p> : null}
          {adminError ? <p className="mt-3 text-sm text-red-600">{adminError}</p> : null}

          <div className="mt-4 grid gap-3 sm:grid-cols-2">
            <label className="text-sm">
              <div className="mb-1 text-slate-700">Новый логин</div>
              <input
                className="w-full rounded border border-slate-300 px-3 py-2"
                value={newAdminUsername}
                onChange={(e) => setNewAdminUsername(e.target.value)}
              />
            </label>
            <label className="text-sm">
              <div className="mb-1 text-slate-700">Новый пароль</div>
              <input
                className="w-full rounded border border-slate-300 px-3 py-2"
                type="password"
                value={newAdminPassword}
                onChange={(e) => setNewAdminPassword(e.target.value)}
              />
            </label>
            <div className="flex items-end">
              <button
                className="w-full rounded bg-slate-900 px-3 py-2 text-sm font-medium text-white"
                onClick={() => void createAdmin()}
              >
                Создать администратора
              </button>
            </div>
          </div>

          {adminUsers.length ? (
            <div className="mt-4 overflow-hidden rounded border border-slate-200">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-left">
                  <tr>
                    <th className="px-3 py-2">username</th>
                    <th className="px-3 py-2">disabled</th>
                    <th className="px-3 py-2">created</th>
                  </tr>
                </thead>
                <tbody>
                  {adminUsers.map((u) => (
                    <tr key={u.id} className="border-t border-slate-200">
                      <td className="px-3 py-2">{u.username}</td>
                      <td className="px-3 py-2">{u.disabled ? 'yes' : 'no'}</td>
                      <td className="px-3 py-2">{formatUnixSeconds(u.created_unix)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </div>

        <div className="mt-6 rounded-lg border border-slate-200 p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 className="text-lg font-medium">Последние события</h2>
            <button
              className="rounded border border-slate-300 px-3 py-2 text-sm"
              onClick={() => void loadTail()}
            >
              Обновить
            </button>
          </div>

          <p className="mt-2 text-sm text-slate-600">
            Показывает последние 200 событий из SQLite (учитывает Tag/Origin/Session сверху).
          </p>

          {tailStatus ? <p className="mt-3 text-sm text-slate-600">{tailStatus}</p> : null}
          {tailError ? <p className="mt-3 text-sm text-red-600">{tailError}</p> : null}

          {tailRows.length ? (
            <div className="mt-3 overflow-hidden rounded border border-slate-200">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 text-left">
                  <tr>
                    <th className="px-3 py-2">ts</th>
                    <th className="px-3 py-2">tag</th>
                    <th className="px-3 py-2">origin</th>
                    <th className="px-3 py-2">session</th>
                    <th className="px-3 py-2">args</th>
                  </tr>
                </thead>
                <tbody>
                  {tailRows.map((r, i) => (
                    <tr key={i} className="border-t border-slate-200 align-top">
                      <td className="px-3 py-2 whitespace-nowrap">{r.ts}</td>
                      <td className="px-3 py-2 whitespace-nowrap">{r.tag}</td>
                      <td className="px-3 py-2 whitespace-nowrap">{r.origin}</td>
                      <td className="px-3 py-2 whitespace-nowrap">{r.session ?? '—'}</td>
                      <td className="px-3 py-2 font-mono text-xs">{JSON.stringify(r.args)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
        </div>

            <p className="mt-6 text-sm text-slate-600">
              Примечание: логи доступны только если контейнер web запущен с монтированием
              `/logs` и включён Basic Auth.
            </p>
          </>
        )}
      </div>
    </div>
  )
}

export default App
