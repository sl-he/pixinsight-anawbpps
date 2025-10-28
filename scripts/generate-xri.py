#!/usr/bin/env python3
"""
Generate PixInsight Repository Index (updates.xri) from GitHub Releases
"""
import os
import hashlib
import requests
import xml.etree.ElementTree as ET
from datetime import datetime

# Получаем параметры из переменных окружения
GITHUB_TOKEN = os.environ.get('GITHUB_TOKEN')
REPO_OWNER = os.environ.get('REPO_OWNER')
REPO_NAME = os.environ.get('REPO_NAME')

# URL для GitHub Pages
BASE_URL = f'https://{REPO_OWNER}.github.io/{REPO_NAME}'

def get_releases():
    """Получает список всех релизов через GitHub API"""
    url = f'https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases'
    headers = {
        'Authorization': f'token {GITHUB_TOKEN}',
        'Accept': 'application/vnd.github.v3+json'
    }
    
    print(f'Fetching releases from {url}...')
    response = requests.get(url, headers=headers)
    response.raise_for_status()
    
    releases = response.json()
    print(f'Found {len(releases)} releases')
    return releases

def calculate_sha1_from_url(download_url):
    """Скачивает файл и вычисляет SHA1 хеш"""
    try:
        print(f'  Downloading file to calculate SHA1...')
        response = requests.get(download_url, stream=True)
        response.raise_for_status()
        
        sha1 = hashlib.sha1()
        for chunk in response.iter_content(chunk_size=8192):
            sha1.update(chunk)
        
        hash_value = sha1.hexdigest()
        print(f'  SHA1: {hash_value}')
        return hash_value
    except Exception as e:
        print(f'  Warning: Could not calculate SHA1: {e}')
        return None

def generate_xri():
    """Генерирует updates.xri на основе GitHub Releases"""
    
    # Создаём корневой элемент
    root = ET.Element('xri', version='1.0')
    
    # Добавляем обязательный тег script
    ET.SubElement(root, 'script')
    
    # Добавляем описание репозитория
    repo_desc = ET.SubElement(root, 'description')
    repo_desc_p = ET.SubElement(repo_desc, 'p')
    repo_desc_p.text = f' {REPO_NAME} — Automated Narrowband Astrophotography Workflow Based on PixInsight Processing Scripts. '
    
    # Создаём platform элемент
    platform = ET.SubElement(root, 'platform', 
        os='all',
        arch='noarch', 
        version='1.8.0:1.8.99')
    
    # Получаем все релизы
    releases = get_releases()
    
    package_count = 0
    for release in releases:
        # Пропускаем черновики и pre-release
        if release['draft'] or release['prerelease']:
            print(f"Skipping draft/prerelease: {release['tag_name']}")
            continue
        
        tag_name = release['tag_name']
        version = tag_name.lstrip('v')  # Убираем 'v' если есть
        
        # Форматируем дату как YYYYMMDD
        release_date = datetime.fromisoformat(
            release['published_at'].replace('Z', '+00:00')
        ).strftime('%Y%m%d')
        
        print(f'Processing release: {tag_name} ({release_date})')
        
        # Обрабатываем каждый asset в релизе
        for asset in release['assets']:
            filename = asset['name']
            
            # Проверяем что это ZIP архив
            if not filename.endswith('.zip'):
                print(f"  Skipping non-zip asset: {filename}")
                continue
            
            print(f'  Adding package: {filename}')
            
            # Вычисляем SHA1
            sha1_hash = calculate_sha1_from_url(asset['browser_download_url'])
            
            # Создаём package элемент
            package_attrs = {
                'fileName': filename,
                'type': 'script',
                'releaseDate': release_date
            }
            
            # Добавляем SHA1 если удалось вычислить
            if sha1_hash:
                package_attrs['sha1'] = sha1_hash
            
            package = ET.SubElement(platform, 'package', **package_attrs)
            
            # Заголовок
            title = ET.SubElement(package, 'title')
            title.text = f' {REPO_NAME} '
            
            # Описание пакета
            pkg_desc = ET.SubElement(package, 'description')
            
            # Основное описание
            desc_p = ET.SubElement(pkg_desc, 'p')
            desc_p.text = f' Install or update {REPO_NAME} in PixInsight. '
            
            # Release notes если есть
            if release.get('body'):
                notes_p = ET.SubElement(pkg_desc, 'p')
                # Очищаем markdown
                notes_text = release['body'].replace('\r\n', ' ').replace('\n', ' ')
                if len(notes_text) > 300:
                    notes_text = notes_text[:297] + '...'
                notes_p.text = f' {notes_text} '
            
            # Ссылка на GitHub
            link_p = ET.SubElement(pkg_desc, 'p')
            link_p.text = f' {asset["browser_download_url"]} '
            
            package_count += 1
    
    # Создаём директорию если не существует
    os.makedirs('docs', exist_ok=True)
    
    # Сохраняем XML с правильным форматированием
    tree = ET.ElementTree(root)
    ET.indent(tree, space='')
    
    # Сохраняем в строку
    import xml.dom.minidom as minidom
    xml_str = ET.tostring(root, encoding='UTF-8', method='xml')
    
    # Красиво форматируем
    dom = minidom.parseString(xml_str)
    pretty_xml = dom.toprettyxml(indent='', encoding='UTF-8')
    
    # Убираем лишние пустые строки
    lines = pretty_xml.decode('utf-8').split('\n')
    lines = [line for line in lines if line.strip()]
    
    # Сохраняем
    with open('docs/updates.xri', 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    
    print(f'\n✓ Successfully generated docs/updates.xri')
    print(f'✓ Added {package_count} packages from {len([r for r in releases if not r["draft"] and not r["prerelease"]])} releases')
    print(f'✓ Repository URL: {BASE_URL}/updates.xri')

if __name__ == '__main__':
    try:
        generate_xri()
    except Exception as e:
        print(f'✗ Error: {e}')
        import traceback
        traceback.print_exc()
        exit(1)
