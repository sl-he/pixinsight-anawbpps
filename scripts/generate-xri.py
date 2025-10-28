#!/usr/bin/env python3
"""
Generate PixInsight Repository Index (updates.xri) from GitHub Releases
"""
import os
import json
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

def generate_xri():
    """Генерирует updates.xri на основе GitHub Releases"""
    
    # Создаём корневой элемент
    root = ET.Element('xri', version='1.0')
    repo = ET.SubElement(root, 'repository',
        title=REPO_NAME,
        description='Automated Narrowband Astrophotography Workflow Based on PixInsight Processing Scripts',
        location=BASE_URL,
        attrib={'update-url': f'{BASE_URL}/updates.xri'})
    
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
        release_date = datetime.fromisoformat(
            release['published_at'].replace('Z', '+00:00')
        ).strftime('%Y-%m-%d')
        
        print(f'Processing release: {tag_name} ({release_date})')
        
        # Обрабатываем каждый asset в релизе
        for asset in release['assets']:
            filename = asset['name']
            
            # Проверяем что это ZIP архив
            if not filename.endswith('.zip'):
                print(f"  Skipping non-zip asset: {filename}")
                continue
            
            # Определяем платформу
            if 'x64' in filename or 'amd64' in filename:
                platform = 'x64'
            elif 'arm64' in filename:
                platform = 'arm64'
            else:
                platform = 'all'
            
            print(f'  Adding package: {filename} ({platform})')
            
            # Создаём описание (обрезаем если слишком длинное)
            description = release.get('body', 'No description')
            if description:
                # Убираем markdown и ограничиваем длину
                description = description.replace('\r\n', ' ').replace('\n', ' ')
                if len(description) > 200:
                    description = description[:197] + '...'
            
            package = ET.SubElement(repo, 'package',
                fileName=filename,
                name=REPO_NAME,
                version=version,
                releaseDate=release_date,
                platform=platform,
                description=description)
            
            ET.SubElement(package, 'title').text = REPO_NAME
            ET.SubElement(package, 'download-url').text = asset['browser_download_url']
            ET.SubElement(package, 'download-size').text = str(asset['size'])
            
            # Добавляем release notes если есть
            if release.get('body'):
                notes = ET.SubElement(package, 'release-notes')
                notes_text = release['body']
                if len(notes_text) > 1000:
                    notes_text = notes_text[:997] + '...'
                notes.text = notes_text
            
            package_count += 1
    
    # Создаём директорию если не существует
    os.makedirs('docs', exist_ok=True)
    
    # Сохраняем XML
    tree = ET.ElementTree(root)
    ET.indent(tree, space='   ')
    tree.write('docs/updates.xri', 
               encoding='UTF-8', 
               xml_declaration=True)
    
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
