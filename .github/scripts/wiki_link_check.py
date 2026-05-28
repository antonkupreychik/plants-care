#!/usr/bin/env python3
"""Проверяет внутренние ссылки между страницами GitHub Wiki.

Использование:
    python wiki_link_check.py [WIKI_DIR]   # default: текущая директория

Проверяет каждую markdown-ссылку вида [text](target):
  * целевая страница существует (файл <Page>.md);
  * якорь (#anchor) соответствует реальному заголовку на странице.

Внешние ссылки (http/https/mailto) и картинки игнорируются.
Slug якоря считается по правилам GitHub Wiki (gollum/github-slugger):
  * lowercase;
  * выкидываются все символы, кроме букв/цифр (любой Unicode), пробела, '-' и '_'
    (пунктуация и эмодзи удаляются);
  * пробелы → '-';  ведущий пробел от удалённого эмодзи даёт ведущий '-';
  * дубли заголовков получают суффикс -1, -2, ...

Exit code: 0 — всё ок; 1 — найдены битые ссылки.
"""
import os
import re
import sys
import glob


def gh_slug(heading: str) -> str:
    h = heading.strip().lower()
    out = [ch for ch in h if ch.isalnum() or ch in (" ", "-", "_")]
    return "".join(out).replace(" ", "-")


def collect_anchors(path: str) -> set[str]:
    anchors: set[str] = set()
    seen: dict[str, int] = {}
    in_code = False
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            if line.lstrip().startswith("```"):
                in_code = not in_code
                continue
            if in_code:
                continue
            m = re.match(r"^#{1,6}\s+(.*)$", line.rstrip())
            if not m:
                continue
            base = gh_slug(m.group(1))
            if base in seen:
                seen[base] += 1
                anchors.add(f"{base}-{seen[base]}")
            else:
                seen[base] = 0
                anchors.add(base)
    return anchors


LINK_RE = re.compile(r"(!?)\[[^\]]*\]\(([^)]+)\)")


def main() -> int:
    wiki_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    files = sorted(glob.glob(os.path.join(wiki_dir, "*.md")))
    if not files:
        print(f"::error::В '{wiki_dir}' не найдено ни одного .md — wiki не склонирован?")
        return 1

    pages = {os.path.splitext(os.path.basename(f))[0] for f in files}
    anchors = {os.path.splitext(os.path.basename(f))[0]: collect_anchors(f) for f in files}

    problems: list[str] = []
    checked = 0

    for f in files:
        page = os.path.splitext(os.path.basename(f))[0]
        in_code = False
        for ln, line in enumerate(open(f, encoding="utf-8"), 1):
            if line.lstrip().startswith("```"):
                in_code = not in_code
                continue
            if in_code:
                continue
            for is_img, target in LINK_RE.findall(line):
                target = target.strip()
                if is_img or target.startswith(("http://", "https://", "mailto:")):
                    continue
                checked += 1
                if target.startswith("#"):
                    tpage, anchor = page, target[1:]
                elif "#" in target:
                    tpage, anchor = target.split("#", 1)
                    anchor = anchor or None
                else:
                    tpage, anchor = target, None

                if tpage:
                    norm = tpage.replace(" ", "-")
                    if norm not in pages:
                        problems.append(f"{os.path.basename(f)}:{ln}  → '{target}'  СТРАНИЦА НЕ НАЙДЕНА")
                        continue
                    tpage = norm
                if anchor:
                    ap = tpage or page
                    if anchor not in anchors.get(ap, set()):
                        problems.append(f"{os.path.basename(f)}:{ln}  → '{target}'  ЯКОРЬ НЕ НАЙДЕН")

    print(f"Страниц: {len(pages)} | внутренних ссылок проверено: {checked} | проблем: {len(problems)}")
    for p in problems:
        print(f"::error::{p}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
