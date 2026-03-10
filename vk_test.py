import requests
import json
import re
import sys
from bs4 import BeautifulSoup
import urllib.parse

def scrape_vk_mobile(url):
    """
    Прямой скрапинг мобильной версии m.vk.com. 
    ВК отдает данные о треках в атрибутах data-audio, если имитировать мобильное устройство.
    """
    
    # 1. Парсим параметры из входной ссылки
    owner_id = ""
    playlist_id = ""
    access_hash = ""
    
    # Поиск шаблона плейлиста или альбома: playlist/OWNER_ID_PLAYLIST_ID_HASH или album/OWNER_ID_ALBUM_ID_HASH
    pl_match = re.search(r'(?:playlist|album)/(-?\d+)_(\d+)(?:_([a-z0-9]+))?', url)
    # Поиск шаблона профиля: audios123 или m.vk.com/audio123
    ai_match = re.search(r'audio(?:s)?(-?\d+)', url)

    if pl_match:
        owner_id = pl_match.group(1)
        playlist_id = pl_match.group(2)
        access_hash = pl_match.group(3) if pl_match.group(3) else ""
        
        target_url = f"https://m.vk.com/audio?act=audio_playlist{owner_id}_{playlist_id}"
        if access_hash:
            target_url += f"&access_hash={access_hash}"
    elif ai_match:
        owner_id = ai_match.group(1)
        target_url = f"https://m.vk.com/audio{owner_id}"
    else:
        # Если пришла уже готовая query-ссылка
        if "act=audio_playlist" in url:
            target_url = url.replace('vk.com', 'm.vk.com') if 'm.vk.com' not in url else url
        else:
            print("[!] Не удалось распознать формат ссылки.")
            return

    headers = {
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_8 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.2 Mobile/15E148 Safari/604.1',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3',
    }

    session = requests.Session()
    # Важный костыль для ВК: имитируем экран устройства
    session.cookies.set('remixmdevice', '1920/1080/2/!!-!!!!', domain='.vk.com')
    
    print(f"[*] Загрузка страницы: {target_url}")
    
    try:
        # Сначала посетим главную мобильную, чтобы получить сессионные куки (stid и т.д.)
        session.get("https://m.vk.com/", headers=headers, timeout=10)
        
        response = session.get(target_url, headers=headers, timeout=15)
        response.raise_for_status()
        
        if "login" in response.url and "access_hash" not in target_url:
            print("[!] ВК перенаправил на логин. Этот профиль закрыт настройками приватности.")
            return

        soup = BeautifulSoup(response.text, 'html.parser')
        items = soup.select('.audio_item')
        
        if not items:
            print("[!] Треки не найдены. Возможно, страница пуста или ВК заблокировал запрос.")
            # debug
            # with open("vk_error.html", "w", encoding="utf-8") as f: f.write(response.text)
            return

        print(f"[*] Найдено треков: {len(items)}")
        print("-" * 45)

        for i, item in enumerate(items, 1):
            try:
                # В мобильной версии данные в JSON-массиве в data-audio
                raw_data = item.get('data-audio')
                if not raw_data: continue
                
                # Исправляем возможные сущности &quot;
                raw_data = urllib.parse.unquote(raw_data).replace('&quot;', '"')
                data = json.loads(raw_data)
                
                # Структура: [id, owner, url, title, artist, duration, ...]
                title = data[3]
                artist = data[4]
                # Доп. инфо (например, remix) обычно в data[16]
                extra = data[16] if len(data) > 16 else ""
                
                full_name = f"{title} ({extra})" if extra and isinstance(extra, str) else title
                
                print(f"{i}. {full_name} - {artist}")
            except Exception as e:
                # Если JSON не подошел, пробуем через текст
                t_el = item.select_one('.ai_title')
                a_el = item.select_one('.ai_artist')
                if t_el and a_el:
                    print(f"{i}. {t_el.text.strip()} - {a_el.text.strip()}")
                continue

        print("-" * 45)

    except Exception as e:
        print(f"[!] Ошибка: {e}")

if __name__ == "__main__":
    url = sys.argv[1] if len(sys.argv) > 1 else "https://vk.com/music/playlist/635001978_63_5bbceadee6aef54832"
    scrape_vk_mobile(url)
