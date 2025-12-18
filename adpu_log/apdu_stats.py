import re
import binascii

# Путь к большому лог-файлу
log_path = r"F:\=PROJECTS\PYTHONprojects\adpu_log\server.log"

apdu_pattern = re.compile(r"server data: b'([^']+)'")

ca_count = 0
ca_occurrences = []
commands = {}

# Читаем файл построчно для экономии памяти
with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
    data = f.read()
    matches = apdu_pattern.findall(data)

    for i, m in enumerate(matches):
        try:
            raw_bytes = eval("b'" + m + "'")
            hexdata = raw_bytes.hex().upper()
        except Exception:
            continue

        if "80CA" in hexdata:
            pos = hexdata.find("80CA")
            ca_count += 1
            ca_occurrences.append({
                "index": i,
                "position": pos,
                "hexdata": hexdata,
                "context": hexdata[max(0, pos-20):pos+40]
            })

        if len(hexdata) >= 4:
            cmd = hexdata[:4]   # CLA+INS
            commands[cmd] = commands.get(cmd, 0) + 1

print(f"80CA встречается: {ca_count} раз")

print("\nПервые 10:")
for k, occ in enumerate(ca_occurrences[:10]):
    print(f"{k+1}. Позиция: {occ['position']}, Контекст: ...{occ['context']}...")

print("\nТОП команд по первым 2 байтам (CLA+INS):")
for c, cnt in sorted(commands.items(), key=lambda x: x[1], reverse=True)[:20]:
    print(f"{c}: {cnt}")
