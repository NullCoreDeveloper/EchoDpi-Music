import requests
import json
import re

def get_yandex_music_playlist(username, playlist_id):
    # Прямой API запрос. Важно: Яндекс требует правильный User-Agent и иногда Referer.
    url = f"https://music.yandex.ru/handlers/playlist.jsx?owner={username}&kinds={playlist_id}"
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Accept": "application/json, text/javascript, */*; q=0.01",
        "X-Requested-With": "XMLHttpRequest",
        "Referer": f"https://music.yandex.ru/users/{username}/playlists/{playlist_id}",
    }

    print(f"[*] Пробую получить данные для: {username}/playlists/{playlist_id}")
    
    try:
        session = requests.Session()
        # Сначала "посетим" главную страницу плейлиста, чтобы получить куки
        page_url = f"https://music.yandex.ru/users/{username}/playlists/{playlist_id}"
        session.get(page_url, headers=headers, timeout=10)
        
        # Теперь делаем запрос к API
        response = session.get(url, headers=headers, timeout=10)
        
        if response.status_code != 200:
            print(f"[!] Ошибка HTTP: {response.status_code}")
            if "captcha" in response.url or "showcaptcha" in response.text:
                print("[!] Яндекс выдал капчу. Попробуйте сменить IP или запустить позже.")
            return

        if not response.text.strip():
            print("[!] Получен пустой ответ от сервера.")
            return

        try:
            data = response.json()
        except json.JSONDecodeError:
            print("[!] Не удалось декодировать JSON. Ответ сервера не является JSON.")
            # Сохраним для отладки
            with open("error_log.html", "w", encoding="utf-8") as f:
                f.write(response.text)
            print("[*] Ответ сервера сохранен в error_log.html для анализа.")
            return

        # Проверка структуры ответа
        if 'playlist' not in data:
            print("[!] В ответе нет ключа 'playlist'.")
            return

        playlist = data['playlist']
        title = playlist.get('title', 'Без названия')
        tracks = playlist.get('tracks', [])
        
        print(f"\n[+] Успех! Плейлист: '{title}'")
        print(f"[+] Всего треков: {len(tracks)}\n")
        
        print("-" * 40)
        # Выводим все треки
        for i, track in enumerate(tracks, 1):
            # В разных типах ответов структура track может чуть отличаться
            track_data = track
            if 'track' in track: track_data = track['track']
            
            track_title = track_data.get('title', 'Unknown Title')
            
            # Обработка версии трека (например, "Remix")
            version = track_data.get('version')
            full_title = f"{track_title} ({version})" if version else track_title
            
            artists = ", ".join([a.get('name', 'Unknown Artist') for a in track_data.get('artists', [])])
            
            print(f"{i:3}. {full_title} — {artists}")
        print("-" * 40)

    except Exception as e:
        print(f"[!] Произошла критическая ошибка: {e}")

if __name__ == "__main__":
    # Запуск для твоего плейлиста
    get_yandex_music_playlist("user", "3")
