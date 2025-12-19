# nfcgate_server

Этот репозиторий содержит два независимых проекта, которые можно разворачивать отдельно:

- `server/` — TCP‑сервер‑ретранслятор NFC Gate (порт 5567)
- `web/` — административная панель (React), раздаётся через Nginx (порт 8080; способ публикации зависит от `docker-compose.yml`)

## Запуск на одном VPS (Docker)

Ниже — рекомендованный сценарий для Ubuntu 20.04.6+ (и совместимых).

### 1) Подготовка VPS (Docker)

Установи Docker Engine и Compose plugin (официальный репозиторий Docker):

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
docker compose version
```

### 2) Загрузка проекта на VPS

Варианты:

- Через git: `git clone <repo_url> && cd nfcgate_server`
- Через архив/rsync/scp: положи папку проекта на VPS и перейди в неё.

Дальше все команды выполняются из корня репозитория (где лежит `docker-compose.yml`).

### 3) Создание логина/пароля для входа в админку

Админка защищена Basic Auth на уровне Nginx (это окно логина/пароля браузера).

Важно: внутри панели дополнительно используется встроенная авторизация администраторов (логин/пароль, токен). То есть:

- **Basic Auth (Nginx)** — внешний барьер, не пускает посторонних к UI и `/api/*`.
- **Администраторы панели (server)** — учётные записи, создаются/управляются из самой панели.

1) Установи утилиту для генерации htpasswd:

```bash
sudo apt-get update
sudo apt-get install -y apache2-utils
```

2) Создай файл с пользователем (пароль вводится интерактивно, чтобы не светить его в истории команд):

```bash
mkdir -p web/secrets
htpasswd -c web/secrets/htpasswd "sinup.agency@gmail.com"
```

Если нужно добавить второго пользователя:

```bash
htpasswd web/secrets/htpasswd "another@email"
```

### 4) Первый запуск

```bash
docker compose up --build -d
docker compose ps
```

Порты:

- `5567/tcp` публикуется на всех интерфейсах (для клиентов NFC Gate)
- `8080/tcp` (админка) — **в текущем `docker-compose.yml` опубликован на всех интерфейсах** (доступен и с `127.0.0.1`, и по публичному IP)

Если хочешь оставить админку только локально на VPS (рекомендуется), измени проброс порта в `docker-compose.yml` на:

```yaml
ports:
  - "127.0.0.1:8080:8080"
```

### 5) Как безопасно открыть админку

Рекомендуется один из двух вариантов.

**Вариант A (самый простой и безопасный): SSH‑туннель**

Рекомендуется использовать этот вариант вместе с localhost‑binding (`127.0.0.1:8080:8080`) из пункта выше.

На своём ПК:

```bash
ssh -L 8080:127.0.0.1:8080 root@<VPS_IP>
```

После этого открой в браузере:

- `http://127.0.0.1:8080`

**Вариант B (постоянный доступ извне): HTTPS reverse proxy**

Если админка должна быть доступна по домену из интернета — поставь отдельный reverse proxy на VPS (например, Nginx/Caddy) с TLS и проксированием на `127.0.0.1:8080`.
Важно: без HTTPS Basic Auth будет передаваться по сети в открытом виде.

Если ты публикуешь `8080` напрямую наружу (как сейчас в compose), как минимум ограничь доступ firewall‑ом (по IP) и всё равно лучше добавь TLS перед админкой.

### 6) Firewall (UFW)

Минимальный вариант: открыть только SSH и порт сервера (5567), а админку не публиковать наружу.

```bash
sudo ufw allow OpenSSH
sudo ufw allow 5567/tcp
sudo ufw enable
sudo ufw status
```

### 7) Проверка логов и экспорта

Логи хранятся в:

- JSONL по месяцам: `server/logs/YYYY-MM/YYYY-MM.jsonl`
- SQLite (для быстрого экспорта): `server/logs/logs.sqlite3`

Проверка что логи пишутся:

```bash
ls -la server/logs
```

Экспорт в админке:

- Открой web UI и выбери диапазон времени
- Нажми «Скачать» (формат JSONL/CSV)

Технически экспорт идёт через внутренний API `/api/logs/export`, который проксируется Nginx‑ом.

Доступ защищён в два слоя:

- Basic Auth на Nginx (если не прошли — браузер покажет окно логина/пароля).
- Токен администратора панели (выдается после входа/bootstrap в UI).

Примечание: из‑за Basic Auth заголовок `Authorization` занят, поэтому UI передаёт токен в заголовке `X-NFCGate-Token`.

### 8) Обновление проекта

```bash
git pull
docker compose up --build -d
```

### 9) Бэкап логов

Простой подход: архивировать папку `server/logs` (там и JSONL, и SQLite):

```bash
tar -czf logs_$(date +%F).tar.gz server/logs
```

### Troubleshooting (быстрое устранение проблем)

- Посмотреть логи контейнеров:

```bash
docker compose logs -n 200 --no-log-prefix
```

- Проверить что web слушает только localhost:

```bash
ss -ltnp | grep 8080
```

Если ожидаешь localhost‑only, но видишь `0.0.0.0:8080`, проверь проброс порта в `docker-compose.yml` (см. блок выше).

- Если web не стартует из‑за отсутствия `web/secrets/htpasswd`: создай файл по шагу выше.

## Запуск по отдельности

- Только сервер: `cd server && docker compose up --build`
- Только web (защищённый режим): `cd web && docker compose -f docker-compose.secure.yml up --build`

## Локальная разработка (UI)

- UI в dev‑режиме: `cd web && npm install && npm run dev` (по умолчанию `http://127.0.0.1:5173`).
- Для логина/bootstrаp UI нужен доступ к admin HTTP API сервера на `http://127.0.0.1:8081`.
  - Самый простой способ: поднять сервер через `docker compose up` из корня (в compose порт `8081` опубликован только на localhost для dev‑прокси).

Детали — в [web/README.md](web/README.md) и [server/README.md](server/README.md).
