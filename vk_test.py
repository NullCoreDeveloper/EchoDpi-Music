import requests
import json
import sys
import re
from bs4 import BeautifulSoup

def scrape_vk_audio(url: str):
    """
    Скрейпит аудиозаписи из ВК (плейлисты или профили) без использования браузера.
    Использует подход, аналогичный парсингу Яндекс.Музыки.
    """
    
    # Приводим к мобильной версии для упрощения
    if 'm.vk.com' not in url:
        if 'vk.com/audio' in url:
             url = url.replace('vk.com/audio', 'm.vk.com/audio')
        elif 'vk.com/audios' in url:
             url = url.replace('vk.com/audios', 'm.vk.com/audios')
        else:
             url = url.replace('vk.com', 'm.vk.com')

    headers = {
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 14_8 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/14.1.2 Mobile/15E148 Safari/604.1',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3',
    }

    print(f"[*] Запрос к: {url}")
    
    try:
        response = requests.get(url, headers=headers, timeout=15)
        response.raise_for_status()
    except Exception as e:
        print(f"[!] Ошибка при запросе: {e}")
        return

    soup = BeautifulSoup(response.text, 'html.parser')
    
    # В мобильной версии VK данные о треках лежат в div.audio_item в атрибуте data-audio
    audio_items = soup.select('div.audio_item')
    
    if not audio_items:
        # Проверка на приватность или пустой профиль
        if "запретил" in response.text or "приватный" in response.text:
            print("[!] Ошибка: Доступ к аудиозаписям ограничен настройками приватности.")
        elif "нет аудиозаписей" in response.text.lower():
            print("[!] В этом профиле/плейлисте нет аудиозаписей.")
        else:
            print("[!] Треки не найдены. Возможно, структура страницы изменилась или требуется авторизация.")
            # Для отладки можно сохранить кусок HTML
            # with open("debug.html", "w") as f: f.write(response.text)
        return

    print(f"[*] Найдено треков: {len(audio_items)}")
    print("-" * 40)

    for index, item in enumerate(audio_items, 1):
        try:
            # Атрибут data-audio содержит JSON массив с данными трека
            data_attr = item.get('data-audio')
            if not data_attr:
                continue
                
            track_data = json.loads(data_attr)
            
            # Структура data-audio (индексы):
            # 3 - Название песни
            # 4 - Исполнитель
            # 16 - Доп. инфо (prod, feat и т.д.)
            
            title = track_data[3]
            artist = track_data[4]
            extra = track_data[16] if len(track_data) > 16 else ""
            
            full_title = f"{title} ({extra})" if extra else title
            
            # Формат: (номер). (название песни) - (автор)
            print(f"{index}. {full_title} - {artist}")
            
        except (json.JSONDecodeError, IndexError) as e:
            # Если не получилось распарсить JSON, пробуем через обычные теги
            title_elem = item.select_one('.ai_title')
            artist_elem = item.select_one('.ai_artist')
            
            title = title_elem.get_text(strip=True) if title_elem else "Неизвестно"
            artist = artist_elem.get_text(strip=True) if artist_elem else "Неизвестно"
            print(f"{index}. {title} - {artist} [!] (парсинг через HTML)")

    print("-" * 40)

if __name__ == "__main__":
    # Ссылка по умолчанию (плейлист из запроса)
    default_url = "https://vk.com/music/playlist/635001978_63_5bbceadee6aef54832"
    
    if len(sys.argv) > 1:
        target_url = sys.argv[1]
    else:
        target_url = default_url
        
    scrape_vk_audio(target_url)
