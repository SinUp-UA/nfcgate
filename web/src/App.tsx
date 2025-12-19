import { useEffect, useMemo, useState } from 'react'

type ExportFormat = 'jsonl' | 'csv'

type AuthMode = 'checking' | 'login' | 'bootstrap' | 'authed'

type PageKey = 'users' | 'analytics'

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

function cx(...classes: Array<string | null | undefined | false>) {
  return classes.filter(Boolean).join(' ')
}

function Card(props: { title: string; action?: React.ReactNode; children: React.ReactNode; description?: string }) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white">
      <div className="flex items-start justify-between gap-4 border-b border-slate-100 px-4 py-3">
        <div>
          <h2 className="text-sm font-semibold text-slate-900">{props.title}</h2>
          {props.description ? <p className="mt-1 text-xs text-slate-600">{props.description}</p> : null}
        </div>
        {props.action ? <div className="shrink-0">{props.action}</div> : null}
      </div>
      <div className="px-4 py-4">{props.children}</div>
    </section>
  )
}

function FieldLabel(props: { label: string; children: React.ReactNode }) {
  return (
    <label className="text-sm">
      <div className="mb-1 text-xs font-medium text-slate-700">{props.label}</div>
      {props.children}
    </label>
  )
}

function TextInput(props: React.InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={cx('w-full rounded border border-slate-300 px-3 py-2 text-sm', props.className)} />
}

function SelectInput(props: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select {...props} className={cx('w-full rounded border border-slate-300 px-3 py-2 text-sm', props.className)} />
  )
}

function Button(props: React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }) {
  const variant = props.variant ?? 'secondary'
  const base = 'rounded px-3 py-2 text-sm font-medium disabled:opacity-50'
  const styles =
    variant === 'primary'
      ? 'bg-slate-900 text-white'
      : variant === 'danger'
        ? 'border border-red-300 bg-white text-red-700'
        : 'border border-slate-300 bg-white text-slate-900'
  return (
    <button {...props} className={cx(base, styles, props.className)} />
  )
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

  const [page, setPage] = useState<PageKey>('analytics')

  const [editAdminId, setEditAdminId] = useState<number | ''>('')
  const [editAdminPassword, setEditAdminPassword] = useState<string>('')
  const [editAdminDisabled, setEditAdminDisabled] = useState<boolean>(false)

  const [deleteAdminId, setDeleteAdminId] = useState<number | ''>('')
  const [deleteConfirmUsername, setDeleteConfirmUsername] = useState<string>('')

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

  useEffect(() => {
    if (!adminUsers.length) return

    if (editAdminId !== '') {
      const u = adminUsers.find((x) => x.id === editAdminId)
      if (u) setEditAdminDisabled(Boolean(u.disabled))
    }
    if (deleteAdminId !== '') {
      const u = adminUsers.find((x) => x.id === deleteAdminId)
      setDeleteConfirmUsername(u?.username ?? '')
    }
  }, [adminUsers, editAdminId, deleteAdminId])

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
        const contentType = resp.headers.get('content-type') ?? ''
        const payload = (await resp
          .json()
          .catch(() => null)) as { error?: string } | null
        if (payload.error === 'no_admins') {
          setAuthMode('bootstrap')
          setAuthError('Нет администраторов. Создайте первого администратора.')
          return
        }
        if (payload?.error) {
          setAuthError(`Ошибка авторизации: ${payload.error}`)
          return
        }

        // Non-JSON errors often come from a dev proxy (e.g. backend not running).
        let extra = ''
        if (!contentType.toLowerCase().includes('application/json')) {
          const text = await resp.text().catch(() => '')
          const snippet = text.replaceAll(/\s+/g, ' ').trim().slice(0, 180)
          if (snippet) extra = ` (${snippet})`
        }
        setAuthError(`Ошибка авторизации: ${resp.status}${extra}`)
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

  async function updateAdmin() {
    setAdminError('')
    setAdminStatus('Сохранение изменений...')
    if (editAdminId === '') {
      setAdminStatus('')
      setAdminError('Выберите пользователя для редактирования')
      return
    }

    const payload: Record<string, unknown> = { disabled: editAdminDisabled }
    if (editAdminPassword) payload.password = editAdminPassword

    try {
      const resp = await apiFetch(`/api/admin/users/${editAdminId}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (resp.status === 401) {
        logout()
        return
      }
      if (!resp.ok) {
        const p = (await resp.json().catch(() => ({}))) as { error?: string }
        setAdminStatus('')
        setAdminError(`Ошибка сохранения: ${p.error ?? resp.status}`)
        return
      }
      setEditAdminPassword('')
      setAdminStatus('Готово')
      await loadAdmins()
    } catch {
      setAdminStatus('')
      setAdminError('Ошибка сети при сохранении')
    }
  }

  async function deleteAdmin() {
    setAdminError('')
    setAdminStatus('Удаление пользователя...')
    if (deleteAdminId === '') {
      setAdminStatus('')
      setAdminError('Выберите пользователя для удаления')
      return
    }
    const u = adminUsers.find((x) => x.id === deleteAdminId)
    if (!u) {
      setAdminStatus('')
      setAdminError('Пользователь не найден')
      return
    }
    if (deleteConfirmUsername.trim() !== u.username) {
      setAdminStatus('')
      setAdminError('Для удаления введите username точно как в списке')
      return
    }

    try {
      const resp = await apiFetch(`/api/admin/users/${deleteAdminId}`, { method: 'DELETE' })
      if (resp.status === 401) {
        logout()
        return
      }
      if (!resp.ok) {
        const p = (await resp.json().catch(() => ({}))) as { error?: string }
        setAdminStatus('')
        setAdminError(`Ошибка удаления: ${p.error ?? resp.status}`)
        return
      }
      setDeleteAdminId('')
      setDeleteConfirmUsername('')
      setAdminStatus('Готово')
      await loadAdmins()
    } catch {
      setAdminStatus('')
      setAdminError('Ошибка сети при удалении')
    }
  }

  function AuthScreen() {
    return (
      <div className="mx-auto max-w-lg px-6 py-12">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">NFCGate Admin</h1>
        <p className="mt-2 text-sm text-slate-600">Вход в панель администратора</p>

        <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
          <h2 className="text-sm font-semibold text-slate-900">
            {authMode === 'bootstrap' ? 'Создать первого администратора' : 'Авторизация'}
          </h2>
          <p className="mt-2 text-sm text-slate-600">
            {authMode === 'bootstrap'
              ? 'Панель ещё не инициализирована — создайте первого администратора.'
              : 'Введите логин и пароль администратора.'}
          </p>

          <div className="mt-4 grid gap-3 sm:grid-cols-2">
            <FieldLabel label="Логин">
              <TextInput value={authUsername} onChange={(e) => setAuthUsername(e.target.value)} autoComplete="username" />
            </FieldLabel>
            <FieldLabel label="Пароль">
              <TextInput
                type="password"
                value={authPassword}
                onChange={(e) => setAuthPassword(e.target.value)}
                autoComplete={authMode === 'bootstrap' ? 'new-password' : 'current-password'}
              />
            </FieldLabel>

            <div className="flex items-end">
              <Button
                className="w-full"
                variant="primary"
                onClick={() =>
                  void submitAuth(authMode === 'bootstrap' ? '/api/auth/bootstrap' : '/api/auth/login')
                }
                disabled={authMode === 'checking'}
              >
                {authMode === 'bootstrap' ? 'Создать и войти' : 'Войти'}
              </Button>
            </div>
          </div>

          {authMode === 'checking' ? <p className="mt-3 text-sm text-slate-600">Проверка статуса...</p> : null}
          {authError ? <p className="mt-3 text-sm text-red-600">{authError}</p> : null}
        </div>
      </div>
    )
  }

  function Sidebar() {
    const NavButton = (props: { k: PageKey; label: string }) => (
      <button
        className={cx(
          'flex w-full items-center justify-between rounded px-3 py-2 text-sm',
          page === props.k ? 'bg-slate-800 text-white' : 'text-slate-200 hover:bg-slate-800/60',
        )}
        onClick={() => setPage(props.k)}
      >
        <span className="font-medium">{props.label}</span>
      </button>
    )

    return (
      <aside className="w-64 shrink-0 bg-slate-900 text-slate-100">
        <div className="border-b border-slate-800 px-4 py-4">
          <div className="text-sm font-semibold">NFCGate</div>
          <div className="mt-1 text-xs text-slate-300">Admin panel</div>
        </div>

        <div className="px-3 py-3">
          <nav className="space-y-1">
            <NavButton k="analytics" label="Аналитика" />
            <NavButton k="users" label="Пользователи" />
          </nav>
        </div>

        <div className="mt-auto border-t border-slate-800 px-4 py-3">
          <div className="text-xs text-slate-300">Вы вошли как</div>
          <div className="mt-1 truncate text-sm font-medium text-white">{authUser || 'admin'}</div>
          <Button className="mt-3 w-full" onClick={() => logout()}>
            Выйти
          </Button>
        </div>
      </aside>
    )
  }

  function UsersPage() {
    const selectedEdit = editAdminId === '' ? null : adminUsers.find((u) => u.id === editAdminId) ?? null
    const selectedDelete = deleteAdminId === '' ? null : adminUsers.find((u) => u.id === deleteAdminId) ?? null

    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-lg font-semibold text-slate-900">Пользователи</h1>
          <p className="mt-1 text-sm text-slate-600">Создание, редактирование и удаление администраторов панели.</p>
        </div>

        {adminStatus ? <p className="text-sm text-slate-600">{adminStatus}</p> : null}
        {adminError ? <p className="text-sm text-red-600">{adminError}</p> : null}

        <div className="grid gap-6 xl:grid-cols-3">
          <div className="xl:col-span-2">
            <Card
              title="Список пользователей"
              action={<Button onClick={() => void loadAdmins()}>Обновить</Button>}
              description="Таблица текущих администраторов."
            >
              {adminUsers.length ? (
                <div className="overflow-hidden rounded border border-slate-200">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-left">
                      <tr>
                        <th className="px-3 py-2">username</th>
                        <th className="px-3 py-2">disabled</th>
                        <th className="px-3 py-2">created</th>
                        <th className="px-3 py-2">actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {adminUsers.map((u) => (
                        <tr key={u.id} className="border-t border-slate-200">
                          <td className="px-3 py-2">{u.username}</td>
                          <td className="px-3 py-2">{u.disabled ? 'yes' : 'no'}</td>
                          <td className="px-3 py-2 whitespace-nowrap">{formatUnixSeconds(u.created_unix)}</td>
                          <td className="px-3 py-2">
                            <div className="flex flex-wrap gap-2">
                              <Button
                                className="px-2 py-1 text-xs"
                                onClick={() => {
                                  setEditAdminId(u.id)
                                  setEditAdminPassword('')
                                  setEditAdminDisabled(Boolean(u.disabled))
                                }}
                              >
                                Edit
                              </Button>
                              <Button
                                className="px-2 py-1 text-xs"
                                variant="danger"
                                disabled={u.username === authUser}
                                onClick={() => {
                                  setDeleteAdminId(u.id)
                                  setDeleteConfirmUsername('')
                                }}
                              >
                                Delete
                              </Button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-sm text-slate-600">Нет данных</p>
              )}
            </Card>
          </div>

          <div className="space-y-6">
            <Card title="Создать пользователя" description="Создаёт нового администратора.">
              <div className="grid gap-3">
                <FieldLabel label="Username">
                  <TextInput value={newAdminUsername} onChange={(e) => setNewAdminUsername(e.target.value)} />
                </FieldLabel>
                <FieldLabel label="Пароль">
                  <TextInput
                    type="password"
                    value={newAdminPassword}
                    onChange={(e) => setNewAdminPassword(e.target.value)}
                    autoComplete="new-password"
                  />
                </FieldLabel>
                <Button variant="primary" onClick={() => void createAdmin()}>
                  Создать
                </Button>
              </div>
            </Card>

            <Card title="Редактировать пользователя" description="Сброс пароля и/или отключение пользователя.">
              <div className="grid gap-3">
                <FieldLabel label="Пользователь">
                  <SelectInput
                    value={editAdminId === '' ? '' : String(editAdminId)}
                    onChange={(e) => setEditAdminId(e.target.value ? Number(e.target.value) : '')}
                  >
                    <option value="">Выберите...</option>
                    {adminUsers.map((u) => (
                      <option key={u.id} value={String(u.id)}>
                        {u.username}
                      </option>
                    ))}
                  </SelectInput>
                </FieldLabel>

                <FieldLabel label="Новый пароль (опционально)">
                  <TextInput
                    type="password"
                    value={editAdminPassword}
                    onChange={(e) => setEditAdminPassword(e.target.value)}
                    placeholder="Оставьте пустым, чтобы не менять"
                    autoComplete="new-password"
                  />
                </FieldLabel>

                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    className="h-4 w-4"
                    checked={editAdminDisabled}
                    onChange={(e) => setEditAdminDisabled(e.target.checked)}
                    disabled={selectedEdit?.username === authUser}
                  />
                  <span className="text-slate-800">Отключить (disabled)</span>
                </label>
                {selectedEdit?.username === authUser ? (
                  <p className="text-xs text-slate-600">Нельзя отключить текущего пользователя.</p>
                ) : null}

                <Button variant="primary" onClick={() => void updateAdmin()}>
                  Сохранить
                </Button>
              </div>
            </Card>

            <Card title="Удалить пользователя" description="Удаляет выбранного пользователя (кроме текущего).">
              <div className="grid gap-3">
                <FieldLabel label="Пользователь">
                  <SelectInput
                    value={deleteAdminId === '' ? '' : String(deleteAdminId)}
                    onChange={(e) => setDeleteAdminId(e.target.value ? Number(e.target.value) : '')}
                  >
                    <option value="">Выберите...</option>
                    {adminUsers
                      .filter((u) => u.username !== authUser)
                      .map((u) => (
                        <option key={u.id} value={String(u.id)}>
                          {u.username}
                        </option>
                      ))}
                  </SelectInput>
                </FieldLabel>

                {selectedDelete ? (
                  <>
                    <FieldLabel label={`Введите username для подтверждения (${selectedDelete.username})`}>
                      <TextInput value={deleteConfirmUsername} onChange={(e) => setDeleteConfirmUsername(e.target.value)} />
                    </FieldLabel>
                    <Button variant="danger" onClick={() => void deleteAdmin()}>
                      Удалить
                    </Button>
                  </>
                ) : (
                  <p className="text-sm text-slate-600">Выберите пользователя для удаления.</p>
                )}
              </div>
            </Card>
          </div>
        </div>
      </div>
    )
  }

  function AnalyticsPage() {
    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-lg font-semibold text-slate-900">Аналитика</h1>
          <p className="mt-1 text-sm text-slate-600">Выгрузка логов, APDU статистика и последние события.</p>
        </div>

        <Card title="Фильтры" description="Фильтры применяются к экспорту, аналитике и tail.">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <FieldLabel label="Начало">
              <TextInput type="datetime-local" value={startLocal} onChange={(e) => setStartLocal(e.target.value)} />
            </FieldLabel>

            <FieldLabel label="Конец">
              <TextInput type="datetime-local" value={endLocal} onChange={(e) => setEndLocal(e.target.value)} />
            </FieldLabel>

            <FieldLabel label="Tag (опционально)">
              <TextInput placeholder="например: server или log" value={tag} onChange={(e) => setTag(e.target.value)} />
            </FieldLabel>

            <FieldLabel label="Origin (опционально)">
              <TextInput placeholder="например: 10.0.0.5:12345" value={origin} onChange={(e) => setOrigin(e.target.value)} />
            </FieldLabel>

            <FieldLabel label="Session (опционально)">
              <TextInput placeholder="например: 1" value={session} onChange={(e) => setSession(e.target.value)} />
            </FieldLabel>
          </div>
        </Card>

        <Card
          title="Выгрузка логов"
          description="Скачивание логов по диапазону времени."
          action={
            <div className="flex items-center gap-2">
              <SelectInput value={format} onChange={(e) => setFormat(e.target.value as ExportFormat)}>
                <option value="jsonl">JSONL</option>
                <option value="csv">CSV</option>
              </SelectInput>
              <Button variant="primary" onClick={() => void download()}>
                Скачать
              </Button>
            </div>
          }
        >
          {status ? <p className="text-sm text-slate-600">{status}</p> : null}
          {error ? <p className="text-sm text-red-600">{error}</p> : null}
        </Card>

        <Card
          title="Аналитика APDU"
          description="ТОП команды и ответы карты по APDU."
          action={<Button onClick={() => void loadStats()}>Показать</Button>}
        >
          {statsStatus ? <p className="text-sm text-slate-600">{statsStatus}</p> : null}
          {statsError ? <p className="text-sm text-red-600">{statsError}</p> : null}

          {stats ? (
            <div className="mt-4 space-y-4">
              <div className="text-sm text-slate-700">
                <div>Разобрано APDU: {stats.parsed_apdu}</div>
                <div>Ошибок парсинга: {stats.parse_errors}</div>
                <div>Строк логов просмотрено: {stats.total_log_rows_scanned}</div>
                <div>80CA (CLA+INS) встречается: {stats.highlight?.['80CA'] ?? 0}</div>
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
        </Card>

        <Card title="Последние события" description="Показывает последние 200 событий из SQLite." action={<Button onClick={() => void loadTail()}>Обновить</Button>}>
          {tailStatus ? <p className="text-sm text-slate-600">{tailStatus}</p> : null}
          {tailError ? <p className="text-sm text-red-600">{tailError}</p> : null}

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
          ) : (
            <p className="text-sm text-slate-600">Нет данных</p>
          )}
        </Card>

        <Card title="Статус" action={<Button onClick={() => void loadHealth()}>Проверить</Button>}>
          {healthStatus ? <p className="text-sm text-slate-600">{healthStatus}</p> : null}
          {healthError ? <p className="text-sm text-red-600">{healthError}</p> : null}

          {health ? (
            <div className="mt-3 grid gap-2 text-sm text-slate-700 sm:grid-cols-2">
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
                Retention: db_days={health.retention?.db_days ?? 0}, jsonl_days={health.retention?.jsonl_days ?? 0}, sweep_seconds={health.retention?.sweep_seconds ?? 0}
              </div>
              <div>Started: {formatUnixSeconds(health.started_unix)}</div>
              <div className="sm:col-span-2">Server: {health.server}</div>
            </div>
          ) : null}
        </Card>
      </div>
    )
  }

  if (authMode !== 'authed') {
    return (
      <div className="min-h-screen bg-slate-100 text-slate-900">
        <AuthScreen />
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-slate-100 text-slate-900">
      <div className="flex min-h-screen">
        <Sidebar />

        <main className="flex-1">
          <div className="border-b border-slate-200 bg-white">
            <div className="px-6 py-4">
              <div className="text-sm text-slate-600">Панель администратора</div>
            </div>
          </div>
          <div className="px-6 py-6">
            {page === 'users' ? <UsersPage /> : <AnalyticsPage />}
            <p className="mt-8 text-xs text-slate-500">
              Примечание: логи доступны только если контейнер web запущен с монтированием /logs и включён Basic Auth.
            </p>
          </div>
        </main>
      </div>
    </div>
  )
}

export default App
