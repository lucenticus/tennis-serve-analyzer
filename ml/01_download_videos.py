"""
Скачивает публичные видео подач с YouTube для создания датасета.
Использует yt-dlp (форк youtube-dl с поддержкой новых форматов).

Запуск: python 01_download_videos.py --output ./raw_videos --count 100
"""

import argparse
import subprocess
import json
from pathlib import Path

# Публичные плейлисты и запросы с примерами профессиональных подач
SEARCH_QUERIES = [
    "tennis serve slow motion technique ATP",
    "federer serve slow motion",
    "djokovic serve technique slow motion",
    "nadal serve slow motion analysis",
    "tennis serve biomechanics tutorial",
    "ITF tennis serve coaching",
    "tennis serve trophy position slow motion",
    "professional tennis serve 120fps",
]

# Публичные плейлисты (не требуют аутентификации)
PLAYLISTS = [
    "https://www.youtube.com/playlist?list=PLUFrPGkGBnFqUlXZ5xyLu1G1IflPFpwK_",  # ATP служебные
]


def download_videos(output_dir: Path, max_per_query: int = 15):
    output_dir.mkdir(parents=True, exist_ok=True)

    for query in SEARCH_QUERIES:
        print(f"\n[→] Поиск: {query}")
        cmd = [
            "yt-dlp",
            f"ytsearch{max_per_query}:{query}",
            "--output", str(output_dir / "%(id)s.%(ext)s"),
            "--format", "bestvideo[height<=720][ext=mp4]+bestaudio/best[height<=720]",
            "--merge-output-format", "mp4",
            "--no-playlist",
            "--write-info-json",
            "--ignore-errors",
            "--sleep-interval", "2",       # уважаем YouTube rate limits
            "--max-sleep-interval", "5",
            # Скачиваем только если видео < 10 минут (нам нужны короткие клипы)
            "--match-filter", "duration < 600",
        ]
        subprocess.run(cmd, check=False)

    print(f"\n[✓] Видео сохранены в {output_dir}")
    count = len(list(output_dir.glob("*.mp4")))
    print(f"[✓] Всего файлов: {count}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="./raw_videos", type=Path)
    parser.add_argument("--count", default=15, type=int, help="Видео на запрос")
    args = parser.parse_args()

    download_videos(args.output, args.count)
